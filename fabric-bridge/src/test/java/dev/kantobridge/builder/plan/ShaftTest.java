package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.WorldIndex;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The synthetic cave's shafts (docs/specs/CAVES_3D_PLAN.md §3.5, H5): two ladders and a hole, each top cell straight over
 * its bottom cell; air up the column, a ladder on one wall, rock round it between the two rooms, a light half way.
 */
class ShaftTest {
	private static WorldPlan plan;
	private static ChunkCodesView view;

	@BeforeAll
	static void plan() throws Exception {
		plan = WorldPlanner.plan(ExportBundle.open(TestData.syntheticBundle()),
				new WorldPlanner.Options(null, TestData.ORIGIN_X, TestData.ORIGIN_Z, TestData.MIN_BUILD_HEIGHT));
		view = new ChunkCodesView(plan);
	}

	private static WorldIndex.Shaft shaft(int topX, int topY) {
		return plan.index().shafts().stream().filter(s -> s.top().x() == topX && s.top().y() == topY).findFirst().orElseThrow();
	}

	@Test
	void theCavesLaddersAndHoleLineUp() {
		List<WorldIndex.Shaft> shafts = plan.index().shafts();
		assertEquals(3, shafts.size());
		WorldIndex.Shaft plateau = shaft(9, 3);
		assertEquals(new CellPos("TEST_CAVE_B1F", 9, 3), plateau.bottom());
		assertTrue(plateau.up() && WorldIndex.Shaft.LADDER.equals(plateau.kind()));
		assertEquals(plan.index().map("TEST_CAVE_B1F").y(), plateau.y0(), "from the bottom cell's feet");
		assertEquals(plan.index().map("TEST_CAVE_1F").y() + CaveLevels.RISE - 1, plateau.y1(), "to the raised top cell's floor");
		WorldIndex.Shaft hole = shaft(5, 7);
		assertEquals(new CellPos("TEST_CAVE_B1F", 5, 5), hole.bottom());
		assertFalse(hole.up(), "a hole is fallen down only");
		for (WorldIndex.Shaft shaft : shafts) {
			int[] top = WorldPlan.cellOrigin(plan.index().map(shaft.top().map()), plan.index().layout(shaft.top().map()), shaft.top().x(),
					shaft.top().y());
			int[] bottom = WorldPlan.cellOrigin(plan.index().map(shaft.bottom().map()), plan.index().layout(shaft.bottom().map()),
					shaft.bottom().x(), shaft.bottom().y());
			assertEquals(top[0], bottom[0], "one column");
			assertEquals(top[2], bottom[2], "one column");
			assertEquals(top[0], shaft.x());
		}
		assertEquals(3, plan.warnings().noteCount("cave_shafts"));
		assertEquals(3, plan.warnings().noteCount("cave_rooms"), "1F's one room and B1F's two");
	}

	@Test
	void aLadderShaftIsAirWithALadderOnOneWallAndRockRoundIt() {
		WorldIndex.Shaft s = shaft(9, 3);
		// The top cell on the plateau has walkable neighbours west, north and south: its ladder is on the north wall (HD6).
		assertEquals(Dir.UP, WorldPlan.ladderWall(plan.map("TEST_CAVE_1F").model(), 9, 3));
		assertEquals(Dir.UP, s.wall(), "the index says so");
		int ladder = BlockCode.ladder(Dir.DOWN);
		for (int y = s.y0(); y <= s.y1() + 1; y++) {
			assertEquals(ladder, view.code(s.x(), y, s.z()), "the ladder from the bottom cell's feet to the top cell's, " + y);
			assertEquals(ladder, view.code(s.x() + 1, y, s.z()));
		}
		int middle = (s.y0() + s.y1()) / 2;
		for (int y = s.y0() + 1; y < s.y1(); y++) {
			int code = view.code(s.x() + 1, y, s.z() + 1);
			assertTrue(code == BlockCode.AIR || y == middle, "air up the shaft beside the ladder, " + y);
		}
		// Beside the ladder the top cell keeps its floor: a player climbing up steps back onto it.
		for (int bx = 0; bx < 2; bx++) {
			int floor = view.code(s.x() + bx, s.y1(), s.z() + 1);
			assertTrue(floor != BlockCode.AIR && BlockCode.kind(floor) != BlockCode.KIND_LADDER, "the top cell's floor beside the ladder");
			assertFalse(s.contains(s.x() + bx, s.y1(), s.z() + 1));
			assertTrue(s.contains(s.x() + bx, s.y1(), s.z()), "the ladder's half is open through the floor");
		}
		assertEquals(BlockCode.light(WorldPlan.LIGHT_LEVEL), view.code(s.x(), middle, s.z() + 1), "a light half way up");
		// Between the floors, the ring round the column is rock.
		int between = s.y0() + WorldPlan.maxCeiling(plan.index().layout("TEST_CAVE_B1F")) + 2;
		assertEquals(BlockCode.KIND_FULL, BlockCode.kind(view.code(s.x() - 1, between, s.z())));
		assertEquals(BlockCode.KIND_FULL, BlockCode.kind(view.code(s.x() + 2, between, s.z() + 1)));
		assertEquals(BlockCode.KIND_FULL, BlockCode.kind(view.code(s.x(), between, s.z() + 2)));
	}

	@Test
	void aHoleHasNoLadder() {
		WorldIndex.Shaft s = shaft(5, 7);
		for (int y = s.y0() + 1; y <= s.y1(); y++) {
			for (int b = 0; b < 4; b++) {
				int code = view.code(s.x() + b % 2, y, s.z() + b / 2);
				assertTrue(code == BlockCode.AIR || BlockCode.kind(code) == BlockCode.KIND_LIGHT, "open, " + y);
			}
		}
	}

	@Test
	void insideAShaftIsNoCell() {
		WorldIndex index = plan.index();
		WorldIndex.Shaft s = shaft(3, 5);
		assertTrue(index.toCell(s.x(), s.y0() + 4, s.z()).isEmpty(), "between the floors");
		assertEquals(s, index.shaftAt(s.x() + 1, s.y1(), s.z()), "the ladder's half, through the top cell's floor");
		assertNull(index.shaftAt(s.x() + 1, s.y1(), s.z() + 1), "the floor kept beside it");
		assertNull(index.shaftAt(s.x() + 2, s.y0() + 4, s.z()), "beside it");
		assertEquals(new CellPos("TEST_CAVE_B1F", 3, 3), index.toCell(s.x(), s.y0(), s.z()).orElseThrow(), "standing on its bottom cell");
		assertEquals(new CellPos("TEST_CAVE_1F", 3, 5), index.toCell(s.x(), s.y1() + 1, s.z()).orElseThrow(), "at its top cell's feet");
		assertEquals(s, index.shaftEnd(new CellPos("TEST_CAVE_1F", 3, 5)));
		assertNull(index.shaftEnd(new CellPos("TEST_CAVE_1F", 4, 5)));
	}

	@Test
	void theIndexCarriesItsShafts() throws Exception {
		WorldIndex again = WorldIndex.fromJson(plan.index().toJson());
		assertEquals(plan.index().shafts(), again.shafts());
		assertEquals(plan.index().toJson(), again.toJson());
		assertNotNull(plan.index().layout("TEST_CAVE_B1F"));
		assertEquals(2, plan.index().layout("TEST_CAVE_B1F").pieces().size());
	}
}
