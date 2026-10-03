package dev.kantobridge.server;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.storage.player.GeneralPlayerData;
import com.cobblemon.mod.common.api.storage.player.PlayerInstancedDataStoreTypes;
import com.cobblemon.mod.common.config.CobblemonConfig;
import com.cobblemon.mod.common.world.gamerules.CobblemonGameRules;
import dev.kantobridge.KantoBridge;
import java.util.LinkedHashMap;
import java.util.Map;
import kotlin.ranges.IntRange;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;

/**
 * Cobblemon's settings for Kanto (docs/PLAN.md Appendix C, P12), applied by {@link Rules}: the configuration values in
 * code at server start (in memory only: the user's {@code config/cobblemon/main.json} is never rewritten), Cobblemon's
 * game rules once the world is loaded, and the starter prompt locked for every player whose data Cobblemon
 * synchronises: the starter comes from Oak's lab in the hidden game.
 */
public final class CobblemonSettings {
	/** No Gen 1 shinies: 1 in a billion (a value Cobblemon's fishing code can still add 1 to as an int). */
	public static final float SHINY_RATE = 1.0E9F;
	/** Gen 1 statuses last until healed: about 31 years of seconds. */
	public static final int STATUS_SECONDS = 1_000_000_000;
	public static final float MIN_SPAWN_DISTANCE = 8.0F;
	public static final float MAX_SPAWN_DISTANCE = 32.0F;
	public static final float FLEE_DISTANCE = 12.0F;

	/** Cobblemon's game rules and their Kanto values. */
	public static final Map<GameRules.Key<GameRules.BooleanValue>, Boolean> GAME_RULES;

	static {
		Map<GameRules.Key<GameRules.BooleanValue>, Boolean> rules = new LinkedHashMap<>();
		rules.put(CobblemonGameRules.DO_POKEMON_LOOT, false);
		rules.put(CobblemonGameRules.BATTLE_INVULNERABILITY, true);
		rules.put(CobblemonGameRules.SHINY_STARTERS, false);
		// Natural habitats ride the player spawner, which only runs with spawning on (PLAN P8).
		rules.put(CobblemonGameRules.DO_POKEMON_SPAWNING, true);
		GAME_RULES = Map.copyOf(rules);
	}

	private CobblemonSettings() {
	}

	/** Appendix C's configuration values. Safe to repeat. */
	public static void apply(CobblemonConfig config) {
		// No passive healing or auto-revive: Pokémon Centers heal (P12 and the hidden game's own healing).
		config.setHealPercent(0.0);
		config.setFaintAwakenHealthPercent(0.0F);
		config.setExperienceMultiplier(1.0F);
		config.setAwardExperienceOnBattleLoss(true);
		config.setAllowExperienceFromPvP(false);
		config.setPreventCompletePartyDeposit(true);
		config.setPlayerDamagePokemon(false);
		config.setShinyRate(SHINY_RATE);
		config.setMinimumSpawningZoneDistanceFromPlayer(MIN_SPAWN_DISTANCE);
		config.setMaximumSpawningZoneDistanceFromPlayer(MAX_SPAWN_DISTANCE);
		config.setSavePokemonToWorld(false);
		config.setDefaultFleeDistance(FLEE_DISTANCE);
		Map<String, IntRange> statuses = config.getPassiveStatuses();
		for (String status : statuses.keySet()) {
			statuses.put(status, new IntRange(STATUS_SECONDS, STATUS_SECONDS));
		}
		KantoBridge.LOGGER.info("cobblemon: Appendix C settings applied ({} passive statuses made lasting)", statuses.size());
	}

	/** Cobblemon's game rules (needs the loaded world). Safe to repeat. */
	public static void applyGameRules(MinecraftServer server) {
		GameRules rules = server.getGameRules();
		GAME_RULES.forEach((key, value) -> rules.getRule(key).set(value, server));
	}

	/** Locks Cobblemon's starter prompt for a player and tells their client (Appendix C). */
	public static void lockStarter(ServerPlayer player) {
		GeneralPlayerData data = Cobblemon.INSTANCE.getPlayerDataManager().getGenericData(player);
		if (!data.getStarterLocked()) {
			data.setStarterLocked(true);
			Cobblemon.INSTANCE.getPlayerDataManager().saveSingle(data, PlayerInstancedDataStoreTypes.INSTANCE.getGENERAL());
		}
		data.sendToPlayer(player);
	}
}
