package dev.kantobridge.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** WR6: which battle ends black a player out, and the walking-level spawn rule covering the zones' biome. */
class WildBlackoutsTest {
	@Test
	void onlyALostNaturalWildBattleBlacksOut() {
		assertTrue(WildBlackouts.blacksOut(true, false, true, false));
		assertFalse(WildBlackouts.blacksOut(true, false, false, false), "won");
		assertFalse(WildBlackouts.blacksOut(true, true, true, false), "a catch");
		assertFalse(WildBlackouts.blacksOut(false, false, true, false), "not a wild battle (a player, an NPC)");
		assertFalse(WildBlackouts.blacksOut(true, false, true, true), "the hidden game's own: it answers through battle.result");
	}

	@Test
	void theWalkingLevelRuleCoversTheZonesBiome() throws Exception {
		try (var in = WildBlackoutsTest.class.getResourceAsStream("/data/kantobridge/spawn_rules/walking_level_only.json")) {
			JsonObject rule = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			String selector = rule.getAsJsonArray("components").get(0).getAsJsonObject().get("spawnablePositionSelector").getAsString();
			assertTrue(selector.contains("'kantobridge:wild_forest'"), selector);
			assertTrue(selector.contains("'kantobridge:kanto_outdoor'"), selector);
		}
	}
}
