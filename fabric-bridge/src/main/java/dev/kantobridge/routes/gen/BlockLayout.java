package dev.kantobridge.routes.gen;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * An area's maze of blocks, laid out the way DWM lays out a gate floor (docs/research/ROUTES_RESEARCH.md §4.1): a
 * spiral from the centre outwards; a first pass that only opens a block when a placed neighbour has an exit into it;
 * a second pass that gives every block exactly the exits its neighbours expect, which closes the dangling ones.
 * Blocks are exit masks ({@link BlockShapes}), row-major, {@link BlockShapes#CLOSED} where the maze isn't.
 */
final class BlockLayout {
	private static final int UNSET = -1;

	private BlockLayout() {
	}

	/** Block indices of a {@code w}×{@code h} grid in centre-out spiral order. */
	static int[] spiral(int w, int h) {
		int[] out = new int[w * h];
		int n = 0;
		int r = (h - 1) / 2;
		int c = (w - 1) / 2;
		out[n++] = r * w + c;
		int[][] steps = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};
		int run = 1;
		int k = 0;
		while (n < w * h) {
			for (int leg = 0; leg < 2; leg++) {
				int[] step = steps[k % 4];
				for (int i = 0; i < run; i++) {
					r += step[0];
					c += step[1];
					if (r >= 0 && r < h && c >= 0 && c < w && n < w * h) {
						out[n++] = r * w + c;
					}
				}
				k++;
			}
			run++;
		}
		return out;
	}

	/**
	 * Lays out a maze of blocks.
	 *
	 * @param firstPass how many blocks of the spiral the first pass may open (all of them for a full-size area)
	 */
	static int[] generate(RouteRng rng, int w, int h, int firstPass) {
		int[] order = spiral(w, h);
		int[] grid = new int[w * h];
		Arrays.fill(grid, UNSET);
		grid[order[0]] = BlockShapes.pick(rng, 0, 0);
		for (int i = 1; i < Math.min(firstPass, order.length); i++) {
			int cell = order[i];
			int[] need = constraints(grid, cell, w, h);
			grid[cell] = need[0] != 0 ? BlockShapes.pick(rng, need[0], need[1]) : UNSET;
		}
		for (int cell : order) {
			grid[cell] = constraints(grid, cell, w, h)[0];
		}
		return grid;
	}

	/** {required, forbidden} exits of block {@code d}: toward placed neighbours, and off the grid. */
	private static int[] constraints(int[] grid, int d, int w, int h) {
		int required = 0;
		int forbidden = 0;
		int r = d / w;
		int c = d % w;
		for (Dir dir : Dir.values()) {
			int nr = r + dir.dy;
			int nc = c + dir.dx;
			if (nr < 0 || nr >= h || nc < 0 || nc >= w) {
				forbidden |= dir.bit;
				continue;
			}
			int neighbour = grid[nr * w + nc];
			if (neighbour == UNSET) {
				continue;
			}
			if ((neighbour & dir.opposite().bit) != 0) {
				required |= dir.bit;
			} else {
				forbidden |= dir.bit;
			}
		}
		return new int[] {required, forbidden};
	}

	/** Steps between blocks along their exits from {@code start}; -1 where unreachable. */
	static int[] distances(int[] grid, int w, int h, int start) {
		int[] dist = new int[grid.length];
		Arrays.fill(dist, -1);
		dist[start] = 0;
		Deque<Integer> todo = new ArrayDeque<>();
		todo.add(start);
		while (!todo.isEmpty()) {
			int b = todo.poll();
			for (Dir dir : Dir.values()) {
				if ((grid[b] & dir.bit) == 0) {
					continue;
				}
				int nr = b / w + dir.dy;
				int nc = b % w + dir.dx;
				if (nr < 0 || nr >= h || nc < 0 || nc >= w) {
					continue;
				}
				int nb = nr * w + nc;
				if (dist[nb] < 0) {
					dist[nb] = dist[b] + 1;
					todo.add(nb);
				}
			}
		}
		return dist;
	}
}
