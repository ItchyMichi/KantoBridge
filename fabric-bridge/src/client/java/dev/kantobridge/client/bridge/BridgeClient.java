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
 *
 * <p>gen1-bridge serves one connection at a time and a newer one replaces the older, so another connection to it
 * (the world export, {@link ExportClient}) first takes the link with {@link #pauseReconnects}: this client hangs up
 * and stays off until the pause is closed, then reconnects and gets a fresh snapshot as after any drop.
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

	/** Holds the client off gen1-bridge until closed (idempotent). See {@link #pauseReconnects}. */
	public interface Pause extends AutoCloseable {
		/** False if the client was still busy with a connection when the wait ran out. */
		boolean settled();

		/** Resumes reconnecting (once every pause is closed). */
		@Override
		void close();
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
	/** gen1-bridge's protocol number from its last refusal (-1: none since the last handshake, or not said). */
	private volatile int rejectProtocol = -1;
	/** When the next connection attempt starts, on {@link #clock} (-1: one is under way, or none is planned). */
	private volatile long retryAt = -1;
	private volatile boolean stopping;
	private @Nullable Thread thread; // guarded by lock
	private Transport.@Nullable Link link; // guarded by lock
	private @Nullable Outbox outbox; // guarded by lock
	/** Open pauses; while above 0 the socket thread opens no connection. */
	private int pauses; // guarded by lock
	private @Nullable String pauseReason; // guarded by lock
	/** True from the decision to connect until that connection is torn down; a pause waits for it to clear. */
	private boolean busy; // guarded by lock
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
		return this.enqueue(type, re, data) > 0;
	}

	/**
	 * {@link #send(String, JsonObject)} for a request whose answer names it: returns the message's {@code seq} (the answer's
	 * {@code re}), or -1 when it was dropped.
	 */
	public long sendTracked(String type, JsonObject data) {
		return this.enqueue(type, null, data);
	}

	private long enqueue(String type, @Nullable Long re, JsonObject data) {
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
				return -1;
			}
			return box.enqueue(type, re, data);
		}
	}

	/**
	 * Takes this client off gen1-bridge so another connection can have the link: closes the current connection (if
	 * any), opens no new one until the returned pause is closed, and waits up to {@code waitMs} until no connection is
	 * open or being opened. Pauses nest; reconnecting resumes when the last one closes, with the backoff reset.
	 *
	 * @param reason a short code for logs and the status line
	 */
	public Pause pauseReconnects(String reason, long waitMs) throws InterruptedException {
		Transport.Link open;
		synchronized (this.lock) {
			this.pauses++;
			this.pauseReason = reason;
			open = this.link;
		}
		if (open != null) {
			// The reader fails at once and the session ends; serve() sees the pause and doesn't back off.
			open.close();
		}
		long deadline = System.nanoTime() + Math.max(0, waitMs) * 1_000_000L;
		boolean settled;
		synchronized (this.lock) {
			while (this.busy && !this.stopping) {
				long left = deadline - System.nanoTime();
				if (left <= 0) {
					break;
				}
				this.lock.wait(Math.max(1, left / 1_000_000L));
			}
			settled = !this.busy || this.stopping;
		}
		if (!settled) {
			LOGGER.warn("bridge: still busy with a connection after {} ms; pausing anyway", waitMs);
		} else {
			LOGGER.info("bridge: paused ({})", reason);
		}
		boolean[] closed = {false};
		return new Pause() {
			@Override
			public boolean settled() {
				return settled;
			}

			@Override
			public void close() {
				synchronized (BridgeClient.this.lock) {
					if (closed[0]) {
						return;
					}
					closed[0] = true;
					BridgeClient.this.pauses--;
					BridgeClient.this.lock.notifyAll();
				}
				LOGGER.info("bridge: resumed after {}", reason);
			}
		};
	}

	/** True while a pause holds the client off gen1-bridge. */
	public boolean paused() {
		synchronized (this.lock) {
			return this.pauses > 0;
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
			this.lock.notifyAll();
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

	/** gen1-bridge's protocol number from its last refusal, or -1 (M10: the overlay names both versions). */
	public int rejectProtocol() {
		return this.rejectProtocol;
	}

	/**
	 * When the next connection attempt starts, in this client's clock's milliseconds; -1 while one is under way (or the
	 * client is connected, paused or stopped). The pause overlay counts down to it.
	 */
	public long retryAt() {
		return this.retryAt;
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
			while (this.awaitTurn(backoff)) {
				long delay;
				try {
					delay = this.attempt(backoff);
				} catch (RuntimeException e) {
					// A bug must not kill the thread for the rest of the session; content never reaches this log.
					StackTraceElement[] where = e.getStackTrace();
					LOGGER.error("bridge: unexpected {} at {}", e.getClass().getName(), where.length > 0 ? where[0] : "?");
					this.setState(ConnectionState.DISCONNECTED, "internal");
					delay = backoff.max();
				} finally {
					this.endTurn();
				}
				if (delay > 0 && !this.stopping) {
					this.retryAt = this.clock.millis() + delay;
					try {
						this.clock.sleep(delay);
					} finally {
						this.retryAt = -1;
					}
				}
			}
		} catch (InterruptedException e) {
			// close() interrupts the backoff sleep and a paused wait.
		} finally {
			this.setState(ConnectionState.STOPPED, null);
		}
	}

	/**
	 * Waits while paused, then claims the next connection attempt (marks the client busy, so a pause can wait for it).
	 * Returns false once stopping.
	 */
	private boolean awaitTurn(Backoff backoff) throws InterruptedException {
		while (true) {
			String reason;
			synchronized (this.lock) {
				if (this.stopping) {
					return false;
				}
				if (this.pauses == 0) {
					this.busy = true;
					return true;
				}
				reason = this.pauseReason;
			}
			this.setState(ConnectionState.PAUSED, reason);
			synchronized (this.lock) {
				while (this.pauses > 0 && !this.stopping) {
					this.lock.wait();
				}
			}
			// Whatever failed before the pause says nothing about gen1-bridge now: start the backoff over.
			backoff.reset();
		}
	}

	private void endTurn() {
		synchronized (this.lock) {
			this.busy = false;
			this.lock.notifyAll();
		}
	}

	/** One connection attempt; returns how long to wait before the next one. */
	private long attempt(Backoff backoff) {
		this.setState(ConnectionState.CONNECTING, null);
		Transport.Link opened;
		try {
			opened = this.transport.connect(this.config.host(), this.config.port(), this.config.connectTimeoutMs(), this.config.tickMs());
		} catch (IOException e) {
			if (this.paused()) {
				return 0;
			}
			this.setState(ConnectionState.DISCONNECTED, "unreachable");
			return backoff.next();
		}
		this.stats.connects.incrementAndGet();
		String reason;
		try {
			reason = this.serve(opened, backoff);
		} catch (InterruptedException e) {
			// Only close() interrupts this thread; it is stopping.
			Thread.currentThread().interrupt();
			return 0;
		}
		if (this.stopping || this.paused()) {
			// A pause hung up on purpose: no backoff, and the next turn reports PAUSED.
			return 0;
		}
		if (this.state == ConnectionState.REJECTED) {
			return backoff.max();
		}
		this.setState(ConnectionState.DISCONNECTED, reason);
		return backoff.next();
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
			public void rejected(String reason, int protocol) {
				BridgeClient.this.rejectProtocol = protocol;
				BridgeClient.this.setState(ConnectionState.REJECTED, reason);
			}

			@Override
			public void closed(String reason) {
				endReason[0] = reason;
			}
		});
		synchronized (this.lock) {
			if (this.stopping || this.pauses > 0) {
				// A pause arrived while connecting: hang up before saying hello.
				opened.close();
				return this.stopping ? "stopped" : "paused";
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
		this.rejectProtocol = -1;
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
