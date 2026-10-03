package dev.kantobridge.client.move;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The forced mover's per-tick velocities: Gen 1 frame timing, chained legs, catching up, braking and giving up. */
class ForcedMoverTest {
	private static final double EPS = 1.0E-9;

	/** Runs the mover against a player that moves exactly as told (or not at all); returns the per-tick velocities. */
	private static List<ForcedMover.Velocity> run(ForcedMover mover, double[] position, int maxTicks, boolean blocked) {
		List<ForcedMover.Velocity> out = new ArrayList<>();
		for (int i = 0; i < maxTicks; i++) {
			ForcedMover.Velocity v = mover.tick(position[0], position[1]);
			if (v == null) {
				break;
			}
			out.add(v);
			if (!blocked) {
				position[0] += v.x();
				position[1] += v.z();
			}
		}
		return out;
	}

	@Test
	void oneCellOverSixteenFramesMatchesGen1Timing() {
		ForcedMover mover = new ForcedMover();
		double[] position = {11.0, 17.0};
		mover.add(11.0, 15.0, 16);
		assertTrue(mover.active());
		List<ForcedMover.Velocity> velocities = run(mover, position, 20, false);
		// 16 frames at 3 per tick: five full ticks of 0.375 blocks and one of a single frame, then a braking tick.
		assertEquals(7, velocities.size());
		for (int i = 0; i < 5; i++) {
			assertEquals(0.0, velocities.get(i).x(), EPS);
			assertEquals(-0.375, velocities.get(i).z(), EPS);
		}
		assertEquals(-0.125, velocities.get(5).z(), EPS);
		assertEquals(ForcedMover.Velocity.ZERO, velocities.get(6), "brake: inertia would carry the player on");
		assertEquals(15.0, position[1], EPS);
		assertFalse(mover.active());
		assertNull(mover.tick(position[0], position[1]), "idle: the player is left alone");
		assertFalse(mover.takeStuck());
	}

	@Test
	void legsChainWithinATick() {
		ForcedMover mover = new ForcedMover();
		double[] position = {0.0, 0.0};
		mover.add(2.0, 0.0, 16);
		mover.add(2.0, 2.0, 16);
		List<ForcedMover.Velocity> velocities = run(mover, position, 30, false);
		// Tick 6 spends one frame finishing the first leg and two starting the second.
		assertEquals(0.125, velocities.get(5).x(), EPS);
		assertEquals(0.25, velocities.get(5).z(), EPS);
		assertEquals(2.0, position[0], EPS);
		assertEquals(2.0, position[1], EPS);
		// 32 frames = 10 2/3 ticks: 11 moving ticks, then the brake.
		assertEquals(12, velocities.size());
	}

	@Test
	void aTickLostToCollisionIsCaughtUp() {
		ForcedMover mover = new ForcedMover();
		mover.add(0.0, 2.0, 16);
		ForcedMover.Velocity first = mover.tick(0.0, 0.0);
		assertEquals(0.375, first.z(), EPS);
		// The player didn't move (something was in the way for a tick): the next velocity covers both ticks' share.
		ForcedMover.Velocity second = mover.tick(0.0, 0.0);
		assertEquals(2.0 * 3.0 / 13.0, second.z(), EPS);
	}

	@Test
	void aBlockedPlayerIsGivenUpOnAfterSettling() {
		ForcedMover mover = new ForcedMover();
		double[] position = {0.0, 0.0};
		mover.add(0.0, 2.0, 16);
		List<ForcedMover.Velocity> velocities = run(mover, position, 100, true);
		// Six moving ticks, ten settling ticks pushing at most 0.4 blocks a tick, then a stop.
		assertEquals(6 + ForcedMover.SETTLE_TICKS + 1, velocities.size());
		assertEquals(ForcedMover.SETTLE_SPEED, velocities.get(6).z(), EPS);
		assertEquals(ForcedMover.Velocity.ZERO, velocities.get(velocities.size() - 1));
		assertFalse(mover.active());
		assertTrue(mover.takeStuck());
		assertFalse(mover.takeStuck(), "reported once");
		assertEquals(1, mover.stuckCount());
	}

	@Test
	void instantAndClearedMoves() {
		ForcedMover mover = new ForcedMover();
		mover.add(1.0, 0.0, 0);
		ForcedMover.Velocity v = mover.tick(0.0, 0.0);
		assertEquals(1.0, v.x(), EPS, "zero frames still takes one tick");
		mover.add(5.0, 5.0, 16);
		mover.clear();
		assertFalse(mover.active());
		assertNull(mover.tick(1.0, 0.0));
	}

	@Test
	void farBehindKeepsOnlyTheNewestTarget() {
		ForcedMover mover = new ForcedMover();
		for (int i = 0; i < ForcedMover.MAX_LEGS; i++) {
			mover.add(i, 0.0, 16);
		}
		mover.add(100.0, 0.0, 16);
		double[] position = {0.0, 0.0};
		List<ForcedMover.Velocity> velocities = run(mover, position, 50, false);
		assertEquals(100.0, position[0], EPS);
		assertEquals(7, velocities.size(), "one leg of 16 frames, then the brake");
	}
}
