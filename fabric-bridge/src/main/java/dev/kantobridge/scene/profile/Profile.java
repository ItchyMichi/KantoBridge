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
	 * @param buildings  the tileset's building entries that read, in list order (P2)
	 */
	public record Tileset(String id, Map<Integer, String> pins, Map<Integer, List<Cond>> conds, Map<String, Integer> heights,
			Map<Integer, Integer> propGround, Map<Integer, Integer> propBg, HullParams hull, boolean bookcaseRelief, @Nullable String bookcaseBackfill,
			List<Building> buildings) {
		public Tileset {
			pins = Collections.unmodifiableMap(pins);
			conds = Collections.unmodifiableMap(conds);
			heights = Collections.unmodifiableMap(heights);
			propGround = Collections.unmodifiableMap(propGround);
			propBg = Collections.unmodifiableMap(propBg);
			buildings = List.copyOf(buildings);
		}

		/** This entry with its buildings. */
		Tileset withBuildings(List<Building> list) {
			return new Tileset(this.id, this.pins, this.conds, this.heights, this.propGround, this.propBg, this.hull, this.bookcaseRelief,
					this.bookcaseBackfill, list);
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
	 * A building's band table (docs/specs/PROFILE_PLAN.md §5.5): rows in the drawing's pixel rows, sizes in voxels (one
	 * per Game Boy pixel).
	 *
	 * @param ledge   the awning's rows {r0, r1} (absolute drawing rows), or null
	 * @param seal    the sides ({@code n}, {@code s}, {@code e}, {@code w}) the drawing runs off, which the flood doesn't seed
	 * @param depthPx the footprint's depth in voxels ({@code depthPx}, else 8 × ({@code depth} or the grid's rows))
	 * @param scrub   painted-over rects {x0, y0, x1, y1}, inclusive pixels
	 */
	public record Band(int roofRows, int roofBack, int roofFront, int cycle0, int cycle1, int slab, int frontEave, int @Nullable [] ledge,
			String seal, int depthPx, boolean panes, List<int[]> scrub) {
	}

	/**
	 * One building entry, in list order (its placement priority).
	 *
	 * @param tiles     the grid that matches, north row first; a row compares as many ids as it has, the grid is as wide as
	 *                  its first row
	 * @param topRows   rows drawn above the grid for the model only (never matched)
	 * @param claimOnly claims its cells and draws nothing (the other half of a drawing that straddles two maps)
	 * @param keep      ids the claim leaves to the other passes
	 * @param band      the band table (for a furniture entry its measuring fields only), null for a claim-only entry
	 * @param parts     a furniture entry's pieces (the parts path, P3), null for a roof-path or claim-only entry
	 */
	public record Building(int index, List<int[]> tiles, List<int[]> topRows, boolean claimOnly, Set<Integer> keep, @Nullable Band band,
			@Nullable Parts parts) {
		/** A roof-path or claim-only entry. */
		public Building(int index, List<int[]> tiles, List<int[]> topRows, boolean claimOnly, Set<Integer> keep, @Nullable Band band) {
			this(index, tiles, topRows, claimOnly, keep, band, null);
		}

		/** The grid's width in tiles. */
		public int width() {
			return this.tiles.get(0).length;
		}

		/** The drawing's height in pixel rows: topRows and the grid. */
		public int drawnPx() {
			return 8 * (this.topRows.size() + this.tiles.size());
		}
	}

	/**
	 * A furniture entry's pieces (the research's parts path, P3): a base piece (a tray, or a desk with an optional wall
	 * band behind it, or none) and the parts riding its plane, in list order (a later piece overwrites what an earlier one
	 * left in the same voxel). Rows are the drawing's pixel rows (topRows first), x its pixel columns; sizes, depths and
	 * layers in voxels (one per Game Boy pixel).
	 *
	 * @param plane the layer the parts stand on: a tray's wall height, a desk's base and fascia rows, else 0 (the floor)
	 */
	public record Parts(@Nullable Tray tray, @Nullable Desk desk, @Nullable WallBand wall, List<Part> list, int plane) {
		/**
		 * The model's highest layer at most: the base piece's lid and each part's top, a box part's from the drawing's bottom
		 * row (the lowest its measured ground line can lie).
		 */
		public int ytop(int drawnPx) {
			int top = this.tray != null || this.desk != null ? this.plane - 1 : 0;
			for (Part part : this.list) {
				top = Math.max(top, part.top(this.plane, drawnPx));
			}
			return Math.max(0, top);
		}
	}

	/** An open box the drawing looks down into: its far rim's rows, its front wall's rows, its outer and opening columns, its floor's top layer. */
	public record Tray(int top0, int top1, int front0, int front1, int x0, int x1, int inner0, int inner1, int floor) {
	}

	/**
	 * A desk: its fascia rows (the lid's edge and the band under it), its base rows (standing on the floor), its box's
	 * depth (0: the footprint's) and back row, its columns, and the rows its lid shows (null: a lid of the drawing's own
	 * shades, its field white or grey).
	 */
	public record Desk(int fascia0, int fascia1, int base0, int base1, int depthPx, int z, int x0, int x1, int @Nullable [] top,
			boolean whiteLid) {
	}

	/** The wall band behind a desk: its stripe's rows (cycled down from its top), the column it reads, its height and depth. */
	public record WallBand(int cycle0, int cycle1, int x, int h, int depthPx) {
	}

	/** One part of a furniture entry, over the drawing's columns {@code x0..x1}. */
	public sealed interface Part permits FlatPart, BoxPart, IsoPart, UprightPart {
		int x0();

		int x1();

		/** Its top layer, riding a plane at {@code plane}, in a drawing {@code drawnPx} rows high. */
		int top(int plane, int drawnPx);
	}

	/** A top-view decal: rows {@code r0..r1} laid from depth {@code z}, on layers {@code at - thick + 1..at}. */
	public record FlatPart(int x0, int x1, int r0, int r1, int z, int at, int thick) implements Part {
		@Override
		public int top(int plane, int drawnPx) {
			return this.at;
		}
	}

	/**
	 * A face-on box at its own drawn elevation over the ground line: rows {@code r0..r1}, {@code depth} voxels from
	 * {@code z}, its bottom layer ({@code -1}: where its last row meets the ground line), the rows it cycles.
	 */
	public record BoxPart(int x0, int x1, int r0, int r1, int depth, int z, int base, int c0, int c1) implements Part {
		@Override
		public int top(int plane, int drawnPx) {
			return drawnPx - 1 - this.r0;
		}
	}

	/** A box drawn in 2:1 isometric: rows {@code r0..r1}, its depth centre {@code z}, its radius in plan. */
	public record IsoPart(int x0, int x1, int r0, int r1, int z, int plan) implements Part {
		/** The drawn z radius: half the x radius. */
		public int rz() {
			return (this.x1 - this.x0 + 1) / 2 / 2;
		}

		/** Its height above the plane: the drawn rows less both rhombi. */
		public int height() {
			return this.r1 - 2 * this.rz() - this.r0;
		}

		@Override
		public int top(int plane, int drawnPx) {
			return plane + this.height();
		}
	}

	/**
	 * A lid over a facade: the lid's rows (back to front) and the facade's (its first the lid's front edge), its depth
	 * and back row, its rise off the plane, a sunk rect {x0, x1, r0, r1} (null: none), the lid's rows spread over its
	 * depth.
	 */
	public record UprightPart(int x0, int x1, int t0, int t1, int f0, int f1, int depth, int rise, int z, int @Nullable [] inset, boolean stretch)
			implements Part {
		@Override
		public int top(int plane, int drawnPx) {
			return plane + this.rise + (this.f1 - this.f0);
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
	private final Map<String, List<Building>> readBuildings;
	private final Counts counts;

	private Profile(int version, String hash, Map<String, Tileset> tilesets, Map<String, List<LuaData.Table>> buildings,
			Map<String, List<Building>> readBuildings, Counts counts) {
		this.version = version;
		this.hash = hash;
		this.tilesets = Collections.unmodifiableMap(tilesets);
		this.buildings = Collections.unmodifiableMap(buildings);
		this.readBuildings = Collections.unmodifiableMap(readBuildings);
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

	/**
	 * A tileset's building entries that read (roof-path band tables and claim-only twins), in list order; an entry with
	 * {@code parts} (furniture assembled from parts, P3) or missing a field is left out and counted.
	 */
	public List<Building> buildingSpecs(String tilesetId) {
		return this.readBuildings.getOrDefault(tilesetId, List.of());
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
		Map<String, List<Building>> readBuildings = new TreeMap<>();
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
				List<Building> read = new ArrayList<>();
				for (int k = 0; k < out.size(); k++) {
					Building building = building(k, out.get(k), unknown);
					if (building != null) {
						read.add(building);
					}
				}
				readBuildings.put(id, List.copyOf(read));
			}
		}
		// Each tileset's entry carries its buildings; a tileset with buildings and no pins gets an entry of its own.
		for (Map.Entry<String, List<Building>> e : readBuildings.entrySet()) {
			Tileset t = tilesets.get(e.getKey());
			if (t == null) {
				t = new Tileset(e.getKey(), Map.of(), Map.of(), heights, Map.of(), Map.of(), HullParams.DEFAULTS, true, null, List.of());
			}
			tilesets.put(e.getKey(), t.withBuildings(e.getValue()));
		}
		for (Object key : root.hash().keySet()) {
			if (!Set.of("version", "heights", "tilesets", "buildings").contains(key)) {
				unknown.merge("root." + key, 1, Integer::sum);
			}
		}
		Counts counts = new Counts(tilesets.size(), pins, conds, entries, duplicates, Collections.unmodifiableMap(unknown));
		return new Profile((int) root.integer("version", 0), Hashes.sha256(text), tilesets, buildings, readBuildings, counts);
	}

	/** One building entry read and checked, or null (counted under {@code buildings.*}). */
	private static @Nullable Building building(int index, LuaData.Table entry, Map<String, Integer> unknown) {
		List<int[]> tiles = rows(entry.table("tiles"));
		if (tiles == null || tiles.isEmpty() || tiles.get(0).length == 0) {
			unknown.merge("buildings.tiles", 1, Integer::sum);
			return null;
		}
		List<int[]> topRows = entry.table("topRows") == null ? List.of() : rows(entry.table("topRows"));
		if (topRows == null) {
			unknown.merge("buildings.topRows", 1, Integer::sum);
			return null;
		}
		Set<Integer> keep = new java.util.TreeSet<>();
		LuaData.Table keepTable = entry.table("keep");
		if (keepTable != null) {
			for (long tile : keepTable.integers()) {
				keep.add((int) tile);
			}
		}
		if (entry.bool("claimOnly", false)) {
			return new Building(index, tiles, topRows, true, keep, null);
		}
		// Furniture (P3): with parts, the roof fields are measuring fields only.
		boolean partsPath = entry.get("parts") != null;
		long roofRows = entry.integer("roofRows", -1);
		long slab = entry.integer("slab", -1);
		LuaData.Table cycle = entry.table("roofCycle");
		List<Long> cycleRows = cycle == null ? List.of() : cycle.integers();
		long roofBack = entry.integer("roofBack", -1);
		long roofFront = entry.integer("roofFront", -1);
		int width = tiles.get(0).length;
		int drawnRows = 8 * (topRows.size() + tiles.size());
		if (roofRows < 0 || roofRows > drawnRows || slab < 0 || slab > 32 || (!partsPath && roofRows > 0
				&& (cycleRows.size() != 2 || roofBack < 0 || roofFront < 0 || cycleRows.get(0) < 0 || cycleRows.get(1) < cycleRows.get(0)
						|| cycleRows.get(1) >= roofRows))) {
			unknown.merge("buildings.band", 1, Integer::sum);
			return null;
		}
		int[] ledge = null;
		LuaData.Table ledgeTable = partsPath ? null : entry.table("ledge");
		if (ledgeTable != null) {
			List<Long> rows = ledgeTable.integers();
			if (rows.size() != 2 || rows.get(0) < 0 || rows.get(1) < rows.get(0) || rows.get(1) >= drawnRows) {
				unknown.merge("buildings.ledge", 1, Integer::sum);
				return null;
			}
			ledge = new int[] {rows.get(0).intValue(), rows.get(1).intValue()};
		}
		String seal = entry.string("seal");
		seal = seal == null ? "" : seal;
		if (!seal.matches("[nsew]*")) {
			unknown.merge("buildings.seal", 1, Integer::sum);
			return null;
		}
		long depthPx = entry.integer("depthPx", -1);
		if (depthPx < 0) {
			depthPx = 8 * entry.integer("depth", tiles.size());
		}
		if (depthPx < 1 || depthPx > 8 * 64) {
			unknown.merge("buildings.depth", 1, Integer::sum);
			return null;
		}
		List<int[]> scrub = new ArrayList<>();
		LuaData.Table scrubTable = entry.table("scrub");
		if (scrubTable != null) {
			for (Object rect : scrubTable.array()) {
				List<Long> r = rect instanceof LuaData.Table t ? t.integers() : List.of();
				if (r.size() != 4 || r.get(0) < 0 || r.get(1) < 0 || r.get(2) < r.get(0) || r.get(3) < r.get(1) || r.get(2) >= 8L * width
						|| r.get(3) >= drawnRows) {
					unknown.merge("buildings.scrub", 1, Integer::sum);
					return null;
				}
				scrub.add(new int[] {r.get(0).intValue(), r.get(1).intValue(), r.get(2).intValue(), r.get(3).intValue()});
			}
		}
		Parts parts = null;
		if (partsPath) {
			String[] why = {"list"};
			parts = parts(entry, 8 * width, drawnRows, why);
			if (parts == null) {
				// Which piece was malformed, by name only (counts, never values).
				unknown.merge("buildings.parts." + why[0], 1, Integer::sum);
				return null;
			}
		}
		int c0 = !partsPath && cycleRows.size() == 2 ? cycleRows.get(0).intValue() : 0;
		int c1 = !partsPath && cycleRows.size() == 2 ? cycleRows.get(1).intValue() : 0;
		int frontEave = partsPath ? 0 : (int) Math.max(0, Math.min(16, entry.integer("frontEave", 0)));
		Band band = new Band((int) roofRows, partsPath ? 0 : (int) Math.max(0, roofBack), partsPath ? 0 : (int) Math.max(0, roofFront), c0, c1,
				(int) slab, frontEave, ledge, seal, (int) depthPx, entry.bool("panes", true), List.copyOf(scrub));
		return new Building(index, tiles, topRows, false, keep, band, parts);
	}

	/** The largest layer, depth or height a piece may name, in voxels. */
	private static final int MAX_VOXELS = 512;

	/**
	 * A furniture entry's pieces in a drawing {@code w} pixels wide and {@code h} rows high, or null when one is malformed:
	 * a tray, else a desk (and its wall band), else no base; then each part, by its {@code kind} ({@code flat},
	 * {@code box}, {@code iso}; anything else an upright lid over a facade).
	 */
	private static @Nullable Parts parts(LuaData.Table entry, int w, int h, String[] why) {
		LuaData.Table list = entry.table("parts");
		if (list == null) {
			return null;
		}
		Tray tray = null;
		Desk desk = null;
		WallBand wall = null;
		LuaData.Table t = entry.table("tray");
		LuaData.Table d = entry.table("desk");
		if (t != null) {
			why[0] = "tray";
			int[] top = span(t, "top", h);
			int[] front = span(t, "front", h);
			int[] x = span(t, "x", w);
			int[] inner = span(t, "inner", w);
			long floor = t.integer("floor", 0);
			if (top == null || front == null || x == null || inner == null || floor < 0 || floor > MAX_VOXELS) {
				return null;
			}
			tray = new Tray(top[0], top[1], front[0], front[1], x[0], x[1], inner[0], inner[1], (int) floor);
		} else if (d != null) {
			why[0] = "desk";
			int[] fascia = span(d, "fascia", h);
			int[] base = span(d, "base", h);
			int[] x = d.get("x") == null ? new int[] {0, w - 1} : span(d, "x", w);
			int[] top = d.get("top") == null ? null : span(d, "top", h);
			long depth = d.integer("depthPx", d.get("depth") == null ? 0 : 8 * d.integer("depth", -1));
			long z = d.integer("z", 0);
			if (fascia == null || base == null || x == null || (d.get("top") != null && top == null) || depth < 0 || depth > MAX_VOXELS || z < 0
					|| z >= MAX_VOXELS) {
				return null;
			}
			desk = new Desk(fascia[0], fascia[1], base[0], base[1], (int) depth, (int) z, x[0], x[1], top, "white".equals(d.string("lid")));
			LuaData.Table band = entry.table("wall");
			if (band != null) {
				why[0] = "wall";
				int[] cycle = span(band, "cycle", h);
				long wx = band.integer("x", 0);
				long wh = band.integer("h", -1);
				long wd = band.integer("depthPx", -1);
				if (cycle == null || wx < 0 || wx >= w || wh < 1 || wh > MAX_VOXELS || wd < 1 || wd > MAX_VOXELS) {
					return null;
				}
				wall = new WallBand(cycle[0], cycle[1], (int) wx, (int) wh, (int) wd);
			}
		}
		int plane = tray != null ? tray.front1() - tray.front0() + 1
				: desk != null ? (desk.base1() - desk.base0() + 1) + (desk.fascia1() - desk.fascia0() + 1) : 0;
		List<Part> parts = new ArrayList<>();
		for (Object o : list.array()) {
			if (!(o instanceof LuaData.Table p)) {
				why[0] = "list";
				return null;
			}
			String kind = p.string("kind");
			why[0] = kind == null || !Set.of("flat", "box", "iso").contains(kind) ? "upright" : kind;
			Part part = part(p, w, h, plane);
			if (part == null && why[0].equals("upright")) {
				// The first field that fails, by name.
				why[0] += span(p, "x", w) == null ? ".x" : span(p, "top", h) == null ? ".top" : span(p, "facade", h) == null ? ".facade"
						: p.integer("depth", -1) < 1 || p.integer("depth", -1) > MAX_VOXELS ? ".depth" : ".other";
			}
			if (part == null) {
				return null;
			}
			parts.add(part);
		}
		return new Parts(tray, desk, wall, List.copyOf(parts), plane);
	}

	/** One part, its pixel-free defaults filled in, or null when malformed. */
	private static @Nullable Part part(LuaData.Table p, int w, int h, int plane) {
		int[] x = span(p, "x", w);
		if (x == null) {
			return null;
		}
		String kind = p.string("kind");
		switch (kind == null ? "" : kind) {
			case "flat" -> {
				int[] rows = span(p, "rows", h);
				if (rows == null) {
					return null;
				}
				long z = p.integer("z", rows[0]);
				long at = p.integer("at", plane);
				long thick = p.integer("thick", 1);
				return z < 0 || z >= MAX_VOXELS || at < 0 || at >= MAX_VOXELS || thick < 1 || thick > MAX_VOXELS ? null
						: new FlatPart(x[0], x[1], rows[0], rows[1], (int) z, (int) at, (int) thick);
			}
			case "box" -> {
				int[] rows = span(p, "rows", h);
				if (rows == null) {
					return null;
				}
				int[] cycle = p.get("cycle") == null ? new int[] {rows[1], rows[1]} : span(p, "cycle", h);
				long depth = p.integer("depth", -1);
				long z = p.integer("z", 0);
				long base = p.integer("base", -1);
				return cycle == null || depth < 1 || depth > MAX_VOXELS || z < 0 || z >= MAX_VOXELS || base < -1 || base >= MAX_VOXELS
						|| (p.get("base") != null && base < 0) ? null : new BoxPart(x[0], x[1], rows[0], rows[1], (int) depth, (int) z, (int) base, cycle[0], cycle[1]);
			}
			case "iso" -> {
				int[] rows = span(p, "rows", h);
				long z = p.integer("z", -1);
				if (rows == null || z < 0 || z >= MAX_VOXELS) {
					return null;
				}
				IsoPart probe = new IsoPart(x[0], x[1], rows[0], rows[1], (int) z, 1);
				long plan = p.integer("plan", probe.rz());
				return plan < 1 || plan > MAX_VOXELS ? null : new IsoPart(x[0], x[1], rows[0], rows[1], (int) z, (int) plan);
			}
			default -> {
				int[] top = span(p, "top", h);
				int[] facade = span(p, "facade", h);
				long depth = p.integer("depth", -1);
				long rise = p.integer("rise", 0);
				long z = p.integer("z", 0);
				int[] inset = null;
				LuaData.Table in = p.table("inset");
				if (in != null) {
					int[] ix = span(in, "x", w);
					int[] ir = span(in, "rows", h);
					if (ix == null || ir == null) {
						return null;
					}
					inset = new int[] {ix[0], ix[1], ir[0], ir[1]};
				}
				// A negative rise lowers the part under the plane (the model clips it at the floor).
				return top == null || facade == null || depth < 1 || depth > MAX_VOXELS || rise <= -MAX_VOXELS || rise >= MAX_VOXELS || z < 0 || z >= MAX_VOXELS
						? null : new UprightPart(x[0], x[1], top[0], top[1], facade[0], facade[1], (int) depth, (int) rise, (int) z, inset, p.bool("stretch", false));
			}
		}
	}

	/** A pair {a, b} of integers with 0 &lt;= a &lt;= b &lt; {@code limit}, or null. */
	private static int @Nullable [] span(LuaData.Table table, String key, int limit) {
		LuaData.Table pair = table.table(key);
		List<Long> v = pair == null ? List.of() : pair.integers();
		if (v.size() != 2 || pair.array().size() != 2 || v.get(0) < 0 || v.get(1) < v.get(0) || v.get(1) >= limit) {
			return null;
		}
		return new int[] {v.get(0).intValue(), v.get(1).intValue()};
	}

	/** Rows of tile ids (each a list of integers within the atlas), or null when any isn't. */
	private static @Nullable List<int[]> rows(LuaData.@Nullable Table table) {
		if (table == null) {
			return null;
		}
		List<int[]> out = new ArrayList<>();
		for (Object row : table.array()) {
			if (!(row instanceof LuaData.Table r) || r.array().isEmpty()) {
				return null;
			}
			int[] ids = new int[r.array().size()];
			for (int k = 0; k < ids.length; k++) {
				if (!(r.array().get(k) instanceof Long id) || id < 0 || id > MAX_TILE) {
					return null;
				}
				ids[k] = id.intValue();
			}
			out.add(ids);
		}
		return out;
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
				"above".equals(backfill) ? backfill : null, List.of());
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
