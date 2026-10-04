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
	void theHillsHouseIsATemplateOverItsTierAColumns() {
		// V7: the house (a door folds into it) is a template; its volume, and so its collision, stays its Tier A columns'.
		MapPlacement hill = plan.index().map("TEST_HILL");
		int x = hill.x() + 5;
		int z = hill.z() + 8;
		int feet = hill.y();
		assertEquals(BlockCode.KIND_FULL, view.kind(x, feet - 1, z), "the floor block");
		int band = view.code(x, feet, z);
		for (int k = 0; k <= 4; k++) {
			int code = view.code(x, feet + k, z);
			assertEquals(BlockCode.KIND_SHAPE, BlockCode.kind(code), "block " + k + ": a template's carrier");
			// The slot is the position's own tile, as a tile_full there would show.
			assertEquals(BlockCode.value(band), BlockCode.value(code), "the same slot index");
		}
		assertEquals(BlockCode.AIR, view.code(x, feet + 5, z));
		ShapeMap map = plan.shapes().map();
		ShapeIndex index = plan.shapes().index();
		ShapeKey first = index.key(map.shapeAt(x, feet, z));
		assertEquals(ShapeKey.Kind.TMPL, first.kind());
		assertEquals(0, map.shapeAt(x, feet + 5, z));
		Templates.Entry entry = null;
		for (com.google.gson.JsonElement element : index.templates()) {
			if (element.getAsJsonObject().get("sig").getAsString().equals(first.sig())) {
				entry = Templates.Entry.of(element.getAsJsonObject());
			}
		}
		org.junit.jupiter.api.Assertions.assertNotNull(entry, "the index holds the template");
		ShapeKey[] column = entry.columns()[first.number(2) * entry.w() + first.number(0)];
		assertEquals(5, column.length, "four bands and the roof block: its fallback is Tier A's column");
		assertEquals(ShapeKey.Kind.CUBE, column[0].kind(), "the bands are folded cubes");
		assertEquals(ShapeKey.Kind.ROOF, column[4].kind());
		for (int k = 0; k <= 4; k++) {
			ShapeKey key = index.key(map.shapeAt(x, feet + k, z));
			assertEquals(first.sig(), key.sig());
			assertEquals(k, key.number(1), "the block's height in the template");
		}
	}

	@Test
	void aCutTreesClosedArtRidesOnItsStoryCarriers() {
		MapPlacement town = plan.index().map("TEST_TOWN");
		int x = town.x() + 20;
		int z = town.z() + 28;
		int feet = town.y();
		// Built open: the story tile at feet level carries the bottom of the closed tree, decor_leaves the rest (V5).
		assertEquals(BlockCode.KIND_STORY_TILE, view.kind(x + 1, feet, z + 1));
		assertEquals(BlockCode.decor(true), view.code(x + 1, feet + 1, z + 1), "foliage");
		assertEquals(BlockCode.AIR, view.code(x + 1, feet + 2, z + 1));
		assertEquals(0, plan.shapes().map().shapeAt(x, feet + 1, z), "the open world has no shape there");
		ShapeMap.Overlay tree = plan.shapes().map().overlays().get(0);
		assertEquals("TEST_TOWN:10:14", tree.key());
		assertEquals(x, tree.x());
		assertEquals(feet, tree.y());
		assertEquals(z, tree.z());
		for (int[] column : tree.columns()) {
			assertEquals(2, column.length);
			assertEquals(ShapeKey.Kind.CELL, plan.shapes().index().key(column[1]).kind());
		}
		assertEquals(1, plan.shapes().closedColumns().size(), "the town's one tile story cell");
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
