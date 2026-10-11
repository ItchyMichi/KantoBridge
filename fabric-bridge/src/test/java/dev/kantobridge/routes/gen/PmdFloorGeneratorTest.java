package dev.kantobridge.routes.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Dungeon floors (docs/specs/WILD_ROUTES_PLAN.md §4.8): pinned by fingerprint, and every floor tile reachable. */
class PmdFloorGeneratorTest {
	@Test
	void aSeedAlwaysMakesTheSameFloor() {
		for (long seed : new long[] {0, 1, 0x123457, -9}) {
			assertEquals(PmdFloorGenerator.generate(PmdParams.standard(), seed).fingerprint(),
					PmdFloorGenerator.generate(PmdParams.standard(), seed).fingerprint());
		}
	}

	@Test
	void goldenFloors() {
		PmdFloor plain = PmdFloorGenerator.generate(PmdParams.standard(), 0x123457);
		PmdFloor house = PmdFloorGenerator.generate(PmdParams.standard().withRooms(0, 100, 0), 0x2B7);
		PmdFloor shop = PmdFloorGenerator.generate(PmdParams.standard().withRooms(100, 0, 10), 0x5F00D);
		System.out.println("[routes-gen] golden floors plain " + plain.fingerprint() + " house " + house.fingerprint()
				+ " shop " + shop.fingerprint());
		assertEquals(GOLDEN_PLAIN, plain.fingerprint());
		assertEquals(GOLDEN_HOUSE, house.fingerprint());
		assertEquals(GOLDEN_SHOP, shop.fingerprint());
	}

	private static final String GOLDEN_PLAIN = "97c3032c6817e421de622ffded0cadb460c7e41219ef3b00d56cc43b253c2601";
	private static final String GOLDEN_HOUSE = "d769a9ae048dda5f7af896957cbf565a6e8661f3e55e1daf2a111e4815fb61e0";
	private static final String GOLDEN_SHOP = "6997a46685024689e22993256b7d37ceb545898d66a3415ec7dfe4235e5e7edd";

	@Test
	void tenThousandFloorsCanBeWalked() {
		Queue<String> problems = new ConcurrentLinkedQueue<>();
		AtomicInteger fallbacks = new AtomicInteger();
		AtomicInteger houses = new AtomicInteger();
		AtomicInteger shops = new AtomicInteger();
		long t0 = System.nanoTime();
		IntStream.range(0, 10_000).parallel().forEach(i -> {
			PmdParams params = i % 5 == 0 ? PmdParams.standard().withTraps(6)
					: i % 7 == 0 ? PmdParams.standard().withRooms(8, 6, 10) : PmdParams.standard();
			PmdFloor f = PmdFloorGenerator.generate(params, i * 7919L + 1);
			if (f.fallback()) {
				fallbacks.incrementAndGet();
			}
			boolean anyHouse = false;
			boolean anyShop = false;
			for (int k = 0; k < f.house().length; k++) {
				anyHouse |= f.house()[k];
				anyShop |= f.shop()[k];
			}
			if (anyHouse) {
				houses.incrementAndGet();
			}
			if (anyShop) {
				shops.incrementAndGet();
			}
			for (String p : check(f)) {
				problems.add("floor " + i + ": " + p);
			}
		});
		long ms = (System.nanoTime() - t0) / 1_000_000;
		System.out.println("[routes-gen] 10000 floors in " + ms + " ms on " + Runtime.getRuntime().availableProcessors()
				+ " processors; fallbacks " + fallbacks + ", Monster Houses " + houses + ", shops " + shops);
		assertTrue(problems.isEmpty(), problems.size() + " problems, first: " + problems.stream().limit(5).toList());
	}

	@Test
	void speedOverOneThread() {
		long t0 = System.nanoTime();
		int open = 0;
		for (int i = 0; i < 400; i++) {
			PmdFloor f = PmdFloorGenerator.generate(PmdParams.standard(), i * 7919L + 1);
			for (PmdFloor.Tile t : f.tiles()) {
				if (t == PmdFloor.Tile.FLOOR) {
					open++;
				}
			}
		}
		System.out.printf("[routes-gen] 400 floors, %.2f ms each, one thread; %d open tiles on average%n",
				(System.nanoTime() - t0) / 1e6 / 400, open / 400);
	}

	static List<String> check(PmdFloor f) {
		List<String> out = new ArrayList<>();
		if (f.stairs() == null || f.player() == null) {
			out.add("no stairs or start");
			return out;
		}
		if (f.at(f.stairs()) != PmdFloor.Tile.FLOOR || f.at(f.player()) != PmdFloor.Tile.FLOOR) {
			out.add("stairs or start off the floor");
		}
		if (f.player().equals(f.stairs())) {
			out.add("you start on the stairs");
		}
		for (List<Cell> list : List.of(f.items(), f.traps(), f.enemies())) {
			for (Cell c : list) {
				if (f.at(c) != PmdFloor.Tile.FLOOR) {
					out.add("an object off the floor at " + c);
				}
			}
		}
		for (Cell c : f.items()) {
			if (f.inShop(c)) {
				out.add("an item ball in the shop");
			}
		}
		for (int x = 0; x < f.width(); x++) {
			for (int y = 0; y < f.height(); y++) {
				boolean ring = x <= 1 || x >= f.width() - 1 || y <= 1 || y >= f.height() - 1;
				if (ring && f.at(x, y) == PmdFloor.Tile.WATER) {
					out.add("water on the outer ring");
				}
				if ((x == 0 || y == 0 || x == f.width() - 1 || y == f.height() - 1) && f.at(x, y) != PmdFloor.Tile.WALL) {
					out.add("an open edge");
				}
			}
		}
		// every floor tile, four ways from the stairs
		boolean[][] seen = new boolean[f.width()][f.height()];
		Deque<Cell> todo = new ArrayDeque<>();
		seen[f.stairs().x()][f.stairs().y()] = true;
		todo.add(f.stairs());
		while (!todo.isEmpty()) {
			Cell c = todo.poll();
			for (Dir d : Dir.values()) {
				Cell n = c.step(d);
				if (n.x() >= 0 && n.y() >= 0 && n.x() < f.width() && n.y() < f.height() && !seen[n.x()][n.y()]
						&& f.at(n) == PmdFloor.Tile.FLOOR) {
					seen[n.x()][n.y()] = true;
					todo.add(n);
				}
			}
		}
		for (int x = 0; x < f.width(); x++) {
			for (int y = 0; y < f.height(); y++) {
				if (f.at(x, y) == PmdFloor.Tile.FLOOR && !seen[x][y]) {
					out.add("a floor tile cut off at " + x + "," + y);
					return out;
				}
			}
		}
		return out;
	}
}
