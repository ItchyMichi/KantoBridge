package dev.kantobridge.server;

import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.builder.BuildTask;
import dev.kantobridge.net.KbJsonPayload;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import dev.kantobridge.world.Hashes;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

/**
 * The world's identity for save pairing (docs/PLAN.md §4.4 {@code SessionService}, §2.4 flow 13; DESIGN §8 "Saving":
 * each multiplayer world gets its own paired story save). Each client sends its gen1-bridge {@code session.bind{world,
 * multiplayer}} on joining a Kanto world and after every reconnect; gen1-bridge answers {@code session.bound{slot}}.
 *
 * <ul>
 * <li><b>The world id</b> is a UUID kept in {@code <world>/kantobridge/session.json} ({@link SessionRecord}), created
 * the first time the world starts with this mod and never changed afterwards.</li>
 * <li><b>Getting it to the client:</b> once the protocol gives {@code session.bind} an {@code s2c} leg, the server sends
 * {@code session.bind{world, multiplayer}} to every joining client. Until then only an integrated server's own client
 * (singleplayer, the LAN host) can bind: it reads {@link #worldId} in-process.</li>
 * <li><b>The pairing record:</b> once {@code session.bound} has a {@code c2s} leg, the client relays its gen1-bridge's
 * answer and the slot is stored per player ({@link #record}), for M9's pairing and M10's mismatch warning.</li>
 * </ul>
 * Logs carry short ids, slots and counts. Server thread only, except {@link #worldId}, which any thread may read.
 */
public final class SessionService {
	private static final Map<MinecraftServer, SessionRecord> RECORDS = Collections.synchronizedMap(new WeakHashMap<>());
	private static final Map<String, AtomicLong> WARNINGS = new ConcurrentHashMap<>();
	private static final AtomicLong OFFERED = new AtomicLong();
	private static final AtomicLong BOUND = new AtomicLong();
	/** {@code session.bound.switched}'s values (M10). */
	private static final Set<String> SWITCHES = Set.of("none", "title", "saved", "moved");

	private SessionService() {
	}

	/** Call once from common init, after the relay. */
	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(SessionService::load);
		ServerLifecycleEvents.SERVER_STOPPED.register(RECORDS::remove);
		if (hasLeg(MsgTypes.SESSION_BIND, Catalog.Leg.S2C)) {
			ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> offer(handler.getPlayer()));
		}
		if (hasLeg(MsgTypes.SESSION_BOUND, Catalog.Leg.C2S)) {
			RelayService.get().register(MsgTypes.SESSION_BOUND, SessionService::onBound);
		}
	}

	/** True when the bundled protocol catalog lets {@code type} travel this leg (the session legs come with M9). */
	public static boolean hasLeg(String type, Catalog.Leg leg) {
		Catalog.Entry entry = Catalog.bundled().get(type);
		return entry != null && entry.legs().contains(leg);
	}

	/** The record's file in the server's world folder. */
	public static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(BuildTask.DATA_FOLDER).resolve(SessionRecord.FILE);
	}

	/**
	 * Reads the world's record, creating it on the world's first start (server start; tests call it again to act like a
	 * restart, which reads the same file). Null when the file can't be read or written: clients then can't bind.
	 */
	public static @Nullable SessionRecord load(MinecraftServer server) {
		try {
			SessionRecord.Loaded loaded = SessionRecord.loadOrCreate(file(server));
			SessionRecord record = loaded.record();
			RECORDS.put(server, record);
			switch (loaded.origin()) {
				case CREATED -> KantoBridge.LOGGER.info("session: new world id {}", shortId(record.world()));
				case REPLACED -> {
					warn("record_unreadable");
					KantoBridge.LOGGER.warn("session: the world's session record was unreadable; moved aside, new world id {}",
							shortId(record.world()));
				}
				case READ -> KantoBridge.LOGGER.info("session: world id {} ({} pairings)", shortId(record.world()), record.players());
			}
			return record;
		} catch (IOException e) {
			RECORDS.remove(server);
			warn("record_io");
			KantoBridge.LOGGER.warn("session: the world's session record can't be written ({}); clients can't bind this world",
					e.getClass().getSimpleName());
			return null;
		}
	}

	/** The world's id, or null before the server started (or when its record failed). Safe from any thread. */
	public static @Nullable String worldId(MinecraftServer server) {
		SessionRecord record = RECORDS.get(server);
		return record == null ? null : record.world();
	}

	/** The slot the player's gen1-bridge reported for this world's legacy region, or null. */
	public static @Nullable Integer slotOf(MinecraftServer server, UUID player) {
		SessionRecord record = RECORDS.get(server);
		return record == null ? null : record.slot(player);
	}

	/** WS2: the save choice recorded with the player's pairing of a region ({@code null} or the legacy id: the legacy one), or null. */
	public static @Nullable String saveOf(MinecraftServer server, UUID player, @Nullable String region) {
		SessionRecord record = RECORDS.get(server);
		return record == null ? null : record.save(player, Regions.isLegacy(server, region) ? null : region);
	}

	/** The slot the player paired with a region (MG3; {@code null} or the legacy id: the legacy slot), or null. */
	public static @Nullable Integer slotOf(MinecraftServer server, UUID player, @Nullable String region) {
		SessionRecord record = RECORDS.get(server);
		return record == null ? null : record.slot(player, Regions.isLegacy(server, region) ? null : region);
	}

	/**
	 * Whether this world is a multiplayer world for {@code player}: everyone but the owner of an integrated server
	 * (singleplayer, or a LAN host) plays it online, with a paired save of its own (DESIGN §8).
	 */
	public static boolean multiplayer(ServerPlayer player) {
		return !player.server.isSingleplayerOwner(player.getGameProfile());
	}

	/**
	 * The {@code session.bind} body for a player of this world, or null without a world id. WORLD_SESSIONS_PLAN WS2: with
	 * {@code regions}, the player's recorded slot and save choice per region (the legacy region by its id), when any.
	 */
	public static @Nullable JsonObject bindData(ServerPlayer player) {
		String world = worldId(player.server);
		if (world == null) {
			return null;
		}
		JsonObject data = new JsonObject();
		data.addProperty("world", world);
		data.addProperty("multiplayer", multiplayer(player));
		JsonObject regions = recorded(player);
		if (regions.size() > 0) {
			data.add("regions", regions);
		}
		return data;
	}

	/** WS2: the player's recorded choices, region id → {@code {slot, save?}}. */
	public static JsonObject recorded(ServerPlayer player) {
		JsonObject out = new JsonObject();
		SessionRecord record = RECORDS.get(player.server);
		if (record == null) {
			return out;
		}
		UUID id = player.getUUID();
		Integer legacy = record.slot(id);
		if (legacy != null) {
			out.add(Regions.legacyId(player.server), choice(legacy, record.save(id, null)));
		}
		record.regions(id).forEach((region, slot) -> out.add(region, choice(slot, record.save(id, region))));
		return out;
	}

	private static JsonObject choice(int slot, @Nullable String save) {
		JsonObject one = new JsonObject();
		one.addProperty("slot", slot);
		if (save != null) {
			one.addProperty("save", save);
		}
		return one;
	}

	/** Tells a joining client the world id (needs the protocol's {@code s2c} leg for {@code session.bind}). */
	static void offer(ServerPlayer player) {
		JsonObject data = bindData(player);
		if (data == null || !ServerPlayNetworking.canSend(player, KbJsonPayload.TYPE)) {
			return;
		}
		if (RelayService.get().send(player, MsgTypes.SESSION_BIND, data, null)) {
			OFFERED.incrementAndGet();
		}
	}

	/**
	 * The client relays its gen1-bridge's {@code session.bound{slot}} (needs the {@code c2s} leg). M10's {@code switched}
	 * (how the hidden game reached the slot) is the client's business; it is accepted and not kept.
	 */
	static void onBound(ServerPlayer player, Envelope message) {
		int slot;
		String region;
		String save;
		try {
			DataReader reader = DataReader.of(message.data(), "").allowOnly(Set.of("slot", "switched", "region", "save", "started", "parked"));
			slot = reader.integer("slot", 0, SessionRecord.MAX_SLOT);
			// WS2: the choice gen1-bridge applied, recorded with the slot
			save = reader.has("save") ? reader.string("save") : null;
			if (save != null && !SessionRecord.SAVES.contains(save)) {
				throw new DataException(reader.path("save"), "unknown value");
			}
			String switched = reader.has("switched") ? reader.string("switched") : null;
			if (switched != null && !SWITCHES.contains(switched)) {
				throw new DataException(reader.path("switched"), "unknown value");
			}
			// MG3: the region whose link bound; unknown to this world is a malformed message
			region = Regions.currentRegionId(player, reader.optionalRegion());
			if (reader.has("region") && region == null) {
				throw new DataException(reader.path("region"), "unknown region");
			}
		} catch (DataException e) {
			warn("bad_message");
			RelayService.get().sendError(player, "bad_message", message.seq());
			return;
		}
		record(player, region, slot, save);
	}

	/**
	 * Records the save slot the player's hidden game paired with this world's legacy region, and writes the record. False
	 * when there is no record or it can't be written (a counted warning).
	 */
	public static boolean record(ServerPlayer player, int slot) {
		return record(player, null, slot);
	}

	/**
	 * {@link #record(ServerPlayer, int)} for a region (MG3): {@code null} or the legacy region's id keeps format 1's one
	 * slot; another region's slot goes under {@code regions} (format 2).
	 */
	public static boolean record(ServerPlayer player, @Nullable String region, int slot) {
		return record(player, region, slot, null);
	}

	/** WS2: {@link #record(ServerPlayer, String, int)} with the save choice applied ({@code null}: the recorded one stays). */
	public static boolean record(ServerPlayer player, @Nullable String region, int slot, @Nullable String save) {
		SessionRecord record = RECORDS.get(player.server);
		if (record == null) {
			warn("no_record");
			return false;
		}
		String key = Regions.isLegacy(player.server, region) ? null : region;
		if (!record.put(player.getUUID(), key, slot, save)) {
			return true;
		}
		try {
			record.write(file(player.server));
		} catch (IOException e) {
			warn("record_io");
			return false;
		}
		long count = BOUND.incrementAndGet();
		KantoBridge.LOGGER.info("session: a player paired save slot {} with this world; {} pairings recorded", slot, count);
		return true;
	}

	// ---- views (tests, diagnostics) ----

	/** The id's first 8 characters, for log lines. */
	public static String shortId(String world) {
		return Hashes.shortHash(world);
	}

	private static void warn(String code) {
		long count = WARNINGS.computeIfAbsent(code, key -> new AtomicLong()).incrementAndGet();
		KantoBridge.LOGGER.warn("session: {} ({} so far)", code, count);
	}

	public static long warnings(String code) {
		AtomicLong count = WARNINGS.get(code);
		return count == null ? 0 : count.get();
	}

	public static Map<String, Long> warnings() {
		Map<String, Long> out = new TreeMap<>();
		WARNINGS.forEach((code, count) -> out.put(code, count.get()));
		return out;
	}

	/** {@code session.bind}s sent to joining clients. */
	public static long offered() {
		return OFFERED.get();
	}
}
