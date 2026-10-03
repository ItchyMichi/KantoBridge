package dev.kantobridge.net;

import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.proto.ProtocolException;
import dev.kantobridge.proto.Redact;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * The server end of the client relay (docs/PLAN.md §3.3, §4.4 {@code RelayService}). Each player's client sends
 * its own story data here; the server never talks to Gen1Recomp.
 *
 * <p>Payload handlers run on the server thread. Every message is parsed strictly and must be a known type whose
 * catalog legs include {@code c2s}; logs carry only type, numbers and sizes.
 */
public final class RelayService {
	/** Handles one accepted client message on the server thread. */
	@FunctionalInterface
	public interface Handler {
		void handle(ServerPlayer player, Envelope message);
	}

	private static final RelayService INSTANCE = new RelayService(Catalog.bundled());

	private final Catalog catalog;
	private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
	// Outgoing seq per player connection; the protocol counts per sender and per connection.
	private final Map<UUID, Long> nextSeq = new ConcurrentHashMap<>();
	/** Observers of accepted outgoing messages; GameTests use them in place of a client. */
	private final List<BiConsumer<ServerPlayer, Envelope>> taps = new CopyOnWriteArrayList<>();

	RelayService(Catalog catalog) {
		this.catalog = catalog;
	}

	public static RelayService get() {
		return INSTANCE;
	}

	/** Registers the payload in both directions and the server receiver. Call once from common init. */
	public static void init() {
		PayloadTypeRegistry.playC2S().register(KbJsonPayload.TYPE, KbJsonPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(KbJsonPayload.TYPE, KbJsonPayload.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(KbJsonPayload.TYPE,
				(payload, context) -> INSTANCE.receive(context.player(), payload.json()));
		ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> INSTANCE.nextSeq.remove(listener.getPlayer().getUUID()));
	}

	/** Sets the handler for a client-to-server type; the type must have a {@code c2s} leg. */
	public void register(String type, Handler handler) {
		Catalog.Entry entry = this.catalog.get(type);
		if (entry == null || !entry.legs().contains(Catalog.Leg.C2S)) {
			throw new IllegalArgumentException("not a client-to-server message type: " + type);
		}
		this.handlers.put(type, handler);
	}

	public void unregister(String type) {
		this.handlers.remove(type);
	}

	/** The handler registered for {@code type}, or null; tests that stand in for one put it back afterwards. */
	public @Nullable Handler handler(String type) {
		return this.handlers.get(type);
	}

	/**
	 * Observes every message {@link #send} accepts, before it goes out (mock players have no client to receive it).
	 * Close the returned handle to stop.
	 */
	public AutoCloseable tap(BiConsumer<ServerPlayer, Envelope> observer) {
		this.taps.add(observer);
		return () -> this.taps.remove(observer);
	}

	/** Entry point for one relay payload from {@code player}'s client; public so GameTests can feed it directly. */
	public void receive(ServerPlayer player, String json) {
		int bytes = json.getBytes(StandardCharsets.UTF_8).length;
		Envelope message;
		try {
			message = Envelope.parse(json);
		} catch (ProtocolException e) {
			KantoBridge.LOGGER.warn("relay: dropped a malformed payload ({} bytes): {}", bytes, e.code());
			this.sendError(player, e.code(), null);
			return;
		}
		try {
			this.catalog.check(message, Catalog.Leg.C2S);
		} catch (ProtocolException e) {
			KantoBridge.LOGGER.warn("relay: rejected {}: {}", Redact.summary(message, bytes), e.code());
			this.sendError(player, e.code(), message.seq());
			return;
		}
		Handler handler = this.handlers.get(message.type());
		if (handler == null) {
			KantoBridge.LOGGER.debug("relay: no handler yet for {}", Redact.summary(message, bytes));
			return;
		}
		KantoBridge.LOGGER.debug("relay: from client {}", Redact.summary(message, bytes));
		handler.handle(player, message);
	}

	/** Sends one message to {@code player}'s client. Returns false if the type may not travel s2c or is too big. */
	public boolean send(ServerPlayer player, String type, JsonObject data, @Nullable Long re) {
		Envelope message = new Envelope(type, this.takeSeq(player.getUUID()), re, data);
		try {
			this.catalog.check(message, Catalog.Leg.S2C);
		} catch (ProtocolException e) {
			KantoBridge.LOGGER.error("relay: refusing to send {}: {}", type, e.code());
			return false;
		}
		String json = message.toJson();
		int bytes = json.getBytes(StandardCharsets.UTF_8).length;
		if (bytes > this.catalog.relayMaxBytes()) {
			KantoBridge.LOGGER.error("relay: {} is over the relay limit", Redact.summary(message, bytes));
			return false;
		}
		for (BiConsumer<ServerPlayer, Envelope> tap : this.taps) {
			tap.accept(player, message);
		}
		ServerPlayNetworking.send(player, new KbJsonPayload(json));
		return true;
	}

	/** Answers a client message the server could not use with {@code error{code}} (a short code, never data). */
	public void sendError(ServerPlayer player, String code, @Nullable Long re) {
		JsonObject data = new JsonObject();
		data.addProperty("code", code);
		data.addProperty("msg", "rejected by the server relay");
		this.send(player, MsgTypes.ERROR, data, re);
	}

	private long takeSeq(UUID player) {
		return this.nextSeq.merge(player, 1L, Long::sum);
	}
}
