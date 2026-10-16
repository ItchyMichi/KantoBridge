package dev.kantobridge.client.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** SE6: lightning strikes every 6 to 12 seconds, a bolt flashes briefly, thunder follows, nothing without a storm. */
class LightningClockTest {
	@Test
	void strikesEverySixToTwelveSecondsWithThunderAfter() {
		LightningClock clock = new LightningClock(new SplittableRandom(7));
		List<Integer> strikes = new ArrayList<>();
		List<Integer> thunders = new ArrayList<>();
		int lit = 0;
		for (int t = 0; t < 20 * 600; t++) {
			LightningClock.Tick tick = clock.tick(true);
			if (tick.bolt() && (strikes.isEmpty() || t - strikes.get(strikes.size() - 1) > 20)) {
				strikes.add(t);
			}
			if (tick.thunder()) {
				thunders.add(t);
			}
			if (tick.flash() > 0) {
				lit++;
			}
			assertTrue(tick.flash() >= 0 && tick.flash() <= 1);
		}
		assertTrue(strikes.size() >= 600 / 13 && strikes.size() <= 600 / 6 + 1, "strikes in ten minutes: " + strikes.size());
		for (int i = 1; i < strikes.size(); i++) {
			int gap = strikes.get(i) - strikes.get(i - 1);
			assertTrue(gap >= LightningClock.MIN_WAIT && gap <= LightningClock.MAX_WAIT + LightningClock.LONG_TICKS + 1, "gap " + gap);
		}
		assertTrue(Math.abs(thunders.size() - strikes.size()) <= 1, "a thunder per strike");
		for (int i = 0; i < Math.min(thunders.size(), strikes.size()); i++) {
			int delay = thunders.get(i) - strikes.get(i);
			assertTrue(delay >= 10 && delay <= 41, "thunder " + delay + " ticks after its strike");
		}
		assertTrue(lit < strikes.size() * LightningClock.LONG_TICKS, "the screen is lit only briefly");
	}

	@Test
	void nothingWithoutAStorm() {
		LightningClock clock = new LightningClock(new SplittableRandom(3));
		for (int t = 0; t < 20 * 60; t++) {
			assertEquals(LightningClock.Tick.QUIET, clock.tick(false));
		}
	}
}
