package dev.kantobridge.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Which tile block state shows which tile art (docs/PLAN.md §4.5 "Slot map"). A key is
 * {@code (atlas id, palette id, tile index, shape)}; its slot is a family ({@code tile_<shape>_NN}) plus a state
 * ({@code hi}/{@code lo}).
 *
 * <p>Slots are assigned by enumerating the used keys in sorted order, per shape, 256 per family. The world stores
 * the result ({@code <world>/kantobridge/slot_map.json}) and it is authoritative in multiplayer: every player's
 * locally generated resource pack draws its own art into the host's slots.
 */
public final class SlotMap {
	public static final int VERSION = 1;
	/** Tile families per shape; equals {@code KbBlocks.FAMILIES_PER_SHAPE} (checked by a unit test). */
	public static final int FAMILIES_PER_SHAPE = 16;
	public static final int SLOTS_PER_FAMILY = 256;
	/** Slots available per shape. */
	public static final int CAPACITY = FAMILIES_PER_SHAPE * SLOTS_PER_FAMILY;

	private static final Pattern ATLAS = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");
	private static final Set<String> ROOT_FIELDS = Set.of("version", "slots");
	private static final Set<String> ENTRY_FIELDS = Set.of("atlas", "palette", "tile", "shape", "family", "slot");
	private static final Comparator<Key> KEY_ORDER = Comparator.comparing(Key::atlas)
			.thenComparing(Key::palette)
			.thenComparingInt(Key::tile)
			.thenComparing(Key::shape);

	/** The two tile block shapes: full cubes and half-height blocks (water). */
	public enum Shape {
		FULL,
		HALF;

		public String id() {
			return this.name().toLowerCase(Locale.ROOT);
		}

		public static @Nullable Shape byId(String id) {
			for (Shape shape : values()) {
				if (shape.id().equals(id)) {
					return shape;
				}
			}
			return null;
		}
	}

	/**
	 * @param atlas   the export image id holding the tileset atlas, e.g. {@code tilesets.overworld}
	 * @param palette the SGB palette name the tile is coloured with
	 * @param tile    the tile index in the atlas
	 */
	public record Key(String atlas, String palette, int tile, Shape shape) implements Comparable<Key> {
		public Key {
			Objects.requireNonNull(shape, "shape");
			if (atlas == null || !ATLAS.matcher(atlas).matches()) {
				throw new IllegalArgumentException("invalid atlas id");
			}
			if (palette == null || !DataReader.SYMBOL.matcher(palette).matches()) {
				throw new IllegalArgumentException("invalid palette id");
			}
			if (tile < 0 || tile > 1023) {
				throw new IllegalArgumentException("tile index out of range");
			}
		}

		@Override
		public int compareTo(Key other) {
			return KEY_ORDER.compare(this, other);
		}
	}

	/** One tile block state: {@code index} counts slots of one shape, 256 per family. */
	public record Slot(Shape shape, int index) {
		public Slot {
			if (index < 0 || index >= CAPACITY) {
				throw new IllegalArgumentException("slot index out of range");
			}
		}

		public int family() {
			return this.index / SLOTS_PER_FAMILY;
		}

		/** The state within its family, 0..255 ({@code hi * 16 + lo}). */
		public int slot() {
			return this.index % SLOTS_PER_FAMILY;
		}

		public int hi() {
			return this.slot() >> 4;
		}

		public int lo() {
			return this.slot() & 15;
		}

		/** The block's registry path, e.g. {@code tile_full_00}. */
		public String blockPath() {
			return blockPath(this.shape, this.family());
		}

		public static String blockPath(Shape shape, int family) {
			return String.format(Locale.ROOT, "tile_%s_%02d", shape.id(), family);
		}
	}

	/** Thrown when the keys of one shape don't fit the registered families. */
	public static final class FullException extends Exception {
		private final Shape shape;
		private final int needed;

		FullException(Shape shape, int needed) {
			super("slot map full: " + needed + " " + shape.id() + " slots needed, " + CAPACITY + " available");
			this.shape = shape;
			this.needed = needed;
		}

		public Shape shape() {
			return this.shape;
		}

		public int needed() {
			return this.needed;
		}
	}

	private final Map<Key, Slot> slots;
	private final String json;
	private final String hash;

	private SlotMap(Map<Key, Slot> slots) {
		this.slots = Collections.unmodifiableMap(slots);
		this.json = ProtoJson.write(this.toJsonObject());
		this.hash = Hashes.sha256(this.json);
	}

	/** Assigns slots to {@code keys} (duplicates ignored) in sorted order, independently per shape. */
	public static SlotMap enumerate(Collection<Key> keys) throws FullException {
		TreeSet<Key> sorted = new TreeSet<>(keys);
		Map<Key, Slot> out = new LinkedHashMap<>();
		int[] next = new int[Shape.values().length];
		for (Key key : sorted) {
			int index = next[key.shape().ordinal()]++;
			if (index >= CAPACITY) {
				int needed = (int) sorted.stream().filter(k -> k.shape() == key.shape()).count();
				throw new FullException(key.shape(), needed);
			}
			out.put(key, new Slot(key.shape(), index));
		}
		return new SlotMap(out);
	}

	public @Nullable Slot slot(Key key) {
		return this.slots.get(key);
	}

	/** Every key with its slot, in sorted key order. */
	public Map<Key, Slot> slots() {
		return this.slots;
	}

	public int size() {
		return this.slots.size();
	}

	public int count(Shape shape) {
		int n = 0;
		for (Key key : this.slots.keySet()) {
			if (key.shape() == shape) {
				n++;
			}
		}
		return n;
	}

	/** The families of {@code shape} that hold at least one slot. */
	public Set<Integer> usedFamilies(Shape shape) {
		Set<Integer> out = new TreeSet<>();
		for (Slot slot : this.slots.values()) {
			if (slot.shape() == shape) {
				out.add(slot.family());
			}
		}
		return out;
	}

	/** Canonical JSON text (the contents of {@code slot_map.json}). */
	public String toJson() {
		return this.json;
	}

	/** SHA-256 of {@link #toJson()}; the WorldIndex's {@code slotMapHash}. */
	public String hash() {
		return this.hash;
	}

	private JsonObject toJsonObject() {
		JsonObject root = new JsonObject();
		root.addProperty("version", VERSION);
		JsonArray entries = new JsonArray();
		for (Map.Entry<Key, Slot> entry : this.slots.entrySet()) {
			Key key = entry.getKey();
			Slot slot = entry.getValue();
			JsonObject item = new JsonObject();
			item.addProperty("atlas", key.atlas());
			item.addProperty("palette", key.palette());
			item.addProperty("tile", key.tile());
			item.addProperty("shape", key.shape().id());
			item.addProperty("family", slot.family());
			item.addProperty("slot", slot.slot());
			entries.add(item);
		}
		root.add("slots", entries);
		return root;
	}

	/** Reads a stored slot map; its assignments are taken as they are (they are the host's). */
	public static SlotMap fromJson(String text) throws DataException {
		DataReader root;
		try {
			root = DataReader.of(ProtoJson.parse(text), "");
		} catch (ProtocolException e) {
			throw new DataException("", "invalid JSON (" + e.code() + ")");
		}
		root.allowOnly(ROOT_FIELDS);
		if (root.integer("version") != VERSION) {
			throw new DataException(root.path("version"), "unsupported version");
		}
		Map<Key, Slot> slots = new LinkedHashMap<>();
		Set<String> states = new HashSet<>();
		List<Key> order = new ArrayList<>();
		for (DataReader item : DataReader.objects(root.array("slots"), "/slots")) {
			item.allowOnly(ENTRY_FIELDS);
			Shape shape = Shape.byId(item.string("shape"));
			if (shape == null) {
				throw new DataException(item.path("shape"), "unknown shape");
			}
			Key key;
			try {
				key = new Key(item.string("atlas", ATLAS), item.string("palette", DataReader.SYMBOL), item.integer("tile", 0, 1023), shape);
			} catch (IllegalArgumentException e) {
				throw new DataException(item.path(), "invalid key");
			}
			int family = item.integer("family", 0, FAMILIES_PER_SHAPE - 1);
			int slot = item.integer("slot", 0, SLOTS_PER_FAMILY - 1);
			if (!states.add(shape.id() + ":" + family + ":" + slot)) {
				throw new DataException(item.path(), "two keys share a slot");
			}
			if (slots.put(key, new Slot(shape, family * SLOTS_PER_FAMILY + slot)) != null) {
				throw new DataException(item.path(), "duplicate key");
			}
			order.add(key);
		}
		// Keep the canonical (sorted) order so the hash doesn't depend on how the file listed its entries.
		Map<Key, Slot> sorted = new LinkedHashMap<>();
		order.stream().sorted().forEach(key -> sorted.put(key, slots.get(key)));
		return new SlotMap(sorted);
	}
}
