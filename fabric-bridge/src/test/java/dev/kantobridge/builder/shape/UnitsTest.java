package dev.kantobridge.builder.shape;

import static dev.kantobridge.builder.shape.ShapeFixtures.surface;
import static dev.kantobridge.builder.shape.ShapeFixtures.tile;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.shape.geom.CellUnits;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.world.MapPlacement;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Stage 4 of the planner (docs/specs/VOXEL_BLOCKS_PLAN.md §5.4, Tier B): tufts on grass and flowers; cell units for
 * lone cells, cut trees and foliage (tree walls), never a house's repeating wall; their ground tile and context; story
 * blocks keep their feet level; the void fill's ring holds border trees within {@code hullRing} only. Pixel-free.
 */
class UnitsTest {
	private static ShapePlanner.MapShapes plan(SceneMap map) {
		return ShapePlanner.planMap(map, ShapeFixtures.TIER_B, 0, ShapeParams.v4());
	}

	private static int i(SceneMap map, int tx, int ty) {
		return ty * map.artCols() + tx;
	}

	@Test
	void grassAndFlowersStandAsTufts() {
		SceneMap map = surface("GRASS",
				",,..0.",
				",,..0.");
		ShapePlanner.MapShapes shapes = plan(map);
		assertArrayEquals(new ShapeKey[] {ShapeKey.tuft(0, tile(','))}, shapes.open()[i(map, 1, 1)]);
		assertArrayEquals(new ShapeKey[] {ShapeKey.tuft(0, tile('0'))}, shapes.open()[i(map, 4, 0)], "a flower");
		assertNull(shapes.open()[i(map, 2, 0)], "plain ground");
		assertEquals(1, shapes.decor()[i(map, 0, 0)], "on a decor carrier at feet level");
		assertEquals(0, shapes.decor()[i(map, 3, 0)]);
		assertEquals(0, shapes.blocks()[i(map, 0, 0)], "no column: the floor block stays as it was");
	}

	@Test
	void aLoneCellIsAUnitStandingOnItsGround() {
		SceneMap map = surface("LONE",
				"......",
				"......",
				"..AB..",
				"..CD..",
				"......",
				"......");
		ShapePlanner.MapShapes shapes = plan(map);
		int[] tiles = {tile('A'), tile('B'), tile('C'), tile('D')};
		ShapeKey[] column = shapes.open()[i(map, 3, 3)];
		assertEquals(2, column.length);
		assertEquals(ShapeKey.cell(0, tiles, tile('.'), 1, 1, 1).withVariant("g2"), column[1], "outdoors, walkable ground to the south");
		for (int ty = 2; ty < 4; ty++) {
			for (int tx = 2; tx < 4; tx++) {
				assertEquals(2, shapes.blocks()[i(map, tx, ty)]);
				assertEquals(0b11, shapes.shapeMask()[i(map, tx, ty)], "both blocks on shape_full");
				assertEquals(tile('.'), shapes.floor()[i(map, tx, ty)], "the floor wears the ground");
			}
		}
		assertEquals(1, shapes.units().size());
		assertEquals(-1, shapes.floor()[0], "elsewhere the floor keeps its tile");
	}

	@Test
	void aRepeatingWallStaysAColumnButATreeWallIsUnits() {
		SceneMap walls = surface("WALLS",
				"AAAA..",
				"AAAA..",
				"......",
				"......");
		assertEquals(List.of(), plan(walls).units(), "a house's plain wall repeats, but isn't foliage");
		SceneMap trees = surface("TREES",
				"TTTT..",
				"TTTT..",
				"......",
				"......");
		ShapePlanner.MapShapes shapes = plan(trees);
		assertEquals(2, shapes.units().size());
		assertEquals("g2t", shapes.units().get(0).key().variant());
		assertTrue(shapes.units().get(0).foliage());
	}

	@Test
	void theContextSaysSignsVerticalRepeatsAndIndoorSides() {
		SceneMap sign = surface("SIGN",
				"......",
				"......",
				"..!!..",
				"..!!..",
				"......",
				"......");
		assertEquals("g2s", plan(sign).units().get(0).key().variant());
		SceneMap column = surface("COLUMN",
				"..TT..",
				"..TT..",
				"..TT..",
				"..TT..",
				"......",
				"......");
		ShapePlanner.MapShapes trees = plan(column);
		assertEquals("g0rt", trees.units().get(0).key().variant(), "the upper tree: a tree north of the lower one, no ground south");
		assertEquals("g2rt", trees.units().get(1).key().variant());
		SceneMap room = ShapeFixtures.interior("ROOM",
				"......",
				"......",
				"..AB..",
				"..CD..",
				"......",
				"......");
		ShapePlanner.MapShapes indoors = ShapePlanner.planMap(room, ShapeFixtures.TIER_B, 0, ShapeParams.v4());
		assertEquals("gfi", indoors.units().get(0).key().variant(), "indoors every walkable side counts");
	}

	@Test
	void theGroundIsTheMostFrequentWalkableNeighbourTile() {
		SceneMap map = surface("GROUND",
				"..bb..",
				"..bb..",
				"ccAB..",
				"ccCD..",
				"..c,..",
				"..c,..");
		// Neighbours: north b ×4, west c ×4, east . ×4, south c ×2 and grass ×2 (left out): c has six.
		assertEquals(tile('c'), plan(map).units().get(0).key().number(4));
		SceneMap tie = surface("TIE",
				"..bb..",
				"..bb..",
				"ccAB..",
				"ccCD..",
				"......",
				"......");
		// b ×4, c ×4, . ×8: '.' wins outright; with only b and c tied, the lowest id (b) would.
		assertEquals(tile('.'), plan(tie).units().get(0).key().number(4));
		assertEquals(tile('b'), Units.ground(surface("TIE2",
				"..bb",
				"..bb",
				"ccAB",
				"ccCD"), 1, 1, java.util.Set.of(), java.util.Set.of(), -1));
	}

	@Test
	void aTallCellIsNoUnit() {
		// A six-row drawing over a foliage cell: the run stands six blocks high, so its foot is no unit.
		SceneMap map = surface("TALL",
				"..AA..",
				"..BB..",
				"..CC..",
				"..DD..",
				"..TT..",
				"..TT..");
		assertEquals(List.of(), plan(map).units());
	}

	@Test
	void storyBlocksKeepTheirFeetLevelAndSkippedTuftsAreCounted() {
		SceneMap map = surface("GRASS",
				",,,,",
				",,,,");
		BitSet story = new BitSet();
		story.set(0);
		MapPlacement placement = ShapeFixtures.surfaceAt(map, 0, 0);
		ShapePlan plan = ShapePlanner.plan(ShapeFixtures.scene(ShapeFixtures.TIER_B, map), List.of(placement), ShapeParams.v4(),
				new Occupancy(Map.of("GRASS", story), null));
		assertEquals(4, plan.decorSkipped(), "the story cell's four tiles");
		assertEquals(0, plan.map().lookup().shapeAt(0, ShapeFixtures.SURFACE_FEET, 0));
		assertTrue(plan.map().lookup().shapeAt(2, ShapeFixtures.SURFACE_FEET, 0) > 0, "the next cell keeps its tufts");
	}

	@Test
	void theVoidFillsRingHoldsBorderTreesWithinHullRingOnly() {
		SceneMap map = ShapeFixtures.withBorder(surface("TOWN",
				"....",
				"....",
				"....",
				"...."), 'T');
		// A 10 × 10-cell surface whose body is the town's 2 × 2 cells at cells (4, 4); the rest is void the town owns.
		MapPlacement placement = ShapeFixtures.surfaceAt(map, 8, 8);
		String[] owners = new String[100];
		Arrays.fill(owners, "TOWN");
		for (int cz = 4; cz < 6; cz++) {
			for (int cx = 4; cx < 6; cx++) {
				owners[cz * 10 + cx] = null;
			}
		}
		Occupancy taken = new Occupancy(Map.of(), new Occupancy.VoidFill(0, 0, 10, 10, 2, owners));
		ShapePlan plan = ShapePlanner.plan(ShapeFixtures.scene(ShapeFixtures.TIER_B, map), List.of(placement), ShapeParams.v4(), taken);
		assertEquals(6 * 6 - 2 * 2, plan.voidUnits().size(), "the two-cell ring around the body");
		for (Map.Entry<Integer, ShapePlan.VoidUnit> e : plan.voidUnits().entrySet()) {
			assertTrue(e.getValue().distance() <= 2);
			assertEquals("TOWN", e.getValue().owner());
			assertEquals(CellUnits.Context.parse("g0rt"), CellUnits.Context.parse(e.getValue().key().variant()));
		}
		assertNull(plan.voidUnit(0), "a corner three cells out keeps its void column");
		assertNotNull(plan.voidUnit(3 * 10 + 3));
		// The ring's blocks are in the shape map, two high from feet level; its keys came from the whole export.
		int id = plan.map().lookup().shapeAt(6, ShapeFixtures.SURFACE_FEET + 1, 6);
		assertEquals(ShapeKey.Kind.CELL, plan.index().key(id).kind());
		assertEquals(0, plan.map().lookup().shapeAt(6, ShapeFixtures.SURFACE_FEET + 2, 6));
		ShapePlan unplaced = ShapePlanner.plan(ShapeFixtures.scene(ShapeFixtures.TIER_B, map), List.of(placement), ShapeParams.v4());
		assertEquals(plan.index().hash(), unplaced.index().hash(), "the void fill changes positions, never keys");
	}
}
