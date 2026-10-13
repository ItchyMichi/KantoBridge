package dev.kantobridge.client.net;

import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.client.world.ClientRegions;
import dev.kantobridge.client.world.RegionTags;
import dev.kantobridge.net.KbJsonPayload;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.ProtocolException;
import dev.kantobridge.proto.Redact;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.jetbrains.annotations.Nullable;

/**
 * The client end of the server relay (docs/PLAN.md §3.3): this player's story data goes up, battle results and
 * Cobblemon state come down. Receivers run on the client thread; send from the client thread too, so relayed
 * story changes stay ordered with movement packets.
 */
public final class ClientRelay {
	/** Handles one accepted server message on the client thread. */
	@FunctionalInterface
	public interface Handler {
		void handle(Envelope message);
	}

	private static final ClientRelay INSTANCE = new ClientRelay(Catalog.bundled());

	private final Catalog catalog;
	private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
	private long nextSeq = 1;

	ClientRelay(Catalog catalog) {
		this.catalog = catalog;
	}

	public static ClientRelay get() {
		return INSTANCE;
	}

	/** Registers the client receiver. Call once from client init (the payload type is registered in common init). */
	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(KbJsonPayload.TYPE, (payload, context) -> INSTANCE.receive(payload.json()));
		// The protocol counts seq per connection.
		ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> INSTANCE.nextSeq = 1);
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> INSTANCE.nextSeq = 1);
	}

	/** Sets the handler for a server-to-client type; the type must have an {@code s2c} leg. */
	public void register(String type, Handler handler) {
		Catalog.Entry entry = this.catalog.get(type);
		if (entry == null || !entry.legs().contains(Catalog.Leg.S2C)) {
			throw new IllegalArgumentException("not a server-to-client message type: " + type);
		}
		this.handlers.put(type, handler);
	}

	void receive(String json) {
		int bytes = json.getBytes(StandardCharsets.UTF_8).length;
		Envelope message;
		try {
			message = Envelope.parse(json);
			this.catalog.check(message, Catalog.Leg.S2C);
		} catch (ProtocolException e) {
			KantoBridge.LOGGER.warn("relay: dropped a server payload ({} bytes): {}", bytes, e.code());
			return;
		}
		Handler handler = this.handlers.get(message.type());
		if (handler == null) {
			KantoBridge.LOGGER.debug("relay: no handler yet for {}", Redact.summary(message, bytes));
			return;
		}
		handler.handle(message);
	}

	/** Sends one message to the server. Returns false when not connected, not a c2s type, or too big. */
	public boolean send(String type, JsonObject data, @Nullable Long re) {
		return this.post(type, data, re) > 0;
	}

	/** True while a server connection can take payloads (the relay is the client's only way up). */
	public static boolean canSend() {
		return ClientPlayNetworking.canSend(KbJsonPayload.TYPE);
	}

	/**
	 * {@link #send}, returning the message's {@code seq} (which the server's reply carries as {@code re}), or -1 when it
	 * wasn't sent.
	 */
	public long post(String type, JsonObject data, @Nullable Long re) {
		// MG3 (docs/PROTOCOL.md "Regions"): in a region world every relayed type carries the followed region; a legacy world
		// sends today's bytes.
		return this.post(type, data, re, ClientRegions.tagFor());
	}

	/**
	 * {@link #post(String, JsonObject, Long)} tagged with {@code region} (MG4: a link's own region, which may differ from the
	 * followed one), or untagged when it is null. A message that names its region already keeps it.
	 */
	public long post(String type, JsonObject data, @Nullable Long re, @Nullable String region) {
		if (!canSend()) {
			return -1;
		}
		if (region != null && RegionTags.tagged(type) && !data.has("region")) {
			data = data.deepCopy();
			data.addProperty("region", region);
		}
		Envelope message = new Envelope(type, this.nextSeq, re, data);
		try {
			this.catalog.check(message, Catalog.Leg.C2S);
		} catch (ProtocolException e) {
			KantoBridge.LOGGER.error("relay: refusing to send {}: {}", type, e.code());
			return -1;
		}
		String json = message.toJson();
		int bytes = json.getBytes(StandardCharsets.UTF_8).length;
		if (bytes > this.catalog.relayMaxBytes()) {
			KantoBridge.LOGGER.error("relay: {} is over the relay limit", Redact.summary(message, bytes));
			return -1;
		}
		this.nextSeq++;
		ClientPlayNetworking.send(new KbJsonPayload(json));
		return message.seq();
	}
}
