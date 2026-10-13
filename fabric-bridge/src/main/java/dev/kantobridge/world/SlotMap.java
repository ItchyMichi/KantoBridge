package dev.kantobridge.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
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
 *
 * <p><b>Version 2</b> (docs/specs/MULTI_GAME_PLAN.md §4.6, MG3): block states are global, so a world with several
 * regions keeps <b>one</b> slot map, in which each region owns a family range of the 32 families per shape and every
 * key names its region and the art set (its game's export) it is drawn with. A region's keys are enumerated within
 * its range from that region's whole export, so a rebuild of one region never renumbers another's blocks. A
 * region's {@code WorldIndex.slotMapHash} is {@link #regionHash}: the hash of the version-1 form of that region's
 * entries alone (its <i>slice</i>), so it stays stable while other regions come and go; the legacy region at
 * families 0..15 keeps exactly today's hash. A version-1 map is the legacy world's: one region, {@link #LEGACY_REGION},
 * at {@link #LEGACY_FAMILIES}, written byte for byte as before.
 */
public final class SlotMap {
	/** The legacy format: one region's keys, no region field. */
	public static final int VERSION = 1;
	/** Several regions in one map (MG3): a family range and an art set per region, the region on every entry. */
	public static final int VERSION_2 = 2;
	/** Tile families per shape; equals {@code KbBlocks.FAMILIES_PER_SHAPE} (checked by a unit test). */
	public static final int FAMILIES_PER_SHAPE = 32;
	public static final int SLOTS_PER_FAMILY = 256;
	/** Slots available per shape. */
	public static final int CAPACITY = FAMILIES_PER_SHAPE * SLOTS_PER_FAMILY;
	/** The region every version-1 map belongs to (MULTI_GAME_PLAN §4.14), and the families it uses. */
	public static final String LEGACY_REGION = "kanto";
	public static final Range LEGACY_FAMILIES = new Range(0, 15);

	private static final Pattern ATLAS = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");
	private static final Set<String> ROOT_FIELDS = Set.of("version", "slots");
	private static final Set<String> ROOT_FIELDS_V2 = Set.of("version", "regions", "slots");
	private static final Set<String> ENTRY_FIELDS = Set.of("atlas", "palette", "tile", "shape", "family", "slot");
	private static final Set<String> ENTRY_FIELDS_V2 = Set.of("atlas", "palette", "tile", "shape", "family", "slot", "region");
	private static final Set<String> REGION_FIELDS = Set.of("set", "families");
	private static final Comparator<Key> KEY_ORDER = Comparator.comparing(Key::region)
			.thenComparing(Key::atlas)
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

	/** A range of families {@code [lo, hi]} (inclusive) one region's keys are assigned within. */
	public record Range(int lo, int hi) {
		public Range {
			if (lo < 0 || hi >= FAMILIES_PER_SHAPE || lo > hi) {
				throw new IllegalArgumentException("bad family range");
			}
		}

		public boolean contains(int family) {
			return family >= this.lo && family <= this.hi;
		}

		public boolean overlaps(Range other) {
			return this.lo <= other.hi && other.lo <= this.hi;
		}

		/** Families in the range. */
		public int size() {
			return this.hi - this.lo + 1;
		}

		/** The first slot index of the range, per shape. */
		public int firstIndex() {
			return this.lo * SLOTS_PER_FAMILY;
		}

		/** One past the last slot index of the range, per shape. */
		public int endIndex() {
			return (this.hi + 1) * SLOTS_PER_FAMILY;
		}
	}

	/**
	 * One region's share of a version-2 map.
	 *
	 * @param set      the art set its keys are drawn with (a game id); empty for a version-1 map, whose set is unknown
	 * @param families its family range
	 */
	public record RegionSlots(String set, Range families) {
		public RegionSlots {
			Objects.requireNonNull(families, "families");
			if (set == null || !(set.isEmpty() || Games.GAME_ID.matcher(set).matches())) {
				throw new IllegalArgumentException("invalid art set");
			}
		}
	}

	/**
	 * @param region  the region the key belongs to (version 2), or empty for a legacy key
	 * @param set     the art set the key is drawn with (version 2), or empty for a legacy key
	 * @param atlas   the export image id holding the tileset atlas, e.g. {@code tilesets.overworld}
	 * @param palette the SGB palette name the tile is coloured with
	 * @param tile    the tile index in the atlas
	 */
	public record Key(String region, String set, String atlas, String palette, int tile, Shape shape) implements Comparable<Key> {
		public Key {
			Objects.requireNonNull(shape, "shape");
			if (region == null || !(region.isEmpty() || Games.REGION_ID.matcher(region).matches())) {
				throw new IllegalArgumentException("invalid region id");
			}
			if (set == null || !(set.isEmpty() || Games.GAME_ID.matcher(set).matches())) {
				throw new IllegalArgumentException("invalid art set");
			}
			if (region.isEmpty() != set.isEmpty()) {
				throw new IllegalArgumentException("a region key names its art set");
			}
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

		/** A legacy key: no region, no art set (the only kind a version-1 map holds). */
		public Key(String atlas, String palette, int tile, Shape shape) {
			this("", "", atlas, palette, tile, shape);
		}

		/** True when the key names no region (a version-1 map's). */
		public boolean legacy() {
			return this.region.isEmpty();
		}

		/** The same art in region {@code region}, drawn with art set {@code set}. */
		public Key withRegion(String region, String set) {
			return new Key(region, set, this.atlas, this.palette, this.tile, this.shape);
		}

		/** The same art as a legacy key (no region). */
		public Key legacyForm() {
			return this.legacy() ? this : new Key(this.atlas, this.palette, this.tile, this.shape);
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

	/** Thrown when the keys of one shape don't fit the families they may use. */
	public static final class FullException extends Exception {
		private final Shape shape;
		private final int needed;
		private final int available;
		private final @Nullable String region;

		FullException(Shape shape, int needed) {
			this(shape, needed, LEGACY_FAMILIES.size() * SLOTS_PER_FAMILY, null);
		}

		FullException(Shape shape, int needed, int available, @Nullable String region) {
			super("slot map full: " + needed + " " + shape.id() + " slots needed, " + available + " available"
					+ (region == null ? "" : " in region " + region));
			this.shape = shape;
			this.needed = needed;
			this.available = available;
			this.region = region;
		}

		public Shape shape() {
			return this.shape;
		}

		public int needed() {
			return this.needed;
		}

		/** The slots the shape had: a legacy map's 16 families, or the region's range. */
		public int available() {
			return this.available;
		}

		/** The region whose range was full, or null for a legacy map. */
		public @Nullable String region() {
			return this.region;
		}
	}

	private final int version;
	private final Map<Key, Slot> slots;
	private final Map<String, RegionSlots> regions;
	private final String json;
	private final String hash;

	private SlotMap(int version, Map<Key, Slot> slots, Map<String, RegionSlots> regions) {
		this.version = version;
		this.slots = Collections.unmodifiableMap(slots);
		this.regions = Collections.unmodifiableMap(new TreeMap<>(regions));
		this.json = ProtoJson.write(this.toJsonObject());
		this.hash = Hashes.sha256(this.json);
	}

	private static int count(Collection<Key> keys, Shape shape) {
		int n = 0;
		for (Key key : keys) {
			if (key.shape() == shape) {
				n++;
			}
		}
		return n;
	}

	/**
	 * A version-1 map: assigns slots to {@code keys} (duplicates ignored, any region dropped) in sorted order,
	 * independently per shape, within the legacy families 0..15.
	 */
	public static SlotMap enumerate(Collection<Key> keys) throws FullException {
		TreeSet<Key> sorted = new TreeSet<>();
		for (Key key : keys) {
			sorted.add(key.legacyForm());
		}
		Map<Key, Slot> out = new LinkedHashMap<>();
		int[] next = new int[Shape.values().length];
		for (Key key : sorted) {
			int index = next[key.shape().ordinal()]++;
			if (index >= LEGACY_FAMILIES.endIndex()) {
				throw new FullException(key.shape(), count(sorted, key.shape()));
			}
			out.put(key, new Slot(key.shape(), index));
		}
		return new SlotMap(VERSION, out, Map.of());
	}

	/**
	 * A version-2 map with region {@code region}'s keys (duplicates ignored; their own region and set are replaced by
	 * {@code region} and {@code set}) assigned in sorted order, per shape, within {@code families}, on top of
	 * {@code previous}: every other region's entries and ranges are kept exactly, the region's own old entries are
	 * dropped. {@code previous} may be null (a first region) or a version-1 map only when {@code region} is the legacy
	 * region (whose entries it holds); any other version-1 map must be {@link #migrate migrated} first.
	 *
	 * @throws IllegalArgumentException when {@code families} overlaps another region's range
	 * @throws FullException            when a shape's keys don't fit the range
	 */
	public static SlotMap enumerateRegion(@Nullable SlotMap previous, String region, String set, Range families, Collection<Key> keys)
			throws FullException {
		if (region == null || !Games.REGION_ID.matcher(region).matches()) {
			throw new IllegalArgumentException("invalid region id");
		}
		if (set == null || !Games.GAME_ID.matcher(set).matches()) {
			throw new IllegalArgumentException("invalid art set");
		}
		Objects.requireNonNull(families, "families");
		Map<Key, Slot> kept = new LinkedHashMap<>();
		Map<String, RegionSlots> regions = new TreeMap<>();
		if (previous != null) {
			if (previous.version == VERSION) {
				if (!LEGACY_REGION.equals(region)) {
					throw new IllegalArgumentException("a version 1 map must be migrated before another region joins it");
				}
				// its entries are all the legacy region's: replaced below
			} else {
				for (Map.Entry<String, RegionSlots> other : previous.regions.entrySet()) {
					if (other.getKey().equals(region)) {
						continue;
					}
					if (other.getValue().families().overlaps(families)) {
						throw new IllegalArgumentException("family range overlaps another region's");
					}
					regions.put(other.getKey(), other.getValue());
				}
				for (Map.Entry<Key, Slot> entry : previous.slots.entrySet()) {
					if (!entry.getKey().region().equals(region)) {
						kept.put(entry.getKey(), entry.getValue());
					}
				}
			}
		}
		TreeSet<Key> sorted = new TreeSet<>();
		for (Key key : keys) {
			sorted.add(key.withRegion(region, set));
		}
		Map<Key, Slot> own = new LinkedHashMap<>();
		int[] next = new int[Shape.values().length];
		Arrays.fill(next, families.firstIndex());
		for (Key key : sorted) {
			int index = next[key.shape().ordinal()]++;
			if (index >= families.endIndex()) {
				throw new FullException(key.shape(), count(sorted, key.shape()), families.size() * SLOTS_PER_FAMILY, region);
			}
			own.put(key, new Slot(key.shape(), index));
		}
		regions.put(region, new RegionSlots(set, families));
		TreeMap<Key, Slot> all = new TreeMap<>();
		all.putAll(kept);
		all.putAll(own);
		return new SlotMap(VERSION_2, new LinkedHashMap<>(all), regions);
	}

	/**
	 * This map as a version-2 map whose one region is the legacy region at families 0..15, drawn with {@code legacySet}
	 * (the legacy world's game); the slots stay as they are. A version-2 map is returned as it is.
	 */
	public SlotMap migrate(String legacySet) {
		if (this.version != VERSION) {
			return this;
		}
		if (legacySet == null || !Games.GAME_ID.matcher(legacySet).matches()) {
			throw new IllegalArgumentException("invalid art set");
		}
		Map<Key, Slot> out = new LinkedHashMap<>();
		for (Map.Entry<Key, Slot> entry : this.slots.entrySet()) {
			out.put(entry.getKey().withRegion(LEGACY_REGION, legacySet), entry.getValue());
		}
		return new SlotMap(VERSION_2, out, Map.of(LEGACY_REGION, new RegionSlots(legacySet, LEGACY_FAMILIES)));
	}

	/** 1 (the legacy format) or 2 (regions). */
	public int version() {
		return this.version;
	}

	/** The regions sharing this map, by id (sorted): a version-1 map's one legacy region has an empty art set. */
	public Map<String, RegionSlots> regions() {
		if (this.version == VERSION) {
			return Map.of(LEGACY_REGION, new RegionSlots("", LEGACY_FAMILIES));
		}
		return this.regions;
	}

	/** A region's family range, or null for a region this map doesn't hold. */
	public @Nullable Range families(String region) {
		RegionSlots slots = this.regions().get(region);
		return slots == null ? null : slots.families();
	}

	/** A region's art set (empty for a version-1 map), or null for a region this map doesn't hold. */
	public @Nullable String artSet(String region) {
		RegionSlots slots = this.regions().get(region);
		return slots == null ? null : slots.set();
	}

	/** The slot of exactly this key (a legacy key in a version-1 map, a region key in a version-2 one). */
	public @Nullable Slot slot(Key key) {
		return this.slots.get(key);
	}

	/** The slot of {@code key}'s art in {@code region}, whatever region or set the key itself carries. */
	public @Nullable Slot slot(String region, Key key) {
		if (this.version == VERSION) {
			return LEGACY_REGION.equals(region) ? this.slots.get(key.legacyForm()) : null;
		}
		RegionSlots slots = this.regions.get(region);
		return slots == null ? null : this.slots.get(key.withRegion(region, slots.set()));
	}

	/** Every key with its slot, in sorted key order. */
	public Map<Key, Slot> slots() {
		return this.slots;
	}

	/** One region's keys with their slots, in sorted order (a version-1 map's are all the legacy region's). */
	public Map<Key, Slot> slots(String region) {
		if (this.version == VERSION) {
			return LEGACY_REGION.equals(region) ? this.slots : Map.of();
		}
		Map<Key, Slot> out = new LinkedHashMap<>();
		for (Map.Entry<Key, Slot> entry : this.slots.entrySet()) {
			if (entry.getKey().region().equals(region)) {
				out.put(entry.getKey(), entry.getValue());
			}
		}
		return Collections.unmodifiableMap(out);
	}

	/**
	 * One region's entries as a version-1 map (legacy keys, absolute family numbers): the document whose hash a
	 * region's WorldIndex names as its {@code slotMapHash}. A version-1 map is its own legacy region's slice.
	 */
	public SlotMap regionSlice(String region) {
		if (this.version == VERSION) {
			return LEGACY_REGION.equals(region) ? this : new SlotMap(VERSION, Map.of(), Map.of());
		}
		Map<Key, Slot> out = new LinkedHashMap<>();
		for (Map.Entry<Key, Slot> entry : this.slots.entrySet()) {
			if (entry.getKey().region().equals(region)) {
				out.put(entry.getKey().legacyForm(), entry.getValue());
			}
		}
		return new SlotMap(VERSION, out, Map.of());
	}

	/** SHA-256 of {@link #regionSlice}'s JSON: stable while other regions are rebuilt. */
	public String regionHash(String region) {
		return this.regionSlice(region).hash();
	}

	public int size() {
		return this.slots.size();
	}

	public int count(Shape shape) {
		return count(this.slots.keySet(), shape);
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

	/** SHA-256 of {@link #toJson()}; a version-1 map's is the WorldIndex's {@code slotMapHash}. */
	public String hash() {
		return this.hash;
	}

	private JsonObject toJsonObject() {
		JsonObject root = new JsonObject();
		root.addProperty("version", this.version);
		if (this.version == VERSION_2) {
			JsonObject regions = new JsonObject();
			for (Map.Entry<String, RegionSlots> entry : this.regions.entrySet()) {
				JsonObject region = new JsonObject();
				region.addProperty("set", entry.getValue().set());
				JsonArray families = new JsonArray();
				families.add(entry.getValue().families().lo());
				families.add(entry.getValue().families().hi());
				region.add("families", families);
				regions.add(entry.getKey(), region);
			}
			root.add("regions", regions);
		}
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
			if (this.version == VERSION_2) {
				item.addProperty("region", key.region());
			}
			entries.add(item);
		}
		root.add("slots", entries);
		return root;
	}

	/** Reads a stored slot map of either version; its assignments are taken as they are (they are the host's). */
	public static SlotMap fromJson(String text) throws DataException {
		DataReader root;
		try {
			root = DataReader.of(ProtoJson.parse(text), "");
		} catch (ProtocolException e) {
			throw new DataException("", "invalid JSON (" + e.code() + ")");
		}
		int version = root.integer("version");
		if (version == VERSION) {
			root.allowOnly(ROOT_FIELDS);
			return readV1(root);
		}
		if (version == VERSION_2) {
			root.allowOnly(ROOT_FIELDS_V2);
			return readV2(root);
		}
		throw new DataException(root.path("version"), "unsupported version");
	}

	private static SlotMap readV1(DataReader root) throws DataException {
		Map<Key, Slot> slots = new LinkedHashMap<>();
		Set<String> states = new HashSet<>();
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
			int family = item.integer("family", 0, LEGACY_FAMILIES.hi());
			int slot = item.integer("slot", 0, SLOTS_PER_FAMILY - 1);
			if (!states.add(shape.id() + ":" + family + ":" + slot)) {
				throw new DataException(item.path(), "two keys share a slot");
			}
			if (slots.put(key, new Slot(shape, family * SLOTS_PER_FAMILY + slot)) != null) {
				throw new DataException(item.path(), "duplicate key");
			}
		}
		// Keep the canonical (sorted) order so the hash doesn't depend on how the file listed its entries.
		return new SlotMap(VERSION, new LinkedHashMap<>(new TreeMap<>(slots)), Map.of());
	}

	private static SlotMap readV2(DataReader root) throws DataException {
		DataReader regionsReader = root.object("regions");
		Map<String, RegionSlots> regions = new TreeMap<>();
		for (String id : regionsReader.json().keySet()) {
			if (!Games.REGION_ID.matcher(id).matches()) {
				throw new DataException(regionsReader.path(), "bad region id");
			}
			DataReader region = regionsReader.object(id).allowOnly(REGION_FIELDS);
			String set = region.string("set", Games.GAME_ID);
			int[] families = DataReader.intArray(region.array("families"), region.path("families"), 0, FAMILIES_PER_SHAPE - 1);
			if (families.length != 2 || families[0] > families[1]) {
				throw new DataException(region.path("families"), "bad family range");
			}
			Range range = new Range(families[0], families[1]);
			for (RegionSlots other : regions.values()) {
				if (other.families().overlaps(range)) {
					throw new DataException(region.path("families"), "family ranges overlap");
				}
			}
			regions.put(id, new RegionSlots(set, range));
		}
		if (regions.isEmpty()) {
			throw new DataException(regionsReader.path(), "no regions");
		}
		Map<Key, Slot> slots = new LinkedHashMap<>();
		Set<String> states = new HashSet<>();
		for (DataReader item : DataReader.objects(root.array("slots"), "/slots")) {
			item.allowOnly(ENTRY_FIELDS_V2);
			Shape shape = Shape.byId(item.string("shape"));
			if (shape == null) {
				throw new DataException(item.path("shape"), "unknown shape");
			}
			String region = item.string("region", Games.REGION_ID);
			RegionSlots owner = regions.get(region);
			if (owner == null) {
				throw new DataException(item.path("region"), "unknown region");
			}
			Key key;
			try {
				key = new Key(region, owner.set(), item.string("atlas", ATLAS), item.string("palette", DataReader.SYMBOL),
						item.integer("tile", 0, 1023), shape);
			} catch (IllegalArgumentException e) {
				throw new DataException(item.path(), "invalid key");
			}
			int family = item.integer("family", 0, FAMILIES_PER_SHAPE - 1);
			if (!owner.families().contains(family)) {
				throw new DataException(item.path("family"), "outside the region's families");
			}
			int slot = item.integer("slot", 0, SLOTS_PER_FAMILY - 1);
			if (!states.add(shape.id() + ":" + family + ":" + slot)) {
				throw new DataException(item.path(), "two keys share a slot");
			}
			if (slots.put(key, new Slot(shape, family * SLOTS_PER_FAMILY + slot)) != null) {
				throw new DataException(item.path(), "duplicate key");
			}
		}
		return new SlotMap(VERSION_2, new LinkedHashMap<>(new TreeMap<>(slots)), regions);
	}
}
