package dev.kantobridge.server;

import dev.kantobridge.KantoBridge;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.RegionRecord;
import dev.kantobridge.world.WorldIndex;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Each player's generated-pack state ({@code texture.status}, docs/PLAN.md §3.3, §4.3 "Multiplayer clients"; the voxel
 * plan's V1): whether their client draws this world with its own art ({@code ok}), against which slot map, how many
 * textures and how many of the world's keys it lacks, or why it shows placeholders. The art itself never reaches the server; this is a hash and a count. Kept per
 * player for diagnostics and tests, forgotten when they leave. A client speaks only for its own player; a malformed
 * message changes nothing and is answered with {@code error{bad_message}}. Logs carry counts, short hashes and reason
 * codes. Server thread only.
 */
public final class TextureStatusService {
	private static final Set<String> FIELDS = Set.of("ok", "slotMapHash", "textures", "missing", "shapes", "shapeIndexHash", "packs", "reason",
			"region");
	private static final Pattern REASON = Pattern.compile("^[a-z][a-z0-9_.]{0,63}$");
	/** protocol/defs.schema.json {@code count}. */
	private static final int MAX_COUNT = Integer.MAX_VALUE;

	/**
	 * One player's report.
	 *
	 * @param missing        the world's slot-map keys the client has no art for (drawn as placeholders); 0 when not said
	 * @param shapes         shape ids the client has geometry for (V2); 0 while it draws cubes
	 * @param shapeIndexHash the shape index it built them from, or null
	 * @param packs          texture-pack manifests the client applied (V6); 0 when not said
	 * @param matchesWorld   the client draws with its own art, resolved against the slot map the region's index names
	 * @param region         the region the report is about (MG3; null in a legacy world or when the client didn't say)
	 */
	public record Status(boolean ok, @Nullable String slotMapHash, int textures, int missing, int shapes, @Nullable String shapeIndexHash,
			int packs, @Nullable String reason, boolean matchesWorld, @Nullable String region) {
		/** A report without a region (a legacy world's). */
		public Status(boolean ok, @Nullable String slotMapHash, int textures, int missing, int shapes, @Nullable String shapeIndexHash, int packs,
				@Nullable String reason, boolean matchesWorld) {
			this(ok, slotMapHash, textures, missing, shapes, shapeIndexHash, packs, reason, matchesWorld, null);
		}
	}

	/** Each player's newest report (any region), and (MG3) their newest per region. */
	private static final Map<UUID, Status> STATUS = new ConcurrentHashMap<>();
	private static final Map<UUID, Map<String, Status>> BY_REGION = new ConcurrentHashMap<>();
	private static final Map<String, AtomicLong> WARNINGS = new ConcurrentHashMap<>();

	private TextureStatusService() {
	}

	/** Call once from common init, after the relay. */
	public static void init() {
		RelayService.get().register(MsgTypes.TEXTURE_STATUS, TextureStatusService::onStatus);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			STATUS.remove(handler.getPlayer().getUUID());
			BY_REGION.remove(handler.getPlayer().getUUID());
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			STATUS.clear();
			BY_REGION.clear();
		});
	}

	static void onStatus(ServerPlayer player, Envelope message) {
		Status status;
		try {
			DataReader reader = DataReader.of(message.data(), "").allowOnly(FIELDS);
			boolean ok = reader.bool("ok");
			String hash = reader.optionalString("slotMapHash", DataReader.HASH);
			int textures = reader.has("textures") ? reader.integer("textures", 0, MAX_COUNT) : 0;
			int missing = reader.has("missing") ? reader.integer("missing", 0, MAX_COUNT) : 0;
			int shapes = reader.has("shapes") ? reader.integer("shapes", 0, MAX_COUNT) : 0;
			String shapeIndexHash = reader.optionalString("shapeIndexHash", DataReader.HASH);
			int packs = reader.has("packs") ? reader.integer("packs", 0, MAX_COUNT) : 0;
			String reason = reader.optionalString("reason", REASON);
			// MG3: the report is about one region's index (its slot-map slice); the player's region when the client didn't say
			String region = reader.optionalRegion();
			Regions regions = Regions.of(player.server);
			RegionRecord record = regions == null ? null : regions.regionOf(player, region);
			if (region != null && record == null) {
				throw new DataException(reader.path("region"), "unknown region");
			}
			WorldIndex index = record == null ? null : regions.indexOf(record.id());
			boolean matches = ok && hash != null && index != null && hash.equals(index.slotMapHash());
			status = new Status(ok, hash, textures, missing, shapes, shapeIndexHash, packs, reason, matches, record == null ? null : record.id());
		} catch (DataException e) {
			warn("bad_message");
			RelayService.get().sendError(player, "bad_message", message.seq());
			return;
		}
		STATUS.put(player.getUUID(), status);
		if (status.region() != null) {
			BY_REGION.computeIfAbsent(player.getUUID(), key -> new ConcurrentHashMap<>()).put(status.region(), status);
		}
		if (status.ok()) {
			if (!status.matchesWorld()) {
				// Not fatal (the client judged against what it received), but its tiles would show other art.
				warn("slot_map_mismatch");
			}
			KantoBridge.LOGGER.info("texture.status: a player draws with their own art ({} textures, slot map {}, {} shapes of index {}, {} packs)",
					status.textures(), status.slotMapHash() == null ? "-" : Hashes.shortHash(status.slotMapHash()), status.shapes(),
					status.shapeIndexHash() == null ? "-" : Hashes.shortHash(status.shapeIndexHash()), status.packs());
		} else {
			KantoBridge.LOGGER.warn("texture.status: a player sees placeholders ({}, {} missing)", status.reason() == null ? "-" : status.reason(),
					status.missing());
		}
	}

	/** The player's last report (about any region), or null when their client hasn't sent one in this session. */
	public static @Nullable Status of(ServerPlayer player) {
		return STATUS.get(player.getUUID());
	}

	/** The player's last report about one region (MG3), or null. */
	public static @Nullable Status of(ServerPlayer player, String region) {
		Map<String, Status> reports = BY_REGION.get(player.getUUID());
		return reports == null ? null : reports.get(region);
	}

	private static void warn(String code) {
		long count = WARNINGS.computeIfAbsent(code, key -> new AtomicLong()).incrementAndGet();
		KantoBridge.LOGGER.warn("texture.status: {} ({} so far)", code, count);
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
}
