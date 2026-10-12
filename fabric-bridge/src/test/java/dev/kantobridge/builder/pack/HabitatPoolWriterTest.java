package dev.kantobridge.builder.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportEncounters;
import dev.kantobridge.builder.export.ExportField;
import dev.kantobridge.proto.ProtoJson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Habitat pools from Gen 1 grass tables (docs/PLAN.md §4.5 "Datapack writer"): one entry per slot, bucket common, weight
 * = the slot's percentage, the single level, Cobblemon's species id by dex number, no phases; and the datapack that holds
 * them. Uses the synthetic bundle's made-up table.
 */
class HabitatPoolWriterTest {
	@TempDir
	Path temp;

	@Test
	void slotPercentagesFromTheThresholds() {
		ExportEncounters encounters = new ExportEncounters(List.of(51, 102, 141, 166, 191, 216, 229, 242, 253, 256), Map.of());
		double[] expected = {19.921875, 19.921875, 15.234375, 9.765625, 9.765625, 9.765625, 5.078125, 5.078125, 4.296875, 1.171875};
		double sum = 0;
		for (int slot = 0; slot < 10; slot++) {
			assertEquals(expected[slot], encounters.percent(slot), 0.0, "slot " + slot);
			sum += encounters.percent(slot);
		}
		assertEquals(100.0, sum, 1e-9, "the ten slots share the whole roll");
	}

	@Test
	void thePoolOfTheSyntheticTown() throws Exception {
		ExportBundle bundle = ExportBundle.open(TestData.syntheticBundle());
		ExportEncounters encounters = bundle.encounters();
		Map<String, String> pools = HabitatPoolWriter.pools(encounters, bundle.field());
		assertEquals(Set.of("kantobridge:test_town", "kantobridge:test_cave_1f", "kantobridge:test_cave_b1f"), pools.keySet(),
				"the town's grass and the cave's floors (CAVES_3D_PLAN §3.3)");
		JsonObject pool = ProtoJson.parseObject(pools.get("kantobridge:test_town"));
		assertEquals(Set.of("name", "spawns"), pool.keySet());
		JsonArray spawns = pool.getAsJsonArray("spawns");
		ExportEncounters.Table table = encounters.grass().get("TEST_TOWN");
		assertEquals(table.slots().size(), spawns.size(), "one entry per Gen 1 slot");
		double sum = 0;
		for (int slot = 0; slot < spawns.size(); slot++) {
			JsonObject spawn = spawns.get(slot).getAsJsonObject();
			ExportEncounters.Slot gen1 = table.slots().get(slot);
			assertEquals(Set.of("species", "spawnablePositionType", "bucket", "weight", "levelRange"), spawn.keySet(), "no phases");
			assertEquals(gen1.dex() == 16 ? "cobblemon:pidgey" : "cobblemon:rattata", spawn.get("species").getAsString());
			assertEquals("grounded", spawn.get("spawnablePositionType").getAsString());
			assertEquals("common", spawn.get("bucket").getAsString());
			assertEquals(encounters.percent(slot), spawn.get("weight").getAsDouble(), 0.0);
			assertEquals(Integer.toString(gen1.level()), spawn.get("levelRange").getAsString(), "a single level");
			sum += spawn.get("weight").getAsDouble();
		}
		assertEquals(100.0, sum, 1e-9);
		// Canonical text: the same table always gives the same file.
		assertEquals(ProtoJson.write(pool), pools.get("kantobridge:test_town"));
	}

	@Test
	void safariTowerAndRateZeroTablesGetNoPool() {
		ExportEncounters.Table table = new ExportEncounters.Table(20, List.of(new ExportEncounters.Slot(1, 5)));
		ExportEncounters encounters = new ExportEncounters(List.of(51, 102, 141, 166, 191, 216, 229, 242, 253, 256),
				Map.of("ROUTE_TEST", table, "SAFARI_ZONE_TEST", table, "POKEMON_TOWER_3F", table, "TEST_SLEEPY",
						new ExportEncounters.Table(0, table.slots())));
		assertEquals(Set.of("kantobridge:route_test"), HabitatPoolWriter.pools(encounters, ExportField.empty()).keySet());
	}

	@Test
	void theDatapackHoldsThePools() throws Exception {
		Map<String, String> pools = Map.of("kantobridge:route_test", "{\"name\":\"x\",\"spawns\":[]}");
		PackFiles files = DatapackWriter.generate(pools);
		assertEquals(Set.of("pack.mcmeta", "data/kantobridge/habitat_pools/route_test.json"), files.files().keySet());
		Path world = this.temp.resolve("world");
		DatapackWriter.write(world, pools);
		Path file = world.resolve("datapacks/kantobridge_generated/data/kantobridge/habitat_pools/route_test.json");
		assertEquals(pools.get("kantobridge:route_test"), Files.readString(file, StandardCharsets.UTF_8));
		// A rebuild replaces the pack as a whole: a pool the new build no longer has is gone.
		DatapackWriter.write(world, Map.of());
		assertFalse(Files.exists(file));
		assertTrue(Files.isRegularFile(world.resolve("datapacks/kantobridge_generated/pack.mcmeta")));
		assertEquals("data/kantobridge/habitat_pools/viridian_forest.json", HabitatPoolWriter.path("kantobridge:viridian_forest"));
		assertEquals("file/kantobridge_generated", DatapackWriter.PACK_ID);
	}
}
