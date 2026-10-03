package dev.kantobridge.net;

import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * The relay's own version handshake (M10; docs/PROTOCOL.md {@code relay.hello}; DESIGN §9 "version handshake between the
 * bridge halves"). When a player joins, the server sends {@code relay.hello{protocol, mod}} and the client answers with
 * its own. A client that can't receive the relay's payload at all (no kantobridge, or one too old to register it) and a
 * client that answers with another protocol are disconnected with a reason naming both versions; it is a translatable
 * text with an English fallback, since a client without the mod has no lang file. Nothing else waits for the answer.
 *
 * <p>GameTest mock players have no client and register no payloads: on a GameTest server they are left alone (their
 * tests feed the relay directly). The decision itself ({@link #decide}) is plain Java; the disconnect goes through
 * {@link #kicker}, which GameTests replace to watch it. Logs carry protocol numbers, versions and counts. Server thread
 * only.
 */
public final class RelayHandshake {
	/** {@code relay.hello.mod}'s format (versions only). */
	public static final Pattern VERSION = Pattern.compile("^[0-9A-Za-z.+_-]{1,32}$");

	/** What becomes of a player's relay. */
	public enum Verdict {
		/** Their client speaks our relay protocol (or hasn't answered yet). */
		OK,
		/** Their client can't receive {@code kantobridge:json}: no kantobridge mod. */
		NO_MOD,
		/** Their client answered with another protocol. */
		PROTOCOL
	}

	/** How a player is sent away (GameTests swap it to watch instead). */
	@FunctionalInterface
	public interface Kicker {
		void kick(ServerPlayer player, Component reason);
	}

	private static final Kicker DISCONNECT = (player, reason) -> player.connection.disconnect(reason);
	private static volatile Kicker kicker = DISCONNECT;

	/** Players whose client answered with our protocol, this server run. */
	private static final Set<UUID> AGREED = ConcurrentHashMap.newKeySet();
	private static final Map<String, AtomicLong> COUNTS = new ConcurrentHashMap<>();

	private RelayHandshake() {
	}

	/** Call once from common init, after the relay. */
	public static void init() {
		RelayService.get().register(MsgTypes.RELAY_HELLO, RelayHandshake::onHello);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(handler.getPlayer(), server));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> AGREED.remove(handler.getPlayer().getUUID()));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> AGREED.clear());
	}

	/**
	 * The decision: a client that can't receive our payload has no kantobridge; one that answered with another protocol
	 * number can't talk to this server; anything else is fine (an answer may still be on its way).
	 *
	 * @param theirs the protocol the client answered with, or null before its answer
	 */
	public static Verdict decide(boolean canReceive, int ours, @Nullable Integer theirs) {
		if (!canReceive) {
			return Verdict.NO_MOD;
		}
		return theirs != null && theirs != ours ? Verdict.PROTOCOL : Verdict.OK;
	}

	/** Our relay protocol (the bundled catalog's). */
	public static int protocol() {
		return Catalog.bundled().protocol();
	}

	/** This mod's version, as the hello carries it. */
	public static String modVersion() {
		String version = FabricLoader.getInstance().getModContainer(KantoBridge.MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
		return VERSION.matcher(version).matches() ? version : "unknown";
	}

	/** The {@code relay.hello} body this side sends. */
	public static JsonObject helloData() {
		JsonObject data = new JsonObject();
		data.addProperty("protocol", protocol());
		data.addProperty("mod", modVersion());
		return data;
	}

	/** The reason a player without kantobridge reads. */
	public static Component noModReason() {
		return Component.translatableWithFallback("kantobridge.relay.kick.no_mod",
				"This server runs Kanto Bridge (kantobridge %s, relay protocol %s). Install the matching kantobridge mod to join.",
				modVersion(), protocol());
	}

	/** The reason a player whose kantobridge speaks another protocol reads: both versions. */
	public static Component protocolReason(int theirs, String theirMod) {
		return Component.translatableWithFallback("kantobridge.relay.kick.protocol",
				"Kanto Bridge versions don't match: this server has kantobridge %s (relay protocol %s), you have kantobridge %s (relay protocol %s). Install the same version.",
				modVersion(), protocol(), theirMod, theirs);
	}

	static void onJoin(ServerPlayer player, MinecraftServer server) {
		boolean canReceive = ServerPlayNetworking.canSend(player, KbJsonPayload.TYPE);
		if (canReceive) {
			join(player, true);
			return;
		}
		if (server instanceof GameTestServer) {
			// A mock player: no client, no payloads; its tests feed the relay directly.
			return;
		}
		// Not from inside the join itself (other mods' join handlers still run for this player): on the next task.
		server.tell(new TickTask(server.getTickCount(), () -> {
			if (!player.hasDisconnected()) {
				join(player, false);
			}
		}));
	}

	/**
	 * A player joined: a client that can't receive our payload is sent away, any other gets our {@code relay.hello}
	 * (GameTests call this for a mock player with what its client would have said).
	 */
	public static void join(ServerPlayer player, boolean canReceive) {
		if (decide(canReceive, protocol(), null) == Verdict.NO_MOD) {
			long count = count("no_mod");
			KantoBridge.LOGGER.warn("relay: a player joined without kantobridge; disconnecting ({} so far)", count);
			kicker.kick(player, noModReason());
			return;
		}
		if (RelayService.get().send(player, MsgTypes.RELAY_HELLO, helloData(), null)) {
			count("sent");
		}
	}

	static void onHello(ServerPlayer player, Envelope message) {
		int theirs;
		String theirMod;
		try {
			DataReader reader = DataReader.of(message.data(), "").allowOnly(Set.of("protocol", "mod"));
			theirs = reader.integer("protocol", 1, 1000);
			theirMod = reader.string("mod", VERSION);
		} catch (DataException e) {
			count("bad_message");
			RelayService.get().sendError(player, "bad_message", message.seq());
			return;
		}
		if (decide(true, protocol(), theirs) == Verdict.PROTOCOL) {
			count("protocol");
			KantoBridge.LOGGER.warn("relay: a client speaks relay protocol {} (kantobridge {}), this server {}; disconnecting", theirs, theirMod,
					protocol());
			kicker.kick(player, protocolReason(theirs, theirMod));
			return;
		}
		AGREED.add(player.getUUID());
		count("agreed");
	}

	// ---- views and test hooks ----

	/** True once the player's client answered with our protocol (this server run). */
	public static boolean agreed(ServerPlayer player) {
		return AGREED.contains(player.getUUID());
	}

	/** Counts per outcome: {@code sent}, {@code agreed}, {@code no_mod}, {@code protocol}, {@code bad_message}. */
	public static Map<String, Long> counts() {
		Map<String, Long> out = new TreeMap<>();
		COUNTS.forEach((code, count) -> out.put(code, count.get()));
		return out;
	}

	/** Swaps how players are sent away (GameTests); null puts the real disconnect back. */
	public static void kickWith(@Nullable Kicker replacement) {
		kicker = replacement == null ? DISCONNECT : replacement;
	}

	private static long count(String code) {
		return COUNTS.computeIfAbsent(code, key -> new AtomicLong()).incrementAndGet();
	}
}
