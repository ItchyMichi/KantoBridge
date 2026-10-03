package dev.kantobridge.client.bridge;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.ProtocolException;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client's connection to gen1-bridge (docs/PROTOCOL.md, PLAN §4.3 {@code bridge/}).
 *
 * <p>One daemon socket thread connects to {@code 127.0.0.1:<port>}, reconnects with backoff (0.5 s doubling to
 * 5 s, reset by a handshake; the longest wait after {@code hello.reject}), reads NDJSON lines and runs each
 * connection's {@link BridgeSession}. A second thread per connection writes the {@link Outbox}, so
 * {@link #send} never blocks its caller.
 *
 * <p>No Minecraft classes are used here: listeners are called on the socket thread, and the game hands messages to
 * its own thread through {@link Dispatcher} (PLAN §2.3).
 */
public final class BridgeClient implements AutoCloseable {
	public static final String ROLE = "client";
	private static final Logger LOGGER = LoggerFactory.getLogger("kantobridge");
	private static final int READ_BUFFER = 64 * 1024;

	/** Receives the connection's events, on the socket thread. */
	public interface Listener {
		default void stateChanged(ConnectionState state, @Nullable String detail) {
		}

		/** A message from gen1-bridge that passed the envelope and catalog checks. */
		default void message(Envelope message) {
		}
	}

	private final BridgeConfig config;
	private final Catalog catalog;
	private final Supplier<HelloInfo> helloInfo;
	private final Transport transport;
	private final BridgeClock clock;
	private final Listener listener;
	private final BridgeStats stats = new BridgeStats();
	private final Object lock = new Object();

	private volatile ConnectionState state = ConnectionState.IDLE;
	private volatile @Nullable String detail;
	private volatile @Nullable HelloOk handshake;
	private volatile boolean stopping;
	private @Nullable Thread thread; // guarded by lock
	private Transport.@Nullable Link link; // guarded by lock
	private @Nullable Outbox outbox; // guarded by lock
	private @Nullable String lastSession; // socket thread only

	public BridgeClient(BridgeConfig config, Catalog catalog, Supplier<HelloInfo> helloInfo, Transport transport, BridgeClock clock,
			Listener listener) {
		this.config = Objects.requireNonNull(config);
		this.catalog = Objects.requireNonNull(catalog);
		this.helloInfo = Objects.requireNonNull(helloInfo);
		this.transport = Objects.requireNonNull(transport);
		this.clock = Objects.requireNonNull(clock);
		this.listener = Objects.requireNonNull(listener);
	}

	/** Starts the socket thread (once). */
	public void start() {
		synchronized (this.lock) {
			if (this.thread != null || this.stopping) {
				return;
			}
			Thread socketThread = new Thread(this::run, "kantobridge-bridge");
			socketThread.setDaemon(true);
			this.thread = socketThread;
			socketThread.start();
		}
	}

	/**
	 * Queues a message for gen1-bridge. Never blocks.
	 *
	 * @return false if not connected (the message is dropped; the caller resends from state after the next snapshot)
	 * @throws IllegalArgumentException if the type may not travel client → gen1-bridge, or breaks its reply rule
	 */
	public boolean send(String type, JsonObject data) {
		return this.send(type, null, data);
	}

	/** {@link #send(String, JsonObject)} for replies, with the envelope's {@code re}. */
	public boolean send(String type, @Nullable Long re, JsonObject data) {
		Catalog.Entry entry = this.catalog.get(type);
		if (entry == null || !entry.legs().contains(Catalog.Leg.M2G)) {
			throw new IllegalArgumentException("not a client-to-gen1-bridge message type: " + type);
		}
		if ((entry.re() == Catalog.RePolicy.REQUIRED && re == null) || (entry.re() == Catalog.RePolicy.NEVER && re != null)) {
			throw new IllegalArgumentException("reply rule broken for " + type);
		}
		synchronized (this.lock) {
			Outbox box = this.outbox;
			if (box == null || this.state != ConnectionState.CONNECTED) {
				return false;
			}
			return box.enqueue(type, re, data) > 0;
		}
	}

	/** Stops for good: closes the connection and ends the socket thread. Idempotent. */
	@Override
	public void close() {
		Thread socketThread;
		synchronized (this.lock) {
			this.stopping = true;
			if (this.link != null) {
				this.link.close();
			}
			socketThread = this.thread;
		}
		if (socketThread != null && socketThread != Thread.currentThread()) {
			socketThread.interrupt();
			try {
				socketThread.join(2000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		this.setState(ConnectionState.STOPPED, null);
	}

	public ConnectionState state() {
		return this.state;
	}

	/** Why the client is rejected or disconnected (a short code), or the game once connected. */
	public @Nullable String detail() {
		return this.detail;
	}

	/** The current handshake, or null when not connected. */
	public @Nullable HelloOk handshake() {
		return this.handshake;
	}

	public BridgeStats stats() {
		return this.stats;
	}

	public BridgeConfig config() {
		return this.config;
	}

	// ---- socket thread ----

	private void run() {
		Backoff backoff = new Backoff(this.config.reconnectInitialMs(), this.config.reconnectMaxMs());
		try {
			while (!this.stopping) {
				try {
					this.attempt(backoff);
				} catch (RuntimeException e) {
					// A bug must not kill the thread for the rest of the session; content never reaches this log.
					StackTraceElement[] where = e.getStackTrace();
					LOGGER.error("bridge: unexpected {} at {}", e.getClass().getName(), where.length > 0 ? where[0] : "?");
					this.setState(ConnectionState.DISCONNECTED, "internal");
					this.clock.sleep(backoff.max());
				}
			}
		} catch (InterruptedException e) {
			// close() interrupts the backoff sleep.
		} finally {
			this.setState(ConnectionState.STOPPED, null);
		}
	}

	private void attempt(Backoff backoff) throws InterruptedException {
		this.setState(ConnectionState.CONNECTING, null);
		Transport.Link opened;
		try {
			opened = this.transport.connect(this.config.host(), this.config.port(), this.config.connectTimeoutMs(), this.config.tickMs());
		} catch (IOException e) {
			this.setState(ConnectionState.DISCONNECTED, "unreachable");
			this.clock.sleep(backoff.next());
			return;
		}
		this.stats.connects.incrementAndGet();
		String reason = this.serve(opened, backoff);
		if (this.stopping) {
			return;
		}
		if (this.state == ConnectionState.REJECTED) {
			this.clock.sleep(backoff.max());
		} else {
			this.setState(ConnectionState.DISCONNECTED, reason);
			this.clock.sleep(backoff.next());
		}
	}

	/** Runs one connection until it ends; returns why it ended. */
	private String serve(Transport.Link opened, Backoff backoff) throws InterruptedException {
		Outbox box = new Outbox(this.config.maxLineBytes(), this.stats);
		String[] endReason = {"closed"};
		BridgeSession session = new BridgeSession(this.catalog, this.config, this.helloData(), this.stats, new BridgeSession.Output() {
			@Override
			public long send(String type, @Nullable Long re, JsonObject data) {
				return box.enqueue(type, re, data);
			}

			@Override
			public void deliver(Envelope message) {
				BridgeClient.this.listener.message(message);
			}

			@Override
			public void connected(HelloOk ok) {
				BridgeClient.this.handshaken(ok, backoff);
			}

			@Override
			public void rejected(String reason) {
				BridgeClient.this.setState(ConnectionState.REJECTED, reason);
			}

			@Override
			public void closed(String reason) {
				endReason[0] = reason;
			}
		});
		synchronized (this.lock) {
			if (this.stopping) {
				opened.close();
				return "stopped";
			}
			this.link = opened;
			this.outbox = box;
		}
		Thread writer = new Thread(() -> this.write(opened, box), "kantobridge-bridge-writer");
		writer.setDaemon(true);
		this.setState(ConnectionState.HANDSHAKE, null);
		writer.start();
		try {
			session.open(this.clock.millis());
			this.read(opened, session, box);
		} catch (ProtocolException e) {
			session.close(e.code());
		} catch (IOException e) {
			session.close(this.stopping ? "stopped" : "io");
		} finally {
			synchronized (this.lock) {
				this.link = null;
				this.outbox = null;
			}
			this.handshake = null;
			session.close(this.stopping ? "stopped" : "closed");
			box.shut();
			opened.close();
			writer.join(1000);
		}
		return endReason[0];
	}

	private void read(Transport.Link opened, BridgeSession session, Outbox box) throws IOException, ProtocolException {
		LineFramer framer = new LineFramer(this.config.maxLineBytes());
		InputStream in = opened.input();
		byte[] buffer = new byte[READ_BUFFER];
		while (!this.stopping && !session.isClosed()) {
			int count;
			try {
				count = in.read(buffer);
			} catch (SocketTimeoutException idle) {
				count = 0;
			}
			long now = this.clock.millis();
			if (count < 0) {
				session.close("eof");
				return;
			}
			if (count > 0) {
				for (LineFramer.Line line : framer.feed(buffer, 0, count)) {
					session.receive(line.text(), line.bytes(), now);
					if (session.isClosed()) {
						return;
					}
				}
			}
			if (box.overflowed()) {
				session.close("backlog");
				return;
			}
			session.tick(now);
		}
	}

	private void write(Transport.Link opened, Outbox box) {
		try {
			OutputStream out = new BufferedOutputStream(opened.output(), READ_BUFFER);
			byte[] line;
			while ((line = box.take()) != null) {
				out.write(line);
				if (box.isEmpty()) {
					out.flush();
				}
			}
			out.flush();
		} catch (IOException e) {
			// The reader notices the closed link and ends the session.
			opened.close();
		} catch (InterruptedException e) {
			opened.close();
		}
	}

	private void handshaken(HelloOk ok, Backoff backoff) {
		backoff.reset();
		this.stats.handshakes.incrementAndGet();
		if (this.lastSession != null) {
			(this.lastSession.equals(ok.session()) ? this.stats.reconnects : this.stats.restarts).incrementAndGet();
		}
		this.lastSession = ok.session();
		this.handshake = ok;
		this.setState(ConnectionState.CONNECTED, ok.game());
	}

	private JsonObject helloData() {
		HelloInfo info = null;
		try {
			info = this.helloInfo.get();
		} catch (RuntimeException e) {
			LOGGER.warn("bridge: could not collect hello info ({})", e.getClass().getSimpleName());
		}
		if (info == null) {
			info = new HelloInfo("unknown", null, null, null);
		}
		return info.toData(this.catalog.protocol(), ROLE);
	}

	private void setState(ConnectionState next, @Nullable String why) {
		ConnectionState previous;
		synchronized (this.lock) {
			previous = this.state;
			if (previous == ConnectionState.STOPPED || (previous == next && Objects.equals(this.detail, why))) {
				return;
			}
			this.state = next;
			this.detail = why;
		}
		// While Gen1Recomp is closed the client cycles CONNECTING/DISCONNECTED every few seconds: keep that quiet.
		boolean routine = next == ConnectionState.CONNECTING || next == ConnectionState.HANDSHAKE
				|| (next == ConnectionState.DISCONNECTED && previous != ConnectionState.CONNECTED);
		if (routine) {
			LOGGER.debug("bridge: {}{}", next, why == null ? "" : " (" + why + ")");
		} else {
			LOGGER.info("bridge: {}{}", next, why == null ? "" : " (" + why + ")");
		}
		this.listener.stateChanged(next, why);
	}
}
