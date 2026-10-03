package dev.kantobridge.server;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import org.jetbrains.annotations.Nullable;

/**
 * Minecraft's game rules for a Kanto world (docs/PLAN.md Appendix D, P7). The client's "Create Kanto world" flow puts
 * them in the new level's settings; the server-side rules service (M3) can re-apply them at start. Rules not listed
 * keep their defaults (Cobblemon's own rules are Appendix C's business).
 */
public final class KantoGameRules {
	/** Appendix D's boolean rules and their values. */
	public static final Map<GameRules.Key<GameRules.BooleanValue>, Boolean> BOOLEANS;
	/** Appendix D's integer rules and their values. */
	public static final Map<GameRules.Key<GameRules.IntegerValue>, Integer> INTEGERS;

	static {
		Map<GameRules.Key<GameRules.BooleanValue>, Boolean> booleans = new LinkedHashMap<>();
		// Time, weather and spawning.
		booleans.put(GameRules.RULE_DAYLIGHT, false);
		booleans.put(GameRules.RULE_WEATHER_CYCLE, false);
		booleans.put(GameRules.RULE_DOMOBSPAWNING, false);
		booleans.put(GameRules.RULE_DOINSOMNIA, false);
		booleans.put(GameRules.RULE_DO_PATROL_SPAWNING, false);
		booleans.put(GameRules.RULE_DO_TRADER_SPAWNING, false);
		booleans.put(GameRules.RULE_DO_WARDEN_SPAWNING, false);
		booleans.put(GameRules.RULE_DISABLE_RAIDS, true);
		// Player damage and death.
		booleans.put(GameRules.RULE_FALL_DAMAGE, false);
		booleans.put(GameRules.RULE_DROWNING_DAMAGE, false);
		booleans.put(GameRules.RULE_FIRE_DAMAGE, false);
		booleans.put(GameRules.RULE_FREEZE_DAMAGE, false);
		booleans.put(GameRules.RULE_KEEPINVENTORY, true);
		booleans.put(GameRules.RULE_DO_IMMEDIATE_RESPAWN, true);
		// Protecting the built world.
		booleans.put(GameRules.RULE_MOBGRIEFING, false);
		booleans.put(GameRules.RULE_DOFIRETICK, false);
		booleans.put(GameRules.RULE_DO_VINES_SPREAD, false);
		booleans.put(GameRules.RULE_DOBLOCKDROPS, false);
		booleans.put(GameRules.RULE_DOENTITYDROPS, false);
		booleans.put(GameRules.RULE_DOMOBLOOT, false);
		booleans.put(GameRules.RULE_PROJECTILESCANBREAKBLOCKS, false);
		// Chat.
		booleans.put(GameRules.RULE_ANNOUNCE_ADVANCEMENTS, false);
		booleans.put(GameRules.RULE_SHOWDEATHMESSAGES, false);
		booleans.put(GameRules.RULE_COMMANDBLOCKOUTPUT, false);
		BOOLEANS = Map.copyOf(booleans);

		Map<GameRules.Key<GameRules.IntegerValue>, Integer> integers = new LinkedHashMap<>();
		integers.put(GameRules.RULE_SPAWN_RADIUS, 0);
		integers.put(GameRules.RULE_RANDOMTICKING, 0);
		INTEGERS = Map.copyOf(integers);
	}

	private KantoGameRules() {
	}

	/** A fresh rule set with Appendix D applied, for new level settings. */
	public static GameRules create() {
		GameRules rules = new GameRules();
		apply(rules, null);
		return rules;
	}

	/** Sets Appendix D's rules; with a server, changes are announced as a command would announce them. */
	public static void apply(GameRules rules, @Nullable MinecraftServer server) {
		BOOLEANS.forEach((key, value) -> rules.getRule(key).set(value, server));
		INTEGERS.forEach((key, value) -> rules.getRule(key).set(value, server));
	}
}
