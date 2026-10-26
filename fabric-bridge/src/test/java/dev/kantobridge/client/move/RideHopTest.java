package dev.kantobridge.client.move;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.entity.BikeEntity;
import org.junit.jupiter.api.Test;

/** BIKE_PLAN B5: a hop's lift by its kind, and free mode's speeds. */
class RideHopTest {
	@Test
	void eachHopKindLiftsTheSeat() {
		assertEquals(RideHop.LOW, RideHop.velocity("low"));
		assertEquals(RideHop.NORMAL, RideHop.velocity("normal"));
		assertEquals(RideHop.HIGH, RideHop.velocity("high"));
		assertEquals(0.0, RideHop.velocity("sideways"));
		assertTrue(RideHop.LOW < RideHop.NORMAL && RideHop.NORMAL < RideHop.HIGH);
	}

	@Test
	void aHighHopLandsWithinItsLegAndALowOneWithinHalfOfIt() {
		// gravity 0.08 a tick with 0.98 drag: the ticks until the seat is back on the ground
		assertTrue(airTicks(RideHop.HIGH) <= 32 / 3 + 1, "a ledge hop (32 frames) outlasts its leg");
		assertTrue(airTicks(RideHop.LOW) <= 16 / 3, "a bunny hop (16 frames) outlasts its leg");
		assertTrue(airTicks(RideHop.NORMAL) <= 16 / 3 + 1, "a side jump (16 frames) lands more than a tick after its leg");
	}

	private static int airTicks(double v) {
		double y = 0.0;
		int ticks = 0;
		do {
			v -= 0.08;
			y += v;
			v *= 0.98;
			ticks++;
		} while (y > 0.0 && ticks < 100);
		return ticks;
	}

	@Test
	void freeModeRidesAtTheKindsBaseSpeed() {
		assertEquals(0.75, RideHop.freeSpeed(BikeEntity.Kind.MACH));
		assertEquals(0.75, RideHop.freeSpeed(BikeEntity.Kind.BIKE));
		assertEquals(1.0, RideHop.freeSpeed(BikeEntity.Kind.ACRO));
	}
}
