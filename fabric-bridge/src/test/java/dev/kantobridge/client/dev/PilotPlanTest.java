package dev.kantobridge.client.dev;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportMap;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.WorldIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The caves pilot's video planned on the synthetic world (its golden index): TEST_CAVE_1F's stairs climb east at (5, 2)
 * and (5, 3) onto the raised ground x 6–9, y 2–5; its ladders' tops are (9, 3) on the raised ground and (3, 5).
 */
class PilotPlanTest {
	private static WorldIndex index;
	private static PilotPlan.Cells cells;

	@BeforeAll
	static void read() throws Exception {
		index = WorldIndex.fromJson(Files.readString(TestData.goldenIndex(), StandardCharsets.UTF_8));
		ExportBundle bundle = ExportBundle.open(TestData.syntheticBundle());
		java.util.Map<String, ExportMap> maps = new java.util.HashMap<>();
		for (String id : List.of("TEST_CAVE_1F", "TEST_CAVE_B1F")) {
			maps.put(id, bundle.map(id));
		}
		cells = (map, cx, cy) -> {
			ExportMap m = maps.get(map);
			if (m == null || cx < 0 || cy < 0 || cx >= m.cellWidth() || cy >= m.cellHeight()) {
				return false;
			}
			int flags = m.flags(cx, cy);
			return CellFlags.has(flags, CellFlags.WALKABLE) && !CellFlags.has(flags, CellFlags.WATER);
		};
	}

	@Test
	void theCavesBuiltIn3dAreTheSyntheticCavesFloors() {
		assertEquals(List.of("TEST_CAVE_1F", "TEST_CAVE_B1F"), PilotPlan.caves3d(index).stream().sorted().toList());
	}

	@Test
	void upTheStairsAndOnToAnEdgeAwayFromThem() {
		PilotPlan.Stairs stairs = PilotPlan.stairs(index, PilotPlan.caves3d(index), cells);
		assertNotNull(stairs);
		assertEquals("TEST_CAVE_1F", stairs.map());
		assertEquals(Dir.RIGHT, stairs.facing());
		int[][] want = {{3, 2}, {4, 2}, {5, 2}, {6, 2}, {6, 3}, {6, 4}, {6, 5}};
		assertEquals(want.length, stairs.path().size());
		for (int i = 0; i < want.length; i++) {
			assertArrayEquals(want[i], stairs.path().get(i), "a run-up, the stairs, then along the raised ground; cell " + i);
		}
		assertEquals(Dir.LEFT, stairs.look(), "(6, 4)'s drop is beside the stairs; (6, 5) looks over (5, 5)");
	}

	@Test
	void noStairsWhereNothingStands() {
		assertNull(PilotPlan.stairs(index, PilotPlan.caves3d(index), (map, cx, cy) -> false));
	}

	@Test
	void aLadderIsWalkedIntoAcrossFromItsWall() {
		PilotPlan.Ladder ladder = PilotPlan.ladder(index, PilotPlan.caves3d(index), cells);
		assertNotNull(ladder);
		assertEquals(new CellPos("TEST_CAVE_1F", 9, 3), ladder.shaft().top(), "the first ladder; the hole is never one");
		assertArrayEquals(new int[] {9, 4}, new int[] {ladder.x(), ladder.y()});
		assertEquals(Dir.UP, ladder.facing(), "the index's wall: north (HD6)");
		// A ladder on the east wall: (8, 3) is across from it.
		PilotPlan.Ladder east = PilotPlan.ladder(index, PilotPlan.caves3d(index), cells, s -> Dir.RIGHT);
		assertArrayEquals(new int[] {8, 3}, new int[] {east.x(), east.y()});
		assertEquals(Dir.RIGHT, east.facing());
		// No ladder shown: none planned.
		assertNull(PilotPlan.ladder(index, PilotPlan.caves3d(index), cells, s -> null));
	}

	@Test
	void everyOptionInOrderForWhenTheGameRefusesOne() {
		List<PilotPlan.Ladder> ladders = PilotPlan.ladders(index, PilotPlan.caves3d(index), cells, WorldIndex.Shaft::wall);
		// (9, 3): across from its wall (9, 4), then walking right from (8, 3) (left from the east is rock); (3, 5): (3, 6), then
		// walking left from (4, 5), then right from (2, 5)
		assertEquals(5, ladders.size());
		assertArrayEquals(new int[] {9, 4, 8, 3, 3, 6, 4, 5, 2, 5}, ladders.stream().flatMapToInt(l -> java.util.stream.IntStream.of(l.x(), l.y()))
				.toArray());
		List<PilotPlan.Stairs> stairs = PilotPlan.allStairs(index, PilotPlan.caves3d(index), cells);
		assertEquals(2, stairs.size(), "both stairs cells");
		assertArrayEquals(new int[] {3, 3}, stairs.get(1).start());
	}
}
