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
