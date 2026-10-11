package dev.kantobridge.routes.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The generators' random numbers: fixed by their seed forever, unbiased in range. */
class RouteRngTest {
	@Test
	void aSeedAlwaysGivesTheSameNumbers() {
		RouteRng a = new RouteRng(42);
		RouteRng b = new RouteRng(42);
		for (int i = 0; i < 1000; i++) {
			assertEquals(a.nextLong(), b.nextLong());
		}
		assertNotEquals(new RouteRng(42).nextLong(), new RouteRng(43).nextLong());
	}

	@Test
	void theFirstNumbersNeverChange() {
		// Worlds keep their zones for life: these pin the generator itself, whatever the JVM.
		RouteRng rng = new RouteRng(0);
		assertEquals(GOLDEN_0, rng.nextLong());
		assertEquals(GOLDEN_1, rng.nextLong());
		assertEquals(GOLDEN_2, rng.nextLong());
		assertEquals(GOLDEN_SEED, RouteSeeds.forRoute(1234L, "viridian_forest_zone"));
	}

	private static final long GOLDEN_0 = -7355399402456485196L;
	private static final long GOLDEN_1 = -4652746763540216534L;
	private static final long GOLDEN_2 = 1900383378846508768L;
	private static final long GOLDEN_SEED = -9041397266197384936L;

	@Test
	void nextIntIsInRangeAndEven() {
		RouteRng rng = new RouteRng(7);
		int[] counts = new int[6];
		for (int i = 0; i < 60_000; i++) {
			counts[rng.nextInt(6)]++;
		}
		for (int c : counts) {
			assertTrue(c > 9_500 && c < 10_500, "count " + c);
		}
		assertThrows(IllegalArgumentException.class, () -> rng.nextInt(0));
	}

	@Test
	void betweenIncludesBothEnds() {
		RouteRng rng = new RouteRng(9);
		boolean lo = false;
		boolean hi = false;
		for (int i = 0; i < 1000; i++) {
			int v = rng.between(-2, 2);
			assertTrue(v >= -2 && v <= 2);
			lo |= v == -2;
			hi |= v == 2;
		}
		assertTrue(lo && hi);
		double d = rng.uniform(1.6, 4.8);
		assertTrue(d >= 1.6 && d < 4.8);
	}

	@Test
	void routeSeedsDependOnTheWorldAndTheId() {
		assertEquals(RouteSeeds.forRoute(1, "a"), RouteSeeds.forRoute(1, "a"));
		assertNotEquals(RouteSeeds.forRoute(1, "a"), RouteSeeds.forRoute(2, "a"));
		assertNotEquals(RouteSeeds.forRoute(1, "a"), RouteSeeds.forRoute(1, "b"));
		assertNotEquals(RouteSeeds.forVisit(5, 1), RouteSeeds.forVisit(5, 2));
	}
}
