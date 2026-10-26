package dev.kantobridge.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** BIKE_PLAN B3: a player whose state says bike is tried at most once a second (20 ticks). */
class BikesTest {
	@Test
	void triesAreRateLimitedPerPlayer() {
		UUID one = UUID.randomUUID();
		UUID two = UUID.randomUUID();
		assertTrue(Bikes.due(one, 100));
		assertFalse(Bikes.due(one, 101));
		assertFalse(Bikes.due(one, 100 + Bikes.ENSURE_EVERY_TICKS - 1));
		assertTrue(Bikes.due(two, 101));                       // another player has a clock of its own
		assertTrue(Bikes.due(one, 100 + Bikes.ENSURE_EVERY_TICKS));
	}
}
