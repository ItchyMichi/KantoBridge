package dev.kantobridge.builder.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.export.G2Data;
import dev.kantobridge.builder.plan.Warnings;
import dev.kantobridge.proto.ProtoJson;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * MG9 (G2E): a Gen 2 grass table's pool: the times of day merged (J6), weights the slots' chances as the engine rolls them,
 * a time of day whose rate is 0 left out, an export without the chances weighing every slot alike, a number no species
 * has skipped. Made-up tables.
 */
class G2PoolWriterTest {
	private static List<G2Data.Slot> slots(int level, int... dex) {
		return java.util.Arrays.stream(dex).mapToObj(d -> new G2Data.Slot(d, level)).toList();
	}

	@Test
	void theTimesOfDayMergeIntoOnePool() throws Exception {
		G2Data.Grass grass = new G2Data.Grass(Map.of("MORN", 10, "DAY", 10, "NITE", 0),
				Map.of("MORN", slots(2, 16, 16, 19, 19, 161, 161, 163), "DAY", slots(2, 16, 19, 19, 19, 19, 19, 19),
						"NITE", slots(9, 163, 163, 163, 163, 163, 163, 163)));
		G2Data.Encounters encounters = new G2Data.Encounters(Map.of("A_ROUTE", grass), Map.of(), new int[] {30, 60, 80, 90, 95, 99, 100});
		Warnings warnings = new Warnings();
		Map<String, String> pools = G2PoolWriter.pools(encounters, Set.of("A_ROUTE"), "johto_", warnings);
		assertEquals(Set.of("kantobridge:johto_a_route"), pools.keySet());
		JsonArray spawns = ProtoJson.parseObject(pools.get("kantobridge:johto_a_route")).getAsJsonArray("spawns");
		// morning: pidgey 30+30, rattata 20+10, sentret 5+4, hoothoot 1; day: pidgey 30, rattata 70; night's rate is 0
		assertEquals(4, spawns.size());
		assertSpawn(spawns.get(0).getAsJsonObject(), "pidgey", 90);
		assertSpawn(spawns.get(1).getAsJsonObject(), "rattata", 100);
		assertSpawn(spawns.get(2).getAsJsonObject(), "sentret", 9);
		assertSpawn(spawns.get(3).getAsJsonObject(), "hoothoot", 1);
		assertEquals(0, warnings.total());
	}

	@Test
	void withoutTheChancesEverySlotWeighsAlike() {
		G2Data.Grass grass = new G2Data.Grass(Map.of("DAY", 10), Map.of("DAY", slots(3, 16, 16, 19, 0)));
		G2Data.Encounters encounters = new G2Data.Encounters(Map.of("A_ROUTE", grass), Map.of(), null);
		Warnings warnings = new Warnings();
		JsonObject pool = G2PoolWriter.pool("A_ROUTE", grass, encounters, warnings);
		JsonArray spawns = pool.getAsJsonArray("spawns");
		assertEquals(2, spawns.size(), "the made-up number 0 is no species");
		assertSpawn(spawns.get(0).getAsJsonObject(), "pidgey", 2);
		assertSpawn(spawns.get(1).getAsJsonObject(), "rattata", 1);
		assertEquals(1, warnings.warnings().get("g2_species_unknown"));
		G2PoolWriter.pools(encounters, Set.of("A_ROUTE"), "", warnings);
		assertTrue(warnings.warnings().containsKey("g2_grass_chances_missing"));
	}

	private static void assertSpawn(JsonObject spawn, String species, int weight) {
		assertEquals("cobblemon:" + species, spawn.get("species").getAsString());
		assertEquals(weight, spawn.get("weight").getAsInt());
	}
}
