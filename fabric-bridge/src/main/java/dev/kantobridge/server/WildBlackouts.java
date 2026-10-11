package dev.kantobridge.server;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.ActorType;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.battles.BattleVictoryEvent;
import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.battle.BattleService;
import dev.kantobridge.battle.EncounterService;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.MsgTypes;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * A lost wild battle blacks the player out, Gen 1's way (docs/specs/WILD_ROUTES_PLAN.md §4.7, WR6): when Cobblemon ends a
 * natural wild battle (a habitat's Pokémon, in a zone or in Kanto) with the player among the losers, the server sends
 * {@code route.blackout}; the client forwards it, and the hidden game runs its own loss handling (heal, half the money,
 * the last Pokémon Center), which the player follows. Battles the hidden game started (trainers, static, surfing and
 * fishing encounters) answer it themselves through {@code battle.result}, so they are left alone, as is a catch.
 */
public final class WildBlackouts {
	private static final AtomicLong SENT = new AtomicLong();

	private WildBlackouts() {
	}

	/** Call once from common init, after the relay. */
	public static void init() {
		CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.NORMAL, WildBlackouts::onVictory);
	}

	/**
	 * True when a battle's end blacks a losing player out: a natural wild battle (not one of the hidden game's own), not a
	 * catch, the player among the losers.
	 */
	static boolean blacksOut(boolean wild, boolean caught, boolean playerLost, boolean hiddenGames) {
		return wild && !caught && playerLost && !hiddenGames;
	}

	private static void onVictory(BattleVictoryEvent event) {
		PokemonBattle battle = event.getBattle();
		Set<UUID> lost = new HashSet<>();
		for (BattleActor loser : event.getLosers()) {
			if (loser.getType() == ActorType.PLAYER) {
				loser.getPlayerUUIDs().forEach(lost::add);
			}
		}
		for (ServerPlayer player : battle.getPlayers()) {
			if (!blacksOut(battle.isPvW(), event.getWasWildCapture(), lost.contains(player.getUUID()), hiddenGames(player, battle))) {
				continue;
			}
			if (RelayService.get().send(player, MsgTypes.ROUTE_BLACKOUT, new JsonObject(), null)) {
				long n = SENT.incrementAndGet();
				KantoBridge.LOGGER.info("battle: a lost wild battle blacks a player out ({} so far)", n);
			}
		}
	}

	/** True when the hidden game started this battle (its result goes back as battle.result). */
	private static boolean hiddenGames(ServerPlayer player, PokemonBattle battle) {
		return same(BattleService.battleOf(player), battle) || same(EncounterService.battleOf(player), battle);
	}

	private static boolean same(@Nullable PokemonBattle ours, PokemonBattle battle) {
		return ours != null && ours.getBattleId().equals(battle.getBattleId());
	}

	public static long sent() {
		return SENT.get();
	}
}
