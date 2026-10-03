package dev.kantobridge.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;

/**
 * Writes one export bundle (docs/PROTOCOL.md "Hashes and the bundle") so that a reader never sees half of it: every
 * file goes into a staging folder next to the target ({@code <game>.export-tmp}), and only a complete bundle is renamed
 * to {@code <out>/<game>}. A bundle that was there before is moved aside first and deleted after the swap, or moved
 * back if the swap fails. An unfinished staging folder is deleted on {@link #close}.
 *
 * <p>Resource files hold exactly the bytes given (their canonical JSON); {@code index.json} is the {@code export.index}
 * reply's data in the repository's pretty format ({@link PrettyJson}), with the protocol's field order.
 */
final class BundleWriter implements AutoCloseable {
	static final String INDEX_FILE = "index.json";
	static final String STAGING_SUFFIX = ".export-tmp";
	static final String OLD_SUFFIX = ".export-old";
	/** The order of export.index's fields and of each resource entry's (protocol/messages/export.index.schema.json). */
	static final List<String> INDEX_ORDER = List.of("game", "engine", "bridge", "resources");
	static final List<String> RESOURCE_ORDER = List.of("kind", "id", "hash", "size");

	private static final int MOVE_ATTEMPTS = 5;

	private final Path target;
	private final Path staging;
	private final Path old;
	private boolean committed;

	/**
	 * Prepares an empty staging folder for {@code <outDir>/<game>}.
	 *
	 * @throws IOException if the target exists but isn't an export bundle (no {@code index.json}, not empty), so it is
	 *                     never replaced, or the staging folder can't be made
	 */
	BundleWriter(Path outDir, String game) throws IOException {
		Path out = outDir.toAbsolutePath().normalize();
		this.target = out.resolve(game);
		this.staging = out.resolve(game + STAGING_SUFFIX);
		this.old = out.resolve(game + OLD_SUFFIX);
		checkReplaceable(this.target);
		deleteTree(this.staging);
		Files.createDirectories(this.staging);
	}

	Path target() {
		return this.target;
	}

	/** Stores one resource's canonical bytes as {@code <kind>/<id>.json}. Kind and id were checked as file names. */
	void write(String kind, String id, byte[] bytes) throws IOException {
		Path file = this.staging.resolve(kind).resolve(id + ".json").normalize();
		if (!file.startsWith(this.staging)) {
			throw new IOException("resource path escapes the bundle");
		}
		Files.createDirectories(file.getParent());
		// CREATE_NEW: ids that differ only in case would overwrite each other on Windows; fail instead.
		Files.write(file, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
	}

	/** Writes {@code index.json} and swaps the finished bundle in. Returns the bundle folder. */
	Path commit(JsonObject index) throws IOException {
		Files.writeString(this.staging.resolve(INDEX_FILE), PrettyJson.write(ordered(index)), StandardCharsets.UTF_8);
		checkReplaceable(this.target);
		deleteTree(this.old);
		boolean hadTarget = Files.exists(this.target, LinkOption.NOFOLLOW_LINKS);
		if (hadTarget) {
			move(this.target, this.old);
		}
		try {
			move(this.staging, this.target);
		} catch (IOException e) {
			if (hadTarget) {
				try {
					move(this.old, this.target);
				} catch (IOException restore) {
					e.addSuppressed(restore);
				}
			}
			throw e;
		}
		this.committed = true;
		if (hadTarget) {
			try {
				deleteTree(this.old);
			} catch (IOException ignored) {
				// The new bundle is in place; a leftover old copy is removed by the next export.
			}
		}
		return this.target;
	}

	/** Deletes the staging folder unless the bundle was committed. */
	@Override
	public void close() {
		if (!this.committed) {
			try {
				deleteTree(this.staging);
			} catch (IOException ignored) {
				// Removed by the next export.
			}
		}
	}

	/** The index with the protocol's field order first (as gen1-bridge and fake_gen1 build it), anything else after. */
	static JsonObject ordered(JsonObject index) {
		JsonObject out = reorder(index, INDEX_ORDER);
		JsonElement resources = out.get("resources");
		if (resources != null && resources.isJsonArray()) {
			JsonArray entries = new JsonArray();
			for (JsonElement entry : resources.getAsJsonArray()) {
				entries.add(entry.isJsonObject() ? reorder(entry.getAsJsonObject(), RESOURCE_ORDER) : entry);
			}
			out.add("resources", entries);
		}
		return out;
	}

	private static JsonObject reorder(JsonObject object, List<String> order) {
		JsonObject out = new JsonObject();
		for (String key : order) {
			if (object.has(key)) {
				out.add(key, object.get(key));
			}
		}
		for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
			if (!out.has(entry.getKey())) {
				out.add(entry.getKey(), entry.getValue());
			}
		}
		return out;
	}

	/** Only a bundle (it has an index) or an empty folder may be replaced. */
	static void checkReplaceable(Path target) throws IOException {
		if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
				|| !(Files.isRegularFile(target.resolve(INDEX_FILE), LinkOption.NOFOLLOW_LINKS) || isEmpty(target))) {
			throw new NotABundleException(target.getFileName().toString());
		}
	}

	/** The target folder exists but holds something other than an export bundle. */
	static final class NotABundleException extends IOException {
		NotABundleException(String name) {
			super("refusing to replace a folder that isn't an export bundle: " + name);
		}
	}

	private static boolean isEmpty(Path directory) throws IOException {
		try (var entries = Files.list(directory)) {
			return entries.findAny().isEmpty();
		}
	}

	/** A same-volume rename, retried briefly: on Windows a virus scanner or indexer can hold a fresh file for a moment. */
	private static void move(Path from, Path to) throws IOException {
		for (int attempt = 1; ; attempt++) {
			try {
				try {
					Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
				} catch (AtomicMoveNotSupportedException e) {
					Files.move(from, to);
				}
				return;
			} catch (IOException e) {
				if (attempt >= MOVE_ATTEMPTS) {
					throw e;
				}
				try {
					Thread.sleep(100L * attempt);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw e;
				}
			}
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
