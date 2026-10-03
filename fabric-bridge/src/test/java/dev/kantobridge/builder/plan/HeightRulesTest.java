package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The measured height rule: repeating art is low (2), unrepeated runs are as tall as they are drawn, up to 6 rows. */
class HeightRulesTest {
	private static int[] units(int... seq) {
		return HeightRules.measureRun(seq, seq.length);
	}

	@Test
	void repeatingArtIsWallHeight() {
		// A tree line: two tile rows per cell, repeating.
		assertArrayEquals(new int[] {2, 2, 2, 2, 2, 2}, units(64, 80, 64, 80, 64, 80));
		// Uniform art repeats with period 1.
		assertArrayEquals(new int[] {2, 2, 2, 2}, units(7, 7, 7, 7));
	}

	@Test
	void unrepeatedRunsAreAsTallAsDrawnUpToSixRows() {
		assertArrayEquals(new int[] {2, 2}, units(1, 2), "a one-cell fence or sign");
		assertArrayEquals(new int[] {4, 4, 4, 4}, units(1, 2, 3, 4), "a two-cell facade");
		assertArrayEquals(new int[] {6, 6, 6, 6, 6, 6, 6, 6}, units(1, 2, 3, 4, 5, 6, 7, 8), "capped at 6 rows (48 px)");
	}

	@Test
	void aTreeLineTouchingARoofStaysLow() {
		// Trees (4 repeating rows) directly above a roof and facade (6 unrepeated rows).
		assertArrayEquals(new int[] {2, 2, 2, 2, 6, 6, 6, 6, 6, 6}, units(64, 80, 64, 80, 10, 11, 12, 13, 14, 15));
		// And below it.
		assertArrayEquals(new int[] {4, 4, 4, 4, 2, 2, 2, 2}, units(10, 11, 12, 13, 64, 80, 64, 80));
	}

	@Test
	void classesRoundToTheMvpHeights() {
		assertEquals(HeightRules.WALL, HeightRules.classify(1));
		assertEquals(HeightRules.WALL, HeightRules.classify(2));
		assertEquals(HeightRules.ROOF, HeightRules.classify(3));
		assertEquals(HeightRules.ROOF, HeightRules.classify(6));
	}

	@Test
	void measureWorksPerTileColumn() {
		// 2 columns × 6 rows; column 0 is a facade, column 1 a tree line; row 5 is not solid.
		int[] tiles = {
				1, 64,
				2, 80,
				3, 64,
				4, 80,
				5, 64,
				9, 9,
		};
		boolean[] solid = new boolean[12];
		for (int i = 0; i < 10; i++) {
			solid[i] = true;
		}
		int[] units = HeightRules.measure(tiles, 2, 6, solid);
		assertArrayEquals(new int[] {5, 2, 5, 2, 5, 2, 5, 2, 5, 2, 0, 0}, units);
	}
}
