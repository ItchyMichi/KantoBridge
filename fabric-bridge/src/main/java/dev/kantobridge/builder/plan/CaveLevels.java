package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.ExportField;
import dev.kantobridge.scene.profile.Profile;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapLayout;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Raised ground in a cave (docs/specs/CAVES_3D_PLAN.md §3.4, H4). The Game Boy fakes a plateau with its tile pairs: the
 * engine refuses a step between the two tiles of a pair, and the stairs are tiles in no pair that join the two sides.
 * Here the plateau really rises {@link #RISE} blocks (U1) and the stairs climb it.
 *
 * <ol>
 * <li>The tileset's land pairs sort its tiles into two sides (a pair always joins the two), the rest neutral.</li>
 * <li>A neutral cell with one side's cell on one side and the other's on the opposite side is a stairs cell.</li>
 * <li>Regions: the walked-on and water cells joined by steps the engine allows, stairs cells left out.</li>
 * <li>The high side: the shape profile's raised pins where it has any (a walkable tile pinned to a class that stands up,
 * as Dramaless Shape raises it), else the art: a raised area's south side is a cliff face, solid, so the floor meets it
 * flush only along its north edge; across a pair edge running east to west, the cell to the south is the higher one
 * (and stairs climb north into it). The side with more votes is high; a tie in both leaves the map flat.</li>
 * <li>Levels: the high side's regions {@link #RISE}, the rest 0; each stairs cell a ramp from its low end to its high
 * one; a rock cell as high as the highest cell around it.</li>
 * <li>The check (§3.1): for every two neighbouring cells, Minecraft (jumping off, a 0.6-block step, invisible walls on
 * pair edges) must allow exactly the steps the engine allows. If any edge disagrees, the map stays flat.</li>
 * </ol>
 *
 * Pure Java.
 */
public final class CaveLevels {
	/** How many blocks a plateau rises (U1). */
	public static final int RISE = 2;

	/** Where the high side came from. */
	public static final String HIGH_PROFILE = "profile";
	public static final String HIGH_CLIFF_FACE = "cliff_face";

	/**
	 * What became of a map.
	 *
	 * @param layout          its levels and ramps, or null when it stays flat
	 * @param outcome         {@code raised}, {@code no_pairs} (no tile pair in its tileset), or {@code flat_<reason>}
	 * @param high            where the high side came from ({@link #HIGH_PROFILE}, {@link #HIGH_CLIFF_FACE}), or empty
	 * @param stairsConfirmed stairs cells the profile pins to a stair class; {@code stairsOther} the rest, with a profile
	 * @param faces           the pair edges running east to west with the high side south of them, and {@code facesOther}
	 *                        those the other way round (how clearly the art said which side is high)
	 */
	public record Result(@Nullable MapLayout layout, String outcome, String high, int regions, int raisedCells, int stairs, int stairsConfirmed,
			int stairsOther, int faces, int facesOther) {
		static Result flat(String reason) {
			return new Result(null, reason, "", 0, 0, 0, 0, 0, 0, 0);
		}
	}

	private CaveLevels() {
	}

	/** The levels of a cave map from its tileset's pairs, without a profile. */
	public static Result plan(MapModel model, ExportField.TilePairs pairs) {
		return plan(model, pairs, null);
	}

	/** The levels of a cave map from its tileset's pairs and, where there is one, the profile's entry for its tileset. */
	public static Result plan(MapModel model, ExportField.TilePairs pairs, Profile.@Nullable Tileset profile) {
		String tileset = model.tileset().id();
		Set<Integer> keys = pairs.land().getOrDefault(tileset, Set.of());
		if (keys.isEmpty()) {
			return Result.flat("no_pairs");
		}
		Map<Integer, Integer> side = sides(keys);
		if (side == null) {
			return Result.flat("flat_pairs_not_bipartite");
		}
		int w = model.cellWidth();
		int h = model.cellHeight();
		int n = w * h;
		boolean[] standable = new boolean[n];
		int[] cellSide = new int[n];
		Arrays.fill(cellSide, -1);
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				MapModel.Kind kind = model.kind(cx, cy);
				if (kind == MapModel.Kind.LEDGE) {
					// Gen 1's caves have no ledges; a one-way hop beside a cliff is more than this planner knows.
					return Result.flat("flat_ledges");
				}
				int i = cy * w + cx;
				standable[i] = kind == MapModel.Kind.GROUND || kind == MapModel.Kind.WATER;
				if (kind == MapModel.Kind.GROUND) {
					cellSide[i] = side.getOrDefault(PairEdges.collisionTile(model, cx, cy), -1);
				}
			}
		}
		int[] onSide = new int[2];
		for (int each : cellSide) {
			if (each >= 0) {
				onSide[each]++;
			}
		}
		if (onSide[0] == 0 || onSide[1] == 0) {
			// One side only (a floor without raised ground, or all of it raised): nothing stands above anything.
			return Result.flat("flat_one_side");
		}
		// Stairs: a neutral floor cell between the two sides, on one axis.
		Dir[] ramp = new Dir[n];
		int stairs = 0;
		int confirmed = 0;
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				int i = cy * w + cx;
				if (!standable[i] || cellSide[i] >= 0 || model.kind(cx, cy) != MapModel.Kind.GROUND) {
					continue;
				}
				int west = sideAt(cellSide, standable, w, h, cx - 1, cy);
				int east = sideAt(cellSide, standable, w, h, cx + 1, cy);
				int north = sideAt(cellSide, standable, w, h, cx, cy - 1);
				int south = sideAt(cellSide, standable, w, h, cx, cy + 1);
				boolean across = west >= 0 && east >= 0 && west != east;
				boolean along = north >= 0 && south >= 0 && north != south;
				if (across && along) {
					return Result.flat("flat_stairs_both_axes");
				}
				if (across || along) {
					// For now the direction of the side-1 end; flipped below once the high side is known.
					ramp[i] = across ? (east == 1 ? Dir.RIGHT : Dir.LEFT) : (south == 1 ? Dir.DOWN : Dir.UP);
					stairs++;
					if (profile != null) {
						String cls = classAt(model, profile, cx, cy);
						if (cls != null && Profile.CLASSES.get(cls) == Profile.Mode.STAIR) {
							confirmed++;
						}
					}
				}
			}
		}
		// Regions: steps the engine allows between standable cells, stairs cells left out.
		int[] region = new int[n];
		Arrays.fill(region, -1);
		List<int[]> regionSides = new ArrayList<>();
		for (int start = 0; start < n; start++) {
			if (!standable[start] || ramp[start] != null || region[start] >= 0) {
				continue;
			}
			int id = regionSides.size();
			int[] sidesSeen = new int[2];
			Deque<Integer> queue = new ArrayDeque<>();
			region[start] = id;
			queue.add(start);
			while (!queue.isEmpty()) {
				int i = queue.poll();
				int cx = i % w;
				int cy = i / w;
				if (cellSide[i] >= 0) {
					sidesSeen[cellSide[i]]++;
				}
				for (Dir dir : Dir.values()) {
					int nx = cx + dir.dx();
					int ny = cy + dir.dy();
					if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
						continue;
					}
					int j = ny * w + nx;
					if (!standable[j] || ramp[j] != null || region[j] >= 0 || PairEdges.blocked(model, pairs, tileset, cx, cy, nx, ny)) {
						continue;
					}
					region[j] = id;
					queue.add(j);
				}
			}
			if (sidesSeen[0] > 0 && sidesSeen[1] > 0) {
				return Result.flat("flat_mixed_region");
			}
			regionSides.add(sidesSeen);
		}
		// The high side: the profile's raised pins, else south across a pair edge that runs east to west.
		int[] pinned = new int[2];
		if (profile != null) {
			for (int i = 0; i < n; i++) {
				if (cellSide[i] >= 0 && raisedClass(classAt(model, profile, i % w, i / w))) {
					pinned[cellSide[i]]++;
				}
			}
		}
		int[] votes = new int[2];
		for (int cy = 0; cy + 1 < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				int a = cy * w + cx;
				int b = a + w;
				if (cellSide[a] >= 0 && cellSide[b] >= 0 && cellSide[a] != cellSide[b] && PairEdges.blocked(model, pairs, tileset, cx, cy, cx, cy + 1)) {
					votes[cellSide[b]]++;
				}
			}
		}
		String source;
		int high;
		if (pinned[0] != pinned[1]) {
			source = HIGH_PROFILE;
			high = pinned[1] > pinned[0] ? 1 : 0;
		} else if (votes[0] != votes[1]) {
			source = HIGH_CLIFF_FACE;
			high = votes[1] > votes[0] ? 1 : 0;
		} else {
			return Result.flat("flat_no_high_side");
		}
		int[] levels = new int[n];
		int raised = 0;
		for (int i = 0; i < n; i++) {
			if (standable[i] && ramp[i] == null) {
				int[] seen = regionSides.get(region[i]);
				levels[i] = seen[high] > 0 ? RISE : 0;
				raised += levels[i] > 0 ? 1 : 0;
			}
		}
		List<MapLayout.Ramp> ramps = new ArrayList<>();
		Map<Integer, Dir> rampAt = new HashMap<>();
		for (int i = 0; i < n; i++) {
			if (ramp[i] != null) {
				// ramp[i] points at the side-1 end: it climbs that way when side 1 is the high one.
				Dir up = high == 1 ? ramp[i] : ramp[i].opposite();
				rampAt.put(i, up);
				ramps.add(new MapLayout.Ramp(i % w, i / w, up, RISE));
			}
		}
		// A rock cell stands as high as the highest cell around it.
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				int i = cy * w + cx;
				if (standable[i]) {
					continue;
				}
				int top = 0;
				for (int dy = -1; dy <= 1; dy++) {
					for (int dx = -1; dx <= 1; dx++) {
						int nx = cx + dx;
						int ny = cy + dy;
						if (nx < 0 || ny < 0 || nx >= w || ny >= h || !standable[ny * w + nx]) {
							continue;
						}
						int j = ny * w + nx;
						top = Math.max(top, rampAt.containsKey(j) ? RISE : levels[j]);
					}
				}
				levels[i] = top;
			}
		}
		String failed = check(model, pairs, tileset, standable, levels, rampAt, w, h);
		if (failed != null) {
			return Result.flat(failed);
		}
		if (raised == 0 && ramps.isEmpty()) {
			return Result.flat("flat_nothing_raised");
		}
		return new Result(MapLayout.flat(w, h, levels, ramps), "raised", source, regionSides.size(), raised, ramps.size(), confirmed,
				profile == null ? 0 : stairs - confirmed, votes[high], votes[1 - high]);
	}

	/** The profile's class of a cell's collision tile (its bottom-left 8×8 tile), the map's border outside it. */
	private static @Nullable String classAt(MapModel model, Profile.Tileset profile, int cx, int cy) {
		int tx = cx * 2;
		int ty = cy * 2 + 1;
		return profile.classAt(model.tile(tx, ty), tileOrBorder(model, tx, ty - 1), tileOrBorder(model, tx, ty + 1));
	}

	private static int tileOrBorder(MapModel model, int tx, int ty) {
		boolean inside = tx >= 0 && ty >= 0 && tx < model.cellWidth() * 2 && ty < model.cellHeight() * 2;
		return inside ? model.tile(tx, ty) : model.borderTile(tx, ty);
	}

	/**
	 * True for a class that stands up from the floor (an upright or top class, as the shape planner's class boxes take
	 * them): on a walkable tile, the profile draws that floor raised. {@code wall} is the profile's fallback for any solid
	 * tile, so it says nothing.
	 */
	static boolean raisedClass(@Nullable String cls) {
		Profile.Mode mode = cls == null ? null : Profile.CLASSES.get(cls);
		return (mode == Profile.Mode.UPRIGHT || mode == Profile.Mode.TOP) && !"wall".equals(cls);
	}

	/** A cell's side, or -1 outside the map, off the floor or neutral. */
	private static int sideAt(int[] cellSide, boolean[] standable, int w, int h, int cx, int cy) {
		if (cx < 0 || cy < 0 || cx >= w || cy >= h || !standable[cy * w + cx]) {
			return -1;
		}
		return cellSide[cy * w + cx];
	}

	/** The two sides of a tileset's pairs (tile to 0 or 1), or null when a pair joins two tiles of one side. */
	static @Nullable Map<Integer, Integer> sides(Set<Integer> keys) {
		Map<Integer, List<Integer>> next = new HashMap<>();
		for (int key : keys) {
			int a = key >>> 16;
			int b = key & 0xFFFF;
			next.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
			next.computeIfAbsent(b, k -> new ArrayList<>()).add(a);
		}
		Map<Integer, Integer> side = new HashMap<>();
		for (int start : next.keySet().stream().sorted().toList()) {
			if (side.containsKey(start)) {
				continue;
			}
			side.put(start, 0);
			Deque<Integer> queue = new ArrayDeque<>(List.of(start));
			while (!queue.isEmpty()) {
				int tile = queue.poll();
				for (int other : next.get(tile)) {
					Integer seen = side.get(other);
					if (seen == null) {
						side.put(other, 1 - side.get(tile));
						queue.add(other);
					} else if (seen.equals(side.get(tile))) {
						return null;
					}
				}
			}
		}
		return side;
	}

	/**
	 * The edge check: Minecraft must allow exactly the steps the engine allows between every two neighbouring cells. On
	 * one level, a pair edge is closed by its wall and any other edge is open. Between levels, Minecraft never climbs the
	 * rise, and the high cell's wall closes a pair edge going down; so an open edge between levels is allowed only into or
	 * out of a ramp, along it or from its sides. Two ramps may stand side by side, climbing the same way (a wide stair);
	 * one may not climb onto another. Returns the failure's reason, or null.
	 */
	private static @Nullable String check(MapModel model, ExportField.TilePairs pairs, String tileset, boolean[] standable, int[] levels,
			Map<Integer, Dir> rampAt, int w, int h) {
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				int a = cy * w + cx;
				for (Dir dir : new Dir[] {Dir.RIGHT, Dir.DOWN}) {
					int nx = cx + dir.dx();
					int ny = cy + dir.dy();
					if (nx >= w || ny >= h) {
						continue;
					}
					int b = ny * w + nx;
					if (!standable[a] || !standable[b]) {
						continue;
					}
					boolean engineAllows = !PairEdges.blocked(model, pairs, tileset, cx, cy, nx, ny);
					Dir rampA = rampAt.get(a);
					Dir rampB = rampAt.get(b);
					if (rampA != null || rampB != null) {
						if (!engineAllows) {
							return "flat_check_ramp_pair";
						}
						if (rampA != null && rampB != null) {
							if (rampA != rampB || dir == rampA || dir == rampA.opposite()) {
								return "flat_check_ramps_meet";
							}
							continue;
						}
						// The ramp's end its neighbour stands on, or its side, must meet the neighbour's level.
						Dir ramp = rampA != null ? rampA : rampB;
						int other = rampA != null ? levels[b] : levels[a];
						Dir toward = rampA != null ? dir : dir.opposite();
						boolean highEnd = toward == ramp;
						boolean lowEnd = toward == ramp.opposite();
						if (highEnd && other != RISE || lowEnd && other != 0) {
							return "flat_check_ramp_end";
						}
						continue;
					}
					if (levels[a] != levels[b] && engineAllows) {
						// The engine allows the step; Minecraft can't climb the rise.
						return "flat_check_open_rise";
					}
				}
			}
		}
		return null;
	}
}
