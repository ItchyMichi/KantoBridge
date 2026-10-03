package dev.kantobridge.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * Splits the WorldIndex JSON into {@code world.index} parts and puts them back together (docs/PROTOCOL.md "Relay
 * only": {@code index, count, hash, part}). Each part is a slice of the text; {@code hash} is the SHA-256 of the whole
 * text, checked once every part has arrived. The slot map travels the same way as {@code world.slots} (M9).
 */
public final class WorldIndexParts {
	/**
	 * Characters per part. The relay payload is at most 256 KiB of envelope JSON, and quoting a part can double its
	 * size (every {@code "} becomes {@code \"}), so a part stays well under half of that.
	 */
	public static final int PART_CHARS = 64 * 1024;
	/** {@code world.index.count} maximum (protocol/messages/world.index.schema.json). */
	public static final int MAX_PARTS = 65536;

	/** One part as sent. */
	public record Part(int index, int count, String hash, String text) {
	}

	private WorldIndexParts() {
	}

	public static List<Part> split(String json, int partChars) {
		if (partChars < 1) {
			throw new IllegalArgumentException("part size must be positive");
		}
		String hash = Hashes.sha256(json);
		List<String> slices = new ArrayList<>();
		int start = 0;
		while (start < json.length() || slices.isEmpty()) {
			int end = Math.min(json.length(), start + partChars);
			// Never cut a surrogate pair in two: each part must be valid UTF-8 on its own.
			if (end < json.length() && end > start && Character.isHighSurrogate(json.charAt(end - 1))) {
				end--;
			}
			slices.add(json.substring(start, end));
			start = end;
		}
		if (slices.size() > MAX_PARTS) {
			throw new IllegalArgumentException("world index too large for world.index parts");
		}
		List<Part> parts = new ArrayList<>(slices.size());
		for (int i = 0; i < slices.size(); i++) {
			parts.add(new Part(i, slices.size(), hash, slices.get(i)));
		}
		return parts;
	}

	/**
	 * Collects parts in any order. A part with a different {@code count} or {@code hash} starts a new index (the server
	 * sent a newer one); a duplicate part replaces the earlier copy.
	 */
	public static final class Assembler {
		private @Nullable String hash;
		private String[] parts = new String[0];
		private int received;

		/** Adds one part; returns the complete, hash-checked text once the last part arrives, else null. */
		public @Nullable String accept(int index, int count, String hash, String text) throws DataException {
			Objects.requireNonNull(hash, "hash");
			Objects.requireNonNull(text, "text");
			if (count < 1 || count > MAX_PARTS || index < 0 || index >= count) {
				throw new DataException("/index", "part index out of range");
			}
			if (!hash.equals(this.hash) || this.parts.length != count) {
				this.hash = hash;
				this.parts = new String[count];
				this.received = 0;
			}
			if (this.parts[index] == null) {
				this.received++;
			}
			this.parts[index] = text;
			if (this.received < count) {
				return null;
			}
			StringBuilder whole = new StringBuilder();
			for (String part : this.parts) {
				whole.append(part);
			}
			String json = whole.toString();
			String expected = this.hash;
			this.reset();
			if (!Hashes.sha256(json).equals(expected)) {
				throw new DataException("/hash", "parts hash mismatch");
			}
			return json;
		}

		public void reset() {
			this.hash = null;
			this.parts = new String[0];
			this.received = 0;
		}

		/** Parts received so far for the index being assembled. */
		public int received() {
			return this.received;
		}
	}
}
