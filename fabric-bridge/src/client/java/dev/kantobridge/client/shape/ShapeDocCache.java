package dev.kantobridge.client.shape;

import dev.kantobridge.world.DataException;
import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.ShapeDocs;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * The shape documents this client has received, kept on disk by the SHA-256 of what they hold (the follow-up's item 4,
 * the voxel plan's R10): {@code <dir>/<hash>.index.z} and {@code <hash>.map.z}, each the document's raw-deflated bytes
 * as the server sent them; since MG9 G2G the world's indices and slot map too ({@code <hash>.world.z},
 * {@code <hash>.slots.z}). A join then asks the server only for documents the cache lacks. A read inflates and checks
 * the hash, and drops a file that doesn't match. Writes go to a temporary file first and are moved into place. Past
 * {@code maxTotal} bytes the least recently used files go. Like the shape table cache beside it, the files are
 * ROM-derived (tile numbers of the game's maps) and never leave this machine. Plain Java; safe from any one thread at a
 * time per file (the glue writes off the client thread).
 */
public final class ShapeDocCache {
	/** The cache's size on disk before the oldest files go (Hoenn's two documents with the landscape models take about 15 MB). */
	public static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;
	private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");
	private static final String SUFFIX = ".z";
	/** The kinds of document kept: a shape index, a shape map, a region's WorldIndex (G2G), the world's slot map (G2G). */
	public static final String SHAPE_INDEX = "index";
	public static final String SHAPE_MAP = "map";
	public static final String WORLD_INDEX = "world";
	public static final String SLOT_MAP = "slots";
	private static final Set<String> KINDS = Set.of(SHAPE_INDEX, SHAPE_MAP, WORLD_INDEX, SLOT_MAP);

	/** A document read back: its deflated bytes (the wire form without Base64) and what they inflate to. */
	public record Loaded(byte[] deflated, byte[] content) {
	}

	private final Path dir;
	private final long maxTotal;

	public ShapeDocCache(Path dir, long maxTotal) {
		this.dir = dir;
		this.maxTotal = maxTotal;
	}

	private @Nullable Path file(String hash, String kind) {
		if (hash == null || !HASH.matcher(hash).matches() || !KINDS.contains(kind)) {
			return null;
		}
		return this.dir.resolve(hash + "." + kind + SUFFIX);
	}

	/** {@link #read(String, String, int)} for a shape index ({@code isIndex}) or a shape map. */
	public @Nullable Loaded read(String hash, boolean isIndex, int maxBytes) {
		return this.read(hash, isIndex ? SHAPE_INDEX : SHAPE_MAP, maxBytes);
	}

	/** {@link #write(String, String, byte[])} for a shape index ({@code isIndex}) or a shape map. */
	public void write(String hash, boolean isIndex, byte[] deflated) throws IOException {
		this.write(hash, isIndex ? SHAPE_INDEX : SHAPE_MAP, deflated);
	}

	/**
	 * The document with this content hash, or null when the cache lacks it (or holds a file that doesn't inflate to that
	 * hash, which is then deleted). Marks it used.
	 */
	public @Nullable Loaded read(String hash, String kind, int maxBytes) {
		Path file = this.file(hash, kind);
		if (file == null || !Files.isRegularFile(file)) {
			return null;
		}
		try {
			byte[] deflated = Files.readAllBytes(file);
			byte[] content = ShapeDocs.inflate(deflated, maxBytes);
			if (!Hashes.sha256(content).equals(hash)) {
				Files.deleteIfExists(file);
				return null;
			}
			Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
			return new Loaded(deflated, content);
		} catch (IOException | DataException e) {
			try {
				Files.deleteIfExists(file);
			} catch (IOException ignored) {
				// it stays; a later read tries again
			}
			return null;
		}
	}

	/** Keeps a document (its deflated bytes) under its content hash, then trims the cache to its size. */
	public void write(String hash, String kind, byte[] deflated) throws IOException {
		Path file = this.file(hash, kind);
		if (file == null) {
			throw new IOException("not a content hash");
		}
		Files.createDirectories(this.dir);
		Path temp = Files.createTempFile(this.dir, hash.substring(0, 8), ".part");
		try {
			Files.write(temp, deflated);
			try {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temp);
		}
		this.trim(file);
	}

	/** Deletes the least recently used documents until the cache fits its size; {@code keep} always stays. */
	void trim(@Nullable Path keep) throws IOException {
		if (!Files.isDirectory(this.dir)) {
			return;
		}
		record Entry(Path path, long size, long used) {
		}
		List<Entry> entries = new ArrayList<>();
		long total = 0;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(this.dir, "*" + SUFFIX)) {
			for (Path path : stream) {
				long size = Files.size(path);
				entries.add(new Entry(path, size, Files.getLastModifiedTime(path).toMillis()));
				total += size;
			}
		}
		entries.sort(Comparator.comparingLong(Entry::used));
		for (Entry entry : entries) {
			if (total <= this.maxTotal) {
				break;
			}
			if (entry.path().equals(keep)) {
				continue;
			}
			Files.deleteIfExists(entry.path());
			total -= entry.size();
		}
	}
}
