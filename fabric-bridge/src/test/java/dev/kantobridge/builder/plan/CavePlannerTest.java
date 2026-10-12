package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportMap;
import dev.kantobridge.builder.zone.ZoneBlock;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.WorldIndex;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The cave area (docs/specs/CAVES_3D_PLAN.md §3.3, H3): which maps go there, how a complex's floors stack and line up,
 * how complexes are packed, and what the synthetic cave's build looks like (rock fill, habitat blocks).
 */
class CavePlannerTest {
	private static WorldPlan plan;

	@BeforeAll
	static void plan() throws Exception {
		plan = WorldPlanner.plan(ExportBundle.open(TestData.syntheticBundle()),
				new WorldPlanner.Options(null, TestData.ORIGIN_X, TestData.ORIGIN_Z, TestData.MIN_BUILD_HEIGHT));
	}

	private static ExportMap map(String id, int index, String tileset, int w, int h, List<ExportMap.Warp> warps, ExportMap.Parent parent) {
		int[] blocks = new int[w * h];
		int[][] cells = new int[h * 2][w * 2];
		return new ExportMap(id, index, tileset, w, h, blocks, 0, false, false, Map.of(), warps, List.of(), List.of(), cells, parent, null, false,
				"PAL");
	}

	private static ExportMap.Warp warp(int x, int y, String to, int number) {
		return new ExportMap.Warp(x, y, to, number);
	}

	@Test
	void floorsComeFromTheirIds() {
		assertEquals(0, CavePlanner.floor("MT_MOON_1F"));
		assertEquals(-1, CavePlanner.floor("MT_MOON_B1F"));
		assertEquals(-2, CavePlanner.floor("MT_MOON_B2F"));
		assertEquals(2, CavePlanner.floor("VICTORY_ROAD_3F"));
		assertEquals(0, CavePlanner.floor("DIGLETTS_CAVE"));
		assertEquals(0, CavePlanner.floor("SEAFOAM_ISLANDS_B1F_2"), "a suffix after the floor isn't one");
	}

	@Test
	void cavesAndTheRoomsTheirDoorsLeadTo() {
		Map<String, ExportMap> all = new LinkedHashMap<>();
		all.put("TOWN", new ExportMap("TOWN", 0, "OVERWORLD", 2, 2, new int[4], 0, true, true, Map.of(), List.of(), List.of(), List.of(),
				new int[4][4], null, null, false, "PAL"));
		all.put("CAVE_1F", map("CAVE_1F", 10, "CAVERN", 2, 2, List.of(), new ExportMap.Parent("TOWN", 1, 0, 0)));
		all.put("SHRINE", map("SHRINE", 11, "INTERIOR", 2, 2, List.of(), new ExportMap.Parent("CAVE_1F", 1, 0, 0)));
		all.put("HOUSE", map("HOUSE", 5, "INTERIOR", 2, 2, List.of(), new ExportMap.Parent("TOWN", 2, 0, 0)));
		Set<String> caves = CavePlanner.caves(all, List.of("TOWN", "HOUSE", "SHRINE", "CAVE_1F"), "CAVERN");
		assertEquals(List.of("CAVE_1F", "SHRINE"), new ArrayList<>(caves), "a cave, then a room whose door is in it");
		assertTrue(CavePlanner.caves(all, List.of("TOWN", "HOUSE"), "CAVERN").isEmpty());
	}

	@Test
	void warpsAndDoorsJoinComplexes() {
		Map<String, ExportMap> all = new LinkedHashMap<>();
		all.put("A_1F", map("A_1F", 1, "CAVERN", 2, 2, List.of(warp(0, 0, "A_B1F", 1)), new ExportMap.Parent("TOWN", 1, 0, 0)));
		all.put("A_B1F", map("A_B1F", 2, "CAVERN", 2, 2, List.of(warp(0, 0, "A_1F", 1)), new ExportMap.Parent("A_1F", 1, 0, 0)));
		all.put("B_1F", map("B_1F", 3, "CAVERN", 2, 2, List.of(), new ExportMap.Parent("TOWN", 2, 0, 0)));
		all.put("B_ROOM", map("B_ROOM", 4, "INTERIOR", 2, 2, List.of(), new ExportMap.Parent("B_1F", 1, 0, 0)));
		List<List<String>> complexes = CavePlanner.complexes(all, new java.util.LinkedHashSet<>(List.of("A_1F", "A_B1F", "B_1F", "B_ROOM")));
		assertEquals(List.of(List.of("A_1F", "A_B1F"), List.of("B_1F", "B_ROOM")), complexes);
	}

	@Test
	void floorsStackAndLineUpTheirFirstLadder() {
		// 1F's ladder at (3, 2) leads to B1F's at (1, 1); B2F hangs off B1F's ladder (2, 3) to its (0, 0).
		Map<String, ExportMap> all = new LinkedHashMap<>();
		all.put("C_1F", map("C_1F", 1, "CAVERN", 3, 3, List.of(warp(0, 5, "LAST_MAP", 1), warp(3, 2, "C_B1F", 1)),
				new ExportMap.Parent("TOWN", 1, 0, 0)));
		all.put("C_B1F", map("C_B1F", 2, "CAVERN", 3, 3, List.of(warp(1, 1, "C_1F", 2), warp(2, 3, "C_B2F", 1)),
				new ExportMap.Parent("C_1F", 2, 3, 2)));
		all.put("C_B2F", map("C_B2F", 3, "CAVERN", 2, 2, List.of(warp(0, 0, "C_B1F", 2)), new ExportMap.Parent("C_B1F", 2, 2, 3)));
		Warnings warnings = new Warnings();
		CavePlanner.Result result = CavePlanner.place(all, new java.util.LinkedHashSet<>(all.keySet()), null, 0, 0, warnings);
		Map<String, MapPlacement> by = new LinkedHashMap<>();
		for (MapPlacement p : result.placements()) {
			by.put(p.id(), p);
		}
		MapPlacement f1 = by.get("C_1F");
		MapPlacement b1 = by.get("C_B1F");
		MapPlacement b2 = by.get("C_B2F");
		assertEquals(CavePlanner.TOP_FEET, f1.y());
		assertEquals(CavePlanner.TOP_FEET - CavePlanner.FLOOR_STEP, b1.y());
		assertEquals(CavePlanner.TOP_FEET - 2 * CavePlanner.FLOOR_STEP, b2.y());
		assertEquals(List.of(1, 2, 3), List.of(f1.layer(), b1.layer(), b2.layer()));
		// The ladders' two ends share a column.
		assertEquals(f1.x() + 3 * 2, b1.x() + 1 * 2);
		assertEquals(f1.z() + 2 * 2, b1.z() + 1 * 2);
		assertEquals(b1.x() + 2 * 2, b2.x());
		assertEquals(b1.z() + 3 * 2, b2.z());
		assertTrue(by.values().stream().allMatch(p -> p.biome().equals(WorldPlan.BIOME_CAVE_3D) && p.kind() == MapPlacement.Kind.INTERIOR));
		assertEquals(1, result.complexes().size());
		// The area lies whole chunks north of the dungeon region, from the origin's chunk (no Kanto box here).
		int[] area = result.area();
		assertEquals(0, area[0] % 16);
		assertEquals(0, Math.floorMod(area[1], 16));
		assertTrue(area[3] <= dev.kantobridge.dungeon.DungeonSlots.region(null, 0, 0).z() - CavePlanner.GAP);
		for (MapPlacement p : by.values()) {
			assertTrue(p.x() >= area[0] && p.x() + p.blockWidth() <= area[2] && p.z() >= area[1] && p.z() + p.blockDepth() <= area[3], p.id());
		}
	}

	@Test
	void aFloorThatWouldOverlapItsLevelMovesAside() {
		// Two rooms on one level (no floor in their ids), both reached by ladders from 1F that would put them on one spot.
		Map<String, ExportMap> all = new LinkedHashMap<>();
		all.put("D_1F", map("D_1F", 1, "CAVERN", 2, 2, List.of(warp(0, 0, "D_B1F", 1), warp(1, 1, "D_B1F_ANNEX", 1)),
				new ExportMap.Parent("TOWN", 1, 0, 0)));
		all.put("D_B1F", map("D_B1F", 2, "CAVERN", 2, 2, List.of(warp(0, 0, "D_1F", 1)), new ExportMap.Parent("D_1F", 1, 0, 0)));
		all.put("D_B1F_ANNEX", map("D_B1F_ANNEX", 3, "CAVERN", 2, 2, List.of(warp(1, 1, "D_1F", 2)), new ExportMap.Parent("D_1F", 2, 1, 1)));
		Warnings warnings = new Warnings();
		List<MapPlacement> placed = CavePlanner.place(all, new java.util.LinkedHashSet<>(all.keySet()), null, 0, 0, warnings).placements();
		MapPlacement room = placed.stream().filter(p -> p.id().equals("D_B1F")).findFirst().orElseThrow();
		MapPlacement annex = placed.stream().filter(p -> p.id().equals("D_B1F_ANNEX")).findFirst().orElseThrow();
		assertEquals(0, CavePlanner.floor("D_B1F_ANNEX"), "its id names no floor, so it's on 1F's level, above B1F");
		MapPlacement first = placed.stream().filter(p -> p.id().equals("D_1F")).findFirst().orElseThrow();
		assertEquals(first.y(), annex.y());
		assertTrue(annex.x() >= first.x() + first.blockWidth() + CavePlanner.FLOOR_GAP || annex.x() + annex.blockWidth() + CavePlanner.FLOOR_GAP <= first.x(),
				"the annex moved aside of the floor on its level");
		assertEquals(1, warnings.noteCount("cave_floor_moved_aside"));
		assertEquals(first.y() - CavePlanner.FLOOR_STEP, room.y());
	}

	@Test
	void complexesFillRowsOfSlots() {
		Map<String, ExportMap> all = new LinkedHashMap<>();
		for (int i = 0; i < 40; i++) {
			String id = "CAVE" + i + "_1F";
			all.put(id, map(id, i, "CAVERN", 20, 20, List.of(), new ExportMap.Parent("TOWN", i + 1, 0, 0)));
		}
		CavePlanner.Result result = CavePlanner.place(all, new java.util.LinkedHashSet<>(all.keySet()), null, 0, 0, new Warnings());
		assertEquals(40, result.complexes().size());
		List<MapPlacement> placed = result.placements();
		for (int i = 0; i < placed.size(); i++) {
			for (int j = i + 1; j < placed.size(); j++) {
				MapPlacement a = placed.get(i);
				MapPlacement b = placed.get(j);
				boolean apart = a.x() + a.blockWidth() + CavePlanner.SLOT_GAP <= b.x() || b.x() + b.blockWidth() + CavePlanner.SLOT_GAP <= a.x()
						|| a.z() + a.blockDepth() + CavePlanner.SLOT_GAP <= b.z() || b.z() + b.blockDepth() + CavePlanner.SLOT_GAP <= a.z();
				assertTrue(apart, a.id() + " and " + b.id() + " are slots apart");
			}
		}
		int[] area = result.area();
		assertTrue(area[2] - area[0] <= CavePlanner.ROW_WIDTH + 40 * 2 + 2 * CavePlanner.MARGIN, "rows stop growing east near the row width");
		assertTrue(area[3] - area[1] > 40 * 2 * 2, "so there are several rows");
	}

	@Test
	void theSyntheticCaveIsFilledAboveItsRock() {
		PlacedMap cave = plan.map("TEST_CAVE_1F");
		MapPlacement p = cave.placement();
		ChunkCodesView view = new ChunkCodesView(plan);
		int rockColumns = 0;
		for (int ty = 0; ty < p.blockDepth(); ty++) {
			for (int tx = 0; tx < p.blockWidth(); tx++) {
				if (cave.layout() != null && cave.layout().pieceAt(tx / 2, ty / 2) == null) {
					// Rock far from every room: no room draws it (H5)
					continue;
				}
				MapModel.Kind kind = cave.model().kind(tx / 2, ty / 2);
				int[] at = WorldPlan.cellOrigin(p, cave.layout(), tx / 2, ty / 2);
				int x = at[0] + tx % 2;
				int z = at[2] + ty % 2;
				if (kind == MapModel.Kind.SOLID) {
					rockColumns++;
					for (int k = 0; k < WorldPlan.CEILING_ABOVE_FEET; k++) {
						int code = view.code(x, at[1] + k, z);
						int kindAt = BlockCode.kind(code);
						boolean solid = kindAt == BlockCode.KIND_FULL || kindAt == BlockCode.KIND_SHAPE || code == BlockCode.vanilla(ZoneBlock.BARRIER)
								|| (kindAt == BlockCode.KIND_DECOR && (BlockCode.value(code) & 4) != 0);
						assertTrue(solid, "nothing to stand on over rock at " + tx + "," + ty + " feet + " + k);
					}
				} else if (kind == MapModel.Kind.GROUND) {
					for (int k = 0; k < WorldPlan.CEILING_ABOVE_FEET; k++) {
						int code = view.code(x, at[1] + k, z);
						assertFalse(code == BlockCode.vanilla(ZoneBlock.BARRIER), "no barrier over a floor");
					}
				}
			}
		}
		assertTrue(rockColumns > 0);
	}

	@Test
	void caveHabitatsReachOnlyTheirOwnFloor() {
		List<WorldIndex.Habitat> cave = plan.habitats().stream().filter(h -> h.map().startsWith("TEST_CAVE")).toList();
		assertFalse(cave.isEmpty());
		for (WorldIndex.Habitat habitat : cave) {
			assertEquals(HabitatPlanner.CAVE_RANGE, habitat.range());
			String other = habitat.map().equals("TEST_CAVE_1F") ? "TEST_CAVE_B1F" : "TEST_CAVE_1F";
			MapPlacement o = plan.index().map(other);
			// No floor block of the other floor within the block's reach (I3), raised floors (H4) included.
			for (int cz = 0; cz < o.h(); cz++) {
				for (int cx = 0; cx < o.w(); cx++) {
					int[] at = WorldPlan.cellOrigin(o, plan.index().layout(other), cx, cz);
					for (int b = 0; b < 4; b++) {
						long dx = at[0] + b % 2 - habitat.x();
						long dy = at[1] - 1 - habitat.y();
						long dz = at[2] + b / 2 - habitat.z();
						assertTrue(dx * dx + dy * dy + dz * dz > (long) habitat.range() * habitat.range(), habitat.map() + " reaches " + other);
					}
				}
			}
		}
		assertNotNull(plan.index().layout("TEST_CAVE_1F"), "1F's plateau is raised (H4)");
		dev.kantobridge.world.MapLayout b1f = plan.index().layout("TEST_CAVE_B1F");
		assertEquals(2, b1f.pieces().size(), "B1F's two rooms lie apart (H5)");
		for (int cz = 0; cz < b1f.h(); cz++) {
			for (int cx = 0; cx < b1f.w(); cx++) {
				assertEquals(0, b1f.level(cx, cz), "B1F has no raised ground");
			}
		}
	}

	@Test
	void everyCaveFloorBlockIsInAHabitatsReach() {
		// The raised floor's cells too, from habitat blocks that stay under the map's floor (H4).
		for (PlacedMap map : plan.maps()) {
			if (!map.id().startsWith("TEST_CAVE")) {
				continue;
			}
			List<WorldIndex.Habitat> own = plan.habitats().stream().filter(h -> h.map().equals(map.id())).toList();
			MapPlacement p = map.placement();
			int raised = 0;
			for (int cz = 0; cz < p.h(); cz++) {
				for (int cx = 0; cx < p.w(); cx++) {
					if (map.model().kind(cx, cz) != MapModel.Kind.GROUND) {
						continue;
					}
					raised += map.level(cx, cz) > 0 ? 1 : 0;
					int[] at = WorldPlan.cellOrigin(p, map.layout(), cx, cz);
					for (int b = 0; b < 4; b++) {
						long x = at[0] + b % 2;
						long y = at[1] - 1;
						long z = at[2] + b / 2;
						assertTrue(own.stream().anyMatch(h -> (x - h.x()) * (x - h.x()) + (y - h.y()) * (y - h.y()) + (z - h.z()) * (z - h.z())
								<= (long) h.range() * h.range()), map.id() + " cell " + cx + "," + cz + " has no habitat in reach");
					}
				}
			}
			assertEquals(map.id().equals("TEST_CAVE_1F"), raised > 0);
		}
		for (WorldIndex.Habitat habitat : plan.habitats()) {
			MapPlacement p = plan.index().map(habitat.map());
			assertEquals(p.y() - HabitatPlanner.BELOW_FEET, habitat.y(), "under the map's floor, outside its volume");
		}
	}
}
