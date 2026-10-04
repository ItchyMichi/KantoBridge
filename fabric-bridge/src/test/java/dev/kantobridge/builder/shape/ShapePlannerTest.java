package dev.kantobridge.builder.shape;

import static dev.kantobridge.builder.shape.ShapeFixtures.interior;
import static dev.kantobridge.builder.shape.ShapeFixtures.interiorAt;
import static dev.kantobridge.builder.shape.ShapeFixtures.scene;
import static dev.kantobridge.builder.shape.ShapeFixtures.surface;
import static dev.kantobridge.builder.shape.ShapeFixtures.surfaceAt;
import static dev.kantobridge.builder.shape.ShapeFixtures.tile;
import static dev.kantobridge.builder.shape.ShapeFixtures.withSite;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.scene.Scene;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.ShapeMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * V2's planner ({@code kb-shapes/1}, docs/specs/VOXEL_BLOCKS_PLAN.md §5.4) on fixtures: today's classified heights with
 * the fold rule, so a wall's south faces show the rows the 2D map draws; a cube that would show its own tile on every
 * face is "no shape"; keys from the whole scene, both swap states; positions of the placed maps from feet level; the
 * same hashes whatever order the maps come in (I7).
 */
class ShapePlannerTest {
	/** A two-row wall: A over B. */
	static final SceneMap WALL = surface("WALL",
			"........",
			"........",
			"AAAA....",
			"BBBB....",
			"........",
			"........");

	private static ShapeKey cube(char up, char north, char front) {
		return ShapeKey.cube(0, tile(up), tile(up), tile(north), tile(front), tile(front), tile(front));
	}

	private static ShapePlanner.MapShapes planned(SceneMap map) {
		return ShapePlanner.planMap(map, null, 0, ShapeParams.v2());
	}

	private static ShapeKey[] column(ShapePlanner.MapShapes shapes, SceneMap map, int tx, int ty) {
		return shapes.open()[ty * map.artCols() + tx];
	}

	@Test
	void theFoldRuleShowsTheDrawnRowsOnTheSouthFaces() {
		ShapePlanner.MapShapes shapes = planned(WALL);
		for (int tx = 0; tx < 4; tx++) {
			// The front column: B at the bottom band, A above it; the top shows A (the run's first row); a plain back.
			assertArrayEquals(new ShapeKey[] {cube('A', 'B', 'B'), cube('A', 'B', 'A')}, column(shapes, WALL, tx, 3));
			// The column behind it folds the same way; only the front one's south faces are seen.
			assertArrayEquals(new ShapeKey[] {cube('A', 'B', 'B'), cube('A', 'B', 'A')}, column(shapes, WALL, tx, 2));
		}
		assertNull(column(shapes, WALL, 4, 3), "ground");
		assertEquals(2, shapes.blocks()[3 * 8], "today's wall height");
		assertEquals(0, shapes.shapeMask()[3 * 8], "every block a tile_full");
	}

	@Test
	void aTallStructureFoldsEveryRow() {
		SceneMap tall = surface("TALL",
				"AAAA",
				"BBBB",
				"CCCC",
				"DDDD",
				"....",
				"....");
		ShapePlanner.MapShapes shapes = planned(tall);
		assertEquals(4, shapes.blocks()[0], "a unit of 4: today's roof class");
		// Front column (row 3): bands show D, C, B, A from the ground up; its top shows the run's first row; the back B.
		assertArrayEquals(new ShapeKey[] {cube('A', 'B', 'D'), cube('A', 'B', 'C'), cube('A', 'B', 'B'), cube('A', 'B', 'A')},
				column(shapes, tall, 0, 3));
		// Row 1: a top four rows up would leave the run, so it shows the first row too.
		assertArrayEquals(new ShapeKey[] {cube('A', 'B', 'D'), cube('A', 'B', 'C'), cube('A', 'B', 'B'), cube('A', 'B', 'A')},
				column(shapes, tall, 0, 1));
	}

	@Test
	void interiorsKeepTheirWallHeight() {
		SceneMap inside = interior("INSIDE",
				"AAAA",
				"BBBB",
				"CCDD",
				"CCDD");
		ShapePlanner.MapShapes shapes = planned(inside);
		int[] two = new int[16];
		java.util.Arrays.fill(two, 2);
		assertArrayEquals(two, shapes.blocks());
		// Column 0, row 3 (h 2 inside a 4-row run): its top shows row 1, its bands C and C.
		assertArrayEquals(new ShapeKey[] {cube('B', 'B', 'C'), cube('B', 'B', 'C')}, column(shapes, inside, 0, 3));
		// Column 2, row 3: D and D; row 1's top shows A... two rows up leaves the run, so the first row.
		assertArrayEquals(new ShapeKey[] {cube('B', 'B', 'D'), cube('B', 'B', 'D')}, column(shapes, inside, 2, 3));
		assertArrayEquals(new ShapeKey[] {cube('A', 'B', 'D'), cube('A', 'B', 'D')}, column(shapes, inside, 2, 1));
	}

	@Test
	void aCubeOfItsOwnTileIsNoShape() {
		SceneMap plain = surface("PLAIN",
				"........",
				"........",
				"..##....",
				"..##....");
		ShapePlan plan = ShapePlanner.plan(scene(plain), List.of(surfaceAt(plain, 0, 0)), ShapeParams.v2());
		assertEquals(0, plan.index().size(), "the plain cube already is that shape");
		assertEquals(0, plan.shapedBlocks());
		assertEquals(0, plan.map().shapedBlocks());
	}

	@Test
	void countersWaterAndGroundGetNoKeys() {
		SceneMap flat = surface("FLAT",
				"==~~..",
				"==~~..");
		ShapePlanner.MapShapes shapes = planned(flat);
		for (ShapeKey[] column : shapes.open()) {
			assertNull(column);
		}
		assertArrayEquals(new int[] {1, 1, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0}, shapes.blocks(), "the counter keeps its one block, as today");
	}

	@Test
	void bothSwapStatesArePlanned() {
		SceneMap open = surface("SWAP",
				"........",
				"........");
		SceneMap swap = withSite(open, "cut", 1, 0, "XY", "ZW");
		ShapePlanner.MapShapes shapes = planned(swap);
		for (ShapeKey[] column : shapes.open()) {
			assertNull(column, "built open: ground everywhere");
		}
		// Closed: a 2-high wall of X/Y over Z/W, folded like any wall; only the site's tiles have closed keys.
		assertArrayEquals(new ShapeKey[] {cube('X', 'Z', 'Z'), cube('X', 'Z', 'X')}, shapes.closed()[1 * 8 + 2]);
		assertArrayEquals(new ShapeKey[] {cube('Y', 'W', 'W'), cube('Y', 'W', 'Y')}, shapes.closed()[1 * 8 + 3]);
		assertArrayEquals(new ShapeKey[] {cube('X', 'Z', 'Z'), cube('X', 'Z', 'X')}, shapes.closed()[2]);
		assertNull(shapes.closed()[0]);
		ShapePlan plan = ShapePlanner.plan(scene(swap), List.of(surfaceAt(swap, 0, 0)), ShapeParams.v2());
		assertEquals(4, plan.index().size(), "the closed keys are in the index");
		assertEquals(0, plan.map().shapedBlocks(), "the world is built open");
	}

	@Test
	void positionsStartAtFeetLevelOfThePlacedMaps() {
		SceneMap inside = interior("INSIDE",
				"AAAA",
				"BBBB",
				"CCDD",
				"CCDD");
		Scene scene = scene(WALL, inside);
		MapPlacement wallAt = surfaceAt(WALL, 100, -40);
		MapPlacement insideAt = interiorAt(inside, 100, -40, 1, "WALL");
		ShapePlan plan = ShapePlanner.plan(scene, List.of(wallAt, insideAt), ShapeParams.v2());
		ShapeMap map = plan.map();
		int id = plan.index().shapeId(cube('A', 'B', 'B'), ShapeFixtures.PALETTE);
		assertTrue(id > 0);
		assertEquals(id, map.shapeAt(100, 64, -40 + 3), "the front column's bottom band at feet level");
		assertEquals(plan.index().shapeId(cube('A', 'B', 'A'), ShapeFixtures.PALETTE), map.shapeAt(100, 65, -37));
		assertEquals(0, map.shapeAt(100, 63, -37), "the floor block is unchanged");
		assertEquals(0, map.shapeAt(100, 66, -37), "above the wall");
		assertEquals(0, map.shapeAt(104, 64, -37), "ground");
		assertEquals(plan.index().shapeId(cube('B', 'B', 'C'), ShapeFixtures.PALETTE), map.shapeAt(100, 42, -37), "the interior at its feet");
		assertEquals(2 * 4 * 2 + 4 * 4 * 2, plan.shapedBlocks(), "the wall's 8 columns and the interior's 16, two bands each");
		assertEquals(plan.shapedBlocks(), map.shapedBlocks());
		assertEquals(ShapeMap.NO_HASH, map.indexHash(), "bound to its WorldIndex later");
		assertEquals(plan.index().hash(), map.shapeHash());
	}

	@Test
	void keysComeFromTheWholeSceneAndOrderNeverMatters() {
		SceneMap inside = interior("INSIDE",
				"AAAA",
				"BBBB",
				"CCDD",
				"CCDD");
		SceneMap swap = withSite(surface("SWAP", "........", "........"), "cut", 1, 0, "XY", "ZW");
		Scene scene = scene(WALL, inside, swap);
		List<MapPlacement> all = new ArrayList<>(List.of(surfaceAt(WALL, 0, 0), interiorAt(inside, 0, 0, 1, "WALL"), surfaceAt(swap, 0, 20)));
		ShapePlan full = ShapePlanner.plan(scene, all, ShapeParams.v2());
		ShapePlan filtered = ShapePlanner.plan(scene, List.of(surfaceAt(WALL, 0, 0)), ShapeParams.v2());
		assertEquals(full.index().hash(), filtered.index().hash(), "a filtered build shares the full build's shape index");
		assertNotEquals(full.map().hash(), filtered.map().hash(), "its map holds only the placed maps");
		Collections.reverse(all);
		ShapePlan reversed = ShapePlanner.plan(scene, all, ShapeParams.v2());
		assertEquals(full.index().hash(), reversed.index().hash());
		assertEquals(full.map().hash(), reversed.map().hash());
		ShapePlan again = ShapePlanner.plan(scene(swap, inside, WALL), all, ShapeParams.v2());
		assertEquals(full.index().hash(), again.index().hash());
		assertEquals(full.map().hash(), again.map().hash());
	}
}
