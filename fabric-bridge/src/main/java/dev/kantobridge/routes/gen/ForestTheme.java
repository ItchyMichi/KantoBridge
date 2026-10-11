package dev.kantobridge.routes.gen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * The forest maze (docs/specs/WILD_ROUTES_PLAN.md §4.4, Q2): walls of trees, 2-cell lanes from each exit to the
 * block's middle, clearings with ragged edges, meadows, ponds, tall grass and flowers. A dead end gets a pocket where
 * a reward or a dungeon's entrance can go. One area in five draws every block as lanes: a hedge maze.
 */
public final class ForestTheme implements Theme {
	public static final ForestTheme INSTANCE = new ForestTheme();

	private enum Style {
		CLEARING(3), MEADOW(7), LANES(0), POND(2);

		/** Trees and rocks dropped inside the block, where they don't cut an exit off. */
		final int obstacles;

		Style(int obstacles) {
			this.obstacles = obstacles;
		}
	}

	private ForestTheme() {
	}

	@Override
	public String id() {
		return "forest";
	}

	@Override
	public double uniformChance() {
		return 0.2;
	}

	@Override
	public CellRole[][] drawBlock(RouteRng rng, int exits, boolean uniform, int width, int height) {
		Style style = uniform ? Style.LANES : Style.values()[rng.nextInt(4)];
		CellRole[][] g = new CellRole[height][width];
		for (CellRole[] row : g) {
			Arrays.fill(row, CellRole.WALL);
		}
		int mc = width / 2 - 1;
		int mr = height / 2 - 1;
		List<int[][]> doors = new ArrayList<>(); // {{row, col}, {row, col}}
		if ((exits & Dir.NORTH.bit) != 0) {
			doors.add(new int[][] {{0, mc}, {0, mc + 1}});
		}
		if ((exits & Dir.SOUTH.bit) != 0) {
			doors.add(new int[][] {{height - 1, mc}, {height - 1, mc + 1}});
		}
		if ((exits & Dir.WEST.bit) != 0) {
			doors.add(new int[][] {{mr, 0}, {mr + 1, 0}});
		}
		if ((exits & Dir.EAST.bit) != 0) {
			doors.add(new int[][] {{mr, width - 1}, {mr + 1, width - 1}});
		}
		int cr = height / 2 + rng.between(-1, 1) - 1;
		int cc = width / 2 + rng.between(-2, 2) - 1;
		if (style == Style.MEADOW) {
			for (int r = 1; r < height - 1; r++) {
				for (int c = 1; c < width - 1; c++) {
					g[r][c] = CellRole.GROUND;
				}
			}
		} else if (style == Style.CLEARING || style == Style.POND) {
			double ry = rng.uniform(1.6, height / 2.0 - 1.2);
			double rx = rng.uniform(2.0, width / 2.0 - 1.2);
			for (int r = 1; r < height - 1; r++) {
				for (int c = 1; c < width - 1; c++) {
					double dy = (r - cr - 0.5) / ry;
					double dx = (c - cc - 0.5) / rx;
					if (dy * dy + dx * dx <= 1 + rng.uniform(-0.25, 0.25)) {
						g[r][c] = CellRole.GROUND;
					}
				}
			}
		}
		// 2-cell lanes from each door to the middle: an L, with its corner on a random row or column
		for (int[][] door : doors) {
			int r0 = door[0][0];
			int c0 = door[0][1];
			int r1 = door[1][0];
			int c1 = door[1][1];
			if (c0 != c1) { // a door in the top or bottom side
				for (int r = Math.min(r0, cr); r <= Math.max(r0, cr) + 1; r++) {
					if (r >= 0 && r < height) {
						path(g, r, c0);
						path(g, r, c1);
					}
				}
				for (int c = Math.min(c0, cc); c <= Math.max(c0, cc) + 1; c++) {
					if (c > 0 && c < width - 1) {
						path(g, cr, c);
						path(g, cr + 1, c);
					}
				}
			} else {
				for (int c = Math.min(c0, cc); c <= Math.max(c0, cc) + 1; c++) {
					if (c >= 0 && c < width) {
						path(g, r0, c);
						path(g, r1, c);
					}
				}
				for (int r = Math.min(r0, cr); r <= Math.max(r0, cr) + 1; r++) {
					if (r > 0 && r < height - 1) {
						path(g, r, cc);
						path(g, r, cc + 1);
					}
				}
			}
		}
		if (doors.size() == 1) { // a dead end: a pocket for a reward
			for (int r = Math.max(1, cr - 1); r < Math.min(height - 1, cr + 3); r++) {
				for (int c = Math.max(1, cc - 2); c < Math.min(width - 1, cc + 4); c++) {
					if (g[r][c] == CellRole.WALL) {
						g[r][c] = CellRole.GROUND;
					}
				}
			}
		}
		List<int[]> doorCells = new ArrayList<>();
		for (int[][] door : doors) {
			doorCells.add(door[0]);
			doorCells.add(door[1]);
		}
		if (style == Style.POND) {
			for (int r = cr - 1; r <= cr + 1; r++) {
				for (int c = cc - 1; c <= cc + 2; c++) {
					if (r >= 1 && r < height - 1 && c >= 1 && c < width - 1) {
						tryPut(g, doorCells, r, c, CellRole.WATER);
					}
				}
			}
		}
		for (int i = 0; i < style.obstacles; i++) {
			int r = rng.between(1, height - 2);
			int c = rng.between(1, width - 2);
			tryPut(g, doorCells, r, c, rng.chance(0.5) ? CellRole.WALL : CellRole.ROCK);
		}
		int blobs = rng.between(0, 2);
		for (int i = 0; i < blobs; i++) {
			int r0 = rng.between(1, height - 2);
			int c0 = rng.between(1, width - 2);
			for (int r = r0 - 1; r <= r0 + 1; r++) {
				for (int c = c0 - 2; c <= c0 + 1; c++) {
					if (r > 0 && r < height - 1 && c > 0 && c < width - 1
							&& (g[r][c] == CellRole.GROUND || g[r][c] == CellRole.PATH) && rng.chance(0.85)) {
						g[r][c] = CellRole.GRASS;
					}
				}
			}
		}
		int flowers = rng.between(0, 3);
		for (int i = 0; i < flowers; i++) {
			int r = rng.between(1, height - 2);
			int c = rng.between(1, width - 2);
			if (g[r][c] == CellRole.GROUND) {
				g[r][c] = CellRole.FLOWER;
			}
		}
		return g;
	}

	private static void path(CellRole[][] g, int r, int c) {
		if (g[r][c] == CellRole.WALL) {
			g[r][c] = CellRole.PATH;
		}
	}

	/** Puts {@code role} at a ground or path cell, unless that cuts a door off from the others. */
	private static void tryPut(CellRole[][] g, List<int[]> doorCells, int r, int c, CellRole role) {
		if (g[r][c] != CellRole.GROUND && g[r][c] != CellRole.PATH) {
			return;
		}
		CellRole old = g[r][c];
		g[r][c] = role;
		if (!doorsConnected(g, doorCells)) {
			g[r][c] = old;
		}
	}

	private static boolean doorsConnected(CellRole[][] g, List<int[]> doorCells) {
		if (doorCells.isEmpty()) {
			return true;
		}
		int h = g.length;
		int w = g[0].length;
		boolean[][] seen = new boolean[h][w];
		Deque<int[]> todo = new ArrayDeque<>();
		int[] start = doorCells.get(0);
		if (!g[start[0]][start[1]].walkable()) {
			return false;
		}
		seen[start[0]][start[1]] = true;
		todo.add(start);
		while (!todo.isEmpty()) {
			int[] p = todo.poll();
			for (Dir dir : Dir.values()) {
				int r = p[0] + dir.dy;
				int c = p[1] + dir.dx;
				if (r >= 0 && r < h && c >= 0 && c < w && !seen[r][c] && g[r][c].walkable()) {
					seen[r][c] = true;
					todo.add(new int[] {r, c});
				}
			}
		}
		for (int[] d : doorCells) {
			if (!seen[d[0]][d[1]]) {
				return false;
			}
		}
		return true;
	}
}
