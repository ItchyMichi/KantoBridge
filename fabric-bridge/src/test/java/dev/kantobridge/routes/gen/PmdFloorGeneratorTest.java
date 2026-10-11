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

	private static final String GOLDEN_PLAIN = "7ba0853c7f7bf3ddfc7c02876cc6b5224224a05c9096fb7a07b849946448ce0f";
	private static final String GOLDEN_HOUSE = "9b12ef85946c0091146f5f8f662ba9353bd153a4c5ee81771084f208e87d33d0";
	private static final String GOLDEN_SHOP = "67938ae4b4e42ad184ea079e86b142995e7617e3f3db88e73dcd11917a88907f";

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
	void plainFloorsPassTheirFirstCheck() {
		// tunnels used to reach row 30, which the edge wipe clears: now and then that stranded a piece and forced a rebuild
		for (int i = 0; i < 2_000; i++) {
			assertEquals(1, PmdFloorGenerator.generate(PmdParams.standard(), i * 7919L + 1).spawnTries(), "floor " + i);
		}
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
		if (f.items().contains(f.stairs()) || f.traps().contains(f.stairs())) {
			out.add("an item or a trap on the stairs");
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
