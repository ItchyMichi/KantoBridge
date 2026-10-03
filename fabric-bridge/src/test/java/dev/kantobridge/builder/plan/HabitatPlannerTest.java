package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportEncounters;
import dev.kantobridge.builder.export.ExportField;
import dev.kantobridge.builder.export.ExportMap;
import dev.kantobridge.builder.export.ExportTileset;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.WorldIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Where habitat blocks go: the synthetic town's grass patch, the greedy cover of large areas, the indoor encounter rule
 * (every floor cell, but not in the FOREST tileset), and the maps that get none (no table, rate 0, the Safari Zone).
 * Made-up maps and tables only.
 */
class HabitatPlannerTest {
	private static final ExportEncounters.Table TABLE = new ExportEncounters.Table(25,
			List.of(new ExportEncounters.Slot(16, 2), new ExportEncounters.Slot(19, 3)));
	private static final List<Integer> BUCKETS = List.of(51, 102, 141, 166, 191, 216, 229, 242, 253, 256);

	@Test
	void theSyntheticTownGetsOneBlockUnderItsGrass() throws Exception {
		ExportBundle bundle = ExportBundle.open(TestData.syntheticBundle());
		WorldPlan plan = WorldPlanner.plan(bundle, new WorldPlanner.Options(null, TestData.ORIGIN_X, TestData.ORIGIN_Z, TestData.MIN_BUILD_HEIGHT));
		List<WorldIndex.Habitat> habitats = plan.habitats();
		assertEquals(1, habitats.size(), "a 4×4-cell patch fits in one sphere");
		WorldIndex.Habitat habitat = habitats.get(0);
		assertEquals("TEST_TOWN", habitat.map());
		assertEquals("kantobridge:test_town", habitat.pool());
		assertEquals(61, habitat.y(), "2 blocks under the ground block at Y 63");
		// Near the patch's middle (cells 14-17 × 4-7, blocks 28-35 × 8-15 of the town).
		assertEquals(TestData.ORIGIN_X + 32, habitat.x());
		assertEquals(TestData.ORIGIN_Z + 12, habitat.z());
		assertEquals(Set.of("kantobridge:test_town"), plan.pools().keySet(), "one pool per wild map of the export");
		assertEquals(1, plan.habitatsIn(habitat.x() >> 4, habitat.z() >> 4).size());
		assertEquals(0, plan.habitatsIn((habitat.x() >> 4) + 1, habitat.z() >> 4).size());
	}

	@Test
	void everyFloorBlockIsWithinReach() {
		// A 60×50-cell field of grass, with a few holes: every floor block of every grass cell must be within range.
		int w = 30;
		int h = 25;
		int[][] cells = new int[h * 2][w * 2];
		for (int cy = 0; cy < h * 2; cy++) {
			for (int cx = 0; cx < w * 2; cx++) {
				cells[cy][cx] = (cx + cy) % 7 == 0 ? CellFlags.WALKABLE : CellFlags.WALKABLE | CellFlags.GRASS;
			}
		}
		PlacedMap map = placed("TEST_FIELD", 3, "TEST", w, h, cells, 100, 64, -40);
		List<WorldIndex.Habitat> habitats = HabitatPlanner.plan(List.of(map), encounters(Map.of("TEST_FIELD", TABLE)), ExportField.empty(),
				new Warnings());
		assertCovered(map, habitats, true);
		// The greedy cover stays within about twice the area divided by one sphere's footprint (about 600 blocks).
		int floor = 0;
		for (int[] row : cells) {
			for (int cell : row) {
				floor += CellFlags.has(cell, CellFlags.GRASS) ? 4 : 0;
			}
		}
		assertTrue(habitats.size() <= floor / 200 + 2, "too many habitat blocks: " + habitats.size() + " for " + floor + " floor blocks");
		for (WorldIndex.Habitat habitat : habitats) {
			assertEquals(64 - HabitatPlanner.BELOW_FEET, habitat.y());
			assertEquals("kantobridge:test_field", habitat.pool());
		}
	}

	@Test
	void indoorMapsCoverEveryFloorCellExceptInTheForest() {
		int[][] cells = new int[8][8];
		for (int[] row : cells) {
			java.util.Arrays.fill(row, CellFlags.WALKABLE);
		}
		cells[3][3] = CellFlags.WALKABLE | CellFlags.GRASS;
		cells[0][0] = 0;
		ExportField field = new ExportField(new ExportField.Indoor(37, "FOREST"), Set.of());
		ExportEncounters tables = encounters(Map.of("TEST_CAVE", TABLE, "TEST_WOOD", TABLE, "TEST_ROUTE", TABLE));

		PlacedMap cave = placed("TEST_CAVE", 40, "CAVERN", 4, 4, cells, 0, 42, 0);
		assertEquals(63, HabitatPlanner.wildCells(cave.model(), true).size(), "every walkable cell of a cave");
		List<WorldIndex.Habitat> inCave = HabitatPlanner.plan(List.of(cave), tables, field, new Warnings());
		assertEquals(1, inCave.size());
		assertEquals(42 - HabitatPlanner.BELOW_FEET, inCave.get(0).y(), "under the cave's own floor");
		assertCovered(cave, inCave, false);

		// The forest tileset is excluded from the indoor rule (Viridian Forest): only its grass.
		PlacedMap forest = placed("TEST_WOOD", 50, "FOREST", 4, 4, cells, 200, 42, 0);
		assertTrue(field.indoorEncounters(50, "CAVERN") && !field.indoorEncounters(50, "FOREST") && !field.indoorEncounters(36, "CAVERN"));
		List<WorldIndex.Habitat> inForest = HabitatPlanner.plan(List.of(forest), tables, field, new Warnings());
		assertEquals(1, inForest.size());
		assertCovered(forest, inForest, true);

		// An outdoor route (index below the first indoor map) only on grass, too.
		PlacedMap route = placed("TEST_ROUTE", 12, "OVERWORLD", 4, 4, cells, 400, 64, 0);
		assertEquals(1, HabitatPlanner.wildCells(route.model(), field.indoorEncounters(12, "OVERWORLD")).size());
	}

	@Test
	void mapsWithoutWildPokemonGetNone() {
		int[][] cells = new int[4][4];
		for (int[] row : cells) {
			java.util.Arrays.fill(row, CellFlags.WALKABLE | CellFlags.GRASS);
		}
		ExportEncounters tables = encounters(Map.of("TEST_QUIET", new ExportEncounters.Table(0, TABLE.slots()), "SAFARI_ZONE_TEST", TABLE,
				"TEST_PARK", TABLE));
		ExportField field = new ExportField(null, Set.of("TEST_PARK"));
		List<PlacedMap> maps = List.of(placed("TEST_QUIET", 1, "OVERWORLD", 2, 2, cells, 0, 64, 0),
				placed("SAFARI_ZONE_TEST", 2, "FOREST", 2, 2, cells, 100, 64, 0), placed("TEST_PARK", 3, "FOREST", 2, 2, cells, 200, 64, 0),
				placed("TEST_NO_TABLE", 4, "OVERWORLD", 2, 2, cells, 300, 64, 0));
		assertTrue(HabitatPlanner.plan(maps, tables, field, new Warnings()).isEmpty(), "rate 0, Safari (by table or name), no table");
		assertTrue(HabitatPlanner.wildMaps(tables, field).isEmpty());
		assertFalse(HabitatPlanner.wild("TEST_PARK", tables, field));
		assertTrue(HabitatPlanner.safari("SAFARI_ZONE_EAST", ExportField.empty()));
		// The Pokémon Tower's encounters stay in the hidden game for now (ghosts until the Silph Scope).
		ExportEncounters tower = encounters(Map.of("POKEMON_TOWER_3F", TABLE));
		assertFalse(HabitatPlanner.wild("POKEMON_TOWER_3F", tower, ExportField.empty()));
		assertTrue(HabitatPlanner.nativeEncounters("POKEMON_TOWER_7F", ExportField.empty()));
	}

	@Test
	void reachAtTheFloor() {
		assertEquals(14, HabitatPlanner.RANGE);
		assertEquals(3, HabitatPlanner.BELOW_FEET);
		assertEquals(14 * 14 - 2 * 2, HabitatPlanner.REACH2, "Cobblemon measures from the floor block, 2 above the habitat block");
		assertEquals("kantobridge:route_1", HabitatPlanner.poolId("ROUTE_1"));
	}

	// ---- helpers ----

	static ExportEncounters encounters(Map<String, ExportEncounters.Table> grass) {
		return new ExportEncounters(BUCKETS, grass);
	}

	/** A made-up map with the given cell flags, placed with its cell (0, 0) at block (x, z) and feet level y. */
	static PlacedMap placed(String id, int index, String tileset, int width, int height, int[][] cells, int x, int y, int z) {
		ExportMap map = new ExportMap(id, index, tileset, width, height, new int[width * height], 0, true, true, Map.of(), List.of(), List.of(),
				List.of(), cells, null, null, false, "TEST_PALETTE");
		int[][] blocks = {new int[16]};
		ExportTileset tiles = new ExportTileset(tileset, "tilesets.test", 16, 16, blocks, "TILEANIM_NONE", Set.of(0), Set.of(), null, Set.of(),
				Set.of(), Set.of(), Set.of(), Set.of());
		MapPlacement placement = new MapPlacement(id, x, y, z, width * 2, height * 2, 0, MapPlacement.Kind.SURFACE, "TEST_PALETTE",
				WorldPlan.BIOME_OUTDOOR, false, null);
		return new PlacedMap(placement, MapModel.build(map, tiles, List.of(), false, new Warnings()));
	}

	/** Every floor block of every wild cell lies within reach of a habitat block. */
	static void assertCovered(PlacedMap map, List<WorldIndex.Habitat> habitats, boolean grassOnly) {
		MapModel model = map.model();
		MapPlacement p = map.placement();
		List<String> uncovered = new ArrayList<>();
		for (int cy = 0; cy < model.cellHeight(); cy++) {
			for (int cx = 0; cx < model.cellWidth(); cx++) {
				boolean wild = model.kind(cx, cy) == MapModel.Kind.GROUND && (!grassOnly || CellFlags.has(model.flags(cx, cy), CellFlags.GRASS));
				if (!wild) {
					continue;
				}
				for (int d = 0; d < 4; d++) {
					int bx = p.x() + cx * 2 + (d & 1);
					int bz = p.z() + cy * 2 + (d >> 1);
					int floorY = p.y() - 1;
					boolean reached = false;
					for (WorldIndex.Habitat habitat : habitats) {
						long dx = bx - habitat.x();
						long dy = floorY - habitat.y();
						long dz = bz - habitat.z();
						reached |= dx * dx + dy * dy + dz * dz <= (long) HabitatPlanner.RANGE * HabitatPlanner.RANGE;
					}
					if (!reached) {
						uncovered.add(cx + "," + cy);
					}
				}
			}
		}
		assertTrue(uncovered.isEmpty(), "uncovered cells: " + uncovered);
	}
}
