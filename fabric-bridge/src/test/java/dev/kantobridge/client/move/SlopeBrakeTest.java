package dev.kantobridge.client.move;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Cycling Road's brake: sneak on a slope map holds B, once per change; a new link hears a held brake again. */
class SlopeBrakeTest {
	@Test
	void sneakOnASlopeHoldsB() {
		SlopeBrake brake = new SlopeBrake();
		assertNull(brake.tick(true, true, true, false), "rolling, nothing held");
		assertEquals(Boolean.TRUE, brake.tick(true, true, true, true), "sneak pressed: hold B");
		assertNull(brake.tick(true, true, true, true), "held: nothing new to send");
		assertEquals(Boolean.FALSE, brake.tick(true, true, true, false), "released");
		assertEquals(2, brake.sent());
	}

	@Test
	void aRunHoldsBOnFootToo() {
		// FA8 item 7: Emerald runs while B is held; the one hold of B serves the run and the brake
		SlopeBrake brake = new SlopeBrake();
		assertEquals(Boolean.TRUE, brake.tick(true, true, false, false, true), "sprint on foot: hold B");
		assertNull(brake.tick(true, true, false, false, true));
		assertEquals(Boolean.FALSE, brake.tick(true, true, false, false, false), "sprint let go");
		assertNull(brake.tick(false, true, false, false, false));
		assertNull(brake.tick(false, true, false, false, true), "not following: nothing");
	}

	@Test
	void leavingTheSlopeOrTheGameReleasesIt() {
		SlopeBrake brake = new SlopeBrake();
		brake.tick(true, true, true, true);
		assertEquals(Boolean.FALSE, brake.tick(true, true, false, true), "off the slope map: sneak is sneak again");
		assertNull(brake.tick(true, true, false, true));
		brake.tick(true, true, true, true);
		assertEquals(Boolean.FALSE, brake.tick(false, true, true, true), "a free camera never brakes");
	}

	@Test
	void aNewLinkGetsTheHeldBrakeAgain() {
		SlopeBrake brake = new SlopeBrake();
		brake.tick(true, true, true, true);
		assertNull(brake.tick(true, false, true, true), "nothing to send without a link");
		assertEquals(Boolean.TRUE, brake.tick(true, true, true, true), "the new link hears it");
		brake.reset();
		assertEquals(Boolean.TRUE, brake.tick(true, true, true, true), "after a failed send it is sent again");
	}
}
