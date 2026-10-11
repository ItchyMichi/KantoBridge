package dev.kantobridge.routes.gen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import org.jetbrains.annotations.Nullable;

/**
 * Makes a dungeon floor the way PMD Red makes its standard floors (docs/research/ROUTES_RESEARCH.md §5), re-implemented
 * from the user's report in its order, with the report's known bugs fixed: the dead-end fix checks the neighbour it
 * links to, the whole grid starts out as wall, and every attempt starts from a clean state.
 *
 * <p>The steps: a 2–6 × 2–4 grid of cells; a room or a one-tile anchor in each; a linking walk with momentum; dead ends
 * linked on; corridors with one jog on a cell edge; 5% room merges; repair of cells the walk missed; a shop or a
 * Monster House; extra tunnelling hallways; room structures; rivers and lakes; then the stairs, items, traps, your
 * start and the wild Pokémon, and a check that every floor tile can be reached from the stairs. Ten layouts per try,
 * ten tries, then the report's one-room fallback.
 *
 * <p>Two choices of ours: our own random numbers ({@link RouteRng}), and reachability counts only floor tiles, four
 * ways, because in Minecraft you can't cut a corner or cross water.
 */
public final class PmdFloorGenerator {
	public static final int WIDTH = 56;
	public static final int HEIGHT = 32;
	private static final int CORR = 0xFF;
	private static final int ANCHOR = 0xFE;
	/** Link directions: right, up, left, down. */
	private static final int[][] DX = {{1, 0}, {0, -1}, {-1, 0}, {0, 1}};
	/** The extra hallways' directions: south, east, north, west. */
	private static final int[][] SDIR = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};
	private static final int[] RIVERS = {1, 1, 1, 2, 2, 2, 3, 3};

	private static final PmdFloor.Tile WALL = PmdFloor.Tile.WALL;
	private static final PmdFloor.Tile FLOOR = PmdFloor.Tile.FLOOR;
	private static final PmdFloor.Tile WATER = PmdFloor.Tile.WATER;

	private final RouteRng rng;
	private final PmdParams p;
	private final int[] budget = new int[1];

	private PmdFloor.Tile[][] t;
	private int[][] room;
	private boolean[][] imp;
	private boolean[][] junction;
	private boolean[][] shop;
	private boolean[][] house;
	private boolean[][] special;
	private boolean[][] cut;
	private List<Cell> items;
	private List<Cell> traps;
	private List<Cell> enemies;
	private @Nullable Cell player;
	private @Nullable Cell stairs;

	private int gx;
	private int gy;
	private int[] xs;
	private int[] ys;
	private GridCell[][] cells;

	private static final class GridCell {
		boolean valid = true;
		boolean isRoom = true;
		boolean connected;
		boolean merged;
		boolean absorbed;
		boolean flagStructure;
		boolean shop;
		boolean house;
		final boolean[] link = new boolean[4];
		int sx;
		int sy;
		int ex;
		int ey;
		int index = -1;

		int links() {
			int n = 0;
			for (boolean l : this.link) {
				if (l) {
					n++;
				}
			}
			return n;
		}
	}

	private PmdFloorGenerator(PmdParams params, long seed) {
		this.p = params;
		this.rng = new RouteRng(seed);
	}

	/** Makes the floor {@code params} describe from {@code seed}; the same seed always makes the same floor. */
	public static PmdFloor generate(PmdParams params, long seed) {
		return new PmdFloorGenerator(params, seed).run(seed);
	}

	private int rint(int n) {
		return n > 0 ? this.rng.nextInt(n) : 0;
	}

	/** The report's range: {@code a} when equal, else uniform from the smaller up to (not including) the larger. */
	private int rrange(int a, int b) {
		return a == b ? a : Math.min(a, b) + this.rint(Math.abs(b - a));
	}

	private PmdFloor run(long seed) {
		this.budget[0] = this.p.structures();
		int layoutTries = 0;
		int spawnTries = 0;
		for (int spawnTry = 0; spawnTry < 10; spawnTry++) {
			boolean built = false;
			for (int layoutTry = 0; layoutTry < 10; layoutTry++) {
				layoutTries++;
				if (layoutTry > 0) {
					this.budget[0] = 0;
				}
				this.reset();
				this.standardFloor();
				for (int x = 0; x < WIDTH; x++) {
					for (int y : new int[] {1, HEIGHT - 2}) {
						this.t[x][y] = WALL;
						this.room[x][y] = CORR;
					}
				}
				int roomTiles = 0;
				Set<Integer> ids = new HashSet<>();
				for (int x = 0; x < WIDTH; x++) {
					for (int y = 0; y < HEIGHT; y++) {
						if (this.t[x][y] == FLOOR && this.room[x][y] <= 240) {
							roomTiles++;
							ids.add(this.room[x][y]);
						}
					}
				}
				if (roomTiles >= 30 && ids.size() >= 2) {
					built = true;
					break;
				}
			}
			if (!built) {
				this.reset();
				this.oneRoomHouse();
			}
			this.finalizeJunctions();
			if (this.p.rivers()) {
				this.riversAndLakes();
			}
			spawnTries++;
			this.spawns();
			if (this.player != null && this.stairs != null && this.allFloorReachable(this.stairs)) {
				return this.result(seed, !built, layoutTries, spawnTries);
			}
		}
		this.reset();
		this.oneRoomHouse();
		this.finalizeJunctions();
		this.spawns();
		return this.result(seed, true, layoutTries, spawnTries);
	}

	private void reset() {
		this.t = new PmdFloor.Tile[WIDTH][HEIGHT];
		this.room = new int[WIDTH][HEIGHT];
		this.imp = new boolean[WIDTH][HEIGHT];
		for (int x = 0; x < WIDTH; x++) {
			for (int y = 0; y < HEIGHT; y++) {
				this.t[x][y] = WALL;
				this.room[x][y] = CORR;
				this.imp[x][y] = x == 0 || x == WIDTH - 1 || y == 0 || y == HEIGHT - 1;
			}
		}
		this.junction = new boolean[WIDTH][HEIGHT];
		this.shop = new boolean[WIDTH][HEIGHT];
		this.house = new boolean[WIDTH][HEIGHT];
		this.special = new boolean[WIDTH][HEIGHT];
		this.cut = new boolean[WIDTH][HEIGHT];
		this.items = new ArrayList<>();
		this.traps = new ArrayList<>();
		this.enemies = new ArrayList<>();
		this.player = null;
		this.stairs = null;
	}

	private boolean open(int x, int y) {
		return x >= 0 && x < WIDTH && y >= 0 && y < HEIGHT && this.t[x][y] == FLOOR;
	}

	private boolean inside(int x, int y, int d) {
		int nx = x + DX[d][0];
		int ny = y + DX[d][1];
		return nx >= 0 && nx < this.gx && ny >= 0 && ny < this.gy;
	}

	private GridCell next(int x, int y, int d) {
		return this.cells[x + DX[d][0]][y + DX[d][1]];
	}

	private void link(int x, int y, int d) {
		this.cells[x][y].link[d] = true;
		this.next(x, y, d).link[(d + 2) % 4] = true;
	}

	private void standardFloor() {
		while (true) { // a uniform grid size, by rejection
			this.gx = this.rrange(2, 9);
			this.gy = this.rrange(2, 8);
			if (this.gx <= 6 && this.gy <= 4) {
				break;
			}
		}
		this.xs = new int[this.gx + 1];
		this.ys = new int[this.gy + 1];
		for (int i = 0; i <= this.gx; i++) {
			this.xs[i] = i * (WIDTH / this.gx);
		}
		for (int j = 0; j <= this.gy; j++) {
			this.ys[j] = j * (HEIGHT / this.gy);
		}
		this.cells = new GridCell[this.gx][this.gy];
		for (int x = 0; x < this.gx; x++) {
			for (int y = 0; y < this.gy; y++) {
				this.cells[x][y] = new GridCell();
			}
		}
		// rooms or anchors
		int n = this.p.roomDensity() + this.rint(3);
		boolean[] arr = new boolean[256];
		for (int i = 0; i < Math.min(n, 256); i++) {
			arr[i] = true;
		}
		for (int i = 0; i < 64; i++) {
			int a = this.rint(this.gx * this.gy);
			int b = this.rint(this.gx * this.gy);
			boolean swap = arr[a];
			arr[a] = arr[b];
			arr[b] = swap;
		}
		int k = 0;
		for (int x = 0; x < this.gx; x++) {
			for (int y = 0; y < this.gy; y++) {
				this.cells[x][y].isRoom = arr[k++];
			}
		}
		if (this.gx % 2 == 1 && this.gy > 1) {
			this.cells[(this.gx - 1) / 2][1].isRoom = false;
		}
		int rooms = 0;
		for (GridCell[] col : this.cells) {
			for (GridCell c : col) {
				if (c.isRoom) {
					rooms++;
				}
			}
		}
		if (rooms < 2) {
			more:
			for (int it = 0; it < 200; it++) {
				for (int x = 0; x < this.gx; x++) {
					for (int y = 0; y < this.gy; y++) {
						if (this.rint(100) < 60) {
							this.cells[x][y].isRoom = true;
							break more;
						}
					}
				}
			}
		}
		// carve the rooms and anchors, row by row
		int idx = 0;
		for (int y = 0; y < this.gy; y++) {
			for (int x = 0; x < this.gx; x++) {
				GridCell c = this.cells[x][y];
				int cx = this.xs[x];
				int cy = this.ys[y];
				int cw = this.xs[x + 1] - this.xs[x];
				int ch = this.ys[y + 1] - this.ys[y];
				int minx = cx + 2;
				int rx = cw - 4;
				int miny = cy + 2;
				int ry = ch - 3;
				if (c.isRoom) {
					int w = this.rrange(5, rx);
					int h = this.rrange(4, ry);
					if ((w | 1) < rx) {
						w |= 1;
					}
					if ((h | 1) < ry) {
						h |= 1;
					}
					if (w > (3 * h) / 2) {
						w = (3 * h) / 2;
					}
					if (h > (3 * w) / 2) {
						h = (3 * w) / 2;
					}
					int sx = minx + this.rint(rx - w);
					int sy = miny + this.rint(ry - h);
					for (int i = sx; i < sx + w; i++) {
						for (int j = sy; j < sy + h; j++) {
							this.t[i][j] = FLOOR;
							this.room[i][j] = idx;
						}
					}
					c.sx = sx;
					c.sy = sy;
					c.ex = sx + w;
					c.ey = sy + h;
					c.index = idx++;
					boolean roll = this.rint(100) < 80;
					c.flagStructure = roll && this.budget[0] > 0;
				} else {
					int l = x == 0 ? 1 : 2;
					int r = x == this.gx - 1 ? 2 : 4;
					int top = y == 0 ? 1 : 2;
					int bottom = y == this.gy - 1 ? 2 : 4;
					int ax = this.rrange(minx + l, minx + rx - r);
					int ay = this.rrange(miny + top, miny + ry - bottom);
					this.t[ax][ay] = FLOOR;
					this.room[ax][ay] = ANCHOR;
					c.sx = ax;
					c.sy = ay;
					c.ex = ax + 1;
					c.ey = ay + 1;
				}
			}
		}
		// the linking walk
		int x = this.rint(this.gx);
		int y = this.rint(this.gy);
		int d = this.rint(4);
		for (int i = 0; i < this.p.connectivity(); i++) {
			int test = this.rint(8);
			int fresh = this.rint(4);
			if (test < 4) {
				d = fresh;
			}
			while (!this.inside(x, y, d)) {
				d = (d + 1) % 4;
			}
			if (this.next(x, y, d).valid) {
				this.link(x, y, d);
				x += DX[d][0];
				y += DX[d][1];
			}
		}
		if (!this.p.allowDeadEnds()) {
			boolean changed = true;
			while (changed) {
				changed = false;
				for (int cx = 0; cx < this.gx; cx++) {
					for (int cy = 0; cy < this.gy; cy++) {
						GridCell c = this.cells[cx][cy];
						if (c.isRoom || c.links() != 1) {
							continue;
						}
						int dir = this.rint(4);
						for (int tries = 0; tries < 8; tries++) {
							if (this.inside(cx, cy, dir) && !c.link[dir] && this.next(cx, cy, dir).valid) {
								this.link(cx, cy, dir);
								changed = true;
								break;
							}
							dir = (dir + 1) % 4;
						}
					}
				}
			}
		}
		// corridors for every link
		for (int cx = 0; cx < this.gx; cx++) {
			for (int cy = 0; cy < this.gy; cy++) {
				GridCell c = this.cells[cx][cy];
				if (!c.valid) {
					continue;
				}
				int[] p0 = this.endpoint(c);
				for (int dir = 0; dir < 4; dir++) {
					if (c.link[dir] && this.inside(cx, cy, dir)) {
						this.carveLink(cx, cy, dir, p0);
					}
				}
			}
		}
		// merges (5%)
		int[] mergeDirs = {2, 1, 0, 3};
		for (int cx = 0; cx < this.gx; cx++) {
			for (int cy = 0; cy < this.gy; cy++) {
				GridCell c = this.cells[cx][cy];
				int r = this.rint(100);
				if (r < 5 && c.valid && c.connected && c.isRoom && !c.merged) {
					int dir = mergeDirs[this.rint(4)];
					if (this.inside(cx, cy, dir)) {
						GridCell nb = this.next(cx, cy, dir);
						if (nb.valid && nb.connected && nb.isRoom && !nb.merged) {
							int bx0 = Math.min(c.sx, nb.sx);
							int by0 = Math.min(c.sy, nb.sy);
							int bx1 = Math.max(c.ex, nb.ex);
							int by1 = Math.max(c.ey, nb.ey);
							for (int i = bx0; i < bx1; i++) {
								for (int j = by0; j < by1; j++) {
									this.t[i][j] = FLOOR;
									this.room[i][j] = c.index;
								}
							}
							c.sx = nb.sx = bx0;
							c.sy = nb.sy = by0;
							c.ex = nb.ex = bx1;
							c.ey = nb.ey = by1;
							c.merged = true;
							nb.merged = true;
							nb.absorbed = true;
						}
					}
				}
			}
		}
		// repair cells the walk missed
		for (int cx = 0; cx < this.gx; cx++) {
			for (int cy = 0; cy < this.gy; cy++) {
				GridCell c = this.cells[cx][cy];
				if (!c.valid || c.absorbed || c.connected) {
					continue;
				}
				if (c.isRoom) {
					int[] p0 = this.endpoint(c);
					for (int dir : new int[] {1, 3, 2, 0}) {
						if (this.inside(cx, cy, dir)) {
							GridCell nb = this.next(cx, cy, dir);
							if (nb.valid && !nb.merged && nb.connected) {
								this.link(cx, cy, dir);
								this.carveLink(cx, cy, dir, p0);
								break;
							}
						}
					}
				} else {
					this.t[c.sx][c.sy] = WALL;
					this.room[c.sx][c.sy] = CORR;
				}
			}
		}
		for (int cx = 0; cx < this.gx; cx++) {
			for (int cy = 0; cy < this.gy; cy++) {
				GridCell c = this.cells[cx][cy];
				if (c.valid && c.isRoom && !c.connected && !c.absorbed) {
					for (int i = c.sx; i < c.ex; i++) {
						for (int j = c.sy; j < c.ey; j++) {
							this.t[i][j] = WALL;
							this.room[i][j] = CORR;
						}
					}
					c.valid = false;
				}
			}
		}
		List<GridCell> roomList = new ArrayList<>();
		for (int cx = 0; cx < this.gx; cx++) {
			for (int cy = 0; cy < this.gy; cy++) {
				GridCell c = this.cells[cx][cy];
				if (c.valid && c.isRoom && c.connected && !c.absorbed) {
					roomList.add(c);
				}
			}
		}
		// a shop (a Mart stall here), else a Monster House
		GridCell shopRoom = null;
		if (this.p.shopChance() > 0 && this.rint(100) < this.p.shopChance()) {
			List<GridCell> order = new ArrayList<>();
			for (GridCell c : roomList) {
				if (!c.flagStructure && c.ex - c.sx >= 5 && c.ey - c.sy >= 4 && !c.merged) {
					order.add(c);
				}
			}
			if (!order.isEmpty()) {
				shopRoom = order.get(this.rint(order.size()));
				shopRoom.shop = true;
				for (int i = shopRoom.sx; i < shopRoom.ex; i++) {
					for (int j = shopRoom.sy; j < shopRoom.ey; j++) {
						this.special[i][j] = true;
						if (shopRoom.sx < i && i < shopRoom.ex - 1 && shopRoom.sy < j && j < shopRoom.ey - 1) {
							this.shop[i][j] = true;
						}
					}
				}
			}
		}
		if (shopRoom == null && this.p.houseChance() > 0 && this.rint(100) < this.p.houseChance()) {
			List<GridCell> eligible = new ArrayList<>();
			for (GridCell c : roomList) {
				if (!c.flagStructure) {
					eligible.add(c);
				}
			}
			if (!eligible.isEmpty()) {
				GridCell h = eligible.get(this.rint(eligible.size()));
				h.house = true;
				for (int i = h.sx; i < h.ex; i++) {
					for (int j = h.sy; j < h.ey; j++) {
						this.house[i][j] = true;
					}
				}
			}
		}
		this.extraHallways();
		this.structures();
	}

	/** A room's random inner point, or an anchor's tile. */
	private int[] endpoint(GridCell c) {
		if (c.isRoom) {
			return new int[] {this.rrange(c.sx + 1, c.ex - 1), this.rrange(c.sy + 1, c.ey - 1)};
		}
		return new int[] {c.sx, c.sy};
	}

	/** A corridor with one jog at {@code jog}, from a to b; stops where it meets floor. */
	private void hallway(int x0, int y0, int x1, int y1, boolean vertical, int jog) {
		List<int[]> pts = new ArrayList<>();
		if (vertical) {
			int step = jog >= y0 ? 1 : -1;
			for (int yy = y0; yy != jog + step; yy += step) {
				pts.add(new int[] {x0, yy});
			}
			step = x1 >= x0 ? 1 : -1;
			for (int xx = x0; xx != x1 + step; xx += step) {
				pts.add(new int[] {xx, jog});
			}
			step = y1 >= jog ? 1 : -1;
			for (int yy = jog; yy != y1 + step; yy += step) {
				pts.add(new int[] {x1, yy});
			}
		} else {
			int step = jog >= x0 ? 1 : -1;
			for (int xx = x0; xx != jog + step; xx += step) {
				pts.add(new int[] {xx, y0});
			}
			step = y1 >= y0 ? 1 : -1;
			for (int yy = y0; yy != y1 + step; yy += step) {
				pts.add(new int[] {jog, yy});
			}
			step = x1 >= jog ? 1 : -1;
			for (int xx = jog; xx != x1 + step; xx += step) {
				pts.add(new int[] {xx, y1});
			}
		}
		int[] last = null;
		boolean first = true;
		for (int[] pt : pts) {
			if (last != null && last[0] == pt[0] && last[1] == pt[1]) {
				continue; // the corners repeat a point: skip it, or the corridor stops on its own floor
			}
			last = pt;
			if (first) {
				first = false;
				continue;
			}
			int tx = pt[0];
			int ty = pt[1];
			if (!(0 < tx && tx < WIDTH - 1 && 0 < ty && ty < HEIGHT - 1)) {
				return;
			}
			if (this.t[tx][ty] == FLOOR) {
				return;
			}
			this.t[tx][ty] = FLOOR;
			this.room[tx][ty] = CORR;
		}
	}

	private void carveLink(int x, int y, int d, int[] p0) {
		GridCell c = this.cells[x][y];
		GridCell nb = this.next(x, y, d);
		int[] q = this.endpoint(nb);
		int px = p0[0];
		int py = p0[1];
		switch (d) {
			case 1 -> this.hallway(px, c.sy, q[0], nb.ey - 1, true, this.ys[y]);
			case 3 -> this.hallway(px, c.ey - 1, q[0], nb.sy, true, this.ys[y + 1] - 1);
			case 2 -> this.hallway(c.sx, py, nb.ex - 1, q[1], false, this.xs[x]);
			default -> this.hallway(c.ex - 1, py, nb.sx, q[1], false, this.xs[x + 1] - 1);
		}
		c.link[d] = false;
		nb.link[(d + 2) % 4] = false;
		c.connected = true;
		nb.connected = true;
	}

	private void extraHallways() {
		for (int it = 0; it < this.p.extraHallways(); it++) {
			int cx = this.rint(this.gx);
			int cy = this.rint(this.gy);
			GridCell c = this.cells[cx][cy];
			if (!(c.valid && c.isRoom && c.connected)) {
				continue;
			}
			int x = this.rrange(c.sx, c.ex);
			int y = this.rrange(c.sy, c.ey);
			int d = this.rint(4);
			if (d == 0 && cy == this.gy - 1) {
				d = 1;
			}
			if (d == 1 && cx == this.gx - 1) {
				d = 2;
			}
			if (d == 2 && cy == 0) {
				d = 3;
			}
			if (d == 3 && cx == 0) {
				d = 0;
			}
			int ddx = SDIR[d][0];
			int ddy = SDIR[d][1];
			while (this.open(x, y)) {
				x += ddx;
				y += ddy;
			}
			if (!(2 <= x && x < WIDTH - 2 && 2 <= y && y < HEIGHT - 2)) {
				continue;
			}
			if (this.t[x][y] == WATER) {
				continue;
			}
			if (this.open(x - ddy, y + ddx) || this.open(x + ddy, y - ddx)) {
				continue;
			}
			int stride = 3 + this.rint(3);
			while (true) {
				if (x <= 1 || y <= 1 || x >= WIDTH - 1 || y >= HEIGHT - 1 || this.open(x, y) || this.imp[x][y]) {
					break;
				}
				boolean square = false; // would this tile finish a 2×2 square of floor?
				for (int ox = -1; ox <= 0; ox++) {
					for (int oy = -1; oy <= 0; oy++) {
						boolean all = true;
						for (int a = 0; a <= 1; a++) {
							for (int b = 0; b <= 1; b++) {
								int i = x + ox + a;
								int j = y + oy + b;
								if (!(this.open(i, j) || (i == x && j == y))) {
									all = false;
								}
							}
						}
						if (all) {
							square = true;
						}
					}
				}
				if (!square) {
					this.t[x][y] = FLOOR;
					this.room[x][y] = CORR;
				}
				if (this.open(x - ddy, y + ddx) || this.open(x + ddy, y - ddx)) {
					break;
				}
				stride--;
				if (stride == 0) {
					stride = 3 + this.rint(3);
					d = (d + (this.rint(100) < 50 ? 1 : 3)) % 4;
					ddx = SDIR[d][0];
					ddy = SDIR[d][1];
				}
				x += ddx;
				y += ddy;
			}
		}
	}

	/** Secondary structures in flagged rooms: a pool, a checkerboard, a lake, an island, a divider. */
	private void structures() {
		for (int y = 0; y < this.gy; y++) {
			for (int x = 0; x < this.gx; x++) {
				GridCell c = this.cells[x][y];
				if (!(c.valid && c.isRoom && c.flagStructure && !c.house && !c.merged && !c.shop)) {
					continue;
				}
				int kind = this.rint(6);
				if (this.budget[0] <= 0 || kind == 0) {
					continue;
				}
				int sx = c.sx;
				int sy = c.sy;
				int ex = c.ex;
				int ey = c.ey;
				int w = ex - sx;
				int h = ey - sy;
				int mx = (sx + ex) / 2;
				int my = (sy + ey) / 2;
				boolean built = false;
				if (kind == 1) {
					this.t[mx][my] = WATER;
					if (w >= 5 && h >= 5) {
						this.t[mx + 1][my] = WATER;
						this.t[mx - 1][my] = WATER;
						this.t[mx][my + 1] = WATER;
						this.t[mx][my - 1] = WATER;
					}
					built = true;
				} else if (kind == 2 && w % 2 == 1 && h % 2 == 1) {
					for (int i = 0; i < 64; i++) {
						int a = this.rrange(sx, ex);
						int b = this.rrange(sy, ey);
						if ((a - sx + b - sy) % 2 == 1) {
							this.t[a][b] = WATER;
						}
					}
					built = true;
				} else if (kind == 3 && w >= 5 && h >= 5) {
					int xa = this.rrange(sx + 2, ex - 3);
					int xb = this.rrange(sx + 2, ex - 3);
					int ya = this.rrange(sy + 2, ey - 3);
					int yb = this.rrange(sy + 2, ey - 3);
					for (int i = Math.min(xa, xb); i <= Math.max(xa, xb); i++) {
						for (int j = Math.min(ya, yb); j <= Math.max(ya, yb); j++) {
							this.t[i][j] = WATER;
						}
					}
					built = true;
				} else if (kind == 4 && w >= 6 && h >= 6) {
					for (int i = mx - 2; i < mx + 2; i++) {
						for (int j = my - 2; j < my + 2; j++) {
							if (mx - 1 <= i && i <= mx && my - 1 <= j && j <= my) {
								this.special[i][j] = true;
							} else {
								this.t[i][j] = WATER;
								this.cut[i][j] = true;
							}
						}
					}
					built = true;
				} else if (kind == 5) {
					built = true; // a divider costs the budget even when refused (next to a corridor)
					List<int[]> line = new ArrayList<>();
					if (this.rint(2) == 0) {
						for (int j = sy; j < ey; j++) {
							line.add(new int[] {mx, j});
						}
					} else {
						for (int i = sx; i < ex; i++) {
							line.add(new int[] {i, my});
						}
					}
					boolean near = false;
					for (int[] q : line) {
						for (int[] o : new int[][] {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
							int i = q[0] + o[0];
							int j = q[1] + o[1];
							if (this.room[i][j] == CORR && this.t[i][j] == FLOOR) {
								near = true;
							}
						}
					}
					if (!near) {
						for (int[] q : line) {
							this.t[q[0]][q[1]] = WATER;
						}
					}
				}
				if (built) {
					this.budget[0]--;
				}
			}
		}
	}

	private void wet(int x, int y) {
		if (x >= 0 && x < WIDTH && y >= 0 && y < HEIGHT && this.t[x][y] == WALL && !this.imp[x][y]) {
			this.t[x][y] = WATER;
		}
	}

	private boolean isWater(int x, int y) {
		return x >= 0 && x < WIDTH && y >= 0 && y < HEIGHT && this.t[x][y] == WATER;
	}

	/** Rivers from the top or bottom edge, swelling into a lake somewhere along them; then lakes. Never on floor. */
	private void riversAndLakes() {
		int rivers = RIVERS[this.rint(8)];
		for (int rv = 0; rv < rivers; rv++) {
			boolean up = this.rint(2) == 0;
			int y = up ? HEIGHT - 1 : 0;
			int dy = up ? -1 : 1;
			int lake = 10 + this.rint(50);
			int x = this.rrange(2, WIDTH - 2);
			boolean vertical = true;
			int dx = 0;
			boolean alive = true;
			while (alive) {
				int seg = 2 + this.rint(6);
				for (int s = 0; s < seg; s++) {
					if (this.isWater(x, y)) {
						alive = false;
						break;
					}
					this.wet(x, y);
					if (vertical) {
						y += dy;
					} else {
						x += dx;
					}
					if (y < 0 || y >= HEIGHT) {
						alive = false;
						break;
					}
					lake--;
					if (lake == 0) {
						for (int i = 0; i < 64; i++) {
							int tx = x + this.rrange(-3, 4);
							int ty = y + this.rrange(-3, 4);
							if (2 <= tx && tx < WIDTH - 2 && 2 <= ty && ty < HEIGHT - 2 && this.waterAround(tx, ty) > 0) {
								this.wet(tx, ty);
							}
						}
						for (int tx = x - 3; tx <= x + 3; tx++) {
							for (int ty = y - 3; ty <= y + 3; ty++) {
								if (2 <= tx && tx < WIDTH - 2 && 2 <= ty && ty < HEIGHT - 2 && this.waterAround(tx, ty) >= 4) {
									this.wet(tx, ty);
								}
							}
						}
					}
				}
				if (!alive) {
					break;
				}
				if (vertical) {
					vertical = false;
					dx = this.rint(2) == 0 ? 1 : -1;
				} else {
					vertical = true;
				}
			}
		}
		for (int lk = 0; lk < this.p.lakes(); lk++) {
			int sx = this.rrange(1, WIDTH - 1);
			int sy = this.rrange(1, HEIGHT - 1);
			boolean[][] mask = new boolean[10][10];
			for (int i = 0; i < 10; i++) {
				for (int j = 0; j < 10; j++) {
					mask[i][j] = i == 0 || i == 9 || j == 0 || j == 9;
				}
			}
			for (int i = 0; i < 80; i++) {
				int a = this.rrange(1, 9);
				int b = this.rrange(1, 9);
				if (mask[a + 1][b] || mask[a - 1][b] || mask[a][b + 1] || mask[a][b - 1]) {
					mask[a][b] = true;
				}
			}
			for (int i = 0; i < 10; i++) {
				for (int j = 0; j < 10; j++) {
					if (!mask[i][j]) {
						this.wet(sx - 5 + i, sy - 5 + j);
					}
				}
			}
		}
		for (int x = 0; x < WIDTH; x++) {
			for (int y = 0; y < HEIGHT; y++) {
				if (this.t[x][y] == WATER && (this.shop[x][y] || this.house[x][y])) {
					this.t[x][y] = FLOOR;
				}
				if (this.t[x][y] == WATER && (x <= 1 || x >= WIDTH - 1 || y <= 1 || y >= HEIGHT - 1)) {
					this.t[x][y] = WALL;
				}
			}
		}
	}

	private int waterAround(int x, int y) {
		int n = 0;
		for (int a = -1; a <= 1; a++) {
			for (int b = -1; b <= 1; b++) {
				if ((a != 0 || b != 0) && this.isWater(x + a, y + b)) {
					n++;
				}
			}
		}
		return n;
	}

	/** Anchors become corridor; room tiles next to a corridor are junctions (nothing spawns there). */
	private void finalizeJunctions() {
		for (int x = 0; x < WIDTH; x++) {
			for (int y = 0; y < HEIGHT; y++) {
				if (this.t[x][y] == FLOOR && this.room[x][y] == ANCHOR) {
					this.room[x][y] = CORR;
				}
			}
		}
		for (int x = 0; x < WIDTH; x++) {
			for (int y = 0; y < HEIGHT; y++) {
				if (this.t[x][y] == FLOOR && this.room[x][y] == CORR) {
					for (int[] d : DX) {
						int i = x + d[0];
						int j = y + d[1];
						if (i >= 0 && i < WIDTH && j >= 0 && j < HEIGHT && this.room[i][j] != CORR && this.t[i][j] != WALL) {
							this.junction[i][j] = true;
						}
					}
				}
			}
		}
	}

	/** Shuffles the tiles, then takes {@code k} in a row from a random start. */
	private List<Cell> run(List<Cell> tiles, int k) {
		int n = tiles.size();
		if (n == 0 || k <= 0) {
			return new ArrayList<>();
		}
		for (int i = 0; i < 2 * n; i++) {
			int a = this.rint(n);
			int b = this.rint(n);
			Cell swap = tiles.get(a);
			tiles.set(a, tiles.get(b));
			tiles.set(b, swap);
		}
		int i = this.rint(n);
		List<Cell> out = new ArrayList<>();
		for (int m = 0; m < Math.min(k, n); m++) {
			out.add(tiles.get(i));
			i = (i + 1) % n;
		}
		return out;
	}

	private List<Cell> roomTiles(BiPredicate<Integer, Integer> extra) {
		List<Cell> out = new ArrayList<>();
		for (int x = 0; x < WIDTH; x++) {
			for (int y = 0; y < HEIGHT; y++) {
				if (this.t[x][y] == FLOOR && this.room[x][y] != CORR && !this.junction[x][y] && extra.test(x, y)) {
					out.add(new Cell(x, y));
				}
			}
		}
		return out;
	}

	private void spawns() {
		List<Cell> st = this.roomTiles((x, y) -> !this.shop[x][y] && !this.special[x][y]);
		if (st.isEmpty()) {
			return;
		}
		this.stairs = st.get(this.rint(st.size()));
		int k = this.p.items() == 0 ? 0 : Math.max(1, this.rrange(this.p.items() - 2, this.p.items() + 2));
		this.items = this.run(this.roomTiles((x, y) -> !this.shop[x][y] && !this.house[x][y]), k);
		List<Cell> houseTiles = new ArrayList<>();
		for (int x = 0; x < WIDTH; x++) {
			for (int y = 0; y < HEIGHT; y++) {
				if (this.house[x][y] && !this.junction[x][y] && this.t[x][y] == FLOOR) {
					houseTiles.add(new Cell(x, y));
				}
			}
		}
		int houseCount = 0;
		for (int x = 0; x < WIDTH; x++) {
			for (int y = 0; y < HEIGHT; y++) {
				if (this.house[x][y]) {
					houseCount++;
				}
			}
		}
		if (houseCount > 0) {
			int m = Math.min(7, Math.max(6, this.rrange(houseCount / 2, (8 * houseCount) / 10)));
			this.items.addAll(this.run(houseTiles, m));
		}
		Set<Cell> taken = new HashSet<>(this.items);
		int kt = Math.min(56, this.rrange(this.p.traps() / 2, this.p.traps()));
		this.traps = this.run(this.roomTiles((x, y) -> !this.shop[x][y] && !taken.contains(new Cell(x, y))), kt);
		taken.addAll(this.traps);
		Cell stairsCell = this.stairs;
		List<Cell> pl = this.roomTiles((x, y) -> !this.shop[x][y] && !taken.contains(new Cell(x, y))
				&& !new Cell(x, y).equals(stairsCell));
		this.player = pl.isEmpty() ? null : pl.get(this.rint(pl.size()));
		Cell playerCell = this.player;
		Set<Cell> itemSet = new HashSet<>(this.items);
		int ke = Math.max(1, this.rrange(this.p.enemies() / 2, this.p.enemies()));
		this.enemies = this.run(this.roomTiles((x, y) -> {
			Cell c = new Cell(x, y);
			return !this.shop[x][y] && !itemSet.contains(c) && !c.equals(stairsCell) && !c.equals(playerCell);
		}), ke);
	}

	/** The report's fallback: one big Monster House. */
	private void oneRoomHouse() {
		for (int x = 2; x < WIDTH - 2; x++) {
			for (int y = 2; y < HEIGHT - 2; y++) {
				this.t[x][y] = FLOOR;
				this.room[x][y] = 0;
				this.house[x][y] = true;
			}
		}
	}

	private boolean allFloorReachable(Cell from) {
		boolean[][] seen = new boolean[WIDTH][HEIGHT];
		Deque<Cell> todo = new ArrayDeque<>();
		seen[from.x()][from.y()] = true;
		todo.add(from);
		while (!todo.isEmpty()) {
			Cell c = todo.poll();
			for (int[] d : DX) {
				int x = c.x() + d[0];
				int y = c.y() + d[1];
				if (this.open(x, y) && !seen[x][y]) {
					seen[x][y] = true;
					todo.add(new Cell(x, y));
				}
			}
		}
		for (int x = 0; x < WIDTH; x++) {
			for (int y = 0; y < HEIGHT; y++) {
				if (this.t[x][y] == FLOOR && !seen[x][y]) {
					return false;
				}
			}
		}
		return true;
	}

	private PmdFloor result(long seed, boolean fallback, int layoutTries, int spawnTries) {
		PmdFloor.Tile[] tiles = new PmdFloor.Tile[WIDTH * HEIGHT];
		boolean[] houseFlags = new boolean[WIDTH * HEIGHT];
		boolean[] shopFlags = new boolean[WIDTH * HEIGHT];
		for (int x = 0; x < WIDTH; x++) {
			for (int y = 0; y < HEIGHT; y++) {
				tiles[y * WIDTH + x] = this.t[x][y];
				houseFlags[y * WIDTH + x] = this.house[x][y];
				shopFlags[y * WIDTH + x] = this.shop[x][y];
			}
		}
		return new PmdFloor(seed, WIDTH, HEIGHT, tiles, houseFlags, shopFlags, List.copyOf(this.items), List.copyOf(this.traps),
				List.copyOf(this.enemies), this.player, this.stairs, fallback, layoutTries, spawnTries);
	}
}
