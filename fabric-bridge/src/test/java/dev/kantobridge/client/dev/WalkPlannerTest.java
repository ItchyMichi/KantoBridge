package dev.kantobridge.client.dev;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The autopilot's walks: the farthest lane within reach, and the path pulled straight where the body fits. */
class WalkPlannerTest {
	/** '.' walkable, '#' not; x runs along a row, z down the rows. */
	static WalkPlanner.Grid grid(String... rows) {
		return (x, z) -> z >= 0 && z < rows.length && x >= 0 && x < rows[z].length() && rows[z].charAt(x) == '.';
	}

	static final String[] MAZE = {
			"..........",
			"########..",
			"..........",
			"..########",
			"..........",
	};
	static final WalkPlanner.Box BOX = new WalkPlanner.Box(0, 0, 9, 4);

	@Test
	void theFarthestColumnIsTheEndOfTheLanes() {
		List<int[]> path = WalkPlanner.farthest(grid(MAZE), BOX, 0, 0, 100);
		assertArrayEquals(new int[] {0, 0}, path.get(0));
		assertArrayEquals(new int[] {9, 4}, path.get(path.size() - 1), "the far end of the zig-zag");
		for (int i = 1; i < path.size(); i++) {
			int[] a = path.get(i - 1);
			int[] b = path.get(i);
			assertEquals(1, Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]), "one step at a time");
			assertTrue(grid(MAZE).walkable(b[0], b[1]));
		}
	}

	@Test
	void aShortReachStopsEarlierAndTheBoxBoundsTheSearch() {
		List<int[]> path = WalkPlanner.farthest(grid(MAZE), BOX, 0, 0, 5);
		assertEquals(6, path.size(), "five steps from the start");
		List<int[]> boxed = WalkPlanner.farthest(grid(MAZE), new WalkPlanner.Box(0, 0, 4, 0), 0, 0, 100);
		assertArrayEquals(new int[] {4, 0}, boxed.get(boxed.size() - 1));
		List<int[]> alone = WalkPlanner.farthest(grid("#.#"), new WalkPlanner.Box(0, 0, 2, 0), 1, 0, 100);
		assertEquals(1, alone.size(), "nowhere to go: just the start");
	}

	@Test
	void smoothingCutsStraightAcrossOpenGroundButNotThroughWalls() {
		WalkPlanner.Grid open = grid("......", "......", "......", "......");
		List<int[]> stairs = List.of(new int[] {0, 0}, new int[] {1, 0}, new int[] {1, 1}, new int[] {2, 1}, new int[] {2, 2},
				new int[] {3, 2}, new int[] {3, 3});
		List<double[]> straight = WalkPlanner.smooth(open, stairs, 0.3);
		assertEquals(2, straight.size(), "one straight line over open ground");
		assertArrayEquals(new double[] {3.5, 3.5}, straight.get(1));

		List<double[]> zigzag = WalkPlanner.smooth(grid(MAZE), WalkPlanner.farthest(grid(MAZE), BOX, 0, 0, 100), 0.3);
		assertTrue(zigzag.size() >= 5, "the walls keep the corners: " + zigzag.size());
		for (int i = 1; i < zigzag.size(); i++) {
			assertTrue(WalkPlanner.clear(grid(MAZE), zigzag.get(i - 1), zigzag.get(i), 0.3), "every leg is clear");
		}
		assertFalse(WalkPlanner.clear(grid(MAZE), new double[] {0.5, 0.5}, new double[] {0.5, 2.5}, 0.3), "a wall between");
		assertTrue(WalkPlanner.length(zigzag) > 20);
	}
}
