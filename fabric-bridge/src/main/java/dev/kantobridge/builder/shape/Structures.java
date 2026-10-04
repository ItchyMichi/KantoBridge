package dev.kantobridge.builder.shape;

import dev.kantobridge.builder.plan.HeightRules;
import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.SceneMap;
import java.util.ArrayList;
import java.util.List;

/**
 * The structure analysis of one map from tile ids alone (docs/specs/VOXEL_BLOCKS_PLAN.md §5.4, stage 3): which tiles are
 * solid, the vertical runs of solid tiles in each tile column, the 4-connected regions they form, and each run's measured
 * unit. Integers only, row-major scans, no pixel: the same map always gives the same result (I7, I8).
 *
 * <p>Coordinates are art tiles; one tile is one block column; rows grow southward. A run is rows {@code n..f} of one
 * tile column ({@code n} north, {@code f} the front).
 */
public final class Structures {
	/** A maximal vertical stretch of solid tiles in one tile column. */
	public record Run(int tx, int n, int f) {
		public int length() {
			return this.f - this.n + 1;
		}
	}

	private Structures() {
	}

	/** Per art tile, true when its cell is solid. */
	public static boolean[] solidTiles(SceneMap map) {
		int cols = map.artCols();
		int rows = map.artRows();
		boolean[] solid = new boolean[cols * rows];
		for (int ty = 0; ty < rows; ty++) {
			for (int tx = 0; tx < cols; tx++) {
				solid[ty * cols + tx] = map.kindAtTile(tx, ty) == CellKind.SOLID;
			}
		}
		return solid;
	}

	/** Every run of every tile column, column by column, north to south. */
	public static List<Run> runs(boolean[] solid, int cols, int rows) {
		List<Run> out = new ArrayList<>();
		for (int tx = 0; tx < cols; tx++) {
			int ty = 0;
			while (ty < rows) {
				if (!solid[ty * cols + tx]) {
					ty++;
					continue;
				}
				int n = ty;
				while (ty < rows && solid[ty * cols + tx]) {
					ty++;
				}
				out.add(new Run(tx, n, ty - 1));
			}
		}
		return out;
	}

	/**
	 * The run each solid tile belongs to (an index into {@code runs}), -1 for tiles that aren't solid.
	 */
	public static int[] runOf(List<Run> runs, int cols, int rows) {
		int[] out = new int[cols * rows];
		java.util.Arrays.fill(out, -1);
		for (int r = 0; r < runs.size(); r++) {
			Run run = runs.get(r);
			for (int ty = run.n(); ty <= run.f(); ty++) {
				out[ty * cols + run.tx()] = r;
			}
		}
		return out;
	}

	/**
	 * 4-connected regions of solid tiles, numbered in row-major scan order of their first tile; -1 for tiles that aren't
	 * solid. One map has one atlas, so a region never crosses an atlas change.
	 */
	public static int[] regions(boolean[] solid, int cols, int rows) {
		int[] region = new int[cols * rows];
		java.util.Arrays.fill(region, -1);
		int next = 0;
		int[] queue = new int[cols * rows];
		for (int start = 0; start < solid.length; start++) {
			if (!solid[start] || region[start] >= 0) {
				continue;
			}
			int head = 0;
			int tail = 0;
			queue[tail++] = start;
			region[start] = next;
			while (head < tail) {
				int i = queue[head++];
				int x = i % cols;
				int y = i / cols;
				int[] neighbours = {y > 0 ? i - cols : -1, x > 0 ? i - 1 : -1, x < cols - 1 ? i + 1 : -1, y < rows - 1 ? i + cols : -1};
				for (int n : neighbours) {
					if (n >= 0 && solid[n] && region[n] < 0) {
						region[n] = next;
						queue[tail++] = n;
					}
				}
			}
			next++;
		}
		return region;
	}

	/**
	 * The measured unit of every solid tile (tile rows, 1..{@code maxUnit}), 0 elsewhere: {@link HeightRules#measure}, the
	 * drawn unit along each tile column with its period-1/period-2 repeat rule.
	 */
	public static int[] units(SceneMap map, boolean[] solid) {
		return HeightRules.measure(map.tiles(), map.artCols(), map.artRows(), solid);
	}

	/**
	 * Today's classified height of every cell (V2, {@code heights: "classified"}): solid cells of surface maps take
	 * {@link HeightRules#classify} of the tallest unit among their tiles (2 or 4 blocks), interior solid cells the
	 * interior wall height, counters 1 block, everything else 0. Exactly what {@code MapModel} builds.
	 */
	public static int[] classifiedHeights(SceneMap map, int[] units, int interiorWall) {
		int w = map.w();
		int h = map.h();
		int cols = map.artCols();
		int per = map.perCell();
		int[] heights = new int[w * h];
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				CellKind kind = map.kind(cx, cy);
				int height = 0;
				if (kind == CellKind.COUNTER) {
					height = HeightRules.COUNTER;
				} else if (kind == CellKind.SOLID) {
					if (!map.surface()) {
						height = interiorWall;
					} else {
						int unit = 0;
						for (int dy = 0; dy < per; dy++) {
							for (int dx = 0; dx < per; dx++) {
								unit = Math.max(unit, units[(cy * per + dy) * cols + cx * per + dx]);
							}
						}
						height = HeightRules.classify(unit);
					}
				}
				heights[cy * w + cx] = height;
			}
		}
		return heights;
	}
}
