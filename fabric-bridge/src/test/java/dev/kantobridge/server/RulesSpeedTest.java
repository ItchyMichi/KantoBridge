package dev.kantobridge.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The Gen 1 speeds against Minecraft's ground movement (LivingEntity.travel): with a key held, each tick adds
 * {@code 0.98·speed} to the velocity, moves by it, then keeps {@code 0.6·0.91} of it. At steady speed a 2-block cell
 * must take 16 Gen 1 frames walking and 8 biking (PLAN P2), so the Minecraft player never outruns the hidden one.
 */
class RulesSpeedTest {
	/** Blocks moved per tick once the speed has settled. */
	private static double steadyBlocksPerTick(float speed) {
		double velocity = 0.0;
		double moved = 0.0;
		for (int tick = 0; tick < 200; tick++) {
			velocity += 0.98 * speed;
			moved = velocity;
			velocity *= 0.6 * 0.91;
		}
		return moved;
	}

	/** Gen 1 frames (60 a second, 3 per tick) per 2-block cell. */
	private static double framesPerCell(float speed) {
		return 2.0 / steadyBlocksPerTick(speed) * 3.0;
	}

	@Test
	void walkingTakesSixteenFramesPerCell() {
		assertEquals(16.0, framesPerCell(Rules.WALK_SPEED), 0.02);
		assertEquals(Rules.WALK_SPEED, Rules.speedFor(false));
	}

	@Test
	void bikingTakesEightFramesPerCell() {
		assertEquals(8.0, framesPerCell(Rules.BIKE_SPEED), 0.01);
		assertEquals(Rules.BIKE_SPEED, Rules.speedFor(true));
	}

	@Test
	void neverFasterThanGen1() {
		// Rounding may only make Minecraft a hair slower: steps are sent on crossings and must not queue up.
		assertEquals(true, framesPerCell(Rules.WALK_SPEED) >= 16.0 - 1.0E-3);
		assertEquals(true, framesPerCell(Rules.BIKE_SPEED) >= 8.0 - 1.0E-3);
	}
}
