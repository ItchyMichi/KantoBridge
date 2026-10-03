package dev.kantobridge.client.bridge;

/**
 * Reconnect delays (docs/PROTOCOL.md "Reconnect"): the initial delay, doubling up to the maximum. A successful
 * handshake resets it; after {@code hello.reject} the client waits the maximum, since retrying faster can't help.
 */
public final class Backoff {
	private final long initialMs;
	private final long maxMs;
	private long nextMs;

	public Backoff(long initialMs, long maxMs) {
		if (initialMs <= 0 || maxMs < initialMs) {
			throw new IllegalArgumentException("need 0 < initial <= max");
		}
		this.initialMs = initialMs;
		this.maxMs = maxMs;
		this.nextMs = initialMs;
	}

	/** The delay before the next attempt; each call doubles the following one, up to the maximum. */
	public long next() {
		long delay = this.nextMs;
		this.nextMs = Math.min(this.maxMs, this.nextMs * 2);
		return delay;
	}

	/** The longest delay, without changing the schedule. */
	public long max() {
		return this.maxMs;
	}

	public void reset() {
		this.nextMs = this.initialMs;
	}
}
