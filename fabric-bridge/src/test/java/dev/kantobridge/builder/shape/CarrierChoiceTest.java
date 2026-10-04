package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.plan.BlockCode;
import dev.kantobridge.builder.plan.ChunkCodesView;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.builder.plan.WorldPlanner;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.ShapeIndex;
import dev.kantobridge.world.ShapeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The carrier table (docs/specs/VOXEL_BLOCKS_PLAN.md §5.4, stage 5) on the synthetic world: a column's cube bands stand on
 * {@code tile_full}, its roof on {@code shape_full}, both showing the position's own tile as their slot; a ledge's lip
 * rides on its ledge collider; counters and walkable cells are as before; the shape map names a shape exactly where the
 * planner put one.
 */
class CarrierChoiceTest {
	private static WorldPlan plan;
	private static ChunkCodesView view;

	@BeforeAll
	static void plan() throws Exception {
		plan = WorldPlanner.plan(ExportBundle.open(TestData.syntheticBundle()),
				new WorldPlanner.Options(null, TestData.ORIGIN_X, TestData.ORIGIN_Z, TestData.MIN_BUILD_HEIGHT));
		view = new ChunkCodesView(plan);
	}

	@Test
	void theHillsHouseStandsOnTilesAndCarriesItsRoof() {
		MapPlacement hill = plan.index().map("TEST_HILL");
		int x = hill.x() + 5;
		int z = hill.z() + 8;
		int feet = hill.y();
		assertEquals(BlockCode.KIND_FULL, view.kind(x, feet - 1, z), "the floor block");
		for (int k = 0; k < 4; k++) {
			assertEquals(BlockCode.KIND_FULL, view.kind(x, feet + k, z), "band " + k);
		}
		int roof = view.code(x, feet + 4, z);
		assertEquals(BlockCode.KIND_SHAPE, BlockCode.kind(roof), "the roof block");
		assertEquals(BlockCode.AIR, view.code(x, feet + 5, z));
		// The roof's slot is the position's own tile, as a tile_full there would show.
		assertEquals(BlockCode.value(view.code(x, feet + 3, z)), BlockCode.value(roof), "the same slot index");
		ShapeMap map = plan.shapes().map();
		ShapeIndex index = plan.shapes().index();
		assertEquals(ShapeKey.Kind.ROOF, index.key(map.shapeAt(x, feet + 4, z)).kind());
		assertEquals(ShapeKey.Kind.CUBE, index.key(map.shapeAt(x, feet, z)).kind(), "the bands are folded cubes");
		assertEquals(0, map.shapeAt(x, feet + 5, z));
	}

	@Test
	void ledgeLipsRideOnTheirColliders() {
		MapPlacement town = plan.index().map("TEST_TOWN");
		int x = town.x() + 4;
		int z = town.z() + 26;
		int feet = town.y();
		assertEquals(BlockCode.KIND_LEDGE, view.kind(x, feet, z));
		ShapeKey lip = plan.shapes().index().key(plan.shapes().map().shapeAt(x, feet, z));
		assertEquals(ShapeKey.Kind.SLAB, lip.kind());
		assertEquals(4, lip.number(1), "4/16 high");
		assertEquals(BlockCode.KIND_FULL, view.kind(x, feet - 1, z), "the ledge art below is plain");
		assertEquals(0, plan.shapes().map().shapeAt(x, feet - 1, z));
	}

	@Test
	void treeWallsAreUnitsCountersAndGroundAsBefore() {
		MapPlacement town = plan.index().map("TEST_TOWN");
		// A border tree (V4): a unit, two shape_full blocks on a floor block wearing the town's ground.
		assertEquals(BlockCode.KIND_SHAPE, view.kind(town.x(), 64, town.z()));
		assertEquals(BlockCode.KIND_SHAPE, view.kind(town.x(), 65, town.z()));
		assertEquals(BlockCode.AIR, view.code(town.x(), 66, town.z()));
		ShapeKey unit = plan.shapes().index().key(plan.shapes().map().shapeAt(town.x(), 65, town.z()));
		assertEquals(ShapeKey.Kind.CELL, unit.kind());
		assertEquals(0, unit.number(4), "its ground is the ground tile");
		assertEquals(view.code(town.x() + 4, 63, town.z() + 4), view.code(town.x(), 63, town.z()), "the floor wears the ground's slot");
		// Open ground: nothing at feet level.
		assertEquals(BlockCode.AIR, view.code(town.x() + 4, 64, town.z() + 4));
		// Tall grass: a tuft on a decor carrier at feet level, nothing above.
		int gx = town.x() + 14 * 2;
		int gz = town.z() + 4 * 2;
		assertEquals(BlockCode.decor(false), view.code(gx, 64, gz));
		assertEquals(BlockCode.AIR, view.code(gx, 65, gz));
		assertEquals(ShapeKey.Kind.TUFT, plan.shapes().index().key(plan.shapes().map().shapeAt(gx, 64, gz)).kind());
		MapPlacement house = plan.index().map("TEST_HOUSE");
		// The counter: one block high.
		assertEquals(BlockCode.KIND_FULL, view.kind(house.x() + 8, house.y(), house.z() + 6));
		assertEquals(BlockCode.AIR, view.code(house.x() + 8, house.y() + 1, house.z() + 6));
		// Every shape_full block of the world lies in a solid cell's column, on a block of that column.
		long shapeBlocks = 0;
		for (var placed : plan.maps()) {
			MapPlacement p = placed.placement();
			for (int z = p.z(); z < p.z() + p.blockDepth(); z++) {
				for (int x = p.x(); x < p.x() + p.blockWidth(); x++) {
					for (int y = p.y(); y < p.y() + 8; y++) {
						if (view.kind(x, y, z) == BlockCode.KIND_SHAPE) {
							shapeBlocks++;
							assertNotEquals(BlockCode.AIR, view.code(x, y - 1, z), "a roof never floats");
						}
					}
				}
			}
		}
		assertTrue(shapeBlocks > 0);
	}
}
