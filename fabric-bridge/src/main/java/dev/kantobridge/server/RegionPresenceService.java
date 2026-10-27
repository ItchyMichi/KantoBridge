package dev.kantobridge.server;

import dev.kantobridge.KantoBridge;
import dev.kantobridge.net.KbJsonPayload;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.story.StoryStates;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.RegionRecord;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * The relay's {@code region.state} (docs/specs/MULTI_GAME_PLAN.md §4.5, §4.6; MG3's record, MG4's messages): each client
 * says which region its followed link belongs to and which regions its parked links do; the server keeps it per player
 * ({@link RegionPresence}, persistent) and tells a joining client what it last stored (S→C {@code region.state}, once,
 * after the world's documents). A swap of the followed region clears the player's {@link dev.kantobridge.story.StoryState}:
 * the client sends a full {@code story.state} for the new region (§4.6). Ids are checked against the world's regions; an
 * unknown one is answered with {@code error{code:"region.unknown"}} and nothing changes. A client speaks only for its own
 * player. Logs carry region ids and counts only. Server thread only.
 */
public final class RegionPresenceService {
	public static final String UNKNOWN = "region.unknown";

	private static final Map<String, AtomicLong> WARNINGS = new ConcurrentHashMap<>();
	private static final AtomicLong STATES = new AtomicLong();
	private static final AtomicLong SWAPS = new AtomicLong();
	private static final AtomicLong OFFERED = new AtomicLong();
	private static final AtomicLong SEEDED = new AtomicLong();

	private RegionPresenceService() {
	}

	/** Call once from common init, after the relay and {@link WorldIndexSync#init} (its join listener runs first). */
	public static void init() {
		RegionPresence.init();
		RelayService.get().register(MsgTypes.REGION_STATE, RegionPresenceService::onState);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> offer(handler.getPlayer()));
	}

	static void onState(ServerPlayer player, Envelope message) {
		RegionPresence presence;
		try {
			presence = RegionPresence.read(message.data());
		} catch (DataException e) {
			warn("bad_message");
			RelayService.get().sendError(player, "bad_message", message.seq());
			return;
		}
		Regions regions = Regions.of(player.server);
		for (String region : presence.regions()) {
			if (regions == null || regions.byId(region) == null) {
				warn("unknown_region");
				RelayService.get().sendError(player, UNKNOWN, message.seq());
				return;
			}
		}
		apply(player, presence);
	}

	/** Stores a presence; a change of the followed region clears the story state the client is about to resend. */
	public static void apply(ServerPlayer player, RegionPresence presence) {
		RegionPresence before = of(player);
		if (before.followed() != null && !before.followed().equals(presence.followed())) {
			StoryStates.of(player).clear();
			SWAPS.incrementAndGet();
			KantoBridge.LOGGER.debug("presence: a player now follows {} (was {})", presence.followed(), before.followed());
		}
		RegionPresence.set(player, presence);
		STATES.incrementAndGet();
		// MG5: a crossing ends when the client follows the far region (the teleport into its gate house)
		PassService.onRegionState(player, presence);
	}

	/**
	 * The player's presence: what they last said, else following the world's legacy region (one link, as every world had
	 * before MG4).
	 */
	/**
	 * WORLD_SESSIONS_PLAN §2.3 (WS3): a player who never said gets the world record's start region as their followed
	 * region, stored, so their first join follows the start town's game (D2). True when seeded.
	 */
	public static boolean seed(ServerPlayer player) {
		if (RegionPresence.stored(player) != null) {
			return false;
		}
		dev.kantobridge.world.WorldRecord record = WorldIndexSync.worldRecord(player.server);
		Regions regions = Regions.of(player.server);
		if (record == null || regions == null || regions.byId(record.start()) == null) {
			return false;
		}
		RegionPresence.set(player, RegionPresence.following(record.start()));
		SEEDED.incrementAndGet();
		KantoBridge.LOGGER.info("presence: a new player starts in {}", record.start());
		return true;
	}

	/** WS3: fresh players seeded with the start region. */
	public static long seeded() {
		return SEEDED.get();
	}

	public static RegionPresence of(ServerPlayer player) {
		RegionPresence stored = RegionPresence.stored(player);
		if (stored != null) {
			return stored;
		}
		return RegionPresence.following(Regions.legacyId(player.server));
	}

	/** True when the player has a link for {@code region} (followed or parked), by their last {@code region.state}. */
	public static boolean hasLink(ServerPlayer player, String region) {
		return of(player).has(region);
	}

	/** The region the player's followed link belongs to, or null when no link is followed. */
	public static @Nullable RegionRecord followed(ServerPlayer player) {
		Regions regions = Regions.of(player.server);
		String followed = of(player).followed();
		return regions == null || followed == null ? null : regions.byId(followed);
	}

	/**
	 * Tells a joining client its stored presence, once, after {@link WorldIndexSync} sent the world: only in a region
	 * world (a legacy world's wire stays exactly what it was) and only to a client with our mod.
	 */
	static void offer(ServerPlayer player) {
		if (!WorldIndexSync.isRegionWorld(player.server) || !ServerPlayNetworking.canSend(player, KbJsonPayload.TYPE)) {
			return;
		}
		seed(player);
		if (RelayService.get().send(player, MsgTypes.REGION_STATE, of(player).toJson(), null)) {
			OFFERED.incrementAndGet();
		}
	}

	private static void warn(String code) {
		long count = WARNINGS.computeIfAbsent(code, key -> new AtomicLong()).incrementAndGet();
		KantoBridge.LOGGER.warn("presence: {} ({} so far)", code, count);
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

	/** {@code region.state}s stored. */
	public static long states() {
		return STATES.get();
	}

	/** Swaps of the followed region. */
	public static long swaps() {
		return SWAPS.get();
	}

	/** {@code region.state}s sent to joining clients. */
	public static long offered() {
		return OFFERED.get();
	}
}
