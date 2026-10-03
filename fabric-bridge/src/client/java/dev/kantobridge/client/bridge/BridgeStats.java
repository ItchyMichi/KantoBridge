package dev.kantobridge.client.bridge;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters for the HUD, the debug overlay and tests. Written by the socket thread (and {@code send} callers), read
 * from any thread.
 */
public final class BridgeStats {
	/** TCP connections opened. */
	final AtomicLong connects = new AtomicLong();
	/** Handshakes completed ({@code hello.ok}). */
	final AtomicLong handshakes = new AtomicLong();
	/** {@code hello.ok} with a new session id after an earlier one: gen1-bridge restarted (or hot-reloaded). */
	final AtomicLong restarts = new AtomicLong();
	/** {@code hello.ok} with the same session id as before: only the connection was lost. */
	final AtomicLong reconnects = new AtomicLong();
	final AtomicLong messagesIn = new AtomicLong();
	final AtomicLong messagesOut = new AtomicLong();
	final AtomicLong bytesIn = new AtomicLong();
	final AtomicLong bytesOut = new AtomicLong();
	final AtomicLong pongs = new AtomicLong();
	/** Incoming messages that broke the protocol (answered with {@code error}). */
	final AtomicLong protocolErrors = new AtomicLong();
	/** Connections closed for any reason. */
	final AtomicLong drops = new AtomicLong();
	volatile long lastRttMs = -1;

	/** A consistent-enough copy of every counter. */
	public Snapshot snapshot() {
		return new Snapshot(this.connects.get(), this.handshakes.get(), this.restarts.get(), this.reconnects.get(),
				this.messagesIn.get(), this.messagesOut.get(), this.bytesIn.get(), this.bytesOut.get(), this.pongs.get(),
				this.protocolErrors.get(), this.drops.get(), this.lastRttMs);
	}

	public record Snapshot(long connects, long handshakes, long restarts, long reconnects, long messagesIn, long messagesOut,
			long bytesIn, long bytesOut, long pongs, long protocolErrors, long drops, long lastRttMs) {
	}
}
