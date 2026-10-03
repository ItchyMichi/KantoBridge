package dev.kantobridge.client.bridge;

/**
 * Time for the bridge connection: a monotonic millisecond clock and a sleep. Injected so tests can record backoff
 * waits instead of sleeping through them.
 */
public interface BridgeClock {
	/** Milliseconds since an arbitrary start; never negative, never goes backwards. */
	long millis();

	/** Waits {@code ms} milliseconds; an interrupt ends the wait early with {@link InterruptedException}. */
	void sleep(long ms) throws InterruptedException;

	/** The real clock, counting from its creation (System.nanoTime itself may be negative). */
	static BridgeClock system() {
		long start = System.nanoTime();
		return new BridgeClock() {
			@Override
			public long millis() {
				return (System.nanoTime() - start) / 1_000_000L;
			}

			@Override
			public void sleep(long ms) throws InterruptedException {
				if (ms > 0) {
					Thread.sleep(ms);
				}
			}
		};
	}
}
