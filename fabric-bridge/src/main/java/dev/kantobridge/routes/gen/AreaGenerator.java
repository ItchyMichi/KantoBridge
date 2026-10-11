package dev.kantobridge.routes.gen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Makes one area (docs/specs/WILD_ROUTES_PLAN.md §4.3): a 4×4 maze of 12×12-cell blocks with its entrance on the
 * bottom edge and an exit in the farthest block of each side asked for; then the dungeon's entrance, item balls and
 * trainers, each placed only where it can't cut anything off (DWM's 3×3 path test, then a walk from the entrance).
 */
final class AreaGenerator {
	/** Blocks per side. */
	static final int BLOCKS = 4;
	/** Cells per block side. */
	static final int BLOCK = 12;
	/** Cells per area side: 48 cells, 96 Minecraft blocks. */
	static final int SIZE = BLOCKS * BLOCK;
	/** An opening uses cells {@code MID} and {@code MID + 1} of a block's side. */
	static final int MID = BLOCK / 2 - 1;
	/** Trainers stand at least this far apart (Manhattan, in cells). */
	static final int TRAINER_SPACING = 8;
	/** The longest line of sight looked for; a trainer needs at least {@link #MIN_SIGHT} clear cells. */
	static final int MAX_SIGHT = 4;
	static final int MIN_SIGHT = 3;

	private AreaGenerator() {
	}

	/**
	 * An area with an exit on each of {@code exitSides} (in its own frame: {@link Dir#NORTH} is its top), or null when
	 * 64 tries can't make one. The first side gets its farthest block first.
	 */
	static @Nullable Area make(RouteRng rng, RouteSpec spec, List<Dir> exitSides, boolean wantDungeon) {
		for (int attempt = 0; attempt < 64; attempt++) {
			Area area = tryMake(rng, spec, exitSides, wantDungeon);
			if (area != null) {
				return area;
			}
		}
		return null;
	}

	/** The blocks along one side of the area, in order. */
	static int[] edgeBlocks(Dir side) {
		int[] out = new int[BLOCKS];
		for (int i = 0; i < BLOCKS; i++) {
			out[i] = switch (side) {
				case NORTH -> i;
				case SOUTH -> (BLOCKS - 1) * BLOCKS + i;
				case WEST -> i * BLOCKS;
				case EAST -> i * BLOCKS + BLOCKS - 1;
			};
		}
		return out;
	}

	/** The two cells of the opening in block {@code b}'s side {@code side}, on the area's edge. */
	static List<Cell> opening(Dir side, int b) {
		int bx = b % BLOCKS;
		int by = b / BLOCKS;
		return switch (side) {
			case SOUTH -> List.of(new Cell(bx * BLOCK + MID, SIZE - 1), new Cell(bx * BLOCK + MID + 1, SIZE - 1));
			case NORTH -> List.of(new Cell(bx * BLOCK + MID, 0), new Cell(bx * BLOCK + MID + 1, 0));
			case WEST -> List.of(new Cell(0, by * BLOCK + MID), new Cell(0, by * BLOCK + MID + 1));
			case EAST -> List.of(new Cell(SIZE - 1, by * BLOCK + MID), new Cell(SIZE - 1, by * BLOCK + MID + 1));
		};
	}

	private static @Nullable Area tryMake(RouteRng rng, RouteSpec spec, List<Dir> exitSides, boolean wantDungeon) {
		int[] grid = BlockLayout.generate(rng, BLOCKS, BLOCKS, BLOCKS * BLOCKS);
		List<Integer> bottom = new ArrayList<>();
		for (int b : edgeBlocks(Dir.SOUTH)) {
			if (grid[b] != BlockShapes.CLOSED) {
				bottom.add(b);
			}
		}
		if (bottom.isEmpty()) {
			return null;
		}
		int entry = rng.pick(bottom);
		int[] dist = BlockLayout.distances(grid, BLOCKS, BLOCKS, entry);
		EnumMap<Dir, Integer> exits = new EnumMap<>(Dir.class);
		for (Dir side : exitSides) {
			int need = side == Dir.NORTH ? BLOCKS - 1 : 2;
			int far = -1;
			List<Integer> farthest = new ArrayList<>();
			for (int b : edgeBlocks(side)) {
				if (dist[b] < need || b == entry || exits.containsValue(b)) {
					continue;
				}
				if (dist[b] > far) {
					far = dist[b];
					farthest.clear();
				}
				if (dist[b] == far) {
					farthest.add(b);
				}
			}
			if (farthest.isEmpty()) {
				return null;
			}
			exits.put(side, rng.pick(farthest));
		}
		// draw every block
		boolean uniform = rng.chance(spec.theme().uniformChance());
		CellRole[][] cells = new CellRole[SIZE][SIZE];
		for (CellRole[] row : cells) {
			Arrays.fill(row, CellRole.WALL);
		}
		int[] outer = new int[grid.length];
		outer[entry] |= Dir.SOUTH.bit;
		exits.forEach((side, b) -> outer[b] |= side.bit);
		for (int b = 0; b < grid.length; b++) {
			if (grid[b] == BlockShapes.CLOSED) {
				continue;
			}
			CellRole[][] g = spec.theme().drawBlock(rng, grid[b] | outer[b], uniform, BLOCK, BLOCK);
			int ox = (b % BLOCKS) * BLOCK;
			int oy = (b / BLOCKS) * BLOCK;
			for (int r = 0; r < BLOCK; r++) {
				System.arraycopy(g[r], 0, cells[oy + r], ox, BLOCK);
			}
		}
		Cells area = new Cells(cells);
		List<Cell> start = opening(Dir.SOUTH, entry);
		EnumMap<Dir, List<Cell>> openings = new EnumMap<>(Dir.class);
		exits.forEach((side, b) -> openings.put(side, opening(side, b)));
		List<Cell> ends = new ArrayList<>();
		ends.add(start.get(0));
		openings.values().forEach(o -> ends.add(o.get(0)));
		boolean[][] blocked = new boolean[SIZE][SIZE];
		int[][] base = area.walk(start.get(0), blocked);
		for (Cell end : ends) {
			if (base[end.y()][end.x()] < 0) {
				return null;
			}
		}
		// the block kinds
		List<Integer> special = new ArrayList<>(exits.values());
		special.add(entry);
		List<Integer> open = new ArrayList<>();
		List<Integer> deadEnds = new ArrayList<>();
		int junctions = 0;
		for (int b = 0; b < grid.length; b++) {
			if (grid[b] == BlockShapes.CLOSED) {
				continue;
			}
			open.add(b);
			if (BlockShapes.exits(grid[b]) == 1 && !special.contains(b)) {
				deadEnds.add(b);
			}
			if (BlockShapes.exits(grid[b] | outer[b]) >= 3) {
				junctions++;
			}
		}
		List<Cell> targets = new ArrayList<>(); // must keep a reachable neighbour: item balls and the dungeon
		// the dungeon's entrance: the cell of a dead end farthest from the way in
		Cell dungeon = null;
		if (wantDungeon && !deadEnds.isEmpty()) {
			int b = rng.pick(deadEnds);
			int bx = (b % BLOCKS) * BLOCK;
			int by = (b / BLOCKS) * BLOCK;
			int best = -1;
			for (int y = by + 1; y < by + BLOCK - 1; y++) {
				for (int x = bx + 1; x < bx + BLOCK - 1; x++) {
					int d = base[y][x];
					if (d >= 0 && cells[y][x].walkable() && area.pathTest(x, y, blocked) && d >= best) {
						best = d;
						dungeon = new Cell(x, y);
					}
				}
			}
			if (dungeon != null) {
				cells[dungeon.y()][dungeon.x()] = CellRole.DUNGEON;
				blocked[dungeon.y()][dungeon.x()] = true;
				targets.add(dungeon);
				deadEnds.remove(Integer.valueOf(b));
			}
		}
		// item balls: dead ends first (they're in the pool twice), at most 3 per block and one per row of a block
		int count = rng.between(spec.itemsMin(), spec.itemsMax());
		if (open.size() < 6) {
			count = Math.min(count, Math.max(spec.itemsMin(), count / 2));
		}
		if (deadEnds.size() >= 3 && rng.chance(0.3)) {
			count++;
		}
		List<Integer> pool = new ArrayList<>(deadEnds);
		pool.addAll(open);
		List<Cell> items = new ArrayList<>();
		Map<Integer, Integer> perBlock = new HashMap<>();
		for (int i = 0; i < count; i++) {
			for (int tries = 0; tries < 15; tries++) {
				int b = rng.pick(pool);
				if (special.contains(b) || perBlock.getOrDefault(b, 0) >= 3) {
					continue;
				}
				int bx = b % BLOCKS;
				int by = b / BLOCKS;
				int y = by * BLOCK + rng.between(1, BLOCK - 2);
				int x = bx * BLOCK + rng.between(1, BLOCK - 2);
				CellRole role = cells[y][x];
				if ((role != CellRole.GROUND && role != CellRole.GRASS && role != CellRole.FLOWER) || blocked[y][x]
						|| base[y][x] < 0 || !area.pathTest(x, y, blocked)) {
					continue;
				}
				boolean rowTaken = false;
				for (Cell it : items) {
					if (it.y() == y && it.x() / BLOCK == bx) {
						rowTaken = true;
					}
				}
				if (rowTaken) {
					continue;
				}
				Cell cell = new Cell(x, y);
				blocked[y][x] = true;
				targets.add(cell);
				if (!area.allReachable(start.get(0), ends, blocked, targets)) {
					blocked[y][x] = false;
					targets.remove(targets.size() - 1);
					continue;
				}
				items.add(cell);
				perBlock.merge(b, 1, Integer::sum);
				break;
			}
		}
		// trainers: on lanes with a clear line of sight, apart, never cutting off the way, an item or the dungeon
		List<Area.AreaTrainer> trainers = new ArrayList<>();
		int want = rng.between(spec.trainersMin(), spec.trainersMax());
		List<Cell> candidates = new ArrayList<>();
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				CellRole role = cells[y][x];
				if ((role == CellRole.PATH || role == CellRole.GROUND) && base[y][x] >= 0 && !blocked[y][x]
						&& (y / BLOCK) * BLOCKS + x / BLOCK != entry) {
					candidates.add(new Cell(x, y));
				}
			}
		}
		rng.shuffle(candidates);
		for (Cell c : candidates) {
			if (trainers.size() >= want) {
				break;
			}
			if (!area.pathTest(c.x(), c.y(), blocked)) {
				continue;
			}
			boolean near = false;
			for (Area.AreaTrainer t : trainers) {
				if (t.cell().manhattan(c) < TRAINER_SPACING) {
					near = true;
				}
			}
			if (near) {
				continue;
			}
			Dir facing = null;
			int sight = 0;
			for (Dir d : Dir.values()) {
				int n = 0;
				while (n < MAX_SIGHT && area.free(c.x() + d.dx * (n + 1), c.y() + d.dy * (n + 1), blocked)) {
					n++;
				}
				if (n >= MIN_SIGHT && n > sight) {
					facing = d;
					sight = n;
				}
			}
			if (facing == null) {
				continue;
			}
			blocked[c.y()][c.x()] = true;
			if (!area.allReachable(start.get(0), ends, blocked, targets)) {
				blocked[c.y()][c.x()] = false;
				continue;
			}
			trainers.add(new Area.AreaTrainer(c, facing, sight));
		}
		return new Area(cells, grid, entry, exits, start, openings, items, trainers, dungeon, open.size(),
				deadEnds.size(), junctions);
	}

	/** Walking questions on one area's cells. */
	private record Cells(CellRole[][] cells) {
		boolean free(int x, int y, boolean[][] blocked) {
			return x >= 0 && y >= 0 && x < SIZE && y < SIZE && this.cells[y][x].walkable() && !blocked[y][x];
		}

		/** Steps from {@code from} to every cell, around blocked ones; -1 where it can't be reached. */
		int[][] walk(Cell from, boolean[][] blocked) {
			int[][] dist = new int[SIZE][SIZE];
			for (int[] row : dist) {
				Arrays.fill(row, -1);
			}
			if (!this.free(from.x(), from.y(), blocked)) {
				return dist;
			}
			dist[from.y()][from.x()] = 0;
			Deque<Cell> todo = new ArrayDeque<>();
			todo.add(from);
			while (!todo.isEmpty()) {
				Cell p = todo.poll();
				for (Dir d : Dir.values()) {
					int x = p.x() + d.dx;
					int y = p.y() + d.dy;
					if (this.free(x, y, blocked) && dist[y][x] < 0) {
						dist[y][x] = dist[p.y()][p.x()] + 1;
						todo.add(new Cell(x, y));
					}
				}
			}
			return dist;
		}

		/** DWM's 3×3 test: blocking (x, y) must leave its walkable neighbours connected among themselves. */
		boolean pathTest(int x, int y, boolean[][] blocked) {
			List<Cell> nb = new ArrayList<>();
			for (int j = -1; j <= 1; j++) {
				for (int i = -1; i <= 1; i++) {
					if ((i != 0 || j != 0) && this.free(x + i, y + j, blocked)) {
						nb.add(new Cell(x + i, y + j));
					}
				}
			}
			if (nb.isEmpty()) {
				return false;
			}
			List<Cell> seen = new ArrayList<>();
			Deque<Cell> todo = new ArrayDeque<>();
			seen.add(nb.get(0));
			todo.add(nb.get(0));
			while (!todo.isEmpty()) {
				Cell p = todo.poll();
				for (Dir d : Dir.values()) {
					Cell q = p.step(d);
					if (nb.contains(q) && !seen.contains(q)) {
						seen.add(q);
						todo.add(q);
					}
				}
			}
			return seen.size() == nb.size();
		}

		/** Every end reachable from {@code start}, and every target next to a reachable cell. */
		boolean allReachable(Cell start, List<Cell> ends, boolean[][] blocked, List<Cell> targets) {
			int[][] dist = this.walk(start, blocked);
			for (Cell end : ends) {
				if (dist[end.y()][end.x()] < 0) {
					return false;
				}
			}
			for (Cell t : targets) {
				boolean ok = false;
				for (Dir d : Dir.values()) {
					int x = t.x() + d.dx;
					int y = t.y() + d.dy;
					if (x >= 0 && y >= 0 && x < SIZE && y < SIZE && dist[y][x] >= 0) {
						ok = true;
					}
				}
				if (!ok) {
					return false;
				}
			}
			return true;
		}
	}
}
