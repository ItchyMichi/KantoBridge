package dev.kantobridge.builder.shape;

import static dev.kantobridge.builder.shape.ShapeFixtures.interior;
import static dev.kantobridge.builder.shape.ShapeFixtures.surface;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.plan.HeightRules;
import dev.kantobridge.scene.Env;
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
	void anOutdoorMapUnderTheOpenSkyStandsAsOnTheSurface() {
		// 2026-10-23: the Battle Frontier's buildings stood 2 blocks tall, by the interior rule; an outdoor map placed apart
		// under the open sky now takes the surface's heights and room, a wood (drawn indoors) keeps the interior's.
		SceneMap sky = ShapeFixtures.openAir("SKY",
				"AAAA....",
				"BBBB..CC",
				"CCDD..EE",
				"CCDD....",
				"==..~~..",
				"==..~~..");
		assertTrue(sky.outdoors());
		int[] units = Structures.units(sky, Structures.solidTiles(sky));
		assertArrayEquals(Structures.classifiedHeights(TWO, Structures.units(TWO, Structures.solidTiles(TWO)), HeightRules.INTERIOR_WALL),
				Structures.classifiedHeights(sky, units, HeightRules.INTERIOR_WALL));
		assertEquals(ShapeHeights.SURFACE, ShapeHeights.headroom(sky));
		SceneMap wood = ShapeFixtures.map("WOOD", false, new Env(false, true, false, Env.DARK_NEVER), "AAAA", "AAAA", "....", "....");
		assertFalse(wood.outdoors());
		assertTrue(ShapeHeights.headroom(wood) < ShapeHeights.SURFACE);
		// Emerald's Petalburg Woods: a route by its map type (outdoor and open), marked a wood by the front end
		SceneMap route = ShapeFixtures.map("WOODS", false, new Env(true, true, false, Env.DARK_NEVER, false, true, -1), "AAAA", "AAAA", "....",
				"....");
		assertFalse(route.outdoors());
		assertTrue(ShapeHeights.headroom(route) < ShapeHeights.SURFACE);
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

	// ---- measured runs (kb-shapes/2) ----

	private static List<Structures.MeasuredRun> measured(SceneMap map) {
		return Structures.measured(map, Structures.solidTiles(map), HeightRules.MAX_UNIT, HeightRules.MIN_REPEAT_ROWS, HeightRules.INTERIOR_WALL);
	}

	private static Structures.MeasuredRun run(List<Structures.MeasuredRun> runs, int tx, int n) {
		return runs.stream().filter(r -> r.tx() == tx && r.n() == n).findFirst().orElseThrow(() -> new AssertionError("no run " + tx + "," + n));
	}

	@Test
	void aTreeLineTouchingABuildingStaysItsOwnLowRun() {
		SceneMap map = surface("TOUCH",
				"TTTTTTTT",
				"UUUUUUUU",
				"TTTTTTTT",
				"UUUUUUUU",
				"RRRR....",
				"SSSS....",
				"FFFF....",
				"GGGG....",
				"........",
				"........");
		List<Structures.MeasuredRun> runs = measured(map);
		Structures.MeasuredRun trees = run(runs, 0, 0);
		assertEquals(3, trees.f());
		assertEquals(true, trees.pureRepeat());
		assertEquals(2, trees.h());
		assertEquals(-1, trees.region(), "pure repeats take no part in a region");
		Structures.MeasuredRun house = run(runs, 0, 4);
		assertEquals(7, house.f());
		assertEquals(false, house.repeatRead());
		assertEquals(4, house.unit(), "four drawn rows");
		assertEquals(4, house.h());
		assertEquals(2, house.r(), "two roof rows: the top rows differ");
		assertEquals(2, house.wallH());
		assertEquals(3, house.blocks(), "two bands and the roof block");
		assertEquals(2, run(runs, 5, 0).h(), "beside the house the tree line is just as low");
	}

	@Test
	void underTheOpenSkyAFittedRunKeepsItsModelsWhereTheSurfaceRoofsIt() {
		// 2026-10-23: the National Park's fences (fitted cells, four drawn rows) rose 3 blocks as a roofed face under the
		// open sky; there they keep their models as under the interior rule, on the surface the face takes them over.
		String[] rows = {"RRRR....", "SSSS....", "FFFF....", "GGGG....", "........", "........"};
		boolean[] fitted = new boolean[8 * 6];
		for (int ty = 0; ty < 4; ty++) {
			for (int tx = 0; tx < 4; tx++) {
				fitted[ty * 8 + tx] = true;
			}
		}
		SceneMap surface = surface("FENCE", rows);
		Structures.MeasuredRun onSurface = run(Structures.measured(surface, Structures.solidTiles(surface), HeightRules.MAX_UNIT,
				HeightRules.MIN_REPEAT_ROWS, HeightRules.INTERIOR_WALL, true, null, fitted), 0, 0);
		assertFalse(onSurface.models());
		assertEquals(2, onSurface.r());
		SceneMap sky = ShapeFixtures.openAir("FENCE", rows);
		Structures.MeasuredRun underSky = run(Structures.measured(sky, Structures.solidTiles(sky), HeightRules.MAX_UNIT,
				HeightRules.MIN_REPEAT_ROWS, HeightRules.INTERIOR_WALL, true, null, fitted), 0, 0);
		assertTrue(underSky.models());
		assertEquals(0, underSky.r());
	}

	@Test
	void aRimOverAUniformBodyIsAFlatMound() {
		SceneMap map = surface("MOUND",
				"RRRR",
				"CCCC",
				"CCCC",
				"CCCC",
				"CCCC",
				"CCCC",
				"....",
				"....");
		Structures.MeasuredRun mound = run(measured(map), 1, 0);
		assertEquals(5, mound.f(), "the rim and its body are one run");
		assertEquals(false, mound.pureRepeat());
		assertEquals(true, mound.repeatRead(), "its front repeats");
		assertEquals(2, mound.h());
		assertEquals(0, mound.r(), "a plain repeat never gets a roof");
	}

	@Test
	void aRepeatWearingATrimAdoptsItsRegionsHeight() {
		SceneMap map = surface("TRIM",
				"AAXX....",
				"BBYY....",
				"BBZZ....",
				"CCWW....",
				"........",
				"........");
		List<Structures.MeasuredRun> runs = measured(map);
		Structures.MeasuredRun trim = run(runs, 0, 0);
		Structures.MeasuredRun drawn = run(runs, 2, 0);
		assertEquals(true, trim.repeatRead(), "two equal rows over a different front row");
		assertEquals(2, trim.unit());
		assertEquals(false, drawn.repeatRead());
		assertEquals(4, drawn.unit());
		assertEquals(drawn.region(), trim.region());
		assertEquals(4, trim.h(), "the region's height");
		assertEquals(2, trim.r(), "raised by the vote: roofed like the rest");
		assertEquals(4, drawn.h());
	}

	@Test
	void aDoorFoldsIntoTheFacadeAtTheRegionsHeight() {
		SceneMap map = surface("DOOR",
				"RRRRRR..",
				"SSSSSS..",
				"FFFFFF..",
				"GGGGGG..",
				"GG++GG..",
				"GG++GG..",
				"........",
				"........");
		List<Structures.MeasuredRun> runs = measured(map);
		Structures.MeasuredRun door = run(runs, 2, 0);
		Structures.MeasuredRun wall = run(runs, 0, 0);
		assertEquals(3, door.f(), "the run stops at the door cell, which stays walkable");
		assertEquals(true, door.doorFold());
		assertEquals(5, door.origin(), "the fold starts at the door cell's southern row");
		assertEquals(5, wall.f());
		assertEquals(6, wall.unit());
		assertEquals(6, door.h(), "the region's height: the facade beside it");
		assertEquals(wall.h(), door.h());
		assertEquals(wall.r(), door.r());
	}

	@Test
	void interiorsKeepTheirWallHeight() {
		SceneMap inside = interior("ROOM",
				"RRRR",
				"SSSS",
				"FFFF",
				"GGGG",
				"....",
				"....");
		for (Structures.MeasuredRun run : measured(inside)) {
			assertEquals(HeightRules.INTERIOR_WALL, run.h());
			assertEquals(0, run.r());
		}
	}

	@Test
	void theSameMapGivesTheSameRuns() {
		SceneMap map = surface("DOOR",
				"RRRRRR..",
				"SSSSSS..",
				"FFFFFF..",
				"GGGGGG..",
				"GG++GG..",
				"GG++GG..",
				"........",
				"........");
		assertEquals(measured(map), measured(map));
	}
}
