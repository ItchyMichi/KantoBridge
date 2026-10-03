package dev.kantobridge.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The mirror's upload limit (M10): at most 30 uploads in any second, never two right after each other, and smooth at
 * common refresh rates; frames arriving faster are dropped by the mirror link, not queued.
 */
class FrameLimiterTest {
	private static final long MS = 1_000_000L;

	/** Uploads when a fresh frame waits at every render frame of {@code hz} for {@code seconds}; returns their times. */
	private static List<Long> render(FrameLimiter limiter, double hz, int seconds) {
		List<Long> uploads = new ArrayList<>();
		long frame = Math.round(1_000_000_000L / hz);
		for (long t = 0; t < seconds * 1_000_000_000L; t += frame) {
			if (limiter.tryAcquire(t)) {
				uploads.add(t);
			}
		}
		return uploads;
	}

	/** The most uploads in any one-second window. */
	private static int busiestSecond(List<Long> uploads) {
		int most = 0;
		for (int i = 0; i < uploads.size(); i++) {
			int j = i;
			while (j < uploads.size() && uploads.get(j) - uploads.get(i) < 1_000_000_000L) {
				j++;
			}
			most = Math.max(most, j - i);
		}
		return most;
	}

	@Test
	void neverMoreThanThirtyInAnySecondAtAnyRefreshRate() {
		for (double hz : new double[] {30, 59.94, 60, 75, 120, 144, 165, 240, 1000}) {
			List<Long> uploads = render(new FrameLimiter(30), hz, 5);
			assertTrue(busiestSecond(uploads) <= 30, hz + " Hz: " + busiestSecond(uploads) + " in one second");
			double perSecond = uploads.size() / 5.0;
			// Smooth: close to the limit wherever the screen renders at least as fast (30 Hz can only give 30).
			assertTrue(perSecond >= Math.min(hz, 30) - 1.5, hz + " Hz: only " + perSecond + " a second");
		}
	}

	@Test
	void uploadsKeepABeatOfOneInterval() {
		FrameLimiter limiter = new FrameLimiter(30);
		assertTrue(limiter.tryAcquire(0));
		assertFalse(limiter.tryAcquire(5 * MS), "a burst of frames waits");
		assertFalse(limiter.tryAcquire(33 * MS));
		assertTrue(limiter.tryAcquire(34 * MS), "an interval later");
		// Taken a little late: the next slot is still an interval after the last one's, so the rate holds.
		assertFalse(limiter.tryAcquire(66 * MS));
		assertTrue(limiter.tryAcquire(67 * MS));
		// Very late (a stall): the beat starts over from there.
		assertTrue(limiter.tryAcquire(500 * MS));
		assertFalse(limiter.tryAcquire(532 * MS));
		assertTrue(limiter.tryAcquire(534 * MS));
	}

	@Test
	void theWindowHoldsAfterAnIdleStretch() {
		FrameLimiter limiter = new FrameLimiter(30);
		int allowed = 0;
		for (long t = 0; t < 2_000 * MS; t += MS) {
			if (limiter.tryAcquire(t)) {
				allowed++;
			}
		}
		assertTrue(allowed <= 60, allowed + " uploads in two seconds of 1000 Hz offers");
		limiter.reset();
		assertTrue(limiter.tryAcquire(2_000 * MS), "a reset forgets the past uploads");
	}

	@Test
	void aLimitBelowOneIsRefused() {
		assertThrows(IllegalArgumentException.class, () -> new FrameLimiter(0));
		assertEquals(1, render(new FrameLimiter(1), 60, 1).size());
	}
}
