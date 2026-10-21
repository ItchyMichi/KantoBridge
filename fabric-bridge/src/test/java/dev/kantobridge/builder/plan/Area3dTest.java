package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The 3D area's maps and floors for any game (docs/specs/HEIGHTS_ALL_GAMES_PLAN.md D1): which interiors go
 * ({@link Area3d}) and each map's floor rank from its id or its ladders ({@link CavePlanner#ranks}), on made-up maps.
 */
class Area3dTest {
	/** A 4 × 4 map of floor with its warps, each {x, y, to, kind, way}; its door from {@code parent}, or none. */
	static CaveMap map(String id, @Nullable String parent, Object[]... warps) {
		BitSet walks = new BitSet(16);
		walks.set(0, 16);
		List<CaveMap.Warp> list = new ArrayList<>();
		for (Object[] w : warps) {
			list.add(new CaveMap.Warp((int) w[0], (int) w[1], (String) w[2], 1, 1, (CaveMap.Kind) w[3], (int) w[4]));
		}
		return new CaveMap(id, 0, 4, 4, walks, list, parent == null ? null : new CaveMap.Door(parent, 0, 0, 1, 1), "PAL", false);
	}

	static Object[] warp(String to, CaveMap.Kind kind, int way) {
		return new Object[] {1, 1, to, kind, way};
	}

	private static Map<String, CaveMap> maps(CaveMap... list) {
		Map<String, CaveMap> out = new LinkedHashMap<>();
		for (CaveMap m : list) {
			out.put(m.id(), m);
		}
		return out;
	}

	@Test
	void cavesAndEveryInteriorALadderOrHoleJoinsGo() {
		Map<String, CaveMap> all = maps(
				map("CAVE_1F", "ROUTE", warp("CAVE_B1F", CaveMap.Kind.LADDER, -1)),
				map("CAVE_B1F", "CAVE_1F", warp("CAVE_1F", CaveMap.Kind.LADDER, 1)),
				map("CAVE_SHOP", "CAVE_1F"),
				map("TOWER_1F", "TOWN", warp("TOWER_2F", CaveMap.Kind.STAIRS, 1)),
				map("TOWER_2F", "TOWER_1F", warp("TOWER_1F", CaveMap.Kind.STAIRS, -1), warp("TOWER_1F", CaveMap.Kind.HOLE, -1)),
				map("GYM", "TOWN", warp("GYM", CaveMap.Kind.HOLE, -1)),
				map("HOUSE", "TOWN"),
				map("PARK", "TOWN", warp("PARK_LOWER", CaveMap.Kind.LADDER, -1)),
				map("PARK_LOWER", "PARK"));
		Set<String> area = Area3d.select(all, List.of("CAVE_1F", "CAVE_B1F", "PARK"), Set.of("PARK"));
		assertEquals(List.of("CAVE_1F", "CAVE_B1F", "CAVE_SHOP", "TOWER_1F", "TOWER_2F"), List.copyOf(area),
				"the caves, a room whose door is in one, both floors a hole joins (stairs alone wouldn't); never an outdoor map, nor"
						+ " a room an outdoor map's ladder leads to (its entrance); a hole back into its own map (a gym's trap) doesn't count");
	}

	@Test
	void floorsFromTheIdsThenTheLadders() {
		assertEquals(0, CavePlanner.namedFloor("EM_METEOR_FALLS_1F_1R"));
		assertEquals(-1, CavePlanner.namedFloor("EM_METEOR_FALLS_B1F_2R"));
		assertEquals(-2, CavePlanner.namedFloor("G2_ICE_PATH_B2F_MAHOGANY_SIDE"));
		assertEquals(1, CavePlanner.namedFloor("G2_MOUNT_MORTAR_2F_INSIDE"));
		assertEquals(null, CavePlanner.namedFloor("SEAFOAM_ISLANDS_B1F_2"), "any other suffix isn't a floor");
		assertEquals(null, CavePlanner.namedFloor("EM_NAVEL_ROCK_DOWN03"));
		// No id names a floor: the entrance is the top, each ladder down a floor lower, a ladder up one higher.
		Map<String, CaveMap> rock = maps(
				map("ROCK_ENTRANCE", "SEA", warp("ROCK_DOWN1", CaveMap.Kind.LADDER, -1)),
				map("ROCK_DOWN1", "ROCK_ENTRANCE", warp("ROCK_ENTRANCE", CaveMap.Kind.LADDER, 1), warp("ROCK_DOWN2", CaveMap.Kind.LADDER, -1)),
				map("ROCK_DOWN2", "ROCK_DOWN1", warp("ROCK_DOWN1", CaveMap.Kind.LADDER, 1), warp("ROCK_SIDE", CaveMap.Kind.OTHER, 0)),
				map("ROCK_SIDE", "ROCK_DOWN2"));
		assertEquals(Map.of("ROCK_ENTRANCE", 0, "ROCK_DOWN1", 1, "ROCK_DOWN2", 2, "ROCK_SIDE", 2),
				CavePlanner.ranks(rock, List.copyOf(rock.keySet()), "ROCK_ENTRANCE"), "a room joined by a plain warp is on its floor");
		// Named floors first; an unnamed map takes its floor from them.
		Map<String, CaveMap> mixed = maps(
				map("C_1F", "TOWN", warp("C_B1F", CaveMap.Kind.LADDER, -1)),
				map("C_B1F", "C_1F", warp("C_PIT", CaveMap.Kind.HOLE, -1)),
				map("C_PIT", "C_B1F"));
		assertEquals(Map.of("C_1F", 0, "C_B1F", 1, "C_PIT", 2), CavePlanner.ranks(mixed, List.copyOf(mixed.keySet()), "C_1F"));
	}
}
