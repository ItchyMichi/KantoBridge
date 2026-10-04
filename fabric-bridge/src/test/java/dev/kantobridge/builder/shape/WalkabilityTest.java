package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.plan.BlockCode;
import dev.kantobridge.builder.plan.ChunkCodesView;
import dev.kantobridge.builder.plan.MapModel;
import dev.kantobridge.builder.plan.PlacedMap;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.builder.plan.WorldPlanner;
import dev.kantobridge.world.MapPlacement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Walkability is unchanged (docs/specs/VOXEL_BLOCKS_PLAN.md I1): for every cell of every map, the shaped build and the
 * unshaped one have the same colliders at feet level. A solid or counter cell has a full-cube collider ({@code tile_full}
 * or {@code shape_full}) in all four of its columns; a walkable, water or ledge cell has exactly the unshaped build's
 * blocks (story cells, ledge colliders, air) at feet level and at feet + 1, except that air may hold a {@code decor}
 * carrier (a tuft, V4), which has no collision: the shaped build never adds a collider there.
 */
class WalkabilityTest {
	private static WorldPlan shaped;
	private static WorldPlan unshaped;

	@BeforeAll
	static void plan() throws Exception {
		ExportBundle bundle = ExportBundle.open(TestData.syntheticBundle());
		shaped = WorldPlanner.plan(bundle, new WorldPlanner.Options(null, TestData.ORIGIN_X, TestData.ORIGIN_Z, TestData.MIN_BUILD_HEIGHT));
		unshaped = WorldPlanner.plan(bundle, new WorldPlanner.Options(null, TestData.ORIGIN_X, TestData.ORIGIN_Z, TestData.MIN_BUILD_HEIGHT,
				false));
	}

	private static boolean collider(int code) {
		int kind = BlockCode.kind(code);
		return kind == BlockCode.KIND_FULL || kind == BlockCode.KIND_SHAPE;
	}

	@Test
	void everyCellKeepsItsColliders() {
		assertNull(unshaped.shapes(), "the unshaped build plans no shapes");
		assertEquals(shaped.index().toJson(), unshaped.index().toJson(), "and places the same maps");
		ChunkCodesView a = new ChunkCodesView(shaped);
		ChunkCodesView b = new ChunkCodesView(unshaped);
		int cells = 0;
		int roofs = 0;
		int tufts = 0;
		for (PlacedMap map : shaped.maps()) {
			MapPlacement p = map.placement();
			MapModel model = map.model();
			for (int cy = 0; cy < model.cellHeight(); cy++) {
				for (int cx = 0; cx < model.cellWidth(); cx++) {
					MapModel.Kind kind = model.kind(cx, cy);
					cells++;
					for (int dz = 0; dz < 2; dz++) {
						for (int dx = 0; dx < 2; dx++) {
							int x = p.x() + cx * 2 + dx;
							int z = p.z() + cy * 2 + dz;
							String where = map.id() + " cell " + cx + "," + cy;
							if (kind == MapModel.Kind.SOLID || kind == MapModel.Kind.COUNTER) {
								assertTrue(collider(a.code(x, p.y(), z)), where + ": no collider at feet level");
								assertTrue(collider(b.code(x, p.y(), z)), where + ": the unshaped build has none either");
								for (int y = p.y(); y < p.y() + 8; y++) {
									roofs += BlockCode.kind(a.code(x, y, z)) == BlockCode.KIND_SHAPE ? 1 : 0;
								}
							} else {
								for (int y = p.y(); y <= p.y() + 1; y++) {
									int code = a.code(x, y, z);
									if (BlockCode.kind(code) == BlockCode.KIND_DECOR && b.code(x, y, z) == BlockCode.AIR) {
										tufts++;
										continue;
									}
									assertEquals(b.code(x, y, z), code, where + ": feet + " + (y - p.y()));
								}
							}
						}
					}
				}
			}
		}
		assertEquals(20 * 18 + 16 * 12 + 10 * 10 + 8 * 8, cells, "every cell of the four maps");
		assertTrue(roofs > 0, "the hill's house carries its roof in shape_full blocks");
		assertTrue(tufts > 0, "the town's grass stands up on decor carriers");
	}
}
