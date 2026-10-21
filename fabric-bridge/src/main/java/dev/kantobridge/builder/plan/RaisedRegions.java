package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.export.G3Map;
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
 * 3D area. A region is the floor cells of one level key that touch (Emerald: one elevation). The heights come from the
 * stairs between regions, never from the keys themselves: Emerald's Victory Road climbs five tiers with two elevations.
 *
 * <ol>
 * <li>Cells that join any level (Emerald's elevation 0) and touch regions of one key are that level. Touching two keys,
 * a straight strip whose ends meet them is a stair: each of its cells a ramp climbing {@link #STAIR_RISE} blocks (a
 * stair cuts through the face, a cell of face per cell of stairs), the higher key's side at its top.</li>
 * <li>Water stands as high as the regions of the shore key it touches (Emerald: elevation 3, the only one surfing stops
 * onto).</li>
 * <li>Regions no stairs join to the rest: a short face (up to {@link #MAX_FACE} solid cells straight between two regions
 * of different keys) sets the northern one two blocks a cell over the southern one; else a region takes the height its
 * key has most elsewhere in the map; else the map's floor level.</li>
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
	/** The longest run of solid cells, in cells, read as a face between two regions (a longer one is rock, not a face). */
	static final int MAX_FACE = 3;
	/** Emerald's elevation that joins any other. */
	static final int G3_ANY = 0;
	/** Emerald's elevation of a bridge: the walker keeps its own. */
	static final int G3_BRIDGE = 15;
	/** Emerald's only elevation surfing stops onto. */
	static final int G3_SHORE = 3;

	/**
	 * What became of a map.
	 *
	 * @param layout      its levels and ramps (the whole map one piece), or null when it stays flat
	 * @param outcome     {@code raised}, or {@code flat_<reason>}
	 * @param regions     its regions
	 * @param raisedCells floor cells above the map's floor level
	 * @param stairs      its stairs (strips of ramps)
	 * @param maxLevel    the highest floor or ramp top, in blocks over the map's floor level
	 */
	public record Result(@Nullable MapLayout layout, String outcome, int regions, int raisedCells, int stairs, int maxLevel) {
		static Result flat(String reason, int regions) {
			return new Result(null, reason, regions, 0, 0, 0);
		}
	}

	/**
	 * A map's cells as the solver reads them.
	 *
	 * @param key    per cell, row by row: a floor cell's level key (ground or ledge), -1 for any other cell
	 * @param joins  per cell: a floor cell that joins any level (Emerald's elevation 0); its key is -1
	 * @param bridge per cell: a floor cell that is two floors at once (Emerald's elevation 15); its key is -1
	 * @param shore  the key water stands level with (the only one the engine lets a surfer step onto), -1 for any
	 */
	public record Input(MapModel model, int[] key, boolean[] joins, boolean[] bridge, int shore) {
	}

	private RaisedRegions() {
	}

	/** Emerald's raised ground: each walkable cell's elevation is its key (0 joins any level, 15 is a bridge). */
	public static Result gen3(MapModel model, G3Map map) {
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
		return solve(new Input(model, key, joins, bridge, G3_SHORE));
	}

	/** The raised ground of one map. */
	public static Result solve(Input in) {
		MapModel model = in.model();
		int w = model.cellWidth();
		int h = model.cellHeight();
		int n = w * h;
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
			List<Integer> cells = flood(w, h, s, (a, b) -> key[b] == key[a] && region[b] < 0);
			for (int c : cells) {
				region[c] = id;
			}
			regionKey.add(key[s]);
			regionCells.add(cells.size());
		}
		int regions = regionKey.size();
		if (regionKey.stream().distinct().count() < 2) {
			return Result.flat("flat_one_level", regions);
		}
		for (boolean b : in.bridge()) {
			if (b) {
				// A cell that is two floors waits for the two-floor cells of W6.
				return Result.flat("flat_bridge", regions);
			}
		}
		Offsets heights = new Offsets(regions);
		// ---- cells that join any level: one level's, or stairs ----
		int[] joinLevelOf = new int[n];
		Arrays.fill(joinLevelOf, -1);
		List<Stair> stairs = new ArrayList<>();
		boolean[] seen = new boolean[n];
		List<int[]> loose = new ArrayList<>();
		for (int s = 0; s < n; s++) {
			if (!in.joins()[s] || seen[s]) {
				continue;
			}
			List<Integer> part = flood(w, h, s, (a, b) -> in.joins()[b] && !seen[b]);
			for (int c : part) {
				seen[c] = true;
			}
			Map<Integer, List<int[]>> touches = new TreeMap<>();
			for (int c : part) {
				for (Dir dir : Dir.values()) {
					int nx = c % w + dir.dx();
					int ny = c / w + dir.dy();
					if (nx >= 0 && ny >= 0 && nx < w && ny < h && region[ny * w + nx] >= 0) {
						touches.computeIfAbsent(region[ny * w + nx], k -> new ArrayList<>()).add(new int[] {c, dir.ordinal()});
					}
				}
			}
			java.util.TreeSet<Integer> keys = new java.util.TreeSet<>();
			for (int r : touches.keySet()) {
				keys.add(regionKey.get(r));
			}
			if (keys.isEmpty()) {
				loose.add(part.stream().mapToInt(Integer::intValue).toArray());
				continue;
			}
			List<Integer> touched = new ArrayList<>(touches.keySet());
			if (keys.size() == 1) {
				for (int r : touched.subList(1, touched.size())) {
					if (!heights.join(touched.get(0), r, 0)) {
						return Result.flat("flat_levels_disagree", regions);
					}
				}
				for (int c : part) {
					joinLevelOf[c] = touched.get(0);
				}
				continue;
			}
			if (keys.size() > 2) {
				return Result.flat("flat_stairs_three_levels", regions);
			}
			Stair stair = stair(w, part, touches, regionKey, keys.first(), keys.last());
			if (stair == null) {
				return Result.flat("flat_stairs_shape", regions);
			}
			List<Integer> low = new ArrayList<>();
			List<Integer> high = new ArrayList<>();
			for (int r : touched) {
				(regionKey.get(r).intValue() == keys.first() ? low : high).add(r);
			}
			for (int r : low.subList(1, low.size())) {
				if (!heights.join(low.get(0), r, 0)) {
					return Result.flat("flat_levels_disagree", regions);
				}
			}
			for (int r : high.subList(1, high.size())) {
				if (!heights.join(high.get(0), r, 0)) {
					return Result.flat("flat_levels_disagree", regions);
				}
			}
			if (!heights.join(low.get(0), high.get(0), STAIR_RISE * stair.length)) {
				// Two stairs of different lengths between the same two regions.
				return Result.flat("flat_stairs_disagree", regions);
			}
			stair.low = low.get(0);
			stairs.add(stair);
		}
		// ---- water: level with the shore key's regions it touches ----
		int[] waterOf = new int[n];
		Arrays.fill(waterOf, -1);
		// each body's shore region (-1 for none) and every region it touches
		List<Integer> waterShore = new ArrayList<>();
		List<List<Integer>> waterBanks = new ArrayList<>();
		boolean[] wet = new boolean[n];
		for (int s = 0; s < n; s++) {
			if (model.kind(s % w, s / w) != MapModel.Kind.WATER || wet[s]) {
				continue;
			}
			List<Integer> part = flood(w, h, s, (a, b) -> model.kind(b % w, b / w) == MapModel.Kind.WATER && !wet[b]);
			int shore = -1;
			List<Integer> banks = new ArrayList<>();
			for (int c : part) {
				wet[c] = true;
				waterOf[c] = waterShore.size();
				for (Dir dir : Dir.values()) {
					int nx = c % w + dir.dx();
					int ny = c / w + dir.dy();
					int r = nx >= 0 && ny >= 0 && nx < w && ny < h ? region[ny * w + nx] : -1;
					if (r < 0) {
						continue;
					}
					if (in.shore() < 0 || regionKey.get(r) == in.shore()) {
						if (shore >= 0 && !heights.join(shore, r, 0)) {
							return Result.flat("flat_water_shores", regions);
						}
						shore = shore < 0 ? r : shore;
					}
					banks.add(r);
				}
			}
			waterShore.add(shore);
			waterBanks.add(banks);
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
		List<int[]> faces = faces(model, region, regionKey, heights);
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
			if (pick < 0) {
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
		for (Stair stair : stairs) {
			for (int k = 0; k < stair.cells.length; k++) {
				int c = stair.cells[k];
				levels[c] = height[stair.low] + STAIR_RISE * stair.steps[k];
				standable[c] = true;
				rampAt.put(c, new MapLayout.Ramp(c % w, c / w, stair.climb, STAIR_RISE));
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
		// The lowest floor is the map's floor level.
		int low = Integer.MAX_VALUE;
		for (int i = 0; i < n; i++) {
			if (standable[i]) {
				low = Math.min(low, levels[i]);
			}
		}
		int top = 0;
		int raised = 0;
		for (int i = 0; i < n; i++) {
			if (standable[i]) {
				levels[i] -= low;
				MapLayout.Ramp ramp = rampAt.get(i);
				top = Math.max(top, levels[i] + (ramp == null ? 0 : ramp.rise()));
				raised += region[i] >= 0 && levels[i] > 0 ? 1 : 0;
			}
		}
		if (top > MapLayout.MAX_LEVEL) {
			return Result.flat("flat_too_tall", regions);
		}
		if (raised == 0 && rampAt.isEmpty()) {
			return Result.flat("flat_one_height", regions);
		}
		rockLevels(model, standable, levels);
		String failed = check(in, standable, levels, rampAt);
		if (failed != null) {
			return Result.flat(failed, regions);
		}
		return new Result(MapLayout.flat(w, h, levels, new ArrayList<>(rampAt.values())), "raised", regions, raised, stairs.size(), top);
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
	 * The short faces between regions of different keys: from each floor cell north through up to {@link #MAX_FACE} solid
	 * cells to a floor cell of another group, as {south region, north region, blocks}.
	 */
	private static List<int[]> faces(MapModel model, int[] region, List<Integer> regionKey, Offsets heights) {
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
				while (y >= 0 && solid(model.kind(cx, y)) && run <= MAX_FACE) {
					run++;
					y--;
				}
				if (run == 0 || run > MAX_FACE || y < 0) {
					continue;
				}
				int north = region[y * w + cx];
				if (north >= 0 && !regionKey.get(north).equals(regionKey.get(south)) && heights.find(north) != heights.find(south)) {
					out.add(new int[] {south, north, STAIR_RISE * run});
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
	 * end); a ledge's three cells stand at one height. Returns the failure's reason, or null.
	 */
	private static @Nullable String check(Input in, boolean[] standable, int[] levels, Map<Integer, MapLayout.Ramp> rampAt) {
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
					Dir hop = switch (CellFlags.ledgeDir(model.flags(cx, cy))) {
						case 0 -> Dir.DOWN;
						case 1 -> Dir.UP;
						case 2 -> Dir.LEFT;
						default -> Dir.RIGHT;
					};
					for (Dir side : new Dir[] {hop, hop.opposite()}) {
						int nx = cx + side.dx();
						int ny = cy + side.dy();
						if (nx >= 0 && ny >= 0 && nx < w && ny < h && standable[ny * w + nx]
								&& (levels[ny * w + nx] != levels[a] || rampAt.containsKey(ny * w + nx))) {
							return "flat_check_ledge";
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
					if (!standable[b] || !joined(model, in, key, a, b)) {
						continue;
					}
					MapLayout.Ramp rampA = rampAt.get(a);
					MapLayout.Ramp rampB = rampAt.get(b);
					if (rampA != null && rampB != null) {
						if (rampA.dir() != rampB.dir()) {
							return "flat_check_ramps_meet";
						}
						boolean along = dir == rampA.dir() || dir == rampA.dir().opposite();
						// side by side: one level; end to end: the lower one's top is the upper one's foot
						int lower = dir == rampA.dir() ? a : b;
						int upper = lower == a ? b : a;
						if (along ? levels[lower] + rampAt.get(lower).rise() != levels[upper] : levels[a] != levels[b]) {
							return "flat_check_ramps_meet";
						}
						continue;
					}
					if (rampA != null || rampB != null) {
						MapLayout.Ramp ramp = rampA != null ? rampA : rampB;
						int r = rampA != null ? a : b;
						int other = rampA != null ? b : a;
						Dir toward = rampA != null ? dir : dir.opposite();
						if (toward == ramp.dir() && levels[other] != levels[r] + ramp.rise()) {
							return "flat_check_ramp_end";
						}
						if (toward == ramp.dir().opposite() && levels[other] != levels[r]) {
							return "flat_check_ramp_end";
						}
						continue;
					}
					if (levels[a] != levels[b]) {
						return "flat_check_open_rise";
					}
				}
			}
		}
		return null;
	}

	/** True when the engine lets a player step between neighbouring cells {@code a} and {@code b} (both stood on). */
	private static boolean joined(MapModel model, Input in, int[] key, int a, int b) {
		int w = model.cellWidth();
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
