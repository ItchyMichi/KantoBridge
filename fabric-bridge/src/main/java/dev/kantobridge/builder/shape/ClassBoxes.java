package dev.kantobridge.builder.shape;

import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.scene.profile.Profile;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * The profile's class boxes in the planner (docs/specs/PROFILE_PLAN.md §5.6, from P3): a solid or counter tile the profile
 * pins to an upright class, a top class or {@code bookcase} stands as a box at its class height (a bookcase rank at its
 * drawn rows) instead of Tier A's measured column. Pixel-free: tile ids, cell kinds, classes and heights. The rules are
 * the research's (Dramaless Shape's TileShape, ChunkMesher and Structures; nothing copied), in our units: one Game Boy
 * pixel a voxel, eight to a block.
 *
 * <pre>
 * which     SOLID or COUNTER tiles pinned to cliff, tree, fence, sign, counter, table, desk (upright), ledge, bed, backrest,
 *           roof (top) or bookcase; not claimed by a building, not in a unit's cell. {@code wall} stays Tier A's: it is
 *           the profile's fallback class for any solid tile, and our measured columns, templates and interior walls know
 *           more. A pin on a walkable tile changes nothing (counted): its collision stays, so its floor stays flat
 * height    the class height (the profile's, then its tileset's); a bookcase rank 8 × (its rows + a cap); under the map's
 *           headroom; a counter cell's one block (its collision)
 * column    block k shows y 8k..min(h, 8k+8): a box key, its sides the band's source tile cropped to its lowest rows, its
 *           top on the last block; a solid cell's blocks over the box, up to two, are empty carriers (PD7); all shape_full
 * fold      (upright) front = the run's south tile (at most six same-class steps); band k's source is the tile k rows north
 *           of the front when it is the same class, else the column's own; the top wears its own row unless it lies in the
 *           face block (the southmost floor(h/8) rows), where it wears the row north of the block (the whole run folded:
 *           the upright pin north of the run, else the run's north row)
 * top       (ledge, bed, backrest, roof) every face its own tile
 * bookcase  each run cut from the south into ranks of at most four rows; a rank's box stands on its two southmost rows,
 *           band b the rank's row b north of its front, the northmost rank's cap (the tile directly north, unpinned or a
 *           table, solid, not claimed) one band more and its top; the rows behind the box are empty carriers on the ground's
 *           floor, or (bookcase_backfill "above") the column of the tile north of the rank
 * culling   a side whose neighbour (a box, or a column's full cubes) stands at least as high as the block is left out
 * panes     a bookcase's front face sinks its panes one pixel (variant p), unless the tileset says bookcase_relief = false
 * </pre>
 */
final class ClassBoxes {
	/** The upright classes P3 boxes ({@code wall} stays Tier A's). */
	static final Set<String> UPRIGHT = Set.of("cliff", "tree", "fence", "sign", "counter", "table", "desk");
	static final Set<String> TOP = Set.of("ledge", "bed", "backrest", "roof");
	static final String BOOKCASE = "bookcase";
	/** The fold walks at most this many tiles along a run. */
	static final int WALK = 6;
	/** A bookcase rank's rows, at most. */
	static final int RANK = 4;

	private ClassBoxes() {
	}

	/** True for a class P3 stands as a box. */
	static boolean boxClass(@Nullable String cls) {
		return cls != null && (UPRIGHT.contains(cls) || TOP.contains(cls) || BOOKCASE.equals(cls));
	}

	/**
	 * The box tiles of a map: per art tile its class, else null. A tile qualifies when it is SOLID or COUNTER, not held by a
	 * building claim, not in a unit's cell, and pinned to a box class whose height is above 0.
	 */
	static @Nullable String[] mark(SceneMap map, ProfileClasses classes, boolean[] held, Map<String, Integer> tally) {
		int cols = map.artCols();
		int rows = map.artRows();
		int per = map.perCell();
		String[] out = new String[cols * rows];
		for (int ty = 0; ty < rows; ty++) {
			for (int tx = 0; tx < cols; tx++) {
				String cls = classes.at(tx, ty);
				if (!boxClass(cls)) {
					continue;
				}
				int i = ty * cols + tx;
				CellKind kind = map.kindAtTile(tx, ty);
				if (kind != CellKind.SOLID && kind != CellKind.COUNTER) {
					tally.merge(kind == CellKind.WALK ? "box_walkable" : "box_other_kind", 1, Integer::sum);
					continue;
				}
				if (held[i]) {
					continue;
				}
				if (kind == CellKind.SOLID) {
					String unit = classes.unitClass(tx / per, ty / per);
					if (unit != null && !ProfileClasses.OTHER.equals(unit)) {
						continue;
					}
				}
				if (classes.tileset().height(cls) <= 0) {
					tally.merge("box_flat", 1, Integer::sum);
					continue;
				}
				out[i] = cls;
			}
		}
		return out;
	}

	/**
	 * Plans the box tiles' columns into a map's columns (the last pass: every other column is final, so culling sees it).
	 *
	 * @param box    per art tile, its box class or null ({@link #mark})
	 * @param held   per art tile, set where a building claim holds it
	 * @param ground the floor a bookcase's empty rows wear
	 */
	static void plan(SceneMap map, int atlas, ProfileClasses classes, @Nullable String[] box, boolean[] held, int ground, ShapeKey[][] keys,
			int[] blocks, int[] mask, int[] floor, Map<String, Integer> tally) {
		int cols = map.artCols();
		int rows = map.artRows();
		int n = cols * rows;
		int headroom = ShapeHeights.headroomPx(map);
		int[] h = new int[n];
		int[][] bands = new int[n][];
		int[] top = new int[n];
		// Bookcase faces inside a rank's box (a front row has no north face, a back row no south face), as bits.
		int[] inner = new int[n];
		// A bookcase rank's empty rows: the tile whose column they take ("above"), -1 for the ground's floor, -2 for none.
		int[] vacated = new int[n];
		java.util.Arrays.fill(vacated, -2);
		boolean backfill = "above".equals(classes.tileset().bookcaseBackfill());
		for (int tx = 0; tx < cols; tx++) {
			int ty = rows - 1;
			while (ty >= 0) {
				if (!BOOKCASE.equals(box[ty * cols + tx])) {
					ty--;
					continue;
				}
				int f = ty;
				int top0 = ty;
				while (top0 > 0 && BOOKCASE.equals(box[(top0 - 1) * cols + tx])) {
					top0--;
				}
				bookcase(map, classes, box, held, tx, top0, f, headroom, h, bands, top, inner, vacated, backfill, tally);
				ty = top0 - 1;
			}
		}
		for (int ty = 0; ty < rows; ty++) {
			for (int tx = 0; tx < cols; tx++) {
				int i = ty * cols + tx;
				String cls = box[i];
				if (cls == null || BOOKCASE.equals(cls)) {
					continue;
				}
				int hh = Math.min(classes.tileset().height(cls), headroom);
				if (map.kindAtTile(tx, ty) == CellKind.COUNTER) {
					hh = Math.min(hh, 8);
				}
				h[i] = hh;
				int nb = (hh + 7) / 8;
				bands[i] = new int[nb];
				int own = map.tile(tx, ty);
				if (TOP.contains(cls)) {
					java.util.Arrays.fill(bands[i], own);
					top[i] = own;
				} else {
					int front = ty;
					for (int s = 0; s < WALK && same(box, cols, rows, tx, front + 1, cls); s++) {
						front++;
					}
					int north = ty;
					for (int s = 0; s < WALK && same(box, cols, rows, tx, north - 1, cls); s++) {
						north--;
					}
					for (int k = 0; k < nb; k++) {
						bands[i][k] = same(box, cols, rows, tx, front - k, cls) ? map.tile(tx, front - k) : own;
					}
					int row = Math.min(ty, front - hh / 8);
					if (row < north) {
						String above = north > 0 ? classes.at(tx, north - 1) : null;
						row = above != null && Profile.CLASSES.get(above) == Profile.Mode.UPRIGHT ? north - 1 : north;
					}
					top[i] = map.tile(tx, row);
				}
				tally.merge("box_" + cls, 1, Integer::sum);
			}
		}
		// The neighbours' heights for culling: a box's own, a column's full cubes from the floor, else nothing.
		int[] cover = new int[n];
		for (int i = 0; i < n; i++) {
			cover[i] = h[i] > 0 ? h[i] : fullCubes(keys[i], blocks[i], mask[i]) * 8;
		}
		for (int ty = 0; ty < rows; ty++) {
			for (int tx = 0; tx < cols; tx++) {
				int i = ty * cols + tx;
				boolean counter = map.kindAtTile(tx, ty) == CellKind.COUNTER;
				if (vacated[i] >= -1) {
					emptyRow(map, atlas, tx, ty, vacated[i], ground, counter, keys, blocks, mask, floor);
					continue;
				}
				if (h[i] <= 0) {
					continue;
				}
				int nb = bands[i].length;
				int total = counter ? nb : Math.max(2, nb);
				ShapeKey[] column = new ShapeKey[total];
				for (int k = 0; k < total; k++) {
					if (k >= nb) {
						column[k] = ShapeKey.empty(atlas);
						continue;
					}
					int y1 = Math.min(h[i], 8 * k + 8);
					int t = bands[i][k];
					int north = (inner[i] & 1) != 0 || covered(cover, cols, rows, tx, ty - 1, y1) ? -1 : t;
					int south = (inner[i] & 2) != 0 || covered(cover, cols, rows, tx, ty + 1, y1) ? -1 : t;
					int west = covered(cover, cols, rows, tx - 1, ty, y1) ? -1 : t;
					int east = covered(cover, cols, rows, tx + 1, ty, y1) ? -1 : t;
					ShapeKey key = ShapeKey.box(atlas, k == nb - 1 ? top[i] : -1, north, south, west, east, 2 * (y1 - 8 * k));
					column[k] = BOOKCASE.equals(box[i]) && south >= 0 && classes.tileset().bookcaseRelief() ? key.withVariant("p") : key;
				}
				keys[i] = column;
				blocks[i] = total;
				mask[i] = (1 << total) - 1;
			}
		}
	}

	/** One bookcase run, rows {@code n..f} of column {@code tx}: its ranks from the south. */
	private static void bookcase(SceneMap map, ProfileClasses classes, String[] box, boolean[] held, int tx, int n, int f, int headroom, int[] h,
			int[][] bands, int[] top, int[] inner, int[] vacated, boolean backfill, Map<String, Integer> tally) {
		int cols = map.artCols();
		int rf = f;
		while (rf >= n) {
			int rn = Math.max(n, rf - (RANK - 1));
			int ranks = rf - rn + 1;
			int cap = -1;
			if (rn == n && n > 0) {
				// The northmost rank's cap: the tile directly north, not claimed, a table pin or an unpinned solid tile.
				String above = classes.at(tx, n - 1);
				CellKind kind = map.kindAtTile(tx, n - 1);
				boolean solid = kind == CellKind.SOLID || kind == CellKind.COUNTER;
				if (!held[(n - 1) * cols + tx] && ("table".equals(above) || (above == null && solid))) {
					cap = map.tile(tx, n - 1);
				}
			}
			int count = ranks + (cap >= 0 ? 1 : 0);
			int depth = Math.min(2, ranks);
			for (int r = rf - depth + 1; r <= rf; r++) {
				int i = r * cols + tx;
				int hh = Math.min(8 * count, headroom);
				if (map.kindAtTile(tx, r) == CellKind.COUNTER) {
					hh = Math.min(hh, 8);
				}
				h[i] = hh;
				int nb = (hh + 7) / 8;
				bands[i] = new int[nb];
				for (int b = 0; b < nb; b++) {
					bands[i][b] = b < ranks ? map.tile(tx, rf - b) : cap;
				}
				top[i] = cap >= 0 ? cap : map.tile(tx, rn);
				inner[i] = (r != rf - depth + 1 ? 1 : 0) | (r != rf ? 2 : 0);
				tally.merge("box_bookcase", 1, Integer::sum);
			}
			for (int r = rn; r <= rf - depth; r++) {
				vacated[r * cols + tx] = backfill && rn > 0 ? map.tile(tx, rn - 1) : -1;
				tally.merge("bookcase_vacated", 1, Integer::sum);
			}
			tally.merge("bookcase_rank", 1, Integer::sum);
			rf = rn - 1;
		}
	}

	/** A bookcase rank's empty row: empty carriers on the ground's floor, or the column the tile north of the rank stands as. */
	private static void emptyRow(SceneMap map, int atlas, int tx, int ty, int source, int ground, boolean counter, ShapeKey[][] keys, int[] blocks,
			int[] mask, int[] floor) {
		int cols = map.artCols();
		int i = ty * cols + tx;
		int total = counter ? 1 : 2;
		ShapeKey[] column = new ShapeKey[total];
		for (int k = 0; k < total; k++) {
			column[k] = source >= 0 ? ShapeKey.cube(atlas, source, source, source, source, source, source) : ShapeKey.empty(atlas);
		}
		keys[i] = column;
		blocks[i] = total;
		mask[i] = (1 << total) - 1;
		floor[i] = source >= 0 ? source : ground;
	}

	/** True when the tile at (tx, ty) holds a box of class {@code cls}. */
	private static boolean same(String[] box, int cols, int rows, int tx, int ty, String cls) {
		return tx >= 0 && ty >= 0 && tx < cols && ty < rows && cls.equals(box[ty * cols + tx]);
	}

	/** True when the neighbour at (tx, ty) covers a side up to {@code y1} pixels. */
	private static boolean covered(int[] cover, int cols, int rows, int tx, int ty, int y1) {
		return tx >= 0 && ty >= 0 && tx < cols && ty < rows && cover[ty * cols + tx] >= y1;
	}

	/** The full cubes a column stands from the floor: plain or cube-keyed blocks that aren't a carrier's shape. */
	static int fullCubes(ShapeKey @Nullable [] column, int blocks, int mask) {
		int k = 0;
		while (k < blocks && (mask >> k & 1) == 0 && (column == null || k >= column.length || column[k] == null
				|| column[k].kind() == ShapeKey.Kind.CUBE)) {
			k++;
		}
		return k;
	}
}
