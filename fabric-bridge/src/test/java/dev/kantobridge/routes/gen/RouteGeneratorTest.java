package dev.kantobridge.routes.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Whole zones and routes (docs/specs/WILD_ROUTES_PLAN.md §4.3): pinned by fingerprint, and over 10,000 seeds the
 * openings meet at every seam, nothing overlaps, everything can be reached and nothing blocks the way.
 */
class RouteGeneratorTest {
	private static final int N = AreaGenerator.SIZE;

	/** The specs the 10,000-seed check cycles through: free-roam and connecting, with and without a side area. */
	private static RouteSpec specFor(int seed) {
		RouteSpec base = seed % 4 == 3 ? RouteSpec.freeRoam(ForestTheme.INSTANCE) : RouteSpec.connecting(ForestTheme.INSTANCE);
		return base.withAreas(3 + seed % 2, 3 + seed % 2).withChances(seed % 3 == 0 ? 1 : 0, 0.6);
	}

	@Test
	void aSeedAlwaysMakesTheSameLayout() {
		RouteSpec spec = RouteSpec.freeRoam(ForestTheme.INSTANCE);
		for (long seed : new long[] {1, 99, -5, Long.MAX_VALUE}) {
			assertEquals(RouteGenerator.generate(spec, seed).fingerprint(), RouteGenerator.generate(spec, seed).fingerprint());
		}
	}

	@Test
	void goldenLayouts() {
		RouteLayout zone = RouteGenerator.generate(RouteSpec.freeRoam(ForestTheme.INSTANCE), 1);
		RouteLayout route = RouteGenerator.generate(RouteSpec.connecting(ForestTheme.INSTANCE), 2);
		RouteLayout branched = RouteGenerator.generate(RouteSpec.freeRoam(ForestTheme.INSTANCE).withChances(1, 1), 3);
		System.out.println("[routes-gen] golden zone " + zone.fingerprint() + " route " + route.fingerprint() + " branched "
				+ branched.fingerprint());
		assertEquals(GOLDEN_ZONE, zone.fingerprint());
		assertEquals(GOLDEN_ROUTE, route.fingerprint());
		assertEquals(GOLDEN_BRANCHED, branched.fingerprint());
		assertNull(zone.farGate());
		assertNotNull(route.farGate());
		assertTrue(branched.areas().stream().anyMatch(RouteLayout.AreaPlacement::side));
		assertNotNull(branched.dungeon());
	}

	private static final String GOLDEN_ZONE = "9287126d8ec5f36a8a069a816b455f4a8dfa67a97e2127285b20f853795fcaf3";
	// MG5 changed the connecting layout (the far gate's house-sized plaza, levels by progress): regenerate this fingerprint
	// from the "[routes-gen] golden ... route <hash>" line of a test run and paste it here (the free-roam goldens are unchanged).
	// MG5: the far gate is a 7x5 gate house and connecting levels follow the layout's progress (re-stamped from the pod run)
	private static final String GOLDEN_ROUTE = "f0c876c57e5a346b72403710b3c388ca0a46ae3b46349521f56805b438ca8af6";
	private static final String GOLDEN_BRANCHED = "b01aed167adb910e12ae1a3572109cec2626ea931d55fdf4dcf2fdac2559928a";

	@Test
	void tenThousandSeedsKeepEveryRule() {
		Queue<String> problems = new ConcurrentLinkedQueue<>();
		long t0 = System.nanoTime();
		IntStream.range(0, 10_000).parallel().forEach(seed -> {
			RouteSpec spec = specFor(seed);
			try {
				RouteLayout layout = RouteGenerator.generate(spec, seed);
				for (String p : check(layout, spec)) {
					problems.add("seed " + seed + ": " + p);
				}
			} catch (RuntimeException e) {
				problems.add("seed " + seed + ": " + e);
			}
		});
		long ms = (System.nanoTime() - t0) / 1_000_000;
		System.out.println("[routes-gen] 10000 layouts generated and checked in " + ms + " ms on "
				+ Runtime.getRuntime().availableProcessors() + " processors");
		assertTrue(problems.isEmpty(), problems.size() + " problems, first: " + problems.stream().limit(5).toList());
	}

	@Test
	void speedAndNumbersOverTheFirst300Seeds() {
		List<int[]> rows = new ArrayList<>();
		long t0 = System.nanoTime();
		for (int seed = 0; seed < 300; seed++) {
			RouteLayout l = RouteGenerator.generate(specFor(seed), seed);
			rows.add(new int[] {l.areas().size(), l.stats().through(), l.stats().deadEnds(), l.stats().junctions(),
					l.items().size(), l.trainers().size(), l.dungeon() == null ? 0 : 1, l.stats().walkable(),
					l.stats().retries(), l.width(), l.height()});
		}
		double each = (System.nanoTime() - t0) / 1e6 / 300;
		String[] names = {"areas", "cells start to far gate", "dead ends", "junction blocks", "items", "trainers",
				"dungeons", "walkable cells", "retries", "width cells", "height cells"};
		StringBuilder out = new StringBuilder(String.format("[routes-gen] 300 layouts, %.2f ms each, one thread%n", each));
		for (int c = 0; c < names.length; c++) {
			int[] col = new int[rows.size()];
			for (int r = 0; r < rows.size(); r++) {
				col[r] = rows.get(r)[c];
			}
			Arrays.sort(col);
			out.append(String.format("  %s: min %d median %d max %d%n", names[c], col[0], col[col.length / 2],
					col[col.length - 1]));
		}
		System.out.print(out);
	}

	/** Every rule a layout must keep; an empty list when it keeps them all. */
	static List<String> check(RouteLayout l, RouteSpec spec) {
		List<String> out = new ArrayList<>();
		List<RouteLayout.AreaPlacement> areas = l.areas();
		// the bound and the gates
		int x0 = Integer.MAX_VALUE;
		int y0 = Integer.MAX_VALUE;
		int x1 = Integer.MIN_VALUE;
		int y1 = Integer.MIN_VALUE;
		for (RouteLayout.AreaPlacement a : areas) {
			x0 = Math.min(x0, a.x());
			y0 = Math.min(y0, a.y());
			x1 = Math.max(x1, a.x() + N);
			y1 = Math.max(y1, a.y() + N);
		}
		List<RouteLayout.Gate> gates = new ArrayList<>(List.of(l.trailhead()));
		if (l.farGate() != null) {
			gates.add(l.farGate());
		}
		for (RouteLayout.Gate g : gates) {
			x0 = Math.min(Math.min(x0, g.x()), g.door().x());
			y0 = Math.min(Math.min(y0, g.y()), g.door().y());
			x1 = Math.max(Math.max(x1, g.x() + g.width()), g.door().x() + 1);
			y1 = Math.max(Math.max(y1, g.y() + g.height()), g.door().y() + 1);
			Cell underStand = g.stand().step(Dir.SOUTH);
			for (int y = g.y(); y < g.y() + g.height(); y++) {
				for (int x = g.x(); x < g.x() + g.width(); x++) {
					if (l.at(x, y) != CellRole.PATH && !underStand.equals(new Cell(x, y))) {
						out.add("a gate cell isn't path");
					}
				}
			}
			// the route map's lower row (under the door and the stand) is closed, as in the hidden game
			if (l.at(underStand) != CellRole.WALL || l.at(g.door().step(Dir.SOUTH)) != CellRole.WALL) {
				out.add("the route map's lower row at " + g.door() + " isn't wall");
			}
		}
		// the maze is closed: no walkable cell beside the scenery or on the layout's edge
		for (int y = 0; y < l.height(); y++) {
			for (int x = 0; x < l.width(); x++) {
				if (!l.at(x, y).walkable()) {
					continue;
				}
				for (Dir d : Dir.values()) {
					Cell n = new Cell(x, y).step(d);
					if (!l.inside(n) || l.at(n) == CellRole.SCENERY) {
						out.add("the maze opens onto the scenery at " + x + "," + y);
					}
				}
			}
		}
		if (x1 - x0 > spec.bound() || y1 - y0 > spec.bound()) {
			out.add("outside the bound: " + (x1 - x0) + "x" + (y1 - y0));
		}
		if ((spec.kind() == RouteSpec.Kind.CONNECTING) != (l.farGate() != null)) {
			out.add("far gate doesn't match the kind");
		}
		out.addAll(doorRules(l, l.trailhead(), Dir.WEST));
		int main = (int) areas.stream().filter(a -> !a.side()).count();
		out.addAll(plazaRules(l, l.trailhead(), areas.get(0)));
		if (l.farGate() != null) {
			out.addAll(doorRules(l, l.farGate(), Dir.EAST));
			out.addAll(plazaRules(l, l.farGate(), areas.get(main - 1)));
		}
		// no overlaps; openings meet at every link and nowhere else
		int sideLinks = 0;
		for (int i = 0; i < areas.size(); i++) {
			for (int j = i + 1; j < areas.size(); j++) {
				RouteLayout.AreaPlacement a = areas.get(i);
				RouteLayout.AreaPlacement b = areas.get(j);
				if (a.x() < b.x() + N && b.x() < a.x() + N && a.y() < b.y() + N && b.y() < a.y() + N) {
					out.add("areas " + i + " and " + j + " overlap");
					continue;
				}
				int pairs = seamPairs(l, a, b);
				boolean consecutive = j == i + 1 && !a.side() && !b.side();
				if (consecutive && pairs != 2) {
					out.add("areas " + i + " and " + j + " meet in " + pairs + " cells, not 2");
				} else if (!consecutive && pairs != 0) {
					if (b.side() && pairs == 2) {
						sideLinks++;
					} else {
						out.add("areas " + i + " and " + j + " leak through " + pairs + " cells");
					}
				}
			}
		}
		if (areas.size() > main && sideLinks != 1) {
			out.add("the side area has " + sideLinks + " links");
		}
		// levels: a step per area in a zone; toward the pass along a connecting route (MG5)
		for (int i = 0; i < main; i++) {
			int expected = spec.kind() == RouteSpec.Kind.CONNECTING ? RouteGenerator.levelOffset(areas.get(i).progress(), spec.levelCap())
					: Math.min(i * spec.levelStep(), spec.levelCap());
			if (areas.get(i).levelOffset() != expected) {
				out.add("area " + i + " level offset " + areas.get(i).levelOffset());
			}
		}
		if (l.farGate() != null) {
			out.addAll(houseRules(l, areas.get(main - 1)));
		}
		// everything reachable from the trailhead, with balls and trainers in the way
		Set<Cell> blocked = new HashSet<>(l.items());
		l.trainers().forEach(t -> blocked.add(t.cell()));
		int[][] dist = walk(l, l.trailhead().stand(), blocked);
		List<Cell> targets = new ArrayList<>(l.items());
		if (l.dungeon() != null) {
			targets.add(l.dungeon());
			if (l.at(l.dungeon()) != CellRole.DUNGEON) {
				out.add("the dungeon cell isn't a dungeon");
			}
		}
		for (Cell t : targets) {
			boolean ok = false;
			for (Dir d : Dir.values()) {
				Cell n = t.step(d);
				if (l.inside(n) && dist[n.y()][n.x()] >= 0) {
					ok = true;
				}
			}
			if (!ok) {
				out.add("cut off: " + t);
			}
		}
		if (l.farGate() != null && dist[l.farGate().stand().y()][l.farGate().stand().x()] < 0) {
			out.add("the far gate can't be reached");
		}
		for (int i = 0; i < areas.size(); i++) {
			RouteLayout.AreaPlacement a = areas.get(i);
			boolean any = false;
			for (int y = a.y(); y < a.y() + N && !any; y++) {
				for (int x = a.x(); x < a.x() + N && !any; x++) {
					any = dist[y][x] >= 0;
				}
			}
			if (!any) {
				out.add("area " + i + " can't be reached");
			}
		}
		// trainers: a clear line of sight, apart in their area
		for (RouteLayout.Trainer t : l.trainers()) {
			if (t.sight() < AreaGenerator.MIN_SIGHT || t.sight() > AreaGenerator.MAX_SIGHT) {
				out.add("sight " + t.sight());
			}
			for (int n = 1; n <= t.sight(); n++) {
				Cell c = t.cell().offset(t.facing().dx * n, t.facing().dy * n);
				if (!l.inside(c) || !l.at(c).walkable() || blocked.contains(c)) {
					out.add("a blocked line of sight at " + t.cell());
					break;
				}
			}
			for (RouteLayout.Trainer o : l.trainers()) {
				if (o != t && o.area() == t.area() && o.cell().manhattan(t.cell()) < AreaGenerator.TRAINER_SPACING) {
					out.add("trainers too close at " + t.cell());
				}
			}
		}
		if (l.stats().through() < N) {
			out.add("too short a way through: " + l.stats().through());
		}
		return out;
	}

	/**
	 * MG5: the far gate is a gate house: its plaza (the inside) is HOUSE_DEPTH along the way out and HOUSE_WIDTH across, and
	 * the ring around it (its walls) is closed everywhere but at its own area's opening and the door B.
	 */
	private static List<String> houseRules(RouteLayout l, RouteLayout.AreaPlacement own) {
		List<String> out = new ArrayList<>();
		RouteLayout.Gate g = l.farGate();
		boolean east = g.width() == RouteGenerator.HOUSE_DEPTH && g.height() == RouteGenerator.HOUSE_WIDTH;
		boolean northSouth = g.width() == RouteGenerator.HOUSE_WIDTH && g.height() == RouteGenerator.HOUSE_DEPTH;
		if (!east && !northSouth) {
			out.add("the gate house's inside is " + g.width() + "x" + g.height());
		}
		for (int y = g.y() - 1; y <= g.y() + g.height(); y++) {
			for (int x = g.x() - 1; x <= g.x() + g.width(); x++) {
				boolean ring = x == g.x() - 1 || x == g.x() + g.width() || y == g.y() - 1 || y == g.y() + g.height();
				boolean ownArea = x >= own.x() && x < own.x() + N && y >= own.y() && y < own.y() + N;
				Cell c = new Cell(x, y);
				if (!ring || ownArea || c.equals(g.door())) {
					continue;
				}
				if (!l.inside(c) || l.at(c) != CellRole.WALL) {
					out.add("the gate house's wall is open at " + c);
				}
			}
		}
		if (l.at(g.door()) != CellRole.PATH) {
			out.add("the pass isn't path");
		}
		return out;
	}

	/** A gate's door: one cell beside its stand, on {@code side}, with nothing else walkable around it. */
	private static List<String> doorRules(RouteLayout l, RouteLayout.Gate g, Dir side) {
		List<String> out = new ArrayList<>();
		if (!g.door().equals(g.stand().step(side))) {
			out.add("the door isn't " + side + " of its stand");
		}
		if (l.at(g.door()) != CellRole.PATH || l.at(g.stand()) != CellRole.PATH) {
			out.add("door or stand isn't path");
		}
		for (Dir d : Dir.values()) {
			if (d == side.opposite()) {
				continue;
			}
			Cell n = g.door().step(d);
			if (l.inside(n) && l.at(n).walkable()) {
				out.add("the door at " + g.door() + " opens " + d);
			}
		}
		List<Cell> pair = g.routeMapCells();
		if (pair.get(1).x() != pair.get(0).x() + 1 || pair.get(1).y() != pair.get(0).y()) {
			out.add("the route-map cells aren't side by side, west to east");
		}
		return out;
	}

	/** A gate plaza opens only into its own area (and onto its door): no shortcut into another area or gate. */
	private static List<String> plazaRules(RouteLayout l, RouteLayout.Gate g, RouteLayout.AreaPlacement own) {
		List<String> out = new ArrayList<>();
		boolean opensIntoOwn = false;
		for (int y = g.y() - 1; y <= g.y() + g.height(); y++) {
			for (int x = g.x() - 1; x <= g.x() + g.width(); x++) {
				boolean inside = x >= g.x() && x < g.x() + g.width() && y >= g.y() && y < g.y() + g.height();
				boolean corner = (x == g.x() - 1 || x == g.x() + g.width()) && (y == g.y() - 1 || y == g.y() + g.height());
				Cell c = new Cell(x, y);
				if (inside || corner || c.equals(g.door()) || !l.inside(c) || !l.at(c).walkable()) {
					continue;
				}
				if (x >= own.x() && x < own.x() + N && y >= own.y() && y < own.y() + N) {
					opensIntoOwn = true;
				} else {
					out.add("the gate at " + g.x() + "," + g.y() + " leaks at " + c);
				}
			}
		}
		if (!opensIntoOwn) {
			out.add("the gate at " + g.x() + "," + g.y() + " doesn't open into its area");
		}
		return out;
	}

	/** Walkable cell pairs straight across the edge where two area squares touch. */
	private static int seamPairs(RouteLayout l, RouteLayout.AreaPlacement a, RouteLayout.AreaPlacement b) {
		int pairs = 0;
		if (a.x() + N == b.x() || b.x() + N == a.x()) {
			int left = Math.max(a.x(), b.x()) - 1; // the column on the west side of the shared edge
			int from = Math.max(a.y(), b.y());
			int to = Math.min(a.y(), b.y()) + N;
			for (int y = from; y < to; y++) {
				if (l.at(left, y).walkable() && l.at(left + 1, y).walkable()) {
					pairs++;
				}
			}
		} else if (a.y() + N == b.y() || b.y() + N == a.y()) {
			int top = Math.max(a.y(), b.y()) - 1;
			int from = Math.max(a.x(), b.x());
			int to = Math.min(a.x(), b.x()) + N;
			for (int x = from; x < to; x++) {
				if (l.at(x, top).walkable() && l.at(x, top + 1).walkable()) {
					pairs++;
				}
			}
		}
		return pairs;
	}

	private static int[][] walk(RouteLayout l, Cell from, Set<Cell> blocked) {
		int[][] dist = new int[l.height()][l.width()];
		for (int[] row : dist) {
			Arrays.fill(row, -1);
		}
		dist[from.y()][from.x()] = 0;
		Deque<Cell> todo = new ArrayDeque<>();
		todo.add(from);
		while (!todo.isEmpty()) {
			Cell p = todo.poll();
			for (Dir d : Dir.values()) {
				Cell n = p.step(d);
				if (l.inside(n) && dist[n.y()][n.x()] < 0 && l.at(n).walkable() && !blocked.contains(n)) {
					dist[n.y()][n.x()] = dist[p.y()][p.x()] + 1;
					todo.add(n);
				}
			}
		}
		return dist;
	}
}
