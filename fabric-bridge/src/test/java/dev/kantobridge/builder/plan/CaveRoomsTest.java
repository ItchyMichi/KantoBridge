package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.export.ExportMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A cave's rooms and their layout (docs/specs/CAVES_3D_PLAN.md §3.5, H5) on made-up maps, one character a cell:
 * {@code .} floor, {@code #} rock, {@code ~} water, {@code L} a ladder (a warp tile), {@code H} a hole, {@code D} a door.
 */
class CaveRoomsTest {
	/** A map from its cells' rows (an even number of each) and its warps, as {x, y, to, number}. */
	static ExportMap map(String id, List<Object[]> warps, String... rows) {
		int h = rows.length;
		int w = rows[0].length();
		int[][] cells = new int[h][w];
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				cells[cy][cx] = switch (rows[cy].charAt(cx)) {
					case '.' -> CellFlags.WALKABLE;
					case '~' -> CellFlags.WATER;
					case 'L' -> CellFlags.WALKABLE | CellFlags.WARP_TILE;
					case 'H' -> CellFlags.WALKABLE | CellFlags.HOLE;
					case 'D' -> CellFlags.WALKABLE | CellFlags.DOOR | CellFlags.WARP_TILE;
					default -> 0;
				};
			}
		}
		List<ExportMap.Warp> list = new ArrayList<>();
		for (Object[] warp : warps) {
			list.add(new ExportMap.Warp((int) warp[0], (int) warp[1], (String) warp[2], (int) warp[3]));
		}
		return new ExportMap(id, 60, "CAVERN", w / 2, h / 2, new int[(w / 2) * (h / 2)], 0, false, false, Map.of(), list, List.of(), List.of(),
				cells, null, null, false, "PAL");
	}

	private static Object[] warp(int x, int y, String to, int number) {
		return new Object[] {x, y, to, number};
	}

	private static List<Object[]> warps(Object[]... list) {
		return java.util.Arrays.asList(list);
	}

	private static CaveRooms.Plan layOut(Map<String, Integer> ranks, ExportMap... maps) {
		Map<String, ExportMap> all = new LinkedHashMap<>();
		for (ExportMap map : maps) {
			all.put(map.id(), map);
		}
		return CaveRooms.layOut(all, List.copyOf(all.keySet()), ranks, maps[0].id(), Map.of());
	}

	@Test
	void roomsAreTheWalkGraphsPartsWithTheRockRoundThem() {
		ExportMap map = map("C_B1F", List.of(),
				"########",
				"#..##..#",
				"#..##~.#",
				"########");
		List<CavePieces.Room> rooms = CavePieces.of(map, List.of());
		assertEquals(2, rooms.size(), "water joins a room like floor; rock parts the two");
		CavePieces.Room west = rooms.get(0);
		assertEquals(4, west.floor().cardinality());
		assertArrayEquals(new int[] {0, 0, 4, 4}, new int[] {west.x(), west.y(), west.w(), west.h()}, "its cells and the rock round them");
		assertTrue(west.owns(8, 3, 1) && !west.owns(8, 4, 1), "the rock between is the room's next to it");
		assertEquals(rooms.get(1), CavePieces.roomAt(rooms, 8, 5, 2), "the water cell's room");
		// A swap site's block (2 × 2 cells) may be walked on in some story state: it joins what it touches.
		assertEquals(1, CavePieces.of(map, List.of(new int[] {1, 0}, new int[] {2, 0})).size());
	}

	@Test
	void aLadderBetweenTwoFloorsLinesUpAndWorksBothWays() {
		ExportMap top = map("C_1F", warps(warp(2, 1, "C_B1F", 1)),
				"######",
				"#.L..#",
				"#....#",
				"######");
		ExportMap bottom = map("C_B1F", warps(warp(4, 2, "C_1F", 1)),
				"######",
				"#....#",
				"#...L#",
				"######");
		CaveRooms.Plan plan = layOut(Map.of("C_1F", 0, "C_B1F", 1), top, bottom);
		assertEquals(List.of(new CaveRooms.Link("ladder", "C_1F", 2, 1, "C_B1F", 4, 2, true)), plan.shafts());
		assertArrayEquals(new int[] {-4, -2}, plan.frames().get("C_B1F")[0], "B1F's ladder under 1F's: (2, 1) over (4, 2)");
		assertTrue(plan.fades().isEmpty());
	}

	@Test
	void roomsOfOneFloorMoveApartSoEveryLadderLinesUp() {
		ExportMap top = map("C_1F", warps(warp(1, 1, "C_B1F", 1), warp(4, 1, "C_B1F", 2)),
				"######",
				"#L..L#",
				"#....#",
				"######");
		// Two rooms, each with a ladder up a cell lower than 1F's: they move apart, the second 3 cells west.
		ExportMap bottom = map("C_B1F", warps(warp(1, 2, "C_1F", 1), warp(7, 2, "C_1F", 2)),
				"########",
				"#.#####.",
				"#L#####L",
				"########");
		CaveRooms.Plan plan = layOut(Map.of("C_1F", 0, "C_B1F", 1), top, bottom);
		assertEquals(2, plan.shafts().size());
		assertArrayEquals(new int[] {0, -2}, plan.frames().get("C_B1F")[0]);
		assertArrayEquals(new int[] {-6, -2}, plan.frames().get("C_B1F")[1], "the east room 6 blocks west of where B1F would put it");
		assertTrue(plan.fades().isEmpty());
	}

	@Test
	void aLinkThatCannotLineUpKeepsItsFade() {
		// Two ladders into one room at different offsets: the second disagrees with the room the first placed.
		ExportMap top = map("C_1F", warps(warp(1, 1, "C_B1F", 1), warp(4, 1, "C_B1F", 2)),
				"######",
				"#L..L#",
				"#....#",
				"######");
		ExportMap bottom = map("C_B1F", warps(warp(1, 1, "C_1F", 1), warp(2, 2, "C_1F", 2)),
				"######",
				"#L...#",
				"#.L..#",
				"######");
		CaveRooms.Plan plan = layOut(Map.of("C_1F", 0, "C_B1F", 1), top, bottom);
		assertEquals(1, plan.shafts().size());
		assertEquals(Map.of("loop", 1), plan.fades());
	}

	@Test
	void aLadderWhoseTopLeadsElsewhereAndAWarpWithinAFloorKeepTheirFades() {
		ExportMap top = map("C_1F", warps(warp(2, 1, "C_B1F", 2), warp(4, 2, "C_1FB", 1)),
				"######",
				"#.L..#",
				"#...L#",
				"######");
		ExportMap side = map("C_1FB", warps(warp(1, 1, "C_1F", 2)),
				"####",
				"#L.#",
				"#..#",
				"####");
		// B1F's ladder leads up to 1F's (2, 1), whose warp leads to B1F's other cell: up only, so it keeps its fade.
		ExportMap bottom = map("C_B1F", warps(warp(1, 1, "C_1F", 1), warp(3, 2, "C_1F", 1)),
				"######",
				"#L...#",
				"#..L.#",
				"######");
		CaveRooms.Plan plan = layOut(Map.of("C_1F", 0, "C_1FB", 0, "C_B1F", 1), top, side, bottom);
		assertEquals(1, plan.shafts().size(), "1F's ladder down to B1F's (3, 2), which leads back up to it");
		assertTrue(plan.shafts().get(0).up());
		assertEquals(Map.of("one_way_up", 1, "same_floor", 2), plan.fades(), "B1F's other ladder, and the warps within 1F's floor");
	}

	@Test
	void aHoleOverTwoFloorsKeepsItsFadeWhenARoomBetweenIsInTheWay() {
		ExportMap top = map("C_1F", warps(warp(2, 1, "C_B1F", 1), warp(3, 2, "C_B2F", 1)),
				"######",
				"#.L..#",
				"#..H.#",
				"######");
		ExportMap middle = map("C_B1F", warps(warp(2, 1, "C_1F", 1)),
				"######",
				"#.L..#",
				"#....#",
				"######");
		ExportMap bottom = map("C_B2F", warps(warp(3, 2, "C_1F", 2)),
				"######",
				"#....#",
				"#....#",
				"######");
		CaveRooms.Plan plan = layOut(Map.of("C_1F", 0, "C_B1F", 1, "C_B2F", 2), top, middle, bottom);
		assertEquals(1, plan.shafts().size(), "the ladder only");
		assertEquals(Map.of("crosses_floor", 1), plan.fades(), "B1F's room lies under the hole");
	}
}
