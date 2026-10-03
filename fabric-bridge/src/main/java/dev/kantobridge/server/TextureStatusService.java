package dev.kantobridge.server;

import dev.kantobridge.KantoBridge;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import dev.kantobridge.world.Hashes;
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
 * Each player's generated-pack state ({@code texture.status}, docs/PLAN.md §3.3, §4.3 "Multiplayer clients"): whether
 * their client draws this world with its own art built into the world's slots ({@code ok}), for which slot map, how many
 * textures, or why it shows placeholders. The art itself never reaches the server; this is a hash and a count. Kept per
 * player for diagnostics and tests, forgotten when they leave. A client speaks only for its own player; a malformed
 * message changes nothing and is answered with {@code error{bad_message}}. Logs carry counts, short hashes and reason
 * codes. Server thread only.
 */
public final class TextureStatusService {
	private static final Set<String> FIELDS = Set.of("ok", "slotMapHash", "textures", "reason");
	private static final Pattern REASON = Pattern.compile("^[a-z][a-z0-9_.]{0,63}$");
	/** protocol/defs.schema.json {@code count}. */
	private static final int MAX_COUNT = Integer.MAX_VALUE;

	/**
	 * One player's report.
	 *
	 * @param matchesWorld the pack is ready and made for the slot map the world's index names
	 */
	public record Status(boolean ok, @Nullable String slotMapHash, int textures, @Nullable String reason, boolean matchesWorld) {
	}

	private static final Map<UUID, Status> STATUS = new ConcurrentHashMap<>();
	private static final Map<String, AtomicLong> WARNINGS = new ConcurrentHashMap<>();

	private TextureStatusService() {
	}

	/** Call once from common init, after the relay. */
	public static void init() {
		RelayService.get().register(MsgTypes.TEXTURE_STATUS, TextureStatusService::onStatus);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> STATUS.remove(handler.getPlayer().getUUID()));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> STATUS.clear());
	}

	static void onStatus(ServerPlayer player, Envelope message) {
		Status status;
		try {
			DataReader reader = DataReader.of(message.data(), "").allowOnly(FIELDS);
			boolean ok = reader.bool("ok");
			String hash = reader.optionalString("slotMapHash", DataReader.HASH);
			int textures = reader.has("textures") ? reader.integer("textures", 0, MAX_COUNT) : 0;
			String reason = reader.optionalString("reason", REASON);
			WorldIndex index = WorldIndexSync.current(player.server);
			boolean matches = ok && hash != null && index != null && hash.equals(index.slotMapHash());
			status = new Status(ok, hash, textures, reason, matches);
		} catch (DataException e) {
			warn("bad_message");
			RelayService.get().sendError(player, "bad_message", message.seq());
			return;
		}
		STATUS.put(player.getUUID(), status);
		if (status.ok()) {
			if (!status.matchesWorld()) {
				// Not fatal (the client judged against what it received), but its tiles would show other art.
				warn("slot_map_mismatch");
			}
			KantoBridge.LOGGER.info("texture.status: a player's own pack is in use ({} textures, slot map {})", status.textures(),
					status.slotMapHash() == null ? "-" : Hashes.shortHash(status.slotMapHash()));
		} else {
			KantoBridge.LOGGER.warn("texture.status: a player sees placeholders ({})", status.reason() == null ? "-" : status.reason());
		}
	}

	/** The player's last report, or null when their client hasn't sent one in this session. */
	public static @Nullable Status of(ServerPlayer player) {
		return STATUS.get(player.getUUID());
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
