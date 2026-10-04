package dev.kantobridge.builder.shape;

import static dev.kantobridge.builder.shape.ShapeFixtures.interior;
import static dev.kantobridge.builder.shape.ShapeFixtures.surface;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.kantobridge.builder.plan.HeightRules;
import dev.kantobridge.scene.SceneMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The structure analysis from tile ids alone (docs/specs/VOXEL_BLOCKS_PLAN.md §5.4, stage 3): solid tiles, runs per
 * tile column, 4-connected regions numbered in scan order, measured units and V2's classified heights.
 */
class StructuresTest {
	/** Two separate structures: a 4-row block on the west, a 2-row wall on the east, a counter and water. */
	static final SceneMap TWO = surface("TWO",
			"AAAA....",
			"BBBB..CC",
			"CCDD..EE",
			"CCDD....",
			"==..~~..",
			"==..~~..");

	@Test
	void solidTilesFollowTheirCellsKind() {
		boolean[] solid = Structures.solidTiles(TWO);
		int cols = TWO.artCols();
		assertEquals(8, cols);
		// The 2x2 cells on the west, and the east wall's cell (3, 0): its tile rows 0-1. The cell below it is ground (its
		// bottom-left tile is '.'), though its art row 2 draws E.
		for (int ty = 0; ty < 6; ty++) {
			for (int tx = 0; tx < 8; tx++) {
				boolean expected = (tx < 4 && ty < 4) || (tx >= 6 && ty < 2);
				assertEquals(expected, solid[ty * cols + tx], tx + "," + ty);
			}
		}
	}

	@Test
	void runsAreColumnsNorthToSouth() {
		boolean[] solid = Structures.solidTiles(TWO);
		List<Structures.Run> runs = Structures.runs(solid, TWO.artCols(), TWO.artRows());
		assertEquals(List.of(new Structures.Run(0, 0, 3), new Structures.Run(1, 0, 3), new Structures.Run(2, 0, 3), new Structures.Run(3, 0, 3),
				new Structures.Run(6, 0, 1), new Structures.Run(7, 0, 1)), runs);
		assertEquals(4, runs.get(0).length());
		int[] runOf = Structures.runOf(runs, TWO.artCols(), TWO.artRows());
		assertEquals(0, runOf[3 * 8]);
		assertEquals(5, runOf[8 + 7]);
		assertEquals(-1, runOf[4 * 8], "the counter isn't solid");
	}

	@Test
	void regionsAreNumberedInScanOrder() {
		boolean[] solid = Structures.solidTiles(TWO);
		int[] regions = Structures.regions(solid, 8, 6);
		assertEquals(0, regions[0], "the west block is met first");
		assertEquals(0, regions[3 * 8 + 3]);
		assertEquals(1, regions[6], "the east wall second");
		assertEquals(1, regions[8 + 7]);
		assertEquals(-1, regions[4], "ground");
		// The same structures elsewhere in the scan get the same numbers in their scan order.
		SceneMap swapped = surface("SWAPPED",
				"CC..AAAA",
				"EE..BBBB",
				"....CCDD",
				"....CCDD",
				"........",
				"........");
		int[] other = Structures.regions(Structures.solidTiles(swapped), 8, 6);
		assertEquals(0, other[0], "now the wall comes first");
		assertEquals(1, other[4]);
	}

	@Test
	void unitsAreTheMeasuredDrawingAndHeightsTodaysClasses() {
		boolean[] solid = Structures.solidTiles(TWO);
		int[] units = Structures.units(TWO, solid);
		assertArrayEquals(HeightRules.measure(TWO.tiles(), 8, 6, solid), units);
		assertEquals(4, units[0], "four different rows: a unit of 4");
		assertEquals(2, units[6], "two rows: a unit of 2");
		assertEquals(0, units[4 * 8], "not solid");
		int[] heights = Structures.classifiedHeights(TWO, units, HeightRules.INTERIOR_WALL);
		// Cells: 4 x 3.
		assertArrayEquals(new int[] {HeightRules.ROOF, HeightRules.ROOF, 0, HeightRules.WALL, HeightRules.ROOF, HeightRules.ROOF, 0, 0,
				HeightRules.COUNTER, 0, 0, 0}, heights);
		// Inside, every solid cell is the interior wall height, whatever it draws.
		SceneMap inside = interior("INSIDE",
				"AAAA",
				"BBBB",
				"CCDD",
				"CCDD");
		int[] insideHeights = Structures.classifiedHeights(inside, Structures.units(inside, Structures.solidTiles(inside)), 2);
		assertArrayEquals(new int[] {2, 2, 2, 2}, insideHeights);
	}

	@Test
	void aRepeatingTreeLineStaysLow() {
		// Two cells of alternating rows repeat with period 2 over 4 rows: a unit of 2 (HeightRules' repeat rule).
		SceneMap trees = surface("TREES",
				"TTTT",
				"UUUU",
				"TTTT",
				"UUUU");
		int[] units = Structures.units(trees, Structures.solidTiles(trees));
		assertEquals(2, units[0]);
		assertArrayEquals(new int[] {2, 2, 2, 2}, Structures.classifiedHeights(trees, units, 2));
	}
}
