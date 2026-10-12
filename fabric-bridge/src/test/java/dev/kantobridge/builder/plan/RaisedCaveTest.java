package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.block.EdgeWallBlock;
import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapLayout;
import dev.kantobridge.world.MapPlacement;
import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Raised ground in the synthetic cave (docs/specs/CAVES_3D_PLAN.md §3.4, H4): TEST_CAVE_1F's plateau stands 2 blocks up
 * on rock, its stairs climb it on step colliders, its ceilings follow the floor and close up to each other.
 */
class RaisedCaveTest {
	private static WorldPlan plan;
	private static PlacedMap cave;
	private static MapPlacement p;
	private static ChunkCodesView view;

	@BeforeAll
	static void plan() throws Exception {
		plan = WorldPlanner.plan(ExportBundle.open(TestData.syntheticBundle()),
				new WorldPlanner.Options(null, TestData.ORIGIN_X, TestData.ORIGIN_Z, TestData.MIN_BUILD_HEIGHT));
		cave = plan.maps().stream().filter(m -> m.id().equals("TEST_CAVE_1F")).findFirst().orElseThrow();
		p = cave.placement();
		view = new ChunkCodesView(plan);
	}

	/** The plan's code at block (bx, bz) of cell (cx, cy), {@code k} blocks above the map's feet level. */
	private static int code(int cx, int cy, int bx, int bz, int k) {
		return view.code(p.x() + cx * 2 + bx, p.y() + k, p.z() + cy * 2 + bz);
	}

	@Test
	void thePilotsPlateauIsRaisedByItsProfilePins() {
		MapLayout layout = cave.layout();
		assertEquals(layout, plan.index().layout("TEST_CAVE_1F"), "the index carries the levels");
		for (int[] cell : new int[][] {{6, 2}, {9, 2}, {9, 3}, {6, 5}, {9, 5}}) {
			assertEquals(CaveLevels.RISE, layout.level(cell[0], cell[1]), "plateau " + cell[0] + "," + cell[1]);
		}
		for (int[] cell : new int[][] {{2, 2}, {4, 3}, {5, 4}, {4, 6}, {3, 7}}) {
			assertEquals(0, layout.level(cell[0], cell[1]), "floor " + cell[0] + "," + cell[1]);
		}
		assertEquals(new MapLayout.Ramp(5, 2, Dir.RIGHT, CaveLevels.RISE), layout.ramp(5, 2));
		assertEquals(new MapLayout.Ramp(5, 3, Dir.RIGHT, CaveLevels.RISE), layout.ramp(5, 3));
		assertEquals(List.of(Map.of("high", "profile", "map", "TEST_CAVE_1F")), plan.warnings().details("cave_levels_raised"));
		assertEquals(List.of(Map.of("map", "TEST_CAVE_B1F")), plan.warnings().details("cave_levels_flat_one_side"));
		assertEquals(2, plan.warnings().notes().get("cave_stairs_profile_confirmed"));
		assertNull(plan.warnings().notes().get("cave_stairs_profile_other"));
		assertNull(plan.maps().stream().filter(m -> m.id().equals("TEST_CAVE_B1F")).findFirst().orElseThrow().layout());
	}

	@Test
	void aRaisedCellStandsOnRockWithItsCeilingThreeAbove() {
		int rock = code(7, 3, 0, 0, -1);
		assertEquals(BlockCode.KIND_FULL, BlockCode.kind(rock));
		assertEquals(rock, code(7, 3, 0, 0, 0), "rock up to the raised floor");
		assertEquals(BlockCode.KIND_FULL, BlockCode.kind(code(7, 3, 0, 0, 1)), "the floor, 2 up");
		assertNotEquals(rock, code(7, 3, 0, 0, 1), "in the floor's own tile");
		for (int k = 2; k < 5; k++) {
			assertTrue(BlockCode.kind(code(7, 3, 0, 0, k)) != BlockCode.KIND_FULL, "3 blocks of air over it (U5), " + k);
		}
		assertEquals(BlockCode.KIND_FULL, BlockCode.kind(code(7, 3, 0, 0, 5)), "its ceiling");
		assertEquals(p.y() - 1, WorldPlan.interiorVolume(p, WorldPlan.maxCeiling(cave.layout())).minY());
		assertEquals(p.y() + 6, WorldPlan.interiorVolume(p, WorldPlan.maxCeiling(cave.layout())).maxY(), "the volume reaches the raised ceilings");
	}

	@Test
	void aLowCeilingClosesUpToARaisedNeighbours() {
		// (4,4) is low, next to the stairs; (2,4) has only low ground and rock around it.
		for (int k = 0; k < 3; k++) {
			assertTrue(BlockCode.kind(code(4, 4, 0, 0, k)) != BlockCode.KIND_FULL, "air on the low floor, " + k);
		}
		for (int k = 3; k <= 5; k++) {
			assertEquals(BlockCode.KIND_FULL, BlockCode.kind(code(4, 4, 0, 0, k)), "its ceiling and the rock up to the stairs' ceiling, " + k);
		}
		assertEquals(BlockCode.KIND_FULL, BlockCode.kind(code(2, 4, 0, 0, 3)));
		assertEquals(BlockCode.AIR, code(2, 4, 0, 0, 4), "nothing to close over a ceiling as high as its neighbours'");
	}

	@Test
	void theStairsClimbOnStepColliders() {
		int step = BlockCode.step(Dir.RIGHT);
		for (int bz = 0; bz < 2; bz++) {
			assertEquals(step, code(5, 2, 0, bz, 0), "the low row: two half steps on the floor");
			assertEquals(BlockCode.KIND_DECOR, BlockCode.kind(code(5, 2, 0, bz, 1)), "the rest of the stair's art over them");
			assertEquals(BlockCode.KIND_FULL, BlockCode.kind(code(5, 2, 1, bz, 0)), "the high row: a block");
			assertEquals(step, code(5, 2, 1, bz, 1), "and two half steps on it");
			assertEquals(BlockCode.KIND_FULL, BlockCode.kind(code(5, 2, 1, bz, 5)), "the ramp's ceiling, 3 over its top");
		}
		// The stair units' art stands on the steps.
		int lowRow = plan.shapes().map().shapeAt(p.x() + 10, p.y(), p.z() + 4);
		int highRow = plan.shapes().map().shapeAt(p.x() + 11, p.y() + 1, p.z() + 4);
		assertTrue(lowRow != 0 && highRow != 0, "stair keys on the step colliders");
	}

	@Test
	void theCliffIsWalledOnItsHighSide() {
		// The plateau's west edge against the floor, (5,4)|(6,4) and (5,5)|(6,5).
		assertEquals(BlockCode.edge(EdgeWallBlock.SIDE_WEST), code(6, 4, 0, 0, 2), "at the raised floor's feet");
		assertEquals(BlockCode.edge(EdgeWallBlock.SIDE_WEST), code(6, 5, 0, 1, 2));
		assertEquals(BlockCode.edge(EdgeWallBlock.SIDE_EAST), code(5, 4, 1, 0, 0), "the low side keeps its own (harmless)");
	}

	@Test
	void positionsFollowTheLevels() {
		Vec3 high = plan.index().toBlock("TEST_CAVE_1F", 7, 3);
		assertEquals(p.y() + 2, high.y, 1e-9);
		Vec3 ramp = plan.index().toBlock("TEST_CAVE_1F", 5, 2);
		assertEquals(p.y() + 1.5, ramp.y, 1e-9, "a ramp's middle stands on its third half step");
		assertEquals(0.0, plan.index().rampLift("TEST_CAVE_1F", 5, 2, 4.5, 2.0), 1e-9, "its low edge");
		assertEquals(1.0, plan.index().rampLift("TEST_CAVE_1F", 5, 2, 5.0, 2.0), 1e-9);
		assertEquals(2.0, plan.index().rampLift("TEST_CAVE_1F", 5, 2, 5.5, 2.0), 1e-9, "its high edge, the plateau's level");
		assertEquals(0.0, plan.index().rampLift("TEST_CAVE_1F", 4, 2, 4.2, 2.0), 1e-9, "off the ramp");
	}

	@Test
	void ceilingsFollowTheFloorOnAMadeUpLayout() {
		int[] levels = {0, 0, 0, 2, 2, 2, 0, 0, 0};
		MapLayout layout = MapLayout.flat(3, 3, levels, List.of(new MapLayout.Ramp(0, 2, Dir.UP, 2)));
		assertEquals(5, WorldPlan.ceilingLevel(layout, 1, 1), "raised: 3 over its floor");
		assertEquals(3, WorldPlan.ceilingLevel(layout, 1, 2));
		assertEquals(5, WorldPlan.ceilingLevel(layout, 0, 2), "a ramp: 3 over its top");
		assertEquals(5, WorldPlan.ceilingTop(layout, 1, 2), "closed up to its neighbours'");
		assertEquals(5, WorldPlan.maxCeiling(layout));
		assertEquals(WorldPlan.CEILING_ABOVE_FEET, WorldPlan.maxCeiling(null));
		assertEquals(2, WorldPlan.hangBase(layout, 0, 2), "hangings and the light count from a ramp's top");
		assertTrue(WorldPlan.highRow(Dir.RIGHT, 1, 0) && WorldPlan.highRow(Dir.LEFT, 0, 1) && WorldPlan.highRow(Dir.UP, 1, 0)
				&& WorldPlan.highRow(Dir.DOWN, 0, 1));
	}
}
