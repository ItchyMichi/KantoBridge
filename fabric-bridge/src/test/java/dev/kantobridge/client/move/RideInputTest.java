package dev.kantobridge.client.move;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.world.Dir;
import org.junit.jupiter.api.Test;

/** BIKE_PLAN B2: the rider's keys as one held intent (the diagonal rule, the side-jump assist, B). */
class RideInputTest {
	private static final float NORTH = 180.0F;

	@Test
	void theMovementKeysAreOneDirectionByTheCamera() {
		RideInput in = new RideInput();
		assertEquals(new RideInput.Ride(Dir.UP, false), in.tick(true, false, false, false, NORTH, false));
		assertNull(in.tick(true, false, false, false, NORTH, false));  // unchanged: nothing to send
		assertEquals(new RideInput.Ride(Dir.LEFT, false), in.tick(false, false, true, false, NORTH, false)); // strafing left, facing north: west
		assertEquals(new RideInput.Ride(Dir.DOWN, false), in.tick(true, false, false, false, 0.0F, false)); // looking south
		assertEquals(RideInput.Ride.NONE, in.tick(false, false, false, false, NORTH, false));
	}

	@Test
	void aDiagonalKeepsTheDirectionHeldBefore() {
		RideInput in = new RideInput();
		in.tick(true, false, false, false, NORTH, false);
		assertNull(in.tick(true, false, true, false, NORTH, false));    // forward + strafe: still up
		assertEquals(Dir.UP, in.sent().dir());
		in.tick(false, false, false, false, NORTH, false);
		assertNull(in.tick(true, false, true, false, NORTH, false));    // a diagonal from nothing: none
		assertNull(in.sent().dir());
	}

	@Test
	void bWithADirectionHeldGoesAtOnce() {
		RideInput in = new RideInput();
		in.tick(true, false, false, false, NORTH, false);
		assertEquals(new RideInput.Ride(Dir.UP, true), in.tick(true, false, false, false, NORTH, true));
	}

	@Test
	void aFreshBWithNoDirectionWaitsForOne() {
		// the side-jump assist: B, then a direction a tick later, go out together
		RideInput in = new RideInput();
		assertNull(in.tick(false, false, false, false, NORTH, true));
		assertEquals(new RideInput.Ride(Dir.RIGHT, true), in.tick(false, false, false, true, NORTH, true));
		assertEquals(1, in.assisted());
	}

	@Test
	void aFreshBAloneGoesAfterTheWait() {
		RideInput in = new RideInput();
		for (int i = 0; i < RideInput.ASSIST_TICKS; i++) {
			assertNull(in.tick(false, false, false, false, NORTH, true));
		}
		assertEquals(new RideInput.Ride(null, true), in.tick(false, false, false, false, NORTH, true));
		assertTrue(in.holding());
	}

	@Test
	void aTapShorterThanTheWaitStillGoesOut() {
		RideInput in = new RideInput();
		assertNull(in.tick(false, false, false, false, NORTH, true));
		assertEquals(new RideInput.Ride(null, true), in.tick(false, false, false, false, NORTH, false));
		assertEquals(RideInput.Ride.NONE, in.tick(false, false, false, false, NORTH, false));
		assertFalse(in.holding());
	}

	@Test
	void resetForgetsWhatWasHeld() {
		RideInput in = new RideInput();
		in.tick(true, false, false, false, NORTH, false);
		in.reset();
		assertFalse(in.holding());
		assertEquals(new RideInput.Ride(Dir.UP, false), in.tick(true, false, false, false, NORTH, false));
	}
}
