package dev.kantobridge.builder.plan;

import dev.kantobridge.block.EdgeWallBlock;
import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.export.G3Map;
import dev.kantobridge.builder.export.G3Tiles;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapLayout;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * Raised ground from the regions a game's data draws (docs/specs/HEIGHTS_ALL_GAMES_PLAN.md D3, W4), for the maps of the
 * 3D area. A region is the floor cells of one level key that touch, where the engine lets a player walk (Emerald: one
 * elevation; Crystal: any floor, its one-way walls closing the edges between levels). The heights come from the stairs
 * between regions, never from the keys themselves: Emerald's Victory Road climbs five tiers with two elevations.
 *
 * <ol>
 * <li>Cells that join any level (Emerald's elevation 0) and touch regions of one key are that level. Touching two keys,
 * a straight strip whose ends meet them is a stair: each of its cells a ramp climbing {@link #STAIR_RISE} blocks (a
 * stair cuts through the face, a cell of face per cell of stairs), the higher key's side at its top. Stairs a drawing
 * names ({@link FloorKinds}: Crystal's) climb the way it says.</li>
 * <li>Water stands as high as the regions of the shore key it touches (Emerald: elevation 3, the only one surfing stops
 * onto).</li>
 * <li>Regions no stairs join to the rest: a short face (up to {@link #MAX_FACE} solid cells straight between two regions
 * of different keys) sets the northern one two blocks a cell over the southern one, and where walls are a step (Crystal's
 * caves, as Kanto's tile pairs) the region south of a wall stands two over the one north of it; else a region takes the
 * height its key has most elsewhere in the map; else the map's floor level.</li>
 * <li>Rock stands on the floor it is seen from, the one south of its run of solid cells (a face rises from its lower
 * floor); under a raised cell the builder fills rock down to the map's floor level, as for {@link CaveLevels}.</li>
 * <li>The check (as {@link CaveLevels}'): Minecraft must allow exactly the steps the engine allows between neighbouring
 * cells: two cells the engine joins stand at one height or meet as a stair's ends; a ledge hops between two cells at
 * its own height. Two-floor cells (Emerald's bridges, elevation 15) wait for the outdoor phase (W6). Any failure leaves
 * the map flat, with its reason.</li>
 * </ol>
 *
 * Pure Java.
 */
public final class RaisedRegions {
	/** Blocks a stair cell climbs: the two tile rows of the face cell it cuts through. */
	public static final int STAIR_RISE = 2;
	/**
	 * The blocks a cell of stairs or of face climbs outdoors (HEIGHTS_ALL_GAMES_PLAN.md D4): the landscape models' scale, 1.5
	 * blocks a tile row (LANDSCAPE_MODELS.md; the user's choice of 2026-10-21), so the ground behind a cliff meets its top.
	 */
	public static final int OUTDOOR_RISE = 3;
	/**
	 * Outdoors the blocks a ledge drops (HEIGHTS_ALL_GAMES_PLAN.md D4, the user's "small drops", 2026-10-22): the lip the
	 * game draws, about a third of a cell.
	 */
	public static final int LEDGE_DROP = 1;
	/** The longest run of solid cells, in cells, read as a face between two regions (a longer one is rock, not a face). */
	static final int MAX_FACE = 3;
	/** Emerald's elevation that joins any other. */
	static final int G3_ANY = 0;
	/** Emerald's elevation of a bridge: the walker keeps its own. */
	static final int G3_BRIDGE = 15;
	/** Emerald's only elevation surfing stops onto. */
	static final int G3_SHORE = 3;
	/** Emerald's elevation of the water's surface: a floor cell on it is one nobody walks to (a launch tower drawn over the sea). */
	static final int G3_SURFACE = 1;
	/** Outdoors floor cells fewer than this, nothing walkable round them (a cell under a tree), stand as the ground nearest them. */
	static final int POCKET = 4;

	/**
	 * What became of a map.
	 *
	 * @param layout      its levels and ramps (the whole map one piece), or null when it stays flat
	 * @param outcome     {@code raised}, or {@code flat_<reason>}
	 * @param regions     its regions
	 * @param raisedCells floor cells above the map's floor level
	 * @param stairs      its stairs (strips of ramps)
	 * @param maxLevel    the highest floor or ramp top, in blocks over the map's floor level
	 * @param skipped     outdoors: the rules skipped because they disagreed with those before them (a stair, a strip of
	 *                    joining cells, a bank of water)
	 * @param breaks      outdoors: a cell of each edge Minecraft can't walk as the engine does (where a rule was skipped)
	 */
	public record Result(@Nullable MapLayout layout, String outcome, int regions, int raisedCells, int stairs, int maxLevel, int skipped,
			List<Integer> breaks) {
		static Result flat(String reason, int regions) {
			return new Result(null, reason, regions, 0, 0, 0, 0, List.of());
		}
	}

	/**
	 * A map's cells as the solver reads them.
	 *
	 * @param key       per cell, row by row: a floor cell's level key (ground or ledge), -1 for any other cell
	 * @param joins     per cell: a floor cell that joins any level (Emerald's elevation 0); its key is -1
	 * @param bridge    per cell: a floor cell that is two floors at once (Emerald's elevation 15); its key is -1
	 * @param shore     the key water stands level with (the only one the engine lets a surfer step onto), -1 for any
	 * @param climb     per cell, a stairs cell's way up as its drawing says (Crystal's; outdoors the landscape marks' slopes),
	 *                  else null; its key is -1; or null
	 * @param walls     edges the engine closes between neighbouring cells (Crystal's one-way walls), or null
	 * @param wallsRise a wall's south side stands {@link #STAIR_RISE} over its north side (a cave's plateau edges)
	 * @param maxFace   the longest run of solid cells read as a face
	 * @param faceKeys  faces count only between regions of different keys (Emerald: a wall between two corridors of one
	 *                  elevation is no step)
	 * @param room      a room's rules (D5: a house, a gym on a layer): a stair is cut through a wall (solid on both its sides);
	 *                  cells joining two keys that are no such stair (a seat a room draws the player over) join them at
	 *                  one height
	 * @param rise      the blocks a cell of stairs or of face climbs: {@link #STAIR_RISE} underground, {@link #OUTDOOR_RISE}
	 *                  outdoors (the landscape models' scale, the user's choice of 2026-10-21)
	 * @param outdoor   outdoors (the levels pilot, HEIGHTS_ALL_GAMES_PLAN.md D4): a bridge's cells are its deck, at the level
	 *                  of the floors its ends meet (until the two-floor cells of W6; else a bridge leaves the map flat); a
	 *                  rule that disagrees with those before it is skipped and the edges it leaves are counted as breaks,
	 *                  rather than leaving the map flat; the floor along the map's edge is the floor level. With
	 *                  {@code climb} (the drawing's stairs marked) a strip of joining cells that is no marked stair keeps
	 *                  one height where it can: the drawing decides, the data's elevations keep areas apart and join them
	 * @param falls     per cell, a waterfall (Emerald's metatile behaviour), or null: water no body flows across, so the
	 *                  river over it and the one under it stand at their own banks' heights; it stands at the one under it
	 */
	public record Input(MapModel model, int[] key, boolean[] joins, boolean[] bridge, int shore, Dir @Nullable [] climb, @Nullable PairEdges walls,
			boolean wallsRise, int maxFace, boolean faceKeys, boolean room, int rise, boolean outdoor, boolean @Nullable [] falls) {
		/** Emerald's: elevations as keys, no walls of their own. */
		public Input(MapModel model, int[] key, boolean[] joins, boolean[] bridge, int shore) {
			this(model, key, joins, bridge, shore, null, null, false, MAX_FACE, true, false);
		}

		/** Underground: stairs and faces climb {@link #STAIR_RISE} a cell, a bridge leaves the map flat. */
		public Input(MapModel model, int[] key, boolean[] joins, boolean[] bridge, int shore, Dir @Nullable [] climb, @Nullable PairEdges walls,
				boolean wallsRise, int maxFace, boolean faceKeys, boolean room) {
			this(model, key, joins, bridge, shore, climb, walls, wallsRise, maxFace, faceKeys, room, STAIR_RISE, false, null);
		}
	}

	private RaisedRegions() {
	}

	/** Emerald's raised ground in the 3D area: each walkable cell's elevation is its key (0 joins any level, 15 is a bridge). */
	public static Result gen3(MapModel model, G3Map map) {
		return gen3(model, map, false);
	}

	/**
	 * The same for any map: {@code room} for a room on a layer (D5), whose stairs are cut through walls and whose other
	 * changes of elevation are the engine's drawing order, not heights (a seat, a counter's back). A warp cell joins any
	 * level (an escalator's cell keeps the elevation of the floor it leads to).
	 */
	public static Result gen3(MapModel model, G3Map map, boolean room) {
		Input in = emerald(model, map, room);
		return solve(in);
	}

	/** Emerald's cells as the solver reads them (see {@link #gen3(MapModel, G3Map, boolean)}). */
	private static Input emerald(MapModel model, G3Map map, boolean room) {
		int w = model.cellWidth();
		int h = model.cellHeight();
		if (w != map.w() || h != map.h()) {
			throw new IllegalArgumentException("the model is not the map's");
		}
		int[] key = new int[w * h];
		boolean[] joins = new boolean[w * h];
		boolean[] bridge = new boolean[w * h];
		Arrays.fill(key, -1);
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				if (!floor(model.kind(cx, cy))) {
					continue;
				}
				int i = cy * w + cx;
				int elevation = map.elev(cx, cy);
				if (elevation == G3_ANY) {
					joins[i] = true;
				} else if (elevation == G3_BRIDGE) {
					bridge[i] = true;
				} else {
					key[i] = elevation;
				}
			}
		}
		for (G3Map.Warp warp : map.warps()) {
			int i = warp.y() * w + warp.x();
			if (warp.x() >= 0 && warp.y() >= 0 && warp.x() < w && warp.y() < h && key[i] >= 0) {
				key[i] = -1;
				joins[i] = true;
			}
		}
		return new Input(model, key, joins, bridge, G3_SHORE, null, null, false, room ? 0 : MAX_FACE, true, room);
	}

	/**
	 * Emerald's raised ground outdoors (the levels pilot, HEIGHTS_ALL_GAMES_PLAN.md D3, D4): its elevations as for the 3D
	 * area, a cell of stairs or of face climbing {@link #OUTDOOR_RISE} blocks, a bridge its deck; a map of one walkable
	 * elevation takes its faces as steps instead.
	 */
	public static Result gen3Outdoor(MapModel model, G3Map map) {
		return gen3Outdoor(model, map, null, null);
	}

	/** Emerald's primary tilesets hold metatiles 0 to 511; a map's secondary one the rest. */
	static final int G3_PRIMARY_METATILES = 512;

	/**
	 * Emerald's drawn stairs and slopes: per floor cell its way up, else null. A slope the engine pulls a walker down
	 * (MB_CYCLING_ROAD_PULL_DOWN: Route 119's sand slope, climbed by Mach Bike) climbs north; a metatile the jar's table
	 * names ({@link FloorKinds}, {@code floor_kinds/emerald.json}, keyed {@code <tileset>/<metatile>}) climbs as it says.
	 */
	public static Dir[] gen3Stairs(MapModel model, G3Map map, G3Tiles tiles) {
		int w = model.cellWidth();
		int h = model.cellHeight();
		Dir[] out = new Dir[w * h];
		String[] pair = map.pair().split("__", 2);
		Map<String, Dir> table = FloorKinds.table("emerald");
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				if (!floor(model.kind(cx, cy)) || !map.inGrid(cx, cy)) {
					continue;
				}
				int mid = map.mid(cx, cy);
				String behavior = tiles.behaviorName(mid);
				if (behavior != null && behavior.endsWith("CYCLING_ROAD_PULL_DOWN")) {
					out[cy * w + cx] = Dir.UP;
					continue;
				}
				String tileset = mid < G3_PRIMARY_METATILES || pair.length < 2 ? pair[0] : pair[1];
				out[cy * w + cx] = table.get(tileset + "/" + mid);
			}
		}
		return out;
	}

	/** Emerald's waterfalls: the water cells whose metatile behaviour is a waterfall's. */
	public static boolean[] gen3Falls(MapModel model, G3Map map, G3Tiles tiles) {
		int w = model.cellWidth();
		int h = model.cellHeight();
		boolean[] out = new boolean[w * h];
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				if (model.kind(cx, cy) == MapModel.Kind.WATER && map.inGrid(cx, cy)) {
					String behavior = tiles.behaviorName(map.mid(cx, cy));
					out[cy * w + cx] = behavior != null && behavior.contains("WATERFALL");
				}
			}
		}
		return out;
	}

	/**
	 * The same, and with {@code slopes} (per cell the way up of a slope or stairs the landscape marks name: the map's drawing
	 * checked for them) the drawing decides, as the elevations are the engine's layers and not heights (Route 119's floor,
	 * its sand slope's top and its plateau share one): the marked cells climb {@link #OUTDOOR_RISE} a cell, a strip of
	 * elevation 0 no mark names keeps one height where it can, and the faces count between any two areas. {@code falls}
	 * ({@link #gen3Falls}, or null) part a river's water.
	 */
	public static Result gen3Outdoor(MapModel model, G3Map map, Dir @Nullable [] slopes, boolean @Nullable [] falls) {
		Input in = surfaceAside(emerald(model, map, false), map);
		if (slopes != null) {
			int n = in.key().length;
			if (slopes.length != n) {
				throw new IllegalArgumentException("the slopes are not the map's");
			}
			int[] key = in.key().clone();
			boolean[] joins = in.joins().clone();
			Dir[] climb = new Dir[n];
			for (int i = 0; i < n; i++) {
				if (slopes[i] != null && (key[i] >= 0 || joins[i])) {
					climb[i] = slopes[i];
					key[i] = -1;
					joins[i] = false;
				}
			}
			return solve(new Input(model, key, joins, in.bridge(), G3_SHORE, climb, null, false, MAX_FACE, false, false, OUTDOOR_RISE, true,
					falls));
		}
		Result data = solve(new Input(model, in.key(), in.joins(), in.bridge(), G3_SHORE, null, null, false, MAX_FACE, true, false, OUTDOOR_RISE,
				true, falls));
		if (!data.outcome().startsWith("flat_one")) {
			return data;
		}
		// One walkable elevation: the drawing's faces are the steps, as for Crystal and Blue (ground enclosed behind a short
		// face, an island's walkable pits, stands at the face's top)
		return solve(new Input(model, in.key(), in.joins(), in.bridge(), G3_SHORE, null, null, false, MAX_FACE, false, false, OUTDOOR_RISE, true,
				falls));
	}

	/**
	 * {@code in} with the floor cells on the water's surface (elevation {@link #G3_SURFACE}: Mossdeep's launch tower drawn over
	 * the sea, cells nobody walks to) taken out of the regions: they stand as the ground or water nearest them, so no raised
	 * ground is filled under a tall thing's drawing.
	 */
	static Input surfaceAside(Input in, G3Map map) {
		int w = in.model().cellWidth();
		int[] key = in.key().clone();
		for (int i = 0; i < key.length; i++) {
			if (key[i] >= 0 && map.elev(i % w, i / w) == G3_SURFACE) {
				key[i] = -1;
			}
		}
		return new Input(in.model(), key, in.joins(), in.bridge(), in.shore(), in.climb(), in.walls(), in.wallsRise(), in.maxFace(), in.faceKeys(),
				in.room(), in.rise(), in.outdoor(), in.falls());
	}

	/**
	 * Raised ground outdoors where the data has no heights (Crystal, Blue; the levels pilot): every floor cell one key,
	 * {@code walls} (the engine's one-way edges, or null) keeping areas apart, and the faces between areas their steps, a
	 * cell of face climbing {@link #OUTDOOR_RISE} blocks, so the ground behind a cliff stands at its top.
	 */
	public static Result outdoor(MapModel model, @Nullable PairEdges walls) {
		return outdoor(model, walls, null);
	}

	/** The same, the cells {@code slopes} names (the landscape marks' slopes and stairs, per cell its way up) climbing. */
	public static Result outdoor(MapModel model, @Nullable PairEdges walls, Dir @Nullable [] slopes) {
		int w = model.cellWidth();
		int h = model.cellHeight();
		if (slopes != null && slopes.length != w * h) {
			throw new IllegalArgumentException("the slopes are not the map's");
		}
		int[] key = new int[w * h];
		Dir[] climb = slopes == null ? null : new Dir[w * h];
		Arrays.fill(key, -1);
		for (int i = 0; i < w * h; i++) {
			if (floor(model.kind(i % w, i / w))) {
				if (climb != null && slopes[i] != null) {
					climb[i] = slopes[i];
				} else {
					key[i] = 0;
				}
			}
		}
		return solve(new Input(model, key, new boolean[w * h], new boolean[w * h], -1, climb, walls, false, MAX_FACE, false, false, OUTDOOR_RISE,
				true, null));
	}

	/**
	 * Crystal's raised ground: no heights in its data, so every floor cell has one key and the levels are the areas its
	 * one-way walls close off; its stairs are the drawings {@link FloorKinds} names. In a cave the walls are its plateaus'
	 * edges (the higher side south, as Kanto's tile pairs); elsewhere they only keep areas apart. Faces don't count.
	 *
	 * @param walls the map's one-way edges ({@code G2Cells.oneWay})
	 * @param cave  a cave, whose walls are steps
	 */
	public static Result gen2(MapModel model, PairEdges walls, String game, boolean cave) {
		int w = model.cellWidth();
		int h = model.cellHeight();
		int[] key = new int[w * h];
		Dir[] climb = new Dir[w * h];
		Arrays.fill(key, -1);
		String atlas = CaveMap.atlas(model);
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				if (!floor(model.kind(cx, cy))) {
					continue;
				}
				int i = cy * w + cx;
				climb[i] = FloorKinds.climb(game, atlas, CaveMap.cellTiles(model, cx, cy));
				key[i] = climb[i] == null ? 0 : -1;
			}
		}
		// No faces: a wall one cell thick between two corridors of one level (Tin Tower's railings) looks like a cliff's face
		return solve(new Input(model, key, new boolean[w * h], new boolean[w * h], -1, climb, walls, cave, 0, false, false));
	}

	/**
	 * {@code in} with a bridge's cells as its deck: each takes the level key most of its floor neighbours have (along the
	 * bridge: its ends' floor; ties the higher), a cell among bridge cells only its neighbours' once they have one. A path
	 * under the bridge meets the deck as a wall (walking under it waits for the two-floor cells of W6).
	 */
	private static Input decked(Input in, int n) {
		int w = in.model().cellWidth();
		int h = in.model().cellHeight();
		int[] key = in.key().clone();
		boolean[] left = in.bridge().clone();
		boolean changed = true;
		while (changed) {
			changed = false;
			int[] next = key.clone();
			for (int i = 0; i < n; i++) {
				if (!left[i]) {
					continue;
				}
				Map<Integer, Integer> votes = new TreeMap<>();
				for (Dir d : Dir.values()) {
					int nx = i % w + d.dx();
					int ny = i / w + d.dy();
					if (nx >= 0 && ny >= 0 && nx < w && ny < h && !left[ny * w + nx] && key[ny * w + nx] >= 0) {
						votes.merge(key[ny * w + nx], 1, Integer::sum);
					}
				}
				int best = -1;
				for (Map.Entry<Integer, Integer> v : votes.entrySet()) {
					if (best < 0 || v.getValue() >= votes.get(best)) {
						best = v.getKey();
					}
				}
				if (best >= 0) {
					next[i] = best;
					changed = true;
				}
			}
			for (int i = 0; i < n; i++) {
				if (left[i] && next[i] >= 0) {
					left[i] = false;
				}
			}
			key = next;
		}
		return new Input(in.model(), key, in.joins(), new boolean[n], in.shore(), in.climb(), in.walls(), in.wallsRise(), in.maxFace(),
				in.faceKeys(), in.room(), in.rise(), true, in.falls());
	}

	/** True when the input's walls close the edge between neighbouring cells {@code a} and {@code b}. */
	private static boolean walled(Input in, int w, int a, int b) {
		PairEdges walls = in.walls();
		if (walls == null) {
			return false;
		}
		int ax = a % w;
		int ay = a / w;
		int bx = b % w;
		int by = b / w;
		int side = bx > ax ? EdgeWallBlock.SIDE_EAST : bx < ax ? EdgeWallBlock.SIDE_WEST : by > ay ? EdgeWallBlock.SIDE_SOUTH : EdgeWallBlock.SIDE_NORTH;
		return (walls.sides(ax, ay) & side) != 0;
	}

	/** The raised ground of one map. */
	public static Result solve(Input given) {
		MapModel model = given.model();
		int w = model.cellWidth();
		int h = model.cellHeight();
		int n = w * h;
		// (decked keeps its deck flag: the bridges are joins now, and the rules outdoors are known by it)
		Input in = given.outdoor() ? ledgesAside(decked(given, n)) : given;
		// outdoors each ledge cell's hop (its way down), its cells kept out of the regions: a ledge drops
		Dir[] hop = given.outdoor() ? ledgeHops(in.model()) : null;
		// outdoors a river flows on under a bridge's deck: one body of water either side of it
		boolean[] under = given.outdoor() ? given.bridge() : new boolean[n];
		int[] key = in.key();
		// ---- regions ----
		int[] region = new int[n];
		Arrays.fill(region, -1);
		List<Integer> regionKey = new ArrayList<>();
		List<Integer> regionCells = new ArrayList<>();
		for (int s = 0; s < n; s++) {
			if (key[s] < 0 || region[s] >= 0) {
				continue;
			}
			int id = regionKey.size();
			List<Integer> cells = flood(w, h, s, (a, b) -> key[b] == key[a] && region[b] < 0 && !walled(in, w, a, b));
			for (int c : cells) {
				region[c] = id;
			}
			regionKey.add(key[s]);
			regionCells.add(cells.size());
		}
		int regions = regionKey.size();
		boolean drawnStairs = in.climb() != null && Arrays.stream(in.climb()).anyMatch(java.util.Objects::nonNull);
		boolean ledged = hop != null && Arrays.stream(hop).anyMatch(java.util.Objects::nonNull);
		if (regions < 2 || in.faceKeys() && in.walls() == null && !drawnStairs && !ledged && regionKey.stream().distinct().count() < 2) {
			return Result.flat("flat_one_level", regions);
		}
		for (boolean b : in.bridge()) {
			if (b) {
				// A cell that is two floors waits for the two-floor cells of W6.
				return Result.flat("flat_bridge", regions);
			}
		}
		Offsets heights = new Offsets(regions);
		List<Stair> stairs = new ArrayList<>();
		// Outdoors a rule that disagrees with those before it is skipped (counted), so one odd corner leaves the rest standing;
		// with the drawing's stairs marked, a strip of joining cells that is no marked stair keeps one height where it can
		boolean soft = in.outdoor();
		boolean marked = in.outdoor() && in.climb() != null;
		int skipped = 0;
		int[] joinLevelOf = new int[n];
		Arrays.fill(joinLevelOf, -1);
		List<int[]> loose = new ArrayList<>();
		// ---- stairs a drawing names: they climb the way it says ----
		if (drawnStairs) {
			boolean[] taken = new boolean[n];
			for (int s = 0; s < n; s++) {
				Dir climb = in.climb()[s];
				if (climb == null || taken[s]) {
					continue;
				}
				List<Integer> part = flood(w, h, s, (a, b) -> in.climb()[b] == climb && !taken[b]);
				for (int c : part) {
					taken[c] = true;
				}
				Map<Integer, List<int[]>> touches = touches(in, part, region, true);
				Stair stair = drawnStair(w, part, touches, climb);
				Offsets before = heights.copy();
				String failed = stair == null ? "flat_stairs_shape" : joinStair(heights, stair, in.rise() * stair.length);
				if (failed != null) {
					if (!soft) {
						return Result.flat(failed, regions);
					}
					// outdoors: its cells stand at one height with a floor they touch, its other end a break
					heights.restore(before);
					skipped++;
					standWith(part, touches, joinLevelOf, loose);
					continue;
				}
				stair.low = stair.lows.get(0);
				stairs.add(stair);
			}
		}
		// ---- water: level with the regions of the shore key it touches; outdoors the bank it touches most first, and a bank
		// that disagrees stands over it (a break); outdoors, where a shore key says which banks a surfer lands on (Emerald), the
		// sea (every body reaching the map's edge that no waterfall feeds) is one body, one level, whichever side of an island
		// or a cape it lies ----
		int[] waterOf = new int[n];
		Arrays.fill(waterOf, -1);
		// each body's shore region (-1 for none) and every region it touches
		List<Integer> waterShore = new ArrayList<>();
		List<List<Integer>> waterBanks = new ArrayList<>();
		List<List<Integer>> bodies = new ArrayList<>();
		List<Integer> sea = null;
		boolean[] wet = new boolean[n];
		for (int s = 0; s < n; s++) {
			if (model.kind(s % w, s / w) != MapModel.Kind.WATER || wet[s] || in.falls() != null && in.falls()[s]) {
				continue;
			}
			List<Integer> part = flood(w, h, s, (a, b) -> under[b] || model.kind(b % w, b / w) == MapModel.Kind.WATER && !wet[b]
					&& !(in.falls() != null && in.falls()[b]));
			List<Integer> cells = new ArrayList<>();
			boolean edge = false;
			boolean fed = false;
			for (int c : part) {
				if (under[c]) {
					continue;
				}
				wet[c] = true;
				cells.add(c);
				edge |= c % w == 0 || c / w == 0 || c % w == w - 1 || c / w == h - 1;
				for (Dir dir : Dir.values()) {
					int nx = c % w + dir.dx();
					int ny = c / w + dir.dy();
					fed |= nx >= 0 && ny >= 0 && nx < w && ny < h && in.falls() != null && in.falls()[ny * w + nx];
				}
			}
			if (in.outdoor() && in.shore() >= 0 && edge && !fed) {
				if (sea == null) {
					sea = new ArrayList<>();
					bodies.add(sea);
				}
				sea.addAll(cells);
			} else {
				bodies.add(cells);
			}
		}
		if (in.outdoor() && in.shore() >= 0) {
			bodies = reefs(w, h, model, bodies);
		}
		for (List<Integer> cells : bodies) {
			Map<Integer, Integer> shores = new LinkedHashMap<>();
			List<Integer> banks = new ArrayList<>();
			for (int c : cells) {
				waterOf[c] = waterShore.size();
				for (Dir dir : Dir.values()) {
					int nx = c % w + dir.dx();
					int ny = c / w + dir.dy();
					int r = nx >= 0 && ny >= 0 && nx < w && ny < h ? region[ny * w + nx] : -1;
					if (r < 0 || walled(in, w, c, ny * w + nx)) {
						continue;
					}
					if (in.shore() < 0 || regionKey.get(r) == in.shore()) {
						shores.merge(r, 1, Integer::sum);
					}
					banks.add(r);
				}
			}
			List<Integer> order = new ArrayList<>(shores.keySet());
			if (soft) {
				order.sort((a, b) -> shores.get(b) - shores.get(a));
			}
			int shore = -1;
			for (int r : order) {
				if (shore >= 0 && !heights.join(shore, r, 0)) {
					if (!soft) {
						return Result.flat("flat_water_shores", regions);
					}
					skipped++;
					continue;
				}
				shore = shore < 0 ? r : shore;
			}
			waterShore.add(shore);
			waterBanks.add(banks);
		}
		// ---- cells that join any level: one level's, or stairs ----
		boolean[] seen = new boolean[n];
		for (int s = 0; s < n; s++) {
			if (!in.joins()[s] || seen[s]) {
				continue;
			}
			List<Integer> part = flood(w, h, s, (a, b) -> in.joins()[b] && !seen[b]);
			for (int c : part) {
				seen[c] = true;
			}
			Map<Integer, List<int[]>> touches = touches(in, part, region, false);
			java.util.TreeSet<Integer> keys = new java.util.TreeSet<>();
			for (int r : touches.keySet()) {
				keys.add(regionKey.get(r));
			}
			if (keys.isEmpty()) {
				loose.add(part.stream().mapToInt(Integer::intValue).toArray());
				continue;
			}
			List<Integer> touched = new ArrayList<>(touches.keySet());
			Stair stair = keys.size() != 2 ? null : stair(w, part, touches, regionKey, keys.first(), keys.last());
			if (stair != null && in.room() && !flanked(model, w, h, stair)) {
				stair = null;
			}
			if (stair != null) {
				for (int r : touched) {
					(regionKey.get(r).intValue() == keys.first() ? stair.lows : stair.highs).add(r);
				}
			}
			// The ways the strip may stand, in order: one height (one key; a room's cells that are no stair, the engine's
			// drawing order; outdoors with the stairs marked, a strip no mark names: the drawing shows no step), a stair;
			// outdoors the first that agrees with the rules before it, else the strip is a break
			List<Boolean> ways = new ArrayList<>();
			boolean oneFirst = keys.size() == 1 || marked || in.room() && stair == null;
			if (oneFirst) {
				ways.add(true);
			}
			if (stair != null) {
				ways.add(false);
			}
			if (soft && !oneFirst) {
				ways.add(true);
			}
			String failed = keys.size() > 2 ? "flat_stairs_three_levels" : "flat_stairs_shape";
			boolean stood = false;
			for (boolean one : ways) {
				Offsets before = heights.copy();
				failed = one ? joinFlat(heights, touched) : joinStair(heights, stair, in.rise() * stair.length);
				if (failed == null) {
					if (one) {
						for (int c : part) {
							joinLevelOf[c] = touched.get(0);
						}
					} else {
						stair.low = stair.lows.get(0);
						stairs.add(stair);
					}
					stood = true;
					break;
				}
				heights.restore(before);
			}
			if (!stood) {
				if (!soft) {
					return Result.flat(failed, regions);
				}
				skipped++;
				for (int c : part) {
					joinLevelOf[c] = touched.get(0);
				}
			}
		}
		// ---- ledges (outdoors), after every other rule (the weakest: a small drop): a strip of ledge cells hopping one way
		// drops LEDGE_DROP blocks, its cells on the side it is hopped from; a ledge the walker can go round (the same ground on
		// both sides), whose sides another rule keeps level, or that doesn't fit stands at one height with the ground it
		// touches, as before ----
		List<Stair> drops = new ArrayList<>();
		if (hop != null) {
			boolean[] took = new boolean[n];
			for (int s = 0; s < n; s++) {
				Dir way = hop[s];
				if (way == null || took[s]) {
					continue;
				}
				List<Integer> part = flood(w, h, s, (a, b) -> hop[b] == way && !took[b]);
				for (int c : part) {
					took[c] = true;
				}
				Map<Integer, List<int[]>> touches = touches(in, part, region, true);
				Stair stair = drawnStair(w, part, touches, way.opposite());
				Offsets before = heights.copy();
				if (stair != null && joinStair(heights, stair, LEDGE_DROP) == null) {
					stair.low = stair.lows.get(0);
					drops.add(stair);
					continue;
				}
				heights.restore(before);
				List<Integer> touched = new ArrayList<>(touches.keySet());
				if (touched.isEmpty()) {
					loose.add(part.stream().mapToInt(Integer::intValue).toArray());
					continue;
				}
				if (joinFlat(heights, touched) != null) {
					heights.restore(before);
					skipped++;
				}
				for (int c : part) {
					joinLevelOf[c] = touched.get(0);
				}
			}
		}
		// ---- each group of regions joined so far, placed: the biggest at the floor level, then by faces, then by key ----
		Map<Integer, Integer> groupCells = new LinkedHashMap<>();
		for (int r = 0; r < regions; r++) {
			groupCells.merge(heights.find(r), regionCells.get(r), Integer::sum);
		}
		List<Integer> groups = new ArrayList<>(groupCells.keySet());
		groups.sort((a, b) -> groupCells.get(b) - groupCells.get(a) != 0 ? groupCells.get(b) - groupCells.get(a) : a - b);
		Map<Integer, Integer> base = new HashMap<>();
		base.put(groups.get(0), 0);
		List<int[]> faces = faces(model, region, regionKey, heights, in.maxFace(), in.faceKeys(), in.rise());
		if (in.wallsRise()) {
			// a wall's south side over its north side: a vote like a face, the north side two blocks under the south
			for (int cy = 0; cy + 1 < h; cy++) {
				for (int cx = 0; cx < w; cx++) {
					int north = cy * w + cx;
					int south = north + w;
					if (region[north] >= 0 && region[south] >= 0 && walled(in, w, north, south)
							&& heights.find(region[north]) != heights.find(region[south])) {
						faces.add(new int[] {region[south], region[north], -in.rise()});
					}
				}
			}
		}
		while (base.size() < groups.size()) {
			// By faces from a placed group: the most votes first.
			Map<Integer, Map<Integer, Integer>> votes = new TreeMap<>();
			for (int[] face : faces) {
				int south = heights.find(face[0]);
				int north = heights.find(face[1]);
				// h(north region) - h(south region) = face[2] blocks
				if (base.containsKey(south) && !base.containsKey(north)) {
					int at = base.get(south) + heights.offset(face[0]) + face[2] - heights.offset(face[1]);
					votes.computeIfAbsent(north, k -> new TreeMap<>()).merge(at, 1, Integer::sum);
				} else if (base.containsKey(north) && !base.containsKey(south)) {
					int at = base.get(north) + heights.offset(face[1]) - face[2] - heights.offset(face[0]);
					votes.computeIfAbsent(south, k -> new TreeMap<>()).merge(at, 1, Integer::sum);
				}
			}
			int pick = -1;
			int pickAt = 0;
			int pickVotes = 0;
			for (Map.Entry<Integer, Map<Integer, Integer>> e : votes.entrySet()) {
				for (Map.Entry<Integer, Integer> v : e.getValue().entrySet()) {
					if (v.getValue() > pickVotes) {
						pick = e.getKey();
						pickAt = v.getKey();
						pickVotes = v.getValue();
					}
				}
			}
			if (pick < 0 && in.outdoor()) {
				// Outdoors the biggest group left stands as high as the nearest placed region (a pocket in a tree line, a
				// ledge nothing reaches: level with the ground round it, not with its key elsewhere in the map)
				pick = groups.stream().filter(g -> !base.containsKey(g)).findFirst().orElseThrow();
				pickAt = nearestPlaced(w, h, region, heights, base, pick);
			} else if (pick < 0) {
				// Else the biggest group left takes the heights its keys have most among the placed regions.
				pick = groups.stream().filter(g -> !base.containsKey(g)).findFirst().orElseThrow();
				Map<Integer, Map<Integer, Integer>> keyHeights = new HashMap<>();
				for (int r = 0; r < regions; r++) {
					Integer at = base.get(heights.find(r));
					if (at != null) {
						keyHeights.computeIfAbsent(regionKey.get(r), k -> new TreeMap<>()).merge(at + heights.offset(r), regionCells.get(r), Integer::sum);
					}
				}
				Map<Integer, Integer> guess = new TreeMap<>();
				for (int r = 0; r < regions; r++) {
					if (heights.find(r) != pick || !keyHeights.containsKey(regionKey.get(r))) {
						continue;
					}
					int common = mostCommon(keyHeights.get(regionKey.get(r)));
					guess.merge(common - heights.offset(r), regionCells.get(r), Integer::sum);
				}
				pickAt = guess.isEmpty() ? 0 : mostCommon(guess);
			}
			base.put(pick, pickAt);
		}
		int[] height = new int[regions];
		for (int r = 0; r < regions; r++) {
			height[r] = base.get(heights.find(r)) + heights.offset(r);
		}

		// ---- each cell's level ----
		int[] levels = new int[n];
		boolean[] standable = new boolean[n];
		Map<Integer, MapLayout.Ramp> rampAt = new TreeMap<>();
		for (int i = 0; i < n; i++) {
			if (region[i] >= 0) {
				levels[i] = height[region[i]];
				standable[i] = true;
			} else if (joinLevelOf[i] >= 0) {
				levels[i] = height[joinLevelOf[i]];
				standable[i] = true;
			} else if (waterOf[i] >= 0) {
				// level with its shore, else with the lowest floor it touches (a surfer steps onto neither)
				int shore = waterShore.get(waterOf[i]);
				int at = shore >= 0 ? height[shore] : waterBanks.get(waterOf[i]).stream().mapToInt(r -> height[r]).min().orElse(0);
				levels[i] = at;
				standable[i] = true;
			}
		}
		for (Stair drop : drops) {
			// a ledge's own cells stand on the side it is hopped from
			for (int c : drop.cells) {
				levels[c] = height[drop.highs.get(0)];
				standable[c] = true;
			}
		}
		for (Stair stair : stairs) {
			for (int k = 0; k < stair.cells.length; k++) {
				int c = stair.cells[k];
				levels[c] = height[stair.low] + in.rise() * stair.steps[k];
				standable[c] = true;
				rampAt.put(c, new MapLayout.Ramp(c % w, c / w, stair.climb, in.rise()));
			}
		}
		for (int[] part : loose) {
			// Cells that join any level but touch none: the level of the water beside them, else the floor level.
			int at = 0;
			for (int c : part) {
				for (Dir dir : Dir.values()) {
					int nx = c % w + dir.dx();
					int ny = c / w + dir.dy();
					if (nx >= 0 && ny >= 0 && nx < w && ny < h && waterOf[ny * w + nx] >= 0) {
						at = levels[ny * w + nx];
					}
				}
			}
			for (int c : part) {
				levels[c] = at;
				standable[c] = true;
			}
		}
		// The lowest floor is the map's floor level; outdoors (the levels pilot) the floor along the map's edge is, where its
		// neighbours meet it (its commonest level there), and a floor lower than that stands at it (counted: "sunken").
		int low = Integer.MAX_VALUE;
		for (int i = 0; i < n; i++) {
			if (standable[i]) {
				low = Math.min(low, levels[i]);
			}
		}
		int sunken = 0;
		if (in.outdoor()) {
			Map<Integer, Integer> edge = new TreeMap<>();
			for (int i = 0; i < n; i++) {
				int x = i % w;
				int y = i / w;
				if (standable[i] && (x == 0 || y == 0 || x == w - 1 || y == h - 1)) {
					edge.merge(levels[i], 1, Integer::sum);
				}
			}
			if (!edge.isEmpty()) {
				low = mostCommon(edge);
			}
			for (int i = 0; i < n; i++) {
				if (standable[i] && levels[i] < low) {
					levels[i] = low;
					sunken++;
				}
			}
		}
		int top = 0;
		int raised = 0;
		for (int i = 0; i < n; i++) {
			if (standable[i]) {
				levels[i] -= low;
				MapLayout.Ramp ramp = rampAt.get(i);
				top = Math.max(top, levels[i] + (ramp == null ? 0 : ramp.rise()));
				raised += (region[i] >= 0 || joinLevelOf[i] >= 0) && levels[i] > 0 ? 1 : 0;
			}
		}
		if (top > MapLayout.MAX_LEVEL) {
			return Result.flat("flat_too_tall", regions);
		}
		if (raised == 0 && rampAt.isEmpty()) {
			return Result.flat("flat_one_height", regions);
		}
		String raisedOutcome = sunken > 0 ? "raised_sunken" : "raised";
		if (in.outdoor()) {
			// a pocket nothing walkable touches (a cell under a tree, behind a rock) is no ground of its own: a face's vote may have
			// lifted it over everything round it (Mossdeep's cell under the big tree, 33 blocks up)
			boolean[] floorCell = new boolean[n];
			for (int i = 0; i < n; i++) {
				floorCell[i] = standable[i] && waterOf[i] < 0;
			}
			List<int[]> pockets = pockets(w, h, standable, rampAt);
			for (int[] pocket : pockets) {
				for (int c : pocket) {
					standable[c] = false;
					floorCell[c] = false;
				}
			}
			// a pocket stands as the walkable ground nearest it (not the sea beyond its rock)
			int[] onFloor = levels.clone();
			nearestLevels(w, h, floorCell, onFloor);
			nearestLevels(w, h, standable, levels);
			for (int[] pocket : pockets) {
				for (int c : pocket) {
					levels[c] = onFloor[c];
				}
			}
		} else {
			rockLevels(model, standable, levels);
		}
		List<Integer> breaks = soft ? new ArrayList<>() : null;
		String failed = check(in, standable, levels, rampAt, breaks);
		if (failed != null) {
			return Result.flat(failed, regions);
		}
		return new Result(MapLayout.flat(w, h, levels, new ArrayList<>(rampAt.values())), raisedOutcome, regions, raised, stairs.size(), top,
				skipped, breaks == null ? List.of() : List.copyOf(new java.util.TreeSet<>(breaks)));
	}

	/** {@code in} with its ledge cells in no region and joining nothing: outdoors a ledge is its own rule (a drop). */
	private static Input ledgesAside(Input in) {
		MapModel model = in.model();
		int w = model.cellWidth();
		int[] key = in.key().clone();
		boolean[] joins = in.joins().clone();
		boolean any = false;
		for (int i = 0; i < key.length; i++) {
			if (model.kind(i % w, i / w) == MapModel.Kind.LEDGE) {
				key[i] = -1;
				joins[i] = false;
				any = true;
			}
		}
		return !any ? in : new Input(model, key, joins, in.bridge(), in.shore(), in.climb(), in.walls(), in.wallsRise(), in.maxFace(), in.faceKeys(),
				in.room(), in.rise(), in.outdoor(), in.falls());
	}

	/** Per cell a ledge's hop, its way down; null for any other cell. */
	private static Dir[] ledgeHops(MapModel model) {
		int w = model.cellWidth();
		int h = model.cellHeight();
		Dir[] out = new Dir[w * h];
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				if (model.kind(cx, cy) == MapModel.Kind.LEDGE) {
					out[cy * w + cx] = ledgeHop(model, cx, cy);
				}
			}
		}
		return out;
	}

	/** A ledge cell's hop (CellFlags' 0 down, 1 up, 2 left, 3 right). */
	private static Dir ledgeHop(MapModel model, int cx, int cy) {
		return switch (CellFlags.ledgeDir(model.flags(cx, cy))) {
			case 0 -> Dir.DOWN;
			case 1 -> Dir.UP;
			case 2 -> Dir.LEFT;
			default -> Dir.RIGHT;
		};
	}

	/**
	 * {@code bodies} of water with those that face each other across a rock or two in a row (a reef, rocks in a channel)
	 * made one: the same water, one level. Bodies parted by more rock, by land or by a waterfall stay apart.
	 */
	static List<List<Integer>> reefs(int w, int h, MapModel model, List<List<Integer>> bodies) {
		int n = w * h;
		int[] body = new int[n];
		Arrays.fill(body, -1);
		for (int b = 0; b < bodies.size(); b++) {
			for (int c : bodies.get(b)) {
				body[c] = b;
			}
		}
		int[] parent = new int[bodies.size()];
		for (int b = 0; b < parent.length; b++) {
			parent[b] = b;
		}
		for (int c = 0; c < n; c++) {
			if (body[c] < 0) {
				continue;
			}
			for (Dir dir : Dir.values()) {
				int x = c % w;
				int y = c / w;
				for (int step = 1; step <= 3; step++) {
					x += dir.dx();
					y += dir.dy();
					if (x < 0 || y < 0 || x >= w || y >= h) {
						break;
					}
					int j = y * w + x;
					if (body[j] >= 0) {
						if (step > 1) {
							int a = root(parent, body[c]);
							int b = root(parent, body[j]);
							parent[Math.max(a, b)] = Math.min(a, b);
						}
						break;
					}
					if (!solid(model.kind(x, y))) {
						break;
					}
				}
			}
		}
		Map<Integer, List<Integer>> merged = new TreeMap<>();
		for (int b = 0; b < bodies.size(); b++) {
			merged.computeIfAbsent(root(parent, b), k -> new ArrayList<>()).addAll(bodies.get(b));
		}
		return new ArrayList<>(merged.values());
	}

	private static int root(int[] parent, int b) {
		while (parent[b] != b) {
			parent[b] = parent[parent[b]];
			b = parent[b];
		}
		return b;
	}

	/**
	 * The groups of fewer than {@link #POCKET} standable cells (4-connected, no ramp among them) whose every neighbour outside
	 * the group is no standable cell.
	 */
	static List<int[]> pockets(int w, int h, boolean[] standable, Map<Integer, MapLayout.Ramp> rampAt) {
		List<int[]> out = new ArrayList<>();
		boolean[] seen = new boolean[w * h];
		for (int s = 0; s < w * h; s++) {
			if (!standable[s] || seen[s] || rampAt.containsKey(s)) {
				continue;
			}
			List<Integer> part = flood(w, h, s, (a, b) -> standable[b] && !seen[b] && !rampAt.containsKey(b));
			for (int c : part) {
				seen[c] = true;
			}
			if (part.size() >= POCKET) {
				continue;
			}
			boolean enclosed = true;
			for (int c : part) {
				for (Dir dir : Dir.values()) {
					int nx = c % w + dir.dx();
					int ny = c / w + dir.dy();
					if (nx >= 0 && ny >= 0 && nx < w && ny < h && standable[ny * w + nx] && !part.contains(ny * w + nx)) {
						enclosed = false;
					}
				}
			}
			if (enclosed) {
				out.add(part.stream().mapToInt(Integer::intValue).toArray());
			}
		}
		return out;
	}

	/** A ground or ledge cell: where a level key lives. */
	static boolean floor(MapModel.Kind kind) {
		return kind == MapModel.Kind.GROUND || kind == MapModel.Kind.LEDGE;
	}

	/** One stairs: its cells, each one's steps up from its low end, the way it climbs and its length in cells. */
	private static final class Stair {
		int[] cells;
		int[] steps;
		Dir climb;
		int length;
		int low;
		/** A drawn stair's regions at its foot and at its top. */
		List<Integer> lows = new ArrayList<>();
		List<Integer> highs = new ArrayList<>();
	}

	/** True when every cell of a stair has solid cells (or the stair's own) on both sides across its climb: cut through a wall. */
	private static boolean flanked(MapModel model, int w, int h, Stair stair) {
		java.util.Set<Integer> own = new java.util.HashSet<>();
		for (int c : stair.cells) {
			own.add(c);
		}
		Dir[] sides = stair.climb == Dir.UP || stair.climb == Dir.DOWN ? new Dir[] {Dir.LEFT, Dir.RIGHT} : new Dir[] {Dir.UP, Dir.DOWN};
		for (int c : stair.cells) {
			for (Dir side : sides) {
				int nx = c % w + side.dx();
				int ny = c / w + side.dy();
				if (nx < 0 || ny < 0 || nx >= w || ny >= h || own.contains(ny * w + nx)) {
					continue;
				}
				if (!solid(model.kind(nx, ny))) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * A strip of stairs a drawing names, climbing {@code climb}: the regions at its top end (met from any side but below)
	 * are its highs, at its foot (from any side but above) its lows; at least one each, nothing meeting its middle. Null
	 * when it is no such strip.
	 */
	/**
	 * The regions a part's cells touch, each with its touches ({cell, side}); with {@code walls} an edge the engine closes
	 * doesn't count.
	 */
	private static Map<Integer, List<int[]>> touches(Input in, List<Integer> part, int[] region, boolean walls) {
		int w = in.model().cellWidth();
		int h = in.model().cellHeight();
		Map<Integer, List<int[]>> touches = new TreeMap<>();
		for (int c : part) {
			for (Dir dir : Dir.values()) {
				int nx = c % w + dir.dx();
				int ny = c / w + dir.dy();
				if (nx >= 0 && ny >= 0 && nx < w && ny < h && region[ny * w + nx] >= 0 && !(walls && walled(in, w, c, ny * w + nx))) {
					touches.computeIfAbsent(region[ny * w + nx], k -> new ArrayList<>()).add(new int[] {c, dir.ordinal()});
				}
			}
		}
		return touches;
	}

	/** The regions {@code touched} at one height: null, or the reason they can't be. */
	private static @Nullable String joinFlat(Offsets heights, List<Integer> touched) {
		for (int r : touched.subList(1, touched.size())) {
			if (!heights.join(touched.get(0), r, 0)) {
				return "flat_levels_disagree";
			}
		}
		return null;
	}

	/** A stair's regions: its foot's at one height, its top's at one height, the top {@code rise} over the foot. */
	private static @Nullable String joinStair(Offsets heights, Stair stair, int rise) {
		for (int r : stair.lows.subList(1, stair.lows.size())) {
			if (!heights.join(stair.lows.get(0), r, 0)) {
				return "flat_levels_disagree";
			}
		}
		for (int r : stair.highs.subList(1, stair.highs.size())) {
			if (!heights.join(stair.highs.get(0), r, 0)) {
				return "flat_levels_disagree";
			}
		}
		return heights.join(stair.lows.get(0), stair.highs.get(0), rise) ? null : "flat_stairs_disagree";
	}

	/** A part's cells at one height with the first region they touch (none: loose, as joining cells touching none). */
	private static void standWith(List<Integer> part, Map<Integer, List<int[]>> touches, int[] joinLevelOf, List<int[]> loose) {
		if (touches.isEmpty()) {
			loose.add(part.stream().mapToInt(Integer::intValue).toArray());
			return;
		}
		int r = touches.keySet().iterator().next();
		for (int c : part) {
			joinLevelOf[c] = r;
		}
	}

	private static @Nullable Stair drawnStair(int w, List<Integer> part, Map<Integer, List<int[]>> touches, Dir climb) {
		boolean vertical = climb == Dir.UP || climb == Dir.DOWN;
		int first = Integer.MAX_VALUE;
		int last = Integer.MIN_VALUE;
		for (int c : part) {
			int p = vertical ? c / w : c % w;
			first = Math.min(first, p);
			last = Math.max(last, p);
		}
		int highEnd = climb == Dir.UP || climb == Dir.LEFT ? first : last;
		int lowEnd = highEnd == first ? last : first;
		Stair stair = new Stair();
		for (Map.Entry<Integer, List<int[]>> e : touches.entrySet()) {
			boolean high = false;
			boolean low = false;
			for (int[] t : e.getValue()) {
				int p = vertical ? t[0] / w : t[0] % w;
				Dir side = Dir.values()[t[1]];
				if (p == highEnd && side != climb.opposite() && (side == climb || first != last)) {
					high = true;
				} else if (p == lowEnd && side != climb) {
					low = true;
				} else {
					return null;
				}
			}
			if (high && low) {
				return null;
			}
			(high ? stair.highs : stair.lows).add(e.getKey());
		}
		if (stair.highs.isEmpty() || stair.lows.isEmpty()) {
			return null;
		}
		stair.cells = part.stream().mapToInt(Integer::intValue).toArray();
		stair.steps = new int[stair.cells.length];
		for (int k = 0; k < stair.cells.length; k++) {
			int p = vertical ? stair.cells[k] / w : stair.cells[k] % w;
			stair.steps[k] = Math.abs(p - lowEnd);
		}
		stair.climb = climb;
		stair.length = last - first + 1;
		return stair;
	}

	/**
	 * A strip of cells that join any level, as a stair between the regions of key {@code lowKey} and {@code highKey}: it runs
	 * along one axis, the high regions touch only its cells at the top end (at least one straight on, the way it climbs;
	 * any others from the side) and the low regions only those at the bottom end (straight on or from the side, as Victory
	 * Road's stairs turn off at their foot), nothing touches the cells between. Null when it is no such strip.
	 */
	private static @Nullable Stair stair(int w, List<Integer> part, Map<Integer, List<int[]>> touches, List<Integer> regionKey, int lowKey,
			int highKey) {
		for (Dir climb : Dir.values()) {
			boolean vertical = climb == Dir.UP || climb == Dir.DOWN;
			int first = Integer.MAX_VALUE;
			int last = Integer.MIN_VALUE;
			for (int c : part) {
				int p = vertical ? c / w : c % w;
				first = Math.min(first, p);
				last = Math.max(last, p);
			}
			// the high end: the way it climbs (north for UP: the smallest row)
			int highEnd = climb == Dir.UP || climb == Dir.LEFT ? first : last;
			int lowEnd = highEnd == first ? last : first;
			boolean ok = true;
			boolean straightOn = false;
			for (Map.Entry<Integer, List<int[]>> e : touches.entrySet()) {
				boolean high = regionKey.get(e.getKey()) == highKey;
				for (int[] t : e.getValue()) {
					int p = vertical ? t[0] / w : t[0] % w;
					Dir side = Dir.values()[t[1]];
					// a high region meets the top end, from any side but below it; a low one the bottom end, from any side but above
					if (high ? p != highEnd || side == climb.opposite() : p != lowEnd || side == climb) {
						ok = false;
					}
					straightOn |= high && side == climb;
				}
			}
			if (!ok || !straightOn) {
				continue;
			}
			Stair stair = new Stair();
			stair.cells = part.stream().mapToInt(Integer::intValue).toArray();
			stair.steps = new int[stair.cells.length];
			for (int k = 0; k < stair.cells.length; k++) {
				int p = vertical ? stair.cells[k] / w : stair.cells[k] % w;
				stair.steps[k] = Math.abs(p - lowEnd);
			}
			stair.climb = climb;
			stair.length = last - first + 1;
			return stair;
		}
		return null;
	}

	/**
	 * The short faces between regions (of different keys, with {@code faceKeys}): from each floor cell north through up to
	 * {@code maxFace} solid cells to a floor cell of another group, as {south region, north region, blocks the north one
	 * stands over the south one}.
	 */
	private static List<int[]> faces(MapModel model, int[] region, List<Integer> regionKey, Offsets heights, int maxFace, boolean faceKeys,
			int rise) {
		int w = model.cellWidth();
		int h = model.cellHeight();
		List<int[]> out = new ArrayList<>();
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				int south = region[cy * w + cx];
				if (south < 0) {
					continue;
				}
				int y = cy - 1;
				int run = 0;
				while (y >= 0 && solid(model.kind(cx, y)) && run <= maxFace) {
					run++;
					y--;
				}
				if (run == 0 || run > maxFace || y < 0) {
					continue;
				}
				int north = region[y * w + cx];
				if (north >= 0 && (!faceKeys || !regionKey.get(north).equals(regionKey.get(south))) && heights.find(north) != heights.find(south)) {
					out.add(new int[] {south, north, rise * run});
				}
			}
		}
		return out;
	}

	/** Rock and counters: what a column of the map stands up. */
	private static boolean solid(MapModel.Kind kind) {
		return kind == MapModel.Kind.SOLID || kind == MapModel.Kind.COUNTER;
	}

	/**
	 * The base a group {@code pick} takes for its regions to stand as high as the nearest region of a placed group: a search
	 * outward from the group's cells over every cell, the first placed region it meets (on a tie the lower).
	 */
	private static int nearestPlaced(int w, int h, int[] region, Offsets heights, Map<Integer, Integer> base, int pick) {
		int n = w * h;
		int[] from = new int[n];
		Arrays.fill(from, -1);
		List<Integer> ring = new ArrayList<>();
		for (int c = 0; c < n; c++) {
			if (region[c] >= 0 && heights.find(region[c]) == pick) {
				from[c] = region[c];
				ring.add(c);
			}
		}
		while (!ring.isEmpty()) {
			Integer best = null;
			List<Integer> next = new ArrayList<>();
			for (int c : ring) {
				for (Dir dir : Dir.values()) {
					int nx = c % w + dir.dx();
					int ny = c / w + dir.dy();
					if (nx < 0 || ny < 0 || nx >= w || ny >= h || from[ny * w + nx] >= 0) {
						continue;
					}
					int j = ny * w + nx;
					int r = region[j];
					Integer placed = r >= 0 ? base.get(heights.find(r)) : null;
					if (placed != null) {
						int at = placed + heights.offset(r) - heights.offset(from[c]);
						best = best == null ? at : Math.min(best, at);
						continue;
					}
					from[j] = from[c];
					next.add(j);
				}
			}
			if (best != null) {
				return best;
			}
			ring = next;
		}
		return 0;
	}

	/**
	 * Outdoors each cell that isn't stood on stands as high as the nearest that is (on a tie the lower: a face stands on the
	 * floor in front of it, not on the ground over it): a forest's trees on the ground round them, a mountain on the
	 * plateau it rises from, however far its rock runs.
	 */
	static void nearestLevels(int w, int h, boolean[] standable, int[] levels) {
		int n = w * h;
		boolean[] done = standable.clone();
		List<Integer> ring = new ArrayList<>();
		for (int c = 0; c < n; c++) {
			if (standable[c]) {
				ring.add(c);
			}
		}
		while (!ring.isEmpty()) {
			Map<Integer, Integer> next = new TreeMap<>();
			for (int c : ring) {
				for (Dir dir : Dir.values()) {
					int nx = c % w + dir.dx();
					int ny = c / w + dir.dy();
					if (nx < 0 || ny < 0 || nx >= w || ny >= h || done[ny * w + nx]) {
						continue;
					}
					next.merge(ny * w + nx, levels[c], Math::min);
				}
			}
			for (Map.Entry<Integer, Integer> e : next.entrySet()) {
				done[e.getKey()] = true;
				levels[e.getKey()] = e.getValue();
			}
			ring = new ArrayList<>(next.keySet());
		}
	}

	/**
	 * Every run of solid cells down a column stands on the floor south of it (the floor it is seen from), else the one north
	 * of it, else as high as the highest floor around it, else the map's floor level.
	 */
	static void rockLevels(MapModel model, boolean[] standable, int[] levels) {
		int w = model.cellWidth();
		int h = model.cellHeight();
		for (int cx = 0; cx < w; cx++) {
			int cy = 0;
			while (cy < h) {
				if (standable[cy * w + cx]) {
					cy++;
					continue;
				}
				int y0 = cy;
				while (cy < h && !standable[cy * w + cx]) {
					cy++;
				}
				int y1 = cy - 1;
				int at;
				if (y1 + 1 < h) {
					at = levels[(y1 + 1) * w + cx];
				} else if (y0 > 0) {
					at = levels[(y0 - 1) * w + cx];
				} else {
					at = 0;
					for (int y = y0; y <= y1; y++) {
						for (int dx = -1; dx <= 1; dx += 2) {
							int x = cx + dx;
							if (x >= 0 && x < w && standable[y * w + x]) {
								at = Math.max(at, levels[y * w + x]);
							}
						}
					}
				}
				for (int y = y0; y <= y1; y++) {
					levels[y * w + cx] = at;
				}
			}
		}
	}

	/**
	 * The edge check: between every two neighbouring cells a player stands on, Minecraft must allow the step exactly when
	 * the engine does. The engine joins two floor cells unless both have keys and they differ (those edges get the
	 * elevation walls), water and water, and water and a floor cell of the shore key. Joined cells stand at one height, or
	 * meet as a stair's ends (two stairs side by side climb the same way at one height; one climbs onto the next end to
	 * end); a ledge's three cells stand at one height. Returns the failure's reason, or null; with {@code breaks} (outdoors)
	 * each failing edge's first cell goes there instead and the check goes on.
	 */
	private static @Nullable String check(Input in, boolean[] standable, int[] levels, Map<Integer, MapLayout.Ramp> rampAt,
			@Nullable List<Integer> breaks) {
		MapModel model = in.model();
		int w = model.cellWidth();
		int h = model.cellHeight();
		int[] key = in.key();
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				int a = cy * w + cx;
				if (!standable[a]) {
					continue;
				}
				if (model.kind(cx, cy) == MapModel.Kind.LEDGE) {
					Dir hop = ledgeHop(model, cx, cy);
					for (Dir side : new Dir[] {hop, hop.opposite()}) {
						int nx = cx + side.dx();
						int ny = cy + side.dy();
						// outdoors the ground it is hopped onto may stand LEDGE_DROP lower (a ledge's drop)
						int drop = in.outdoor() && side == hop ? LEDGE_DROP : 0;
						if (nx >= 0 && ny >= 0 && nx < w && ny < h && standable[ny * w + nx]
								&& (levels[ny * w + nx] != levels[a] && levels[ny * w + nx] != levels[a] - drop || rampAt.containsKey(ny * w + nx))) {
							if (breaks == null) {
								return "flat_check_ledge";
							}
							breaks.add(a);
						}
					}
				}
				for (Dir dir : new Dir[] {Dir.RIGHT, Dir.DOWN}) {
					int nx = cx + dir.dx();
					int ny = cy + dir.dy();
					if (nx >= w || ny >= h) {
						continue;
					}
					int b = ny * w + nx;
					// outdoors a ledge's own rule (above) checks the edges it touches
					if (!standable[b] || !joined(model, in, key, a, b)
							|| in.outdoor() && (model.kind(cx, cy) == MapModel.Kind.LEDGE || model.kind(nx, ny) == MapModel.Kind.LEDGE)) {
						continue;
					}
					MapLayout.Ramp rampA = rampAt.get(a);
					MapLayout.Ramp rampB = rampAt.get(b);
					if (rampA != null && rampB != null) {
						if (rampA.dir() != rampB.dir()) {
							if (breaks == null) {
								return "flat_check_ramps_meet";
							}
							breaks.add(a);
							continue;
						}
						boolean along = dir == rampA.dir() || dir == rampA.dir().opposite();
						// side by side: one level; end to end: the lower one's top is the upper one's foot
						int lower = dir == rampA.dir() ? a : b;
						int upper = lower == a ? b : a;
						if (along ? levels[lower] + rampAt.get(lower).rise() != levels[upper] : levels[a] != levels[b]) {
							if (breaks == null) {
								return "flat_check_ramps_meet";
							}
							breaks.add(a);
						}
						continue;
					}
					if (rampA != null || rampB != null) {
						MapLayout.Ramp ramp = rampA != null ? rampA : rampB;
						int r = rampA != null ? a : b;
						int other = rampA != null ? b : a;
						Dir toward = rampA != null ? dir : dir.opposite();
						if (toward == ramp.dir() && levels[other] != levels[r] + ramp.rise()
								|| toward == ramp.dir().opposite() && levels[other] != levels[r]) {
							if (breaks == null) {
								return "flat_check_ramp_end";
							}
							breaks.add(a);
						}
						continue;
					}
					if (levels[a] != levels[b]) {
						if (breaks == null) {
							return "flat_check_open_rise";
						}
						breaks.add(a);
					}
				}
			}
		}
		return null;
	}

	/** True when the engine lets a player step between neighbouring cells {@code a} and {@code b} (both stood on). */
	private static boolean joined(MapModel model, Input in, int[] key, int a, int b) {
		int w = model.cellWidth();
		if (walled(in, w, a, b)) {
			return false;
		}
		boolean waterA = model.kind(a % w, a / w) == MapModel.Kind.WATER;
		boolean waterB = model.kind(b % w, b / w) == MapModel.Kind.WATER;
		if (waterA && waterB) {
			return true;
		}
		if (waterA || waterB) {
			int land = waterA ? b : a;
			return in.shore() < 0 || key[land] == in.shore();
		}
		if (in.bridge()[a] || in.bridge()[b]) {
			return false;
		}
		return key[a] < 0 || key[b] < 0 || key[a] == key[b];
	}

	/** The key with the most weight; the lowest on a tie. */
	private static int mostCommon(Map<Integer, Integer> weights) {
		int best = 0;
		int bestWeight = -1;
		for (Map.Entry<Integer, Integer> e : weights.entrySet()) {
			if (e.getValue() > bestWeight) {
				best = e.getKey();
				bestWeight = e.getValue();
			}
		}
		return best;
	}

	/** A step between two neighbouring cells, row by row indices. */
	private interface Step {
		boolean ok(int from, int to);
	}

	/** The cells reachable from {@code start} by steps between four-neighbours that {@code step} allows. */
	private static List<Integer> flood(int w, int h, int start, Step step) {
		List<Integer> out = new ArrayList<>();
		boolean[] in = new boolean[w * h];
		Deque<Integer> queue = new ArrayDeque<>();
		queue.add(start);
		in[start] = true;
		while (!queue.isEmpty()) {
			int c = queue.poll();
			out.add(c);
			for (Dir dir : Dir.values()) {
				int nx = c % w + dir.dx();
				int ny = c / w + dir.dy();
				if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
					continue;
				}
				int j = ny * w + nx;
				if (!in[j] && step.ok(c, j)) {
					in[j] = true;
					queue.add(j);
				}
			}
		}
		return out;
	}

	/** Groups of regions with known height differences: union-find keeping each member's height over its root's. */
	private static final class Offsets {
		private final int[] parent;
		private final int[] offset;

		Offsets(int n) {
			this.parent = new int[n];
			this.offset = new int[n];
			for (int i = 0; i < n; i++) {
				this.parent[i] = i;
			}
		}

		int find(int x) {
			if (this.parent[x] == x) {
				return x;
			}
			int p = this.parent[x];
			int root = this.find(p);
			this.offset[x] += this.offset[p];
			this.parent[x] = root;
			return root;
		}

		/** A region's height over its group's root. */
		int offset(int x) {
			this.find(x);
			return this.offset[x];
		}

		/** A copy, to {@link #restore} when a rule tried on this one fails. */
		Offsets copy() {
			Offsets out = new Offsets(this.parent.length);
			System.arraycopy(this.parent, 0, out.parent, 0, this.parent.length);
			System.arraycopy(this.offset, 0, out.offset, 0, this.offset.length);
			return out;
		}

		/** Back to {@code saved} (a {@link #copy} of this). */
		void restore(Offsets saved) {
			System.arraycopy(saved.parent, 0, this.parent, 0, this.parent.length);
			System.arraycopy(saved.offset, 0, this.offset, 0, this.offset.length);
		}

		/** Records h(b) - h(a) = d; false when it contradicts what is known. */
		boolean join(int a, int b, int d) {
			int ra = this.find(a);
			int rb = this.find(b);
			int oa = this.offset[a];
			int ob = this.offset[b];
			if (ra == rb) {
				return ob - oa == d;
			}
			this.parent[rb] = ra;
			this.offset[rb] = oa + d - ob;
			return true;
		}
	}
}
