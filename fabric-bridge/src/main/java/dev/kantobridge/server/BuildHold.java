package dev.kantobridge.server;

import dev.kantobridge.KantoBridge;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * Keeps players out of a world while the build the client's "Create Kanto world" flow asked for is running
 * (docs/PLAN.md §4.3 {@code world/}): a fresh Kanto world is void until the builder places the ground, so a player who
 * joins meanwhile would fall out of it. Held players are spectators, hovering where they spawned behind the client's
 * progress screen. When the build is done they are moved to the new spawn and get their game mode back.
 *
 * <p>A held player's own game mode is restored before they are saved (on leaving, and when the server stops), so no
 * save ever keeps the spectator mode. Server thread only.
 */
public final class BuildHold {
	/** Per server: held players and the game mode each had before. */
	private static final Map<MinecraftServer, Map<UUID, GameType>> HELD = Collections.synchronizedMap(new WeakHashMap<>());

	private BuildHold() {
	}

	/** Call once from common init. */
	public static void init() {
		ServerPlayerEvents.JOIN.register(BuildHold::onJoin);
		ServerPlayerEvents.LEAVE.register(player -> restore(player, false));
		ServerLifecycleEvents.SERVER_STOPPING.register(BuildHold::releaseModes);
		ServerLifecycleEvents.SERVER_STOPPED.register(HELD::remove);
	}

	/** Starts holding everyone on {@code server}, and everyone who joins it, until {@link #release}. */
	public static void begin(MinecraftServer server) {
		// A second begin keeps who is already held (and the modes they had before).
		HELD.computeIfAbsent(server, key -> new HashMap<>());
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			hold(player);
		}
		KantoBridge.LOGGER.info("builder: holding players until the build is done");
	}

	public static boolean isHolding(MinecraftServer server) {
		return HELD.containsKey(server);
	}

	/** True while {@code player} waits as a spectator. */
	public static boolean isHeld(ServerPlayer player) {
		Map<UUID, GameType> held = HELD.get(player.server);
		return held != null && held.containsKey(player.getUUID());
	}

	/** Lets everyone in: held players go to the world spawn with their own game mode back. Stops holding. */
	public static void release(MinecraftServer server) {
		Map<UUID, GameType> held = HELD.remove(server);
		if (held == null) {
			return;
		}
		ServerLevel level = server.overworld();
		Vec3 spawn = Vec3.atBottomCenterOf(level.getSharedSpawnPos());
		float angle = level.getSharedSpawnAngle();
		int moved = 0;
		for (Map.Entry<UUID, GameType> entry : held.entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				continue;
			}
			// Move first, then leave spectator mode, so nobody falls for a tick at the old position.
			player.teleportTo(level, spawn.x, spawn.y, spawn.z, angle, 0.0F);
			player.resetFallDistance();
			player.setGameMode(entry.getValue());
			moved++;
		}
		KantoBridge.LOGGER.info("builder: let {} player(s) in at the spawn", moved);
	}

	private static void onJoin(ServerPlayer player) {
		if (isHolding(player.server)) {
			hold(player);
		}
	}

	private static void hold(ServerPlayer player) {
		Map<UUID, GameType> held = HELD.get(player.server);
		if (held == null || held.containsKey(player.getUUID())) {
			return;
		}
		held.put(player.getUUID(), player.gameMode.getGameModeForPlayer());
		player.setGameMode(GameType.SPECTATOR);
	}

	/** Gives a held player their own game mode back (they stay held when {@code keep}). */
	private static void restore(ServerPlayer player, boolean keep) {
		Map<UUID, GameType> held = HELD.get(player.server);
		GameType before = held == null ? null : keep ? held.get(player.getUUID()) : held.remove(player.getUUID());
		if (before != null && player.gameMode.getGameModeForPlayer() != before) {
			player.setGameMode(before);
		}
	}

	/** The server is stopping and is about to save everyone: no save may keep the spectator mode. */
	private static void releaseModes(MinecraftServer server) {
		if (!isHolding(server)) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			restore(player, true);
		}
	}
}
