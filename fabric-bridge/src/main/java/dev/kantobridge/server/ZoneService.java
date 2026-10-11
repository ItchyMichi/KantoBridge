package dev.kantobridge.server;

import com.cobblemon.mod.common.battles.BattleRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.story.Activity;
import dev.kantobridge.world.WorldIndex;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Wild Routes trainers on the server (docs/specs/WILD_ROUTES_PLAN.md §4.6, WR5): every tick, each player in a zone of
 * the published WorldIndex goes through {@link ZoneEncounters}; a sighting sends {@code route.sight}, and once the "!"
 * and the walk-up are over, {@code route.battle} with the trainer's party and its line ({@link ZoneLines}). The client
 * forwards that to the hidden game, whose script battle runs as any other; its {@code route.result} comes back here.
 * A player is free while not a spectator, not held by a build, doing nothing for the hidden game ({@link Activity}) and
 * not in a Cobblemon battle. Logs carry zone and trainer ids and counts only. Server thread only.
 */
public final class ZoneService {
	private static final ZoneEncounters ENCOUNTERS = new ZoneEncounters();
	private static final AtomicLong SIGHTS = new AtomicLong();
	private static final AtomicLong BATTLES = new AtomicLong();
	private static final AtomicLong WINS = new AtomicLong();
	private static @Nullable ZoneLines lines;

	private ZoneService() {
	}

	/** Call once from common init, after the relay. */
	public static void init() {
		lines = ZoneLines.bundled();
		RelayService.get().register(MsgTypes.ROUTE_RESULT, ZoneService::onResult);
		// the player's progress matters to their own client (balls, story cells); the server keeps only this visit's wins
		RelayService.get().register(MsgTypes.ROUTE_PROGRESS, (player, message) -> {
		});
		ServerTickEvents.END_SERVER_TICK.register(ZoneService::tick);
		// WR6: a lost natural wild battle blacks the player out, in zones and in Kanto
		WildBlackouts.init();
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> ENCOUNTERS.forget(handler.getPlayer().getUUID()));
	}

	public static ZoneEncounters encounters() {
		return ENCOUNTERS;
	}

	private static void tick(MinecraftServer server) {
		WorldIndex index = WorldIndexSync.current(server);
		if (index == null || index.zones().isEmpty()) {
			return;
		}
		long tick = server.getTickCount();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			tickPlayer(player, index, tick);
		}
	}

	/** One player's tick against {@code index} (GameTests call it with their own index and ticks). */
	public static void tickPlayer(ServerPlayer player, WorldIndex index, long tick) {
		boolean free = !player.isSpectator() && !BuildHold.isHeld(player) && Activity.of(player) == Activity.NONE
				&& BattleRegistry.getBattleByParticipatingPlayer(player) == null;
		ZoneEncounters.Action action = ENCOUNTERS.tick(player.getUUID(), index, player.getX(), player.getZ(), free, tick);
		if (action instanceof ZoneEncounters.Sight sight) {
			JsonObject data = new JsonObject();
			data.addProperty("route", sight.route());
			data.addProperty("id", sight.id());
			data.addProperty("x", sight.x());
			data.addProperty("y", sight.y());
			data.addProperty("frames", sight.frames());
			if (RelayService.get().send(player, MsgTypes.ROUTE_SIGHT, data, null)) {
				SIGHTS.incrementAndGet();
				KantoBridge.LOGGER.debug("zone: {} saw a player in {}", sight.id(), sight.route());
			}
		} else if (action instanceof ZoneEncounters.Battle battle) {
			if (RelayService.get().send(player, MsgTypes.ROUTE_BATTLE, battleData(battle), null)) {
				BATTLES.incrementAndGet();
				KantoBridge.LOGGER.debug("zone: battle with {} in {}", battle.trainer().id(), battle.route());
			}
		}
	}

	static JsonObject battleData(ZoneEncounters.Battle battle) {
		WorldIndex.ZoneTrainer t = battle.trainer();
		JsonObject data = new JsonObject();
		data.addProperty("route", battle.route());
		data.addProperty("id", t.id());
		data.addProperty("class", t.cls());
		JsonArray party = new JsonArray();
		for (WorldIndex.PartyMember m : t.party()) {
			JsonObject member = new JsonObject();
			member.addProperty("species", m.species());
			member.addProperty("level", m.level());
			party.add(member);
		}
		data.add("party", party);
		ZoneLines all = lines != null ? lines : ZoneLines.bundled();
		JsonObject said = new JsonObject();
		said.addProperty("before", all.lineFor(t.cls(), t.id()));
		data.add("lines", said);
		return data;
	}

	static void onResult(ServerPlayer player, Envelope message) {
		JsonObject data = message.data();
		boolean ok = data.has("ok") && data.get("ok").isJsonPrimitive() && data.get("ok").getAsBoolean();
		String outcome = string(data, "outcome");
		if ("battle".equals(string(data, "op")) && ok && "win".equals(outcome)) {
			WINS.incrementAndGet();
		}
		ENCOUNTERS.onResult(player.getUUID(), string(data, "op"), ok, string(data, "route"), string(data, "id"), outcome,
				player.server.getTickCount());
	}

	private static @Nullable String string(JsonObject data, String key) {
		JsonElement value = data.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
	}

	/** Sightings, battles sent and wins so far (for tests and diagnostics). */
	public static long sights() {
		return SIGHTS.get();
	}

	public static long battles() {
		return BATTLES.get();
	}

	public static long wins() {
		return WINS.get();
	}
}
