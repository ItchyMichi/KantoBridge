package dev.kantobridge.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.McBootstrap;
import java.util.Map;
import net.minecraft.world.level.GameRules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A new Kanto world's game rules are PLAN Appendix D's; everything else keeps Minecraft's default. */
class KantoGameRulesTest {
	@BeforeAll
	static void setUp() {
		McBootstrap.ensure();
	}

	@Test
	void appendixDIsApplied() {
		GameRules rules = KantoGameRules.create();
		Map<String, String> expected = Map.ofEntries(
				Map.entry("doDaylightCycle", "false"), Map.entry("doWeatherCycle", "false"), Map.entry("doMobSpawning", "false"),
				Map.entry("doInsomnia", "false"), Map.entry("doPatrolSpawning", "false"), Map.entry("doTraderSpawning", "false"),
				Map.entry("doWardenSpawning", "false"), Map.entry("disableRaids", "true"), Map.entry("fallDamage", "false"),
				Map.entry("drowningDamage", "false"), Map.entry("fireDamage", "false"), Map.entry("freezeDamage", "false"),
				Map.entry("keepInventory", "true"), Map.entry("doImmediateRespawn", "true"), Map.entry("spawnRadius", "0"),
				Map.entry("mobGriefing", "false"), Map.entry("doFireTick", "false"), Map.entry("randomTickSpeed", "0"),
				Map.entry("doVinesSpread", "false"), Map.entry("doTileDrops", "false"), Map.entry("doEntityDrops", "false"),
				Map.entry("doMobLoot", "false"), Map.entry("projectilesCanBreakBlocks", "false"), Map.entry("announceAdvancements", "false"),
				Map.entry("showDeathMessages", "false"), Map.entry("commandBlockOutput", "false"));
		assertEquals(expected.size(), KantoGameRules.BOOLEANS.size() + KantoGameRules.INTEGERS.size(), "Appendix D has 26 rules");
		int[] seen = {0};
		GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
			@Override
			public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
				String want = expected.get(key.getId());
				if (want != null) {
					assertEquals(want, rules.getRule(key).serialize(), key.getId());
					seen[0]++;
				}
			}
		});
		assertEquals(expected.size(), seen[0], "every Appendix D rule exists under that name");
		assertTrue(rules.getBoolean(GameRules.RULE_NATURAL_REGENERATION), "rules outside Appendix D keep their defaults");
	}
}
