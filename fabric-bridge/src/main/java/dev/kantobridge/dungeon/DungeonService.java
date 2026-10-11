package dev.kantobridge.dungeon;

import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.routes.gen.PmdParams;
import dev.kantobridge.server.WorldIndexSync;
import dev.kantobridge.world.WorldIndex;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Wild Routes dungeons on the server (docs/specs/WILD_ROUTES_PLAN.md §4.8, WR7): every tick, while the published
 * WorldIndex has a dungeon region, the players' positions go through {@link DungeonVisits}, whose actions this carries
 * out: {@code route.fade} to the player, floors built and cleared by the {@link DungeonPlacer} within a time budget, and
 * teleports in, between floors and out. Logs carry slots, floors and counts only. Server thread only.
 */
public final class DungeonService {
	/** Time the placer may take each tick. */
	static final long BUDGET_NANOS = 6_000_000L;

	private static @Nullable DungeonVisits visits;
	private static @Nullable DungeonPlacer placer;

	private DungeonService() {
	}

	/** Call once from common init, after the relay. */
	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(DungeonService::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			visits = null;
			placer = null;
		});
	}

	private static void tick(MinecraftServer server) {
		WorldIndex index = WorldIndexSync.current(server);
		if (index == null || index.dungeons() == null) {
			return;
		}
		tickWith(server.overworld(), index, server.getTickCount(), server.getPlayerList().getPlayers());
	}

	/** One tick against {@code index} for {@code players} (GameTests call it with their own index and ticks). */
	public static void tickWith(ServerLevel level, WorldIndex index, long tick, List<ServerPlayer> players) {
		DungeonVisits v = visits(level);
		DungeonPlacer p = placer(level);
		Map<UUID, ServerPlayer> byId = new HashMap<>();
		List<DungeonVisits.Where> where = new ArrayList<>();
		for (ServerPlayer player : players) {
			byId.put(player.getUUID(), player);
			where.add(new DungeonVisits.Where(player.getUUID(), player.getX(), player.getY(), player.getZ()));
		}
		for (DungeonVisits.Action action : v.tick(tick, index, where)) {
			execute(level, v, p, action, byId);
		}
		p.tick(System.nanoTime() + BUDGET_NANOS);
	}

	private static void execute(ServerLevel level, DungeonVisits v, DungeonPlacer p, DungeonVisits.Action action, Map<UUID, ServerPlayer> players) {
		if (action instanceof DungeonVisits.Fade fade) {
			ServerPlayer player = players.get(fade.player());
			if (player != null) {
				JsonObject data = new JsonObject();
				data.addProperty("ticks", Math.min(fade.ticks(), 200));
				RelayService.get().send(player, MsgTypes.ROUTE_FADE, data, null);
			}
		} else if (action instanceof DungeonVisits.Build build) {
			p.build(build.floor(), () -> v.built(build.floor()));
			KantoBridge.LOGGER.debug("dungeon: building a floor at y {}", build.floor().feet());
		} else if (action instanceof DungeonVisits.Enter enter) {
			move(level, players.get(enter.player()), enter.x(), enter.y(), enter.z());
		} else if (action instanceof DungeonVisits.Leave leave) {
			move(level, players.get(leave.player()), leave.x(), leave.y(), leave.z());
		} else if (action instanceof DungeonVisits.Clear clear) {
			if (clear.volumes().isEmpty()) {
				v.cleared(clear.slot());
			}
			for (int i = 0; i < clear.volumes().size(); i++) {
				boolean last = i == clear.volumes().size() - 1;
				p.clear(clear.volumes().get(i), last ? () -> v.cleared(clear.slot()) : () -> {
				});
			}
			KantoBridge.LOGGER.debug("dungeon: clearing slot {}", clear.slot());
		}
	}

	private static void move(ServerLevel level, @Nullable ServerPlayer player, double x, double y, double z) {
		if (player != null) {
			player.teleportTo(level, x, y, z, Set.of(), player.getYRot(), player.getXRot());
			player.resetFallDistance();
		}
	}

	/** The visits for {@code level}'s world (made with its seed on first use). */
	public static DungeonVisits visits(ServerLevel level) {
		if (visits == null) {
			visits = new DungeonVisits(level.getSeed(), PmdParams.standard());
		}
		return visits;
	}

	public static DungeonPlacer placer(ServerLevel level) {
		if (placer == null || placer.level() != level) {
			placer = new DungeonPlacer(level);
		}
		return placer;
	}

	/** Tests: forget every visit (a fresh service). */
	public static void reset() {
		visits = null;
		placer = null;
	}
}
