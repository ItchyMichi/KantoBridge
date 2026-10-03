package dev.kantobridge.battle;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import dev.kantobridge.server.WorldIndexSync;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Gen 1's battle music instead of Cobblemon's (docs/PLAN.md P22, U4): the hidden game plays its own battle theme through
 * Gen1Recomp's window (gen1-bridge), so Cobblemon's battle themes are silenced for Kanto battles. Cobblemon sends each
 * fighting player their actor's {@code battleTheme} when the battle initialises (and to spectators); a null theme plays
 * nothing and leaves Minecraft's music alone, which Kanto's biomes keep silent anyway. So, just before every battle
 * starts ({@code BATTLE_STARTED_PRE}, after Cobblemon's builder chose the theme), the theme of each player actor is
 * cleared when the battle is one the hidden game asked for ({@link #starting}) or the player is in a Kanto world (one
 * with a WorldIndex: also the wild Pokémon of the habitat pools). Battles elsewhere keep Cobblemon's music. Server
 * thread only; counts only.
 */
public final class BattleMusic {
	/** Players whose battle the hidden game is starting right now (BattleService, EncounterService). */
	private static final Set<UUID> STARTING = new HashSet<>();
	private static final AtomicLong SILENCED = new AtomicLong();

	private BattleMusic() {
	}

	/** Call once from common init. */
	public static void init() {
		// Last, so a battle another listener cancels is left alone, and the theme any of them picks is replaced.
		CobblemonEvents.BATTLE_STARTED_PRE.subscribe(Priority.LOWEST, event -> {
			if (!event.isCanceled()) {
				silence(event.getBattle());
			}
		});
	}

	/** Runs {@code start} (a Cobblemon battle builder call) as a battle the hidden game asked for. */
	public static <T> T starting(ServerPlayer player, Supplier<T> start) {
		UUID id = player.getUUID();
		boolean added = STARTING.add(id);
		try {
			return start.get();
		} finally {
			if (added) {
				STARTING.remove(id);
			}
		}
	}

	/** Clears the battle theme of each player actor in a Kanto battle; returns how many were cleared. */
	public static int silence(PokemonBattle battle) {
		int cleared = 0;
		for (BattleActor actor : battle.getActors()) {
			if (actor instanceof PlayerBattleActor player && kanto(player.getEntity())) {
				player.setBattleTheme(null);
				cleared++;
			}
		}
		if (cleared > 0) {
			SILENCED.incrementAndGet();
		}
		return cleared;
	}

	private static boolean kanto(ServerPlayer player) {
		return player != null && (STARTING.contains(player.getUUID()) || WorldIndexSync.current(player.server) != null);
	}

	/** Battles whose Cobblemon theme was silenced. */
	public static long silenced() {
		return SILENCED.get();
	}
}
