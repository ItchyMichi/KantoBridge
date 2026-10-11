package dev.kantobridge.routes.gen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Generates a whole zone or route (docs/specs/WILD_ROUTES_PLAN.md §4.3, choice R5): areas joined edge to edge with no
 * fade in between. Each area is made with its entrance on its own bottom edge, then turned so the entrance faces the
 * previous area's exit and slid along the shared edge until the two 2-cell openings meet, like two Kanto maps at a
 * seam. An area that would overlap one already placed is made again; when that keeps failing, the whole attempt
 * starts over.
 *
 * <p>The first area always heads east, so the trailhead lies west of it. The route map's door A is west of its stand
 * x1 in the hidden game (§4.2), and the WorldIndex places route maps unturned, so A is the trailhead's west end: the
 * way back out. For the same reason a connecting route's last exit never faces west: its far gate's door B must lie
 * east of x2.
 */
public final class RouteGenerator {
	/** A gate plaza's depth and width in cells. */
	static final int GATE_DEPTH = 4;
	static final int GATE_WIDTH = 6;
	/** Scenery around the areas and gates, in cells. */
	static final int MARGIN = 6;
	private static final int ATTEMPTS = 200;
	private static final int AREA_TRIES = 8;
	private static final int NO_DUNGEON = -2;
	private static final int SIDE_AREA = -1;
	private static final int N = AreaGenerator.SIZE;

	private RouteGenerator() {
	}

	private record Rect(int x, int y, int w, int h) {
		boolean overlaps(Rect o) {
			return this.x < o.x + o.w && o.x < this.x + this.w && this.y < o.y + o.h && o.y < this.y + this.h;
		}

		/** One cell bigger on every side: overlapping it means touching. */
		Rect grown() {
			return new Rect(this.x - 1, this.y - 1, this.w + 2, this.h + 2);
		}
	}

	/** A gate's plaza and its door cell, and the area it belongs to (the only one it may touch). */
	private record GateBoxes(Rect plaza, Rect door, int ownArea) {
	}

	/** An area in place: turned {@code k} quarter turns, its square's north-west corner at {@code origin}. */
	private record Placed(Area area, int k, Cell origin, @Nullable Dir main, @Nullable Dir branch, int levelOffset,
			double progress, boolean side) {
		Cell world(Cell own) {
			Cell r = rot(own, this.k);
			return new Cell(this.origin.x() + r.x(), this.origin.y() + r.y());
		}
	}

	/** Makes the zone or route {@code spec} describes from {@code seed}. The same seed always makes the same layout. */
	public static RouteLayout generate(RouteSpec spec, long seed) {
		RouteRng rng = new RouteRng(seed);
		boolean connecting = spec.kind() == RouteSpec.Kind.CONNECTING;
		int areas = rng.between(spec.areasMin(), spec.areasMax());
		boolean wantSide = areas >= 2 && rng.chance(spec.sideAreaChance());
		int branchAt = wantSide ? rng.nextInt(areas - 1) : -1;
		boolean wantDungeon = rng.chance(spec.dungeonChance());
		int dungeonAt = !wantDungeon ? NO_DUNGEON : wantSide ? SIDE_AREA : rng.between(areas / 2, areas - 1);
		for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
			RouteLayout layout = attempt(rng, spec, seed, connecting, areas, branchAt, dungeonAt, attempt);
			if (layout != null) {
				return layout;
			}
		}
		throw new IllegalStateException("no layout after " + ATTEMPTS + " attempts (seed " + seed + ")");
	}

	private static @Nullable RouteLayout attempt(RouteRng rng, RouteSpec spec, long seed, boolean connecting, int areas,
			int branchAt, int dungeonAt, int retries) {
		List<Placed> placed = new ArrayList<>();
		List<Rect> rects = new ArrayList<>();
		int k = Dir.EAST.ordinal();
		for (int i = 0; i < areas; i++) {
			boolean last = i == areas - 1;
			List<Dir> sides = new ArrayList<>();
			Dir main = null;
			if (!last || connecting) {
				main = chooseMain(rng, k, last && connecting);
				sides.add(main);
			}
			Dir branch = null;
			if (i == branchAt) {
				List<Dir> rest = new ArrayList<>(List.of(Dir.NORTH, Dir.WEST, Dir.EAST));
				rest.removeAll(sides);
				branch = rng.pick(rest);
				sides.add(branch);
			}
			Area area = null;
			Cell origin = null;
			for (int t = 0; t < AREA_TRIES; t++) {
				Area candidate = AreaGenerator.make(rng, spec, sides, dungeonAt == i);
				if (candidate == null) {
					break;
				}
				Cell o = i == 0 ? new Cell(0, 0) : attach(placed.get(i - 1), placed.get(i - 1).main(), candidate, k);
				Rect r = new Rect(o.x(), o.y(), N, N);
				if (!overlapsAny(r, rects) && fits(rects, r, spec.bound() - 2 * GATE_DEPTH)) {
					area = candidate;
					origin = o;
					break;
				}
			}
			if (area == null) {
				return null;
			}
			int offset = Math.min(i * spec.levelStep(), spec.levelCap());
			double progress = areas > 1 ? (double) i / (areas - 1) : 0;
			placed.add(new Placed(area, k, origin, main, branch, offset, progress, false));
			rects.add(new Rect(origin.x(), origin.y(), N, N));
			if (main != null) {
				k = main.turn(k).ordinal();
			}
		}
		if (branchAt >= 0) {
			Placed p = placed.get(branchAt);
			Dir d = p.branch().turn(p.k());
			Placed side = null;
			for (int t = 0; t < AREA_TRIES && side == null; t++) {
				Area candidate = AreaGenerator.make(rng, spec, List.of(), dungeonAt == SIDE_AREA);
				if (candidate == null) {
					continue;
				}
				Cell o = attach(p, p.branch(), candidate, d.ordinal());
				Rect r = new Rect(o.x(), o.y(), N, N);
				if (!overlapsAny(r, rects) && fits(rects, r, spec.bound() - 2 * GATE_DEPTH)) {
					side = new Placed(candidate, d.ordinal(), o, null, null, p.levelOffset(), p.progress(), true);
					rects.add(r);
				}
			}
			if (side == null) {
				return null;
			}
			placed.add(side);
		}
		// the trailhead, west of the first area's entrance
		Placed first = placed.get(0);
		Dir in = Dir.SOUTH.turn(first.k());
		if (in != Dir.WEST) {
			throw new IllegalStateException("the first area must head east");
		}
		List<Cell> startCells = first.area().start.stream().map(first::world).sorted().toList();
		Rect trailPlaza = gateRect(startCells, in);
		Cell x1 = new Cell(trailPlaza.x(), startCells.get(0).y());
		Cell a = x1.step(Dir.WEST);
		List<GateBoxes> gates = new ArrayList<>(List.of(new GateBoxes(trailPlaza, new Rect(a.x(), a.y(), 1, 1), 0)));
		// a connecting route's far gate, past the last exit
		Rect farPlaza = null;
		Cell x2 = null;
		Cell b = null;
		if (connecting) {
			Placed last = placed.get(areas - 1);
			Dir out = last.main().turn(last.k());
			List<Cell> farCells = last.area().openings.get(last.main()).stream().map(last::world).sorted().toList();
			farPlaza = gateRect(farCells, out);
			int row = switch (out) {
				case EAST -> farCells.get(0).y();
				case NORTH -> farPlaza.y();
				case SOUTH -> farPlaza.y() + farPlaza.h() - 1;
				case WEST -> throw new IllegalStateException("a connecting route's last exit faced west");
			};
			x2 = new Cell(farPlaza.x() + farPlaza.w() - 1, row);
			b = x2.step(Dir.EAST);
			gates.add(new GateBoxes(farPlaza, new Rect(b.x(), b.y(), 1, 1), areas - 1));
		}
		// a gate touches only its own area, never another area or the other gate: no shortcuts, no leaks
		for (int g = 0; g < gates.size(); g++) {
			GateBoxes gate = gates.get(g);
			for (Rect box : List.of(gate.plaza(), gate.door())) {
				if (!fits(rects, box, spec.bound())) {
					return null;
				}
				for (int i = 0; i < rects.size(); i++) {
					if (box.overlaps(rects.get(i)) || (i != gate.ownArea() && box.grown().overlaps(rects.get(i)))) {
						return null;
					}
				}
				for (int h = g + 1; h < gates.size(); h++) {
					if (box.grown().overlaps(gates.get(h).plaza()) || box.grown().overlaps(gates.get(h).door())) {
						return null;
					}
				}
			}
		}
		List<Rect> gateBoxes = new ArrayList<>();
		gates.forEach(gate -> {
			gateBoxes.add(gate.plaza());
			gateBoxes.add(gate.door());
		});
		// one grid for everything, with a margin of scenery
		List<Rect> boxes = new ArrayList<>(rects);
		boxes.addAll(gateBoxes);
		int x0 = Integer.MAX_VALUE;
		int y0 = Integer.MAX_VALUE;
		int xEnd = Integer.MIN_VALUE;
		int yEnd = Integer.MIN_VALUE;
		for (Rect r : boxes) {
			x0 = Math.min(x0, r.x());
			y0 = Math.min(y0, r.y());
			xEnd = Math.max(xEnd, r.x() + r.w());
			yEnd = Math.max(yEnd, r.y() + r.h());
		}
		x0 -= MARGIN;
		y0 -= MARGIN;
		int width = xEnd + MARGIN - x0;
		int height = yEnd + MARGIN - y0;
		CellRole[] cells = new CellRole[width * height];
		Arrays.fill(cells, CellRole.SCENERY);
		List<Cell> items = new ArrayList<>();
		List<RouteLayout.Trainer> trainers = new ArrayList<>();
		List<RouteLayout.AreaPlacement> placements = new ArrayList<>();
		Cell dungeon = null;
		int deadEnds = 0;
		int junctions = 0;
		for (int ai = 0; ai < placed.size(); ai++) {
			Placed p = placed.get(ai);
			for (int y = 0; y < N; y++) {
				for (int x = 0; x < N; x++) {
					Cell w = p.world(new Cell(x, y));
					cells[(w.y() - y0) * width + (w.x() - x0)] = p.area().cells[y][x];
				}
			}
			for (Cell item : p.area().items) {
				items.add(shift(p.world(item), x0, y0));
			}
			for (Area.AreaTrainer t : p.area().trainers) {
				Cell at = shift(p.world(t.cell()), x0, y0);
				trainers.add(new RouteLayout.Trainer(at, t.facing().turn(p.k()), t.sight(), ai));
			}
			if (p.area().dungeon != null) {
				dungeon = shift(p.world(p.area().dungeon), x0, y0);
			}
			deadEnds += p.area().deadEnds + (p.area().dungeon != null ? 1 : 0);
			junctions += p.area().junctions;
			placements.add(new RouteLayout.AreaPlacement(p.origin().x() - x0, p.origin().y() - y0, p.k(),
					p.levelOffset(), p.progress(), p.side()));
		}
		RouteLayout.Gate trailhead = gate(trailPlaza, shift(a, x0, y0), shift(x1, x0, y0), x0, y0, cells, width);
		RouteLayout.Gate farGate = farPlaza == null ? null
				: gate(farPlaza, shift(b, x0, y0), shift(x2, x0, y0), x0, y0, cells, width);
		// check the whole thing once more: every item and the dungeon next to a reachable cell, the far gate reachable
		boolean[] blocked = new boolean[cells.length];
		items.forEach(c -> blocked[c.y() * width + c.x()] = true);
		trainers.forEach(t -> blocked[t.cell().y() * width + t.cell().x()] = true);
		int[] dist = walk(cells, width, height, trailhead.stand(), blocked);
		List<Cell> targets = new ArrayList<>(items);
		if (dungeon != null) {
			targets.add(dungeon);
		}
		for (Cell t : targets) {
			boolean ok = false;
			for (Dir d : Dir.values()) {
				Cell n = t.step(d);
				if (n.x() >= 0 && n.y() >= 0 && n.x() < width && n.y() < height && dist[n.y() * width + n.x()] >= 0) {
					ok = true;
				}
			}
			if (!ok) {
				return null;
			}
		}
		int through;
		if (farGate != null) {
			through = dist[farGate.stand().y() * width + farGate.stand().x()];
			if (through < 0) {
				return null;
			}
		} else {
			through = Arrays.stream(dist).max().orElse(0);
		}
		int walkable = 0;
		for (CellRole role : cells) {
			if (role.walkable()) {
				walkable++;
			}
		}
		return new RouteLayout(seed, spec.kind(), spec.theme().id(), width, height, cells, List.copyOf(items),
				List.copyOf(trainers), dungeon, trailhead, farGate, List.copyOf(placements),
				new RouteLayout.Stats(through, walkable, deadEnds, junctions, retries));
	}

	/** The main exit's side, top : left : right as 2 : 1 : 1; never west for a connecting route's last area. */
	private static Dir chooseMain(RouteRng rng, int k, boolean notWest) {
		List<Dir> options = new ArrayList<>(List.of(Dir.NORTH, Dir.NORTH, Dir.WEST, Dir.EAST));
		if (notWest) {
			options.removeIf(side -> side.turn(k) == Dir.WEST);
		}
		return rng.pick(options);
	}

	/** Where {@code next}'s square goes so that its entrance meets {@code prev}'s opening on {@code prevSide}. */
	private static Cell attach(Placed prev, Dir prevSide, Area next, int kNext) {
		Dir d = prevSide.turn(prev.k());
		List<Cell> exit = prev.area().openings.get(prevSide).stream().map(prev::world).sorted().toList();
		List<Cell> entrance = next.start.stream().map(c -> rot(c, kNext)).sorted().toList();
		return new Cell(exit.get(0).x() + d.dx - entrance.get(0).x(), exit.get(0).y() + d.dy - entrance.get(0).y());
	}

	/** An area's own cell after {@code k} quarter turns clockwise, inside the same square. */
	static Cell rot(Cell c, int k) {
		int x = c.x();
		int y = c.y();
		for (int i = 0; i < Math.floorMod(k, 4); i++) {
			int nx = N - 1 - y;
			y = x;
			x = nx;
		}
		return new Cell(x, y);
	}

	/** A gate plaza outside a 2-cell opening, reaching out along {@code d}. */
	private static Rect gateRect(List<Cell> opening, Dir d) {
		Cell first = opening.get(0);
		if (d.dy != 0) {
			int top = d == Dir.SOUTH ? first.y() + 1 : first.y() - GATE_DEPTH;
			return new Rect(first.x() - (GATE_WIDTH - 2) / 2, top, GATE_WIDTH, GATE_DEPTH);
		}
		int left = d == Dir.EAST ? first.x() + 1 : first.x() - GATE_DEPTH;
		return new Rect(left, first.y() - (GATE_WIDTH - 2) / 2, GATE_DEPTH, GATE_WIDTH);
	}

	/**
	 * Paints a gate's plaza and door as path and returns it in the layout's coordinates. Where they meet the scenery
	 * they get a ring of wall, so the maze stays closed, and the cell under the stand is wall too: the route map is two
	 * cells high, but only its top row (door and stand) is walkable, in the hidden game as well (kb_routes.closedMove),
	 * so the two players can never part on its lower row.
	 */
	private static RouteLayout.Gate gate(Rect plaza, Cell door, Cell stand, int x0, int y0, CellRole[] cells,
			int width) {
		int gx = plaza.x() - x0;
		int gy = plaza.y() - y0;
		for (int y = gy; y < gy + plaza.h(); y++) {
			for (int x = gx; x < gx + plaza.w(); x++) {
				cells[y * width + x] = CellRole.PATH;
			}
		}
		cells[door.y() * width + door.x()] = CellRole.PATH;
		cells[(stand.y() + 1) * width + stand.x()] = CellRole.WALL;
		int left = Math.min(gx, door.x()) - 1;
		int right = Math.max(gx + plaza.w() - 1, door.x()) + 1;
		int top = Math.min(gy, door.y()) - 1;
		int bottom = Math.max(gy + plaza.h() - 1, door.y()) + 1;
		for (int y = top; y <= bottom; y++) {
			for (int x = left; x <= right; x++) {
				if (cells[y * width + x] == CellRole.SCENERY && touchesWalkable(cells, width, x, y)) {
					cells[y * width + x] = CellRole.WALL;
				}
			}
		}
		return new RouteLayout.Gate(gx, gy, plaza.w(), plaza.h(), door, stand);
	}

	/** Whether any of the eight cells around (x, y) is walkable. */
	private static boolean touchesWalkable(CellRole[] cells, int width, int x, int y) {
		int height = cells.length / width;
		for (int dy = -1; dy <= 1; dy++) {
			for (int dx = -1; dx <= 1; dx++) {
				int nx = x + dx;
				int ny = y + dy;
				if ((dx != 0 || dy != 0) && nx >= 0 && nx < width && ny >= 0 && ny < height && cells[ny * width + nx].walkable()) {
					return true;
				}
			}
		}
		return false;
	}

	private static Cell shift(Cell c, int x0, int y0) {
		return new Cell(c.x() - x0, c.y() - y0);
	}

	private static boolean overlapsAny(Rect r, List<Rect> rects) {
		for (Rect o : rects) {
			if (r.overlaps(o)) {
				return true;
			}
		}
		return false;
	}

	/** True when {@code rects} plus {@code r} span at most {@code bound} cells each way. */
	private static boolean fits(List<Rect> rects, Rect r, int bound) {
		int x0 = r.x();
		int y0 = r.y();
		int x1 = r.x() + r.w();
		int y1 = r.y() + r.h();
		for (Rect o : rects) {
			x0 = Math.min(x0, o.x());
			y0 = Math.min(y0, o.y());
			x1 = Math.max(x1, o.x() + o.w());
			y1 = Math.max(y1, o.y() + o.h());
		}
		return x1 - x0 <= bound && y1 - y0 <= bound;
	}

	/** Steps from {@code from} over walkable, unblocked cells; -1 where unreachable. */
	static int[] walk(CellRole[] cells, int width, int height, Cell from, boolean[] blocked) {
		int[] dist = new int[cells.length];
		Arrays.fill(dist, -1);
		dist[from.y() * width + from.x()] = 0;
		Deque<Cell> todo = new ArrayDeque<>();
		todo.add(from);
		while (!todo.isEmpty()) {
			Cell p = todo.poll();
			for (Dir d : Dir.values()) {
				int x = p.x() + d.dx;
				int y = p.y() + d.dy;
				if (x < 0 || y < 0 || x >= width || y >= height) {
					continue;
				}
				int i = y * width + x;
				if (dist[i] < 0 && cells[i].walkable() && !blocked[i]) {
					dist[i] = dist[p.y() * width + p.x()] + 1;
					todo.add(new Cell(x, y));
				}
			}
		}
		return dist;
	}
}
