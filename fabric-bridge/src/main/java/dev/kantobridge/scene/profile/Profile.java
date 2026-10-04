package dev.kantobridge.scene.profile;

import dev.kantobridge.world.Hashes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * Dramaless Shape's shape profile, read as data (docs/specs/PROFILE_PLAN.md §4.2): class heights, and per tileset id
 * its tile pins, conditional pins, prop grounds, prop backgrounds, hull parameters and bookcase flags, and its building
 * entries. Unknown classes and fields are counted, never fatal. {@code figures}, {@code mounted} and {@code companion}
 * are skipped unread (PD5). Pure; nothing here logs or reports a tile id, only counts and the text's hash.
 */
public final class Profile {
	/** The art modes of the profile's classes (the research's TileShape table). */
	public enum Mode {
		FLAT, TOP, UPRIGHT, HULL, CANOPY, PLANTER, STANDEE, POST, GRASS, FLOWER, RELIEF, BOOKCASE, STAIR
	}

	/** Every class the profile may name, with its art mode. */
	public static final Map<String, Mode> CLASSES;
	/** Heights (Game Boy pixels) of the classes the file's own {@code heights} may leave out. */
	static final Map<String, Integer> FALLBACK_HEIGHTS;

	static {
		Map<String, Mode> classes = new TreeMap<>();
		for (String c : List.of("ground", "water", "void")) {
			classes.put(c, Mode.FLAT);
		}
		for (String c : List.of("ledge", "roof", "bed", "backrest")) {
			classes.put(c, Mode.TOP);
		}
		for (String c : List.of("wall", "cliff", "tree", "fence", "sign", "counter", "table", "desk")) {
			classes.put(c, Mode.UPRIGHT);
		}
		for (String c : List.of("cylinder", "stump", "can")) {
			classes.put(c, Mode.HULL);
		}
		classes.put("canopy", Mode.CANOPY);
		classes.put("planter", Mode.PLANTER);
		for (String c : List.of("billboard", "signpost", "stool", "prop", "cutout", "bike", "console")) {
			classes.put(c, Mode.STANDEE);
		}
		classes.put("post", Mode.POST);
		classes.put("grass", Mode.GRASS);
		classes.put("flower", Mode.FLOWER);
		classes.put("relief", Mode.RELIEF);
		classes.put("bookcase", Mode.BOOKCASE);
		for (String c : List.of("stair_e", "stair_w", "stair_down_e", "stair_down_w")) {
			classes.put(c, Mode.STAIR);
		}
		CLASSES = Collections.unmodifiableMap(classes);
		Map<String, Integer> heights = new TreeMap<>();
		int[] values = {0, -2, 0, 6, 28, 7, 12, 16, 32, 16, 10, 12, 8, 12, 24, 16, 9, 16, 32, 32, 16, 16, 8, 16, 16, 16, 16, 16, 0, 0, 3, 32, 16, 16,
				16, 16};
		String[] names = {"ground", "water", "void", "ledge", "roof", "bed", "backrest", "wall", "cliff", "tree", "fence", "sign", "counter", "table",
				"desk", "cylinder", "can", "stump", "canopy", "planter", "billboard", "signpost", "stool", "prop", "cutout", "bike", "console", "post",
				"grass", "flower", "relief", "bookcase", "stair_e", "stair_w", "stair_down_e", "stair_down_w"};
		for (int i = 0; i < names.length; i++) {
			heights.put(names[i], values[i]);
		}
		FALLBACK_HEIGHTS = Collections.unmodifiableMap(heights);
	}

	/** Keys of a tileset entry that aren't class groups. */
	private static final Set<String> TILESET_FIELDS = Set.of("heights", "prop_ground", "prop_bg", "figures", "mounted", "companion", "when_above",
			"when_below", "stump_cap", "can_cap", "can_base", "can_height", "can_well", "can_taper", "bookcase_relief", "bookcase_backfill");
	/** The highest tile id a pin may name (an atlas holds at most 1024 tiles). */
	public static final int MAX_TILE = 1023;
	/** Shade names by our shade index (0 lightest .. 3 darkest, as {@code ArtSource} gives them). */
	public static final List<String> SHADES = List.of("white", "light", "dark", "black");

	/**
	 * A conditional pin: the tile takes {@code cls} when the tile directly above ({@code above}) or below it is one of
	 * {@code tiles}.
	 */
	public record Cond(boolean above, Set<Integer> tiles, String cls) {
	}

	/** A tileset's hull parameters (art rows for caps and bases, voxels for the rest), with the research's defaults. */
	public record HullParams(int stumpCap, int canCap, int canBase, int canHeight, int canWell, int canTaper) {
		public static final HullParams DEFAULTS = new HullParams(6, 9, 4, 9, 5, 4);
	}

	/**
	 * One tileset's entry.
	 *
	 * @param pins       tile → class (the first group naming a tile wins)
	 * @param conds      tile → its conditional pins, {@code above} ones first, each in file order
	 * @param heights    class → height for this tileset (the profile's, then the tileset's own overrides)
	 * @param propGround prop tile → the ground tile painted under it
	 * @param propBg     tile → the shades that count as background when it is cut out (bit k = shade k)
	 */
	public record Tileset(String id, Map<Integer, String> pins, Map<Integer, List<Cond>> conds, Map<String, Integer> heights,
			Map<Integer, Integer> propGround, Map<Integer, Integer> propBg, HullParams hull, boolean bookcaseRelief, @Nullable String bookcaseBackfill) {
		public Tileset {
			pins = Collections.unmodifiableMap(pins);
			conds = Collections.unmodifiableMap(conds);
			heights = Collections.unmodifiableMap(heights);
			propGround = Collections.unmodifiableMap(propGround);
			propBg = Collections.unmodifiableMap(propBg);
		}

		/** The class pinned on {@code tile}, or null. */
		public @Nullable String pin(int tile) {
			return this.pins.get(tile);
		}

		/** A class's height in Game Boy pixels for this tileset (0 for an unknown class). */
		public int height(String cls) {
			return this.heights.getOrDefault(cls, 0);
		}
	}

	/**
	 * What reading found, for the build report (counts only).
	 *
	 * @param unknown fields and classes skipped because this reader doesn't know them, by name
	 */
	public record Counts(int tilesets, int pins, int conds, int buildingEntries, int duplicatePins, Map<String, Integer> unknown) {
	}

	private final int version;
	private final String hash;
	private final Map<String, Tileset> tilesets;
	private final Map<String, List<LuaData.Table>> buildings;
	private final Counts counts;

	private Profile(int version, String hash, Map<String, Tileset> tilesets, Map<String, List<LuaData.Table>> buildings, Counts counts) {
		this.version = version;
		this.hash = hash;
		this.tilesets = Collections.unmodifiableMap(tilesets);
		this.buildings = Collections.unmodifiableMap(buildings);
		this.counts = counts;
	}

	/** The profile's {@code version} field. */
	public int version() {
		return this.version;
	}

	/** The SHA-256 of the text it was read from (lowercase hex): what keys and caches depend on. */
	public String hash() {
		return this.hash;
	}

	/** A tileset's entry by tileset id, or null when the profile says nothing about it. */
	public @Nullable Tileset tileset(String tilesetId) {
		return this.tilesets.get(tilesetId);
	}

	/** Every tileset entry by id. */
	public Map<String, Tileset> tilesets() {
		return this.tilesets;
	}

	/** A tileset's building entries in list order (placement priority), raw; empty when none. */
	public List<LuaData.Table> buildings(String tilesetId) {
		return this.buildings.getOrDefault(tilesetId, List.of());
	}

	public Counts counts() {
		return this.counts;
	}

	/** Reads a profile's text. */
	public static Profile parse(String text) throws LuaData.DataException {
		LuaData.Table root = LuaData.parse(text);
		Map<String, Integer> unknown = new TreeMap<>();
		Map<String, Integer> heights = new TreeMap<>(FALLBACK_HEIGHTS);
		LuaData.Table fileHeights = root.table("heights");
		if (fileHeights != null) {
			readHeights(fileHeights, heights, unknown);
		}
		Map<String, Tileset> tilesets = new TreeMap<>();
		int pins = 0;
		int conds = 0;
		int duplicates = 0;
		LuaData.Table sets = root.table("tilesets");
		if (sets != null) {
			for (Map.Entry<Object, Object> entry : sets.hash().entrySet()) {
				if (!(entry.getKey() instanceof String id) || !(entry.getValue() instanceof LuaData.Table table)) {
					unknown.merge("tilesets.?", 1, Integer::sum);
					continue;
				}
				int[] tally = new int[3];
				tilesets.put(id, readTileset(id, table, heights, unknown, tally));
				pins += tally[0];
				conds += tally[1];
				duplicates += tally[2];
			}
		}
		Map<String, List<LuaData.Table>> buildings = new TreeMap<>();
		int entries = 0;
		LuaData.Table groups = root.table("buildings");
		if (groups != null) {
			for (Map.Entry<Object, Object> entry : groups.hash().entrySet()) {
				if (!(entry.getKey() instanceof String id) || !(entry.getValue() instanceof LuaData.Table list)) {
					unknown.merge("buildings.?", 1, Integer::sum);
					continue;
				}
				List<LuaData.Table> out = new ArrayList<>();
				for (Object value : list.array()) {
					if (value instanceof LuaData.Table t) {
						out.add(t);
					} else {
						unknown.merge("buildings.entry", 1, Integer::sum);
					}
				}
				entries += out.size();
				buildings.put(id, List.copyOf(out));
			}
		}
		for (Object key : root.hash().keySet()) {
			if (!Set.of("version", "heights", "tilesets", "buildings").contains(key)) {
				unknown.merge("root." + key, 1, Integer::sum);
			}
		}
		Counts counts = new Counts(tilesets.size(), pins, conds, entries, duplicates, Collections.unmodifiableMap(unknown));
		return new Profile((int) root.integer("version", 0), Hashes.sha256(text), tilesets, buildings, counts);
	}

	private static void readHeights(LuaData.Table table, Map<String, Integer> into, Map<String, Integer> unknown) {
		for (Map.Entry<Object, Object> e : table.hash().entrySet()) {
			if (e.getKey() instanceof String cls && CLASSES.containsKey(cls) && e.getValue() instanceof Long h && h >= -16 && h <= 64) {
				into.put(cls, h.intValue());
			} else {
				unknown.merge("heights." + (e.getKey() instanceof String s && CLASSES.containsKey(s) ? s : "?"), 1, Integer::sum);
			}
		}
	}

	private static Tileset readTileset(String id, LuaData.Table table, Map<String, Integer> profileHeights, Map<String, Integer> unknown, int[] tally) {
		Map<Integer, String> pins = new LinkedHashMap<>();
		for (Map.Entry<Object, Object> e : table.hash().entrySet()) {
			if (!(e.getKey() instanceof String key) || TILESET_FIELDS.contains(key)) {
				continue;
			}
			if (!CLASSES.containsKey(key) || !(e.getValue() instanceof LuaData.Table group)) {
				unknown.merge("class", 1, Integer::sum);
				continue;
			}
			for (long tile : group.integers()) {
				if (tile < 0 || tile > MAX_TILE) {
					unknown.merge("tile", 1, Integer::sum);
				} else if (pins.putIfAbsent((int) tile, key) != null) {
					tally[2]++;
				} else {
					tally[0]++;
				}
			}
		}
		Map<Integer, List<Cond>> conds = new TreeMap<>();
		for (String side : List.of("when_above", "when_below")) {
			LuaData.Table rules = table.table(side);
			if (rules == null) {
				continue;
			}
			boolean above = side.equals("when_above");
			for (Map.Entry<Object, Object> e : rules.hash().entrySet()) {
				if (!(e.getKey() instanceof Long tile) || tile < 0 || tile > MAX_TILE || !(e.getValue() instanceof LuaData.Table list)) {
					unknown.merge(side, 1, Integer::sum);
					continue;
				}
				for (Object rule : list.array()) {
					Cond cond = rule instanceof LuaData.Table r ? cond(r, above) : null;
					if (cond == null) {
						unknown.merge(side, 1, Integer::sum);
						continue;
					}
					conds.computeIfAbsent(tile.intValue(), k -> new ArrayList<>()).add(cond);
					tally[1]++;
				}
			}
		}
		// above rules before below rules, each in file order: the loop above already reads when_above first
		conds.replaceAll((k, v) -> List.copyOf(v));
		Map<String, Integer> heights = new TreeMap<>(profileHeights);
		LuaData.Table own = table.table("heights");
		if (own != null) {
			readHeights(own, heights, unknown);
		}
		Map<Integer, Integer> propGround = new TreeMap<>();
		LuaData.Table grounds = table.table("prop_ground");
		if (grounds != null) {
			for (Map.Entry<Object, Object> e : grounds.hash().entrySet()) {
				if (e.getKey() instanceof Long tile && e.getValue() instanceof Long ground && tile >= 0 && tile <= MAX_TILE && ground >= 0 && ground <= MAX_TILE) {
					propGround.put(tile.intValue(), ground.intValue());
				} else {
					unknown.merge("prop_ground", 1, Integer::sum);
				}
			}
		}
		Map<Integer, Integer> propBg = new TreeMap<>();
		LuaData.Table backgrounds = table.table("prop_bg");
		if (backgrounds != null) {
			for (Object rule : backgrounds.array()) {
				LuaData.Table r = rule instanceof LuaData.Table t ? t : null;
				LuaData.Table tiles = r == null ? null : r.table("tiles");
				LuaData.Table shades = r == null ? null : r.table("shades");
				if (tiles == null || shades == null) {
					unknown.merge("prop_bg", 1, Integer::sum);
					continue;
				}
				int bits = 0;
				for (String shade : shades.strings()) {
					int k = SHADES.indexOf(shade);
					bits |= k < 0 ? 0 : 1 << k;
				}
				for (long tile : tiles.integers()) {
					if (tile >= 0 && tile <= MAX_TILE) {
						propBg.putIfAbsent((int) tile, bits);
					}
				}
			}
		}
		HullParams d = HullParams.DEFAULTS;
		HullParams hull = new HullParams(param(table, "stump_cap", d.stumpCap()), param(table, "can_cap", d.canCap()), param(table, "can_base", d.canBase()),
				param(table, "can_height", d.canHeight()), param(table, "can_well", d.canWell()), param(table, "can_taper", d.canTaper()));
		String backfill = table.string("bookcase_backfill");
		return new Tileset(id, pins, conds, heights, propGround, propBg, hull, table.bool("bookcase_relief", true),
				"above".equals(backfill) ? backfill : null);
	}

	private static @Nullable Cond cond(LuaData.Table rule, boolean above) {
		LuaData.Table tiles = rule.table(above ? "above" : "below");
		String cls = rule.string("class");
		if (tiles == null || cls == null || !CLASSES.containsKey(cls)) {
			return null;
		}
		Set<Integer> set = new java.util.TreeSet<>();
		for (long tile : tiles.integers()) {
			if (tile >= 0 && tile <= MAX_TILE) {
				set.add((int) tile);
			}
		}
		return new Cond(above, Collections.unmodifiableSet(set), cls);
	}

	private static int param(LuaData.Table table, String key, int fallback) {
		long value = table.integer(key, fallback);
		return value < 0 || value > 64 ? fallback : (int) value;
	}
}
