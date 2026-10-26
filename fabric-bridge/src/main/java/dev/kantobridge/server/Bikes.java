package dev.kantobridge.server;

import dev.kantobridge.KantoBridge;
import dev.kantobridge.entity.BikeEntity;
import dev.kantobridge.entity.KbEntities;
import dev.kantobridge.story.StoryState;
import dev.kantobridge.story.StoryStates;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * The ridden bikes (docs/specs/BIKE_PLAN.md §2.5, D2, D9): while a player's relayed story state says bike, the server
 * keeps that player seated on a {@link BikeEntity} of the state's kind, with the Acro's pose and the Mach's gear in the
 * entity's synced data; when it says otherwise, the bike goes. Every client sees the bike and its rider (vanilla
 * tracking), so nothing else is synced.
 *
 * <p>{@link #ensure} runs on every relayed {@code story.state} and on each player's tick while the state says bike and the
 * player isn't riding (at most once a {@link #ENSURE_EVERY_TICKS} ticks a player: death, a portal or {@code /kill}
 * dismount, and the next ensure seats the player again). Logs counts only. Server thread only.
 */
public final class Bikes {
	/** Ticks between two tries to seat a player whose state says bike. */
	public static final int ENSURE_EVERY_TICKS = 20;

	/** The rate limit's clock, by player: the tick of the last try. */
	private static final Map<UUID, Long> LAST_TRY = new HashMap<>();
	private static long spawned;
	private static long removed;

	private Bikes() {
	}

	/** Call once from common init. */
	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(Bikes::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			dismount(handler.getPlayer());
			LAST_TRY.remove(handler.getPlayer().getUUID());
		});
	}

	private static void tick(MinecraftServer server) {
		long now = server.getTickCount();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			StoryState state = StoryStates.of(player);
			BikeEntity bike = bikeOf(player);
			if (state.bike() && !state.surf() && bike == null && due(player.getUUID(), now)) {
				ensure(player);
			} else if ((!state.bike() || state.surf()) && bike != null) {
				dismount(player);
			}
		}
	}

	/** True when a try for this player is allowed now (once a {@link #ENSURE_EVERY_TICKS} ticks); notes the try. */
	static boolean due(UUID player, long now) {
		Long last = LAST_TRY.get(player);
		if (last != null && now - last < ENSURE_EVERY_TICKS) {
			return false;
		}
		LAST_TRY.put(player, now);
		return true;
	}

	/** The bike this player rides, or null. */
	public static @Nullable BikeEntity bikeOf(Entity player) {
		return player.getVehicle() instanceof BikeEntity bike ? bike : null;
	}

	/**
	 * Matches the player's seat to the story state: seated on a bike of its kind while it says bike (spawned at the
	 * player's feet with the player's heading), the bike gone when it doesn't; the kind, pose and gear brought up to date.
	 */
	public static void ensure(ServerPlayer player) {
		StoryState state = StoryStates.of(player);
		BikeEntity bike = bikeOf(player);
		// a surfing player is never on a bike (the engines clear it; D9)
		if (!state.bike() || state.surf() || player.isSpectator() || !player.isAlive()) {
			if (bike != null) {
				dismount(player);
			}
			return;
		}
		if (bike == null) {
			if (player.isPassenger()) {
				// a Cobblemon ride or a boat: leave it be (the next try comes later)
				return;
			}
			bike = KbEntities.BIKE.create(player.serverLevel());
			if (bike == null) {
				return;
			}
			bike.moveTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), 0.0F);
			apply(bike, state);
			if (!player.serverLevel().addFreshEntity(bike)) {
				return;
			}
			if (!player.startRiding(bike, true)) {
				bike.discard();
				return;
			}
			spawned++;
			KantoBridge.LOGGER.debug("bikes: seated ({} so far)", spawned);
			return;
		}
		apply(bike, state);
	}

	/** The kind and pose from the story state onto the bike's synced data. */
	static void apply(BikeEntity bike, StoryState state) {
		bike.setKind(BikeEntity.Kind.byId(state.bikeKind()));
		bike.setTrick(BikeEntity.Trick.byId(state.trick()));
	}

	/** Off the bike: the player stands where the bike stood, the bike is gone. */
	public static void dismount(ServerPlayer player) {
		BikeEntity bike = bikeOf(player);
		if (bike == null) {
			return;
		}
		player.stopRiding();
		bike.discard();
		removed++;
		KantoBridge.LOGGER.debug("bikes: removed ({} so far)", removed);
	}

	/** The next tick may seat this player at once (a warp just took the old bike away). */
	public static void reseatSoon(ServerPlayer player) {
		LAST_TRY.remove(player.getUUID());
	}

	public static long spawned() {
		return spawned;
	}

	public static long removed() {
		return removed;
	}
}
