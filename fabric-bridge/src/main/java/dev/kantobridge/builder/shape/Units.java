package dev.kantobridge.builder.shape;

import dev.kantobridge.builder.shape.geom.Background;
import dev.kantobridge.builder.shape.geom.CellUnits;
import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.CellTag;
import dev.kantobridge.scene.SceneAtlas;
import dev.kantobridge.scene.SceneMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * Stage 4 of the planner (docs/specs/VOXEL_BLOCKS_PLAN.md §5.4, Tier B, from {@code kb-shapes/3}): cell units and tufts,
 * from tile ids and cell kinds only (I8). Row-major scans, ties by the lowest tile id.
 *
 * <pre>
 * tuft       a walkable tile showing the atlas's grass tile, or a tile that cycles through frames (a flower): a tuft key
 *            at feet level, on a decor carrier
 * cell unit  a solid cell whose columns stand at most perCell blocks high without a roof, and that (first match):
 *            the profile pins to a unit class (docs/specs/PROFILE_PLAN.md §5.4: a hull, a standee pool, a post);
 *            the profile pins to other classes only: no unit;
 *            is a cut tree, wears the foliage hint on every tile (a tree wall), or stands alone (no solid 4-neighbour);
 *            repeats its four tiles elsewhere in its 4-connected region of solid cells, when the probe says its drawing
 *            stands as a hull or an object (PD3; a plain wall or a cliff stays a column)
 * ground g   a pinned unit's prop_ground when the profile names one; else the most frequent ground tile (walkable,
 *            neither grass nor flower) of its walkable 4-neighbour cells (ties: the lowest id); without one, the map's most
 *            frequent ground tile; without any, -1 (the floor keeps its tile)
 * volume     its perCell × perCell columns, perCell blocks high, every block a cell key on shape_full; its floor wears g
 * context    the keys' variant ({@link CellUnits.Context}): the sides bordering walkable cells (outdoors the south side
 *            only), indoors, repeating vertically (the same tiles in the cell north or south), a sign, foliage, and a
 *            pinned unit's class and prop_bg shades
 * </pre>
 */
final class Units {
	/**
	 * One unit: its cell, the key of its first block (the unit's signature for the report), and why it is one
	 * ({@code pin}, {@code cut}, {@code hint}, {@code lone}, {@code probe}).
	 */
	record Cell(int cx, int cy, ShapeKey key, boolean foliage, String source) {
		Cell(int cx, int cy, ShapeKey key, boolean foliage) {
			this(cx, cy, key, foliage, foliage ? "hint" : "lone");
		}
	}

	private Units() {
	}

	/**
	 * Plans one map's tufts and units into its columns (both arrays per art tile).
	 *
	 * @param floor per tile, the floor block's tile, -1 for its own
	 * @param decor per tile, bit k set when block k (from feet level) is a decor carrier
	 */
	static void plan(SceneMap map, SceneAtlas atlas, int atlasIndex, ShapeKey[][] keys, int[] blocks, int[] mask, int[] floor, int[] decor,
			List<Cell> units) {
		plan(map, atlas, atlasIndex, keys, blocks, mask, floor, decor, units, null, null, new TreeMap<>());
	}

	/**
	 * {@link #plan} with the profile's classes for this map (null: none), the probe of PD3 (null: none), and a tally of
	 * the units by source and of the pinned cells left as they were, by reason ({@code pin_not_low}, {@code pin_not_solid}).
	 */
	static void plan(SceneMap map, SceneAtlas atlas, int atlasIndex, ShapeKey[][] keys, int[] blocks, int[] mask, int[] floor, int[] decor,
			List<Cell> units, @Nullable ProfileClasses classes, @Nullable UnitProbe probe, Map<String, Integer> tally) {
		int per = map.perCell();
		int cols = map.artCols();
		Set<Integer> grass = atlas.grassTiles();
		Set<Integer> frames = atlas.frameTiles();
		// Tufts: grass and flowers stand up from walkable tiles.
		for (int ty = 0; ty < map.artRows(); ty++) {
			for (int tx = 0; tx < cols; tx++) {
				int tile = map.tile(tx, ty);
				if (map.kindAtTile(tx, ty) == CellKind.WALK && (grass.contains(tile) || frames.contains(tile))) {
					keys[ty * cols + tx] = new ShapeKey[] {ShapeKey.tuft(atlasIndex, tile)};
					decor[ty * cols + tx] |= 1;
				}
			}
		}
		int mapGround = mapGround(map, grass, frames);
		boolean indoor = !(map.surface() && map.env().outdoor());
		int[] region = probe == null ? null : regions(map);
		Map<String, Integer> repeats = probe == null ? null : repeats(map, region);
		for (int cy = 0; cy < map.h(); cy++) {
			for (int cx = 0; cx < map.w(); cx++) {
				String pinned = classes == null ? null : classes.unitClass(cx, cy);
				boolean unitPin = pinned != null && !ProfileClasses.OTHER.equals(pinned);
				if (map.kind(cx, cy) != CellKind.SOLID) {
					if (unitPin) {
						tally.merge("pin_not_solid", 1, Integer::sum);
					}
					continue;
				}
				if (!low(map, blocks, mask, cx, cy)) {
					if (unitPin) {
						tally.merge("pin_not_low", 1, Integer::sum);
					}
					continue;
				}
				if (pinned != null && !unitPin) {
					// The profile pins this cell to classes that are not units: it stays what Tier A made it.
					continue;
				}
				int[] tiles = tiles(map, cx, cy);
				boolean cut = map.hasTag(cx, cy, CellTag.CUT);
				boolean foliage = cut || foliage(atlas, tiles);
				boolean lone = true;
				boolean repeatsVertically = false;
				int[][] steps = {{0, -1}, {-1, 0}, {1, 0}, {0, 1}};
				for (int[] step : steps) {
					int nx = cx + step[0];
					int ny = cy + step[1];
					if (nx < 0 || ny < 0 || nx >= map.w() || ny >= map.h() || map.kind(nx, ny) != CellKind.SOLID) {
						continue;
					}
					lone = false;
					repeatsVertically |= step[0] == 0 && Arrays.equals(tiles(map, nx, ny), tiles);
				}
				int background = unitPin ? classes.propBackground(tiles) : 0;
				CellUnits.Context context = new CellUnits.Context(flatSides(map, cx, cy, indoor), indoor, repeatsVertically,
						map.hasTag(cx, cy, CellTag.SIGN), foliage, unitPin ? pinned : null, background);
				String source = unitPin ? "pin" : cut ? "cut" : foliage ? "hint" : lone ? "lone" : null;
				if (source == null && probe != null && repeats.getOrDefault(region[cy * map.w() + cx] + "|" + Arrays.toString(tiles), 0) >= 2
						&& probe.shaped(map.atlas(), tiles, context)) {
					source = "probe";
				}
				if (source == null) {
					continue;
				}
				tally.merge(source, 1, Integer::sum);
				int propGround = unitPin ? classes.propGround(tiles) : -1;
				int g = propGround >= 0 ? propGround : ground(map, cx, cy, grass, frames, mapGround);
				ShapeKey first = null;
				for (int bz = 0; bz < per; bz++) {
					for (int bx = 0; bx < per; bx++) {
						int i = (cy * per + bz) * cols + cx * per + bx;
						ShapeKey[] column = new ShapeKey[per];
						for (int by = 0; by < per; by++) {
							column[by] = ShapeKey.cell(atlasIndex, tiles, g, bx, by, bz).withVariant(context.variant());
						}
						first = first == null ? column[0] : first;
						keys[i] = column;
						blocks[i] = per;
						mask[i] = (1 << per) - 1;
						floor[i] = g;
					}
				}
				units.add(new Cell(cx, cy, first, foliage, source));
			}
		}
	}

	/** Per cell, its 4-connected region of solid cells (the index of the region's first cell in scan order), -1 elsewhere. */
	static int[] regions(SceneMap map) {
		int w = map.w();
		int h = map.h();
		int[] region = new int[w * h];
		Arrays.fill(region, -1);
		int[] queue = new int[w * h];
		for (int start = 0; start < w * h; start++) {
			if (region[start] >= 0 || map.kind(start % w, start / w) != CellKind.SOLID) {
				continue;
			}
			int head = 0;
			int tail = 0;
			queue[tail++] = start;
			region[start] = start;
			while (head < tail) {
				int c = queue[head++];
				int cx = c % w;
				int cy = c / w;
				int[] next = {cy > 0 ? c - w : -1, cx > 0 ? c - 1 : -1, cx < w - 1 ? c + 1 : -1, cy < h - 1 ? c + w : -1};
				for (int n : next) {
					if (n >= 0 && region[n] < 0 && map.kind(n % w, n / w) == CellKind.SOLID) {
						region[n] = start;
						queue[tail++] = n;
					}
				}
			}
		}
		return region;
	}

	/** How often each cell's four tiles occur in its region: {@code "<region>|<tiles>"} to count. */
	static Map<String, Integer> repeats(SceneMap map, int[] region) {
		Map<String, Integer> out = new TreeMap<>();
		for (int cy = 0; cy < map.h(); cy++) {
			for (int cx = 0; cx < map.w(); cx++) {
				if (region[cy * map.w() + cx] >= 0) {
					out.merge(region[cy * map.w() + cx] + "|" + Arrays.toString(tiles(map, cx, cy)), 1, Integer::sum);
				}
			}
		}
		return out;
	}

	/** The keys of a unit drawn by {@code tiles} in a context: per block {bx, by, bz} in the unit's order. */
	static ShapeKey[] keys(int atlasIndex, int[] tiles, int ground, String variant, int per) {
		ShapeKey[] out = new ShapeKey[per * per * per];
		for (int by = 0; by < per; by++) {
			for (int bz = 0; bz < per; bz++) {
				for (int bx = 0; bx < per; bx++) {
					out[CellUnits.block(per, bx, by, bz)] = ShapeKey.cell(atlasIndex, tiles, ground, bx, by, bz).withVariant(variant);
				}
			}
		}
		return out;
	}

	/** A cell's tiles, row-major. */
	static int[] tiles(SceneMap map, int cx, int cy) {
		int per = map.perCell();
		int[] out = new int[per * per];
		for (int dy = 0; dy < per; dy++) {
			for (int dx = 0; dx < per; dx++) {
				out[dy * per + dx] = map.tile(cx * per + dx, cy * per + dy);
			}
		}
		return out;
	}

	/** Every tile wears the front end's foliage hint. */
	static boolean foliage(SceneAtlas atlas, int[] tiles) {
		for (int tile : tiles) {
			if (!SceneAtlas.HINT_FOLIAGE.equals(atlas.hints().get(tile))) {
				return false;
			}
		}
		return true;
	}

	/** A cell's columns all stand at most perCell blocks high, without a roof. */
	private static boolean low(SceneMap map, int[] blocks, int[] mask, int cx, int cy) {
		int per = map.perCell();
		int cols = map.artCols();
		for (int dy = 0; dy < per; dy++) {
			for (int dx = 0; dx < per; dx++) {
				int i = (cy * per + dy) * cols + cx * per + dx;
				if (blocks[i] > per || mask[i] != 0) {
					return false;
				}
			}
		}
		return true;
	}

	/** The sides of a cell that border walkable cells (outdoors only the south side counts). */
	private static int flatSides(SceneMap map, int cx, int cy, boolean indoor) {
		int sides = 0;
		sides |= walk(map, cx, cy + 1) ? Background.SOUTH : 0;
		if (indoor) {
			sides |= walk(map, cx, cy - 1) ? Background.NORTH : 0;
			sides |= walk(map, cx - 1, cy) ? Background.WEST : 0;
			sides |= walk(map, cx + 1, cy) ? Background.EAST : 0;
		}
		return sides;
	}

	private static boolean walk(SceneMap map, int cx, int cy) {
		return cx >= 0 && cy >= 0 && cx < map.w() && cy < map.h() && map.kind(cx, cy) == CellKind.WALK;
	}

	/** The unit's ground tile (see the class comment). */
	static int ground(SceneMap map, int cx, int cy, Set<Integer> grass, Set<Integer> frames, int fallback) {
		TreeMap<Integer, Integer> counts = new TreeMap<>();
		int[][] steps = {{0, -1}, {-1, 0}, {1, 0}, {0, 1}};
		for (int[] step : steps) {
			if (walk(map, cx + step[0], cy + step[1])) {
				count(counts, tiles(map, cx + step[0], cy + step[1]), grass, frames);
			}
		}
		return counts.isEmpty() ? fallback : mostFrequent(counts);
	}

	/** The map's most frequent ground tile, -1 without walkable ground. */
	static int mapGround(SceneMap map, Set<Integer> grass, Set<Integer> frames) {
		TreeMap<Integer, Integer> counts = new TreeMap<>();
		for (int cy = 0; cy < map.h(); cy++) {
			for (int cx = 0; cx < map.w(); cx++) {
				if (map.kind(cx, cy) == CellKind.WALK) {
					count(counts, tiles(map, cx, cy), grass, frames);
				}
			}
		}
		return counts.isEmpty() ? -1 : mostFrequent(counts);
	}

	private static void count(TreeMap<Integer, Integer> counts, int[] tiles, Set<Integer> grass, Set<Integer> frames) {
		for (int tile : tiles) {
			if (!grass.contains(tile) && !frames.contains(tile)) {
				counts.merge(tile, 1, Integer::sum);
			}
		}
	}

	/** The most frequent tile; ties: the lowest id (the map is sorted, and only a larger count replaces). */
	private static int mostFrequent(TreeMap<Integer, Integer> counts) {
		int best = -1;
		int bestCount = 0;
		for (var e : counts.entrySet()) {
			if (e.getValue() > bestCount) {
				best = e.getKey();
				bestCount = e.getValue();
			}
		}
		return best;
	}
}
