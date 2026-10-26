package dev.kantobridge.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The bike's lift before a move (docs/specs/BIKE_CLIMB_PLAN.md §3.4): the plain arithmetic of {@link BikeClimb#lift}. */
class BikeClimbTest {
	private static final double EPS = 1.0E-9;
	private static final double STEP = 0.6;

	@Test
	void stairsHigherThanTheStepLiftTheMove() {
		// the Mach's tick up Route 111's slope: feet at 88, the stairs' top under the path 90.5
		assertEquals(2.5, BikeClimb.lift(-0.08, 88.0, 90.5, STEP, BikeClimb.MAX_LIFT), EPS);
	}

	@Test
	void aRiseWithinTheStepIsLeftToStepUp() {
		assertEquals(-0.08, BikeClimb.lift(-0.08, 88.0, 88.5, STEP, BikeClimb.MAX_LIFT), EPS);
		assertEquals(-0.08, BikeClimb.lift(-0.08, 88.0, 88.6, STEP, BikeClimb.MAX_LIFT), EPS, "exactly the step: step-up's");
	}

	@Test
	void flatAndDownhillAreUntouched() {
		assertEquals(-0.08, BikeClimb.lift(-0.08, 88.0, 88.0, STEP, BikeClimb.MAX_LIFT), EPS);
		assertEquals(-0.08, BikeClimb.lift(-0.08, 91.0, 88.0, STEP, BikeClimb.MAX_LIFT), EPS, "gravity takes the bike down");
	}

	@Test
	void aCliffIsNeverLifted() {
		assertEquals(-0.08, BikeClimb.lift(-0.08, 88.0, 94.0, STEP, BikeClimb.MAX_LIFT), EPS, "six blocks is a level's cliff");
		assertEquals(5.0, BikeClimb.lift(-0.08, 88.0, 93.0, STEP, BikeClimb.MAX_LIFT), EPS, "the most a catch-up tick climbs");
	}

	@Test
	void stairsFallingAwayUnderTheMovesEndAreFollowedDown() {
		assertEquals(-2.0, BikeClimb.drop(-0.08, 91.0, 89.0, STEP, BikeClimb.MAX_LIFT), EPS);
		assertEquals(-0.08, BikeClimb.drop(-0.08, 91.0, 90.5, STEP, BikeClimb.MAX_LIFT), EPS, "half a step: gravity's");
		assertEquals(-0.08, BikeClimb.drop(-0.08, 94.0, 88.0, STEP, BikeClimb.MAX_LIFT), EPS, "a cliff is not followed");
		assertEquals(0.42, BikeClimb.drop(0.42, 91.0, 89.0, STEP, BikeClimb.MAX_LIFT), EPS, "a hop is never pulled down");
		assertEquals(-0.08, BikeClimb.drop(-0.08, 91.0, Double.NaN, STEP, BikeClimb.MAX_LIFT), EPS, "no stairs under the end");
		assertEquals(-0.08, BikeClimb.lift(-0.08, 91.0, Double.NaN, STEP, BikeClimb.MAX_LIFT), EPS, "nor along the path");
	}

	@Test
	void aHopsOwnLiftStaysWhenItIsTheLarger() {
		assertEquals(0.42, BikeClimb.lift(0.42, 88.0, 88.4, STEP, BikeClimb.MAX_LIFT), EPS, "within the step: the hop's");
		assertEquals(1.0, BikeClimb.lift(0.42, 88.0, 89.0, STEP, BikeClimb.MAX_LIFT), EPS, "the stairs need more than the hop");
		assertEquals(3.0, BikeClimb.lift(3.0, 88.0, 89.0, STEP, BikeClimb.MAX_LIFT), EPS, "a bigger lift of its own stays");
	}
}
