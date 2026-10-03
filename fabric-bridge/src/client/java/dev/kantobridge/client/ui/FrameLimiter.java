package dev.kantobridge.client.ui;

/**
 * At most {@code perSecond} uploads a second (M10: the mirror is uploaded at most 30 times a second, however fast frames
 * arrive or the screen renders). Uploads keep a steady beat: each one opens the next slot an interval later, measured
 * from the slot it took rather than from the render frame that took it, so render frames that don't line up with the
 * beat cost no rate; one that comes a whole interval late starts the beat over. A window of the last
 * {@code perSecond} uploads makes sure no second ever holds more. A frame that can't go now waits for a later render
 * frame; the mirror counts the frames a newer one replaced before they went ({@link MirrorLink#dropped}). Plain Java;
 * times in nanoseconds.
 */
public final class FrameLimiter {
	private static final long SECOND = 1_000_000_000L;

	private final long interval;
	private final long[] times;
	private int oldest;
	private int count;
	private long next = Long.MIN_VALUE;

	public FrameLimiter(int perSecond) {
		if (perSecond < 1) {
			throw new IllegalArgumentException("perSecond < 1");
		}
		this.interval = SECOND / perSecond;
		this.times = new long[perSecond];
	}

	/** True when an upload may happen now (and counts it); false when it must wait. */
	public boolean tryAcquire(long nowNanos) {
		if (this.next != Long.MIN_VALUE && nowNanos < this.next) {
			return false;
		}
		if (this.count == this.times.length && nowNanos - this.times[this.oldest] < SECOND) {
			return false;
		}
		// The beat: the next slot an interval after this one's, unless this upload came a whole interval late (a stall, an
		// idle mirror): then the beat starts over from now.
		long slot = this.next == Long.MIN_VALUE || nowNanos - this.next >= this.interval ? nowNanos : this.next;
		this.next = slot + this.interval;
		if (this.count == this.times.length) {
			this.times[this.oldest] = nowNanos;
			this.oldest = (this.oldest + 1) % this.times.length;
		} else {
			this.times[(this.oldest + this.count) % this.times.length] = nowNanos;
			this.count++;
		}
		return true;
	}

	/** Forgets the past uploads (a new link). */
	public void reset() {
		this.count = 0;
		this.oldest = 0;
		this.next = Long.MIN_VALUE;
	}
}
