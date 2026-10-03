package dev.kantobridge.builder.pack;

import dev.kantobridge.world.Hashes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.TreeMap;

/**
 * A generated pack held in memory (relative path → bytes) and written out as a whole, replacing the previous one, so
 * a rebuild never leaves stale textures behind.
 */
public final class PackFiles {
	private final Map<String, byte[]> files = new TreeMap<>();

	public void put(String path, byte[] bytes) {
		if (path.startsWith("/") || path.contains("..") || path.contains("\\")) {
			throw new IllegalArgumentException("bad pack path");
		}
		this.files.put(path, bytes);
	}

	public void put(String path, String text) {
		this.put(path, text.getBytes(StandardCharsets.UTF_8));
	}

	public Map<String, byte[]> files() {
		return this.files;
	}

	public int count(String prefix, String suffix) {
		int n = 0;
		for (String path : this.files.keySet()) {
			if (path.startsWith(prefix) && path.endsWith(suffix)) {
				n++;
			}
		}
		return n;
	}

	/** SHA-256 over every path and its content's hash, in path order. */
	public String hash() {
		StringBuilder listing = new StringBuilder();
		for (Map.Entry<String, byte[]> file : this.files.entrySet()) {
			listing.append(file.getKey()).append(' ').append(Hashes.sha256(file.getValue())).append('\n');
		}
		return Hashes.sha256(listing.toString());
	}

	/**
	 * Writes the files to {@code dir}, replacing what was there. The new pack is written next to it first and swapped
	 * in, and an existing folder is only removed when it looks like one of ours ({@code marker} inside).
	 */
	public void writeTo(Path dir, String marker) throws IOException {
		Path target = dir.toAbsolutePath().normalize();
		Path staging = target.resolveSibling(target.getFileName() + ".tmp");
		deleteTree(staging);
		Files.createDirectories(staging);
		for (Map.Entry<String, byte[]> file : this.files.entrySet()) {
			Path path = staging.resolve(file.getKey()).normalize();
			if (!path.startsWith(staging)) {
				throw new IOException("pack path escapes the pack folder");
			}
			Files.createDirectories(path.getParent());
			Files.write(path, file.getValue());
		}
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) || !(Files.isRegularFile(target.resolve(marker)) || isEmpty(target))) {
				throw new IOException("refusing to replace a folder that isn't a generated pack: " + target.getFileName());
			}
			deleteTree(target);
		}
		try {
			Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(staging, target);
		}
	}

	private static boolean isEmpty(Path directory) throws IOException {
		try (var entries = Files.list(directory)) {
			return entries.findAny().isEmpty();
		}
	}

	/** Deletes a folder tree without following links. */
	static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path directory, IOException e) throws IOException {
				if (e != null) {
					throw e;
				}
				Files.delete(directory);
				return FileVisitResult.CONTINUE;
			}
		});
	}
}
