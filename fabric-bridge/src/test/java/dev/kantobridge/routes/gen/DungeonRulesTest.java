package dev.kantobridge.routes.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Floors per visit by level (Q6, D5) and a level more every second floor (D6). */
class DungeonRulesTest {
	@Test
	void floorRangesRiseWithTheLevel() {
		int[][] table = {
				{1, 3, 5}, {19, 3, 5}, {20, 3, 5}, {29, 3, 5},
				{30, 4, 6}, {39, 4, 6}, {40, 5, 7}, {49, 5, 7},
				{50, 6, 8}, {59, 6, 8}, {60, 7, 9}, {100, 7, 9},
		};
		for (int[] row : table) {
			assertEquals(new DungeonRules.FloorRange(row[1], row[2]), DungeonRules.floorRange(row[0]), "level " + row[0]);
		}
	}

	@Test
	void floorCountsCoverTheWholeRange() {
		RouteRng rng = new RouteRng(11);
		boolean three = false;
		boolean five = false;
		for (int i = 0; i < 500; i++) {
			int n = DungeonRules.floorCount(rng, 8);
			assertTrue(n >= 3 && n <= 5);
			three |= n == 3;
			five |= n == 5;
		}
		assertTrue(three && five);
	}

	@Test
	void aLevelEverySecondFloor() {
		int[] expected = {0, 0, 1, 1, 2, 2, 3, 3, 4};
		for (int floor = 1; floor <= expected.length; floor++) {
			assertEquals(expected[floor - 1], DungeonRules.levelBonus(floor), "floor " + floor);
		}
		assertThrows(IllegalArgumentException.class, () -> DungeonRules.levelBonus(0));
	}
}
