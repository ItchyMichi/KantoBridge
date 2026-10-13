package dev.kantobridge.client.bridge;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.proto.ProtocolException;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The protocol logic of one connection to gen1-bridge (docs/PROTOCOL.md "Handshake" and "Transport"): the
 * {@code hello} handshake, the heartbeat, the silence timeout and the catalog checks on every incoming message.
 *
 * <p>It does no I/O and has no threads: {@link BridgeClient}'s socket thread feeds it lines and timer ticks, and it
 * answers through {@link Output}. Tests drive it with plain calls and a hand-set clock.
 */
final class BridgeSession {
	private static final Logger LOGGER = LoggerFactory.getLogger("kantobridge");
	private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_.]{0,63}$");
	/** {@code hello.ok.tested}'s format (a version only). */
	private static final Pattern VERSION = Pattern.compile("^[0-9A-Za-z.+_-]{1,32}$");
	/** Protocol errors logged at warn per connection; later ones only count. */
	private static final int WARN_LIMIT = 20;

	/** What a session asks of its connection. */
	interface Output {
		/** Queues a message and returns its seq. */
		long send(String type, @Nullable Long re, JsonObject data);

		/** A checked message for the rest of the client (handshake and heartbeat traffic stays here). */
		void deliver(Envelope message);

		void connected(HelloOk ok);

		/**
		 * gen1-bridge refused this client: {@code reason} is a short code ({@code protocol} when the protocol numbers differ);
		 * {@code protocol} is gen1-bridge's protocol number, or -1 when it didn't say.
		 */
		void rejected(String reason, int protocol);

		/** The session ended; the connection must be closed. Called once. */
		void closed(String reason);
	}

	private enum Phase {
		HANDSHAKE,
		CONNECTED,
		CLOSED
	}

	private final Catalog catalog;
	private final BridgeConfig config;
	private final JsonObject helloData;
	private final BridgeStats stats;
	private final Output out;
	private Phase phase = Phase.HANDSHAKE;
	private long helloSeq;
	private long lastReceiveMs;
	private long lastPingMs;
	private int warnings;

	BridgeSession(Catalog catalog, BridgeConfig config, JsonObject helloData, BridgeStats stats, Output out) {
		this.catalog = catalog;
		this.config = config;
		this.helloData = helloData;
		this.stats = stats;
		this.out = out;
	}

	/** The connection is open: say hello first. */
	void open(long nowMs) {
		this.lastReceiveMs = nowMs;
		this.helloSeq = this.out.send(MsgTypes.HELLO, null, this.helloData);
	}

	boolean isClosed() {
		return this.phase == Phase.CLOSED;
	}

	boolean isConnected() {
		return this.phase == Phase.CONNECTED;
	}

	/** One complete line from gen1-bridge. */
	void receive(String line, int bytes, long nowMs) {
		if (this.phase == Phase.CLOSED) {
			return;
		}
		this.lastReceiveMs = nowMs;
		this.stats.messagesIn.incrementAndGet();
		this.stats.bytesIn.addAndGet(bytes);
		Envelope message;
		try {
			message = Envelope.parse(line);
		} catch (ProtocolException e) {
			this.protocolError(null, "bad_envelope", e, bytes);
			return;
		}
		try {
			this.catalog.check(message, Catalog.Leg.G2M);
		} catch (ProtocolException e) {
			this.protocolError(message, e.code().equals("catalog.type") ? "unknown_type" : e.code(), e, bytes);
			return;
		}
		if (this.phase == Phase.HANDSHAKE) {
			this.handshake(message, nowMs);
		} else {
			this.connected(message, nowMs);
		}
	}

	/** Timers: the silence timeout and, once connected, the ping. */
	void tick(long nowMs) {
		if (this.phase == Phase.CLOSED) {
			return;
		}
		if (nowMs - this.lastReceiveMs >= this.config.timeoutMs()) {
			this.close("timeout");
			return;
		}
		if (this.phase == Phase.CONNECTED && nowMs - this.lastPingMs >= this.config.pingMs()) {
			JsonObject ping = new JsonObject();
			ping.addProperty("t", nowMs);
			this.out.send(MsgTypes.PING, null, ping);
			this.lastPingMs = nowMs;
		}
	}

	/** Ends the session (idempotent). */
	void close(String reason) {
		if (this.phase == Phase.CLOSED) {
			return;
		}
		this.phase = Phase.CLOSED;
		this.stats.drops.incrementAndGet();
		this.out.closed(reason);
	}

	private void handshake(Envelope message, long nowMs) {
		JsonObject data = message.data();
		switch (message.type()) {
			case MsgTypes.HELLO_OK -> {
				if (message.re() == null || message.re() != this.helloSeq) {
					this.warn("handshake.re", message);
					this.close("handshake.re");
					return;
				}
				long protocol = JsonFields.integer(data, "protocol", -1);
				if (protocol != this.catalog.protocol()) {
					this.out.rejected("protocol", protocolNumber(protocol));
					this.close("rejected");
					return;
				}
				String session = JsonFields.str(data, "session");
				String game = JsonFields.str(data, "game");
				String engine = JsonFields.str(data, "engine");
				String bridge = JsonFields.str(data, "bridge");
				if (session == null || game == null || engine == null || bridge == null) {
					this.warn("handshake.malformed", message);
					this.close("handshake.malformed");
					return;
				}
				String tested = JsonFields.str(data, "tested");
				this.phase = Phase.CONNECTED;
				this.lastPingMs = nowMs;
				// MG1: the family and the adapter's limits, or the catalogue's for the game when the message doesn't say
				this.out.connected(new HelloOk(session, game, engine, bridge, tested != null && VERSION.matcher(tested).matches() ? tested : null,
						HelloOk.unionRoomField(JsonFields.str(data, "union_room")), HelloOk.familyField(JsonFields.str(data, "family"), game),
						HelloOk.compatField(JsonFields.obj(data, "compat"), game)));
				this.out.deliver(message);
			}
			case MsgTypes.HELLO_REJECT -> {
				String reason = JsonFields.str(data, "reason");
				this.out.rejected(reason != null && CODE.matcher(reason).matches() ? reason : "unknown",
						protocolNumber(JsonFields.integer(data, "protocol", -1)));
				this.close("rejected");
			}
			case MsgTypes.ERROR -> this.warn(this.errorCode(data), message);
			// gen1-bridge sends nothing else before hello.ok; anything that does arrive belongs to no session yet.
			default -> this.warn("before_hello_ok", message);
		}
	}

	private void connected(Envelope message, long nowMs) {
		JsonObject data = message.data();
		switch (message.type()) {
			case MsgTypes.PING -> {
				long t = JsonFields.integer(data, "t", -1);
				if (t < 0) {
					this.protocolError(message, "bad_message", null, 0);
					return;
				}
				JsonObject pong = new JsonObject();
				pong.addProperty("t", t);
				this.out.send(MsgTypes.PONG, message.seq(), pong);
			}
			case MsgTypes.PONG -> {
				long t = JsonFields.integer(data, "t", -1);
				this.stats.pongs.incrementAndGet();
				if (t >= 0 && t <= nowMs) {
					this.stats.lastRttMs = nowMs - t;
				}
			}
			case MsgTypes.HELLO_OK, MsgTypes.HELLO_REJECT -> this.warn("handshake.repeat", message);
			case MsgTypes.ERROR -> {
				this.warn(this.errorCode(data), message);
				this.out.deliver(message);
			}
			default -> this.out.deliver(message);
		}
	}

	/** Counts, logs and answers a message that broke the protocol. Never answers an {@code error} with one. */
	private void protocolError(@Nullable Envelope message, String code, @Nullable ProtocolException cause, int bytes) {
		this.stats.protocolErrors.incrementAndGet();
		if (this.warnings++ < WARN_LIMIT) {
			StringBuilder facts = new StringBuilder();
			if (cause != null && !cause.code().equals(code)) {
				facts.append(cause.code()).append(", ");
			}
			if (message != null) {
				facts.append("type=").append(message.type()).append(" seq=").append(message.seq()).append(", ");
			}
			LOGGER.warn("bridge: rejected a message from gen1-bridge: {} ({}bytes={})", code, facts, bytes);
		}
		if (message != null && message.type().equals(MsgTypes.ERROR)) {
			return;
		}
		JsonObject error = new JsonObject();
		error.addProperty("code", code);
		// ProtocolException messages never quote message content (see its javadoc), so they are safe to send back.
		String text = cause == null ? "message failed the client's checks" : cause.getMessage();
		error.addProperty("msg", text.length() > 500 ? text.substring(0, 500) : text);
		this.out.send(MsgTypes.ERROR, message == null ? null : message.seq(), error);
	}

	/** A peer's protocol number as the HUD may show it, or -1. */
	private static int protocolNumber(long protocol) {
		return protocol >= 1 && protocol <= 1000 ? (int) protocol : -1;
	}

	private String errorCode(JsonObject data) {
		String code = JsonFields.str(data, "code");
		return code != null && CODE.matcher(code).matches() ? "peer." + code : "peer.error";
	}

	private void warn(String what, Envelope message) {
		if (this.warnings++ < WARN_LIMIT) {
			LOGGER.warn("bridge: {} (type={} seq={})", what, message.type(), message.seq());
		}
	}
}
