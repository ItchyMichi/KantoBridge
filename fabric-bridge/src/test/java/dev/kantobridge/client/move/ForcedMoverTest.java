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
		// STEP_SPEED_PLAN Part B: the second leg waits, so the first ticks spend 6 frames (0.75 blocks); tick 3 spends four
		// finishing the first leg and two starting the second; then 3 frames a tick again.
		assertEquals(0.75, velocities.get(0).x(), EPS);
		assertEquals(0.5, velocities.get(2).x(), EPS);
		assertEquals(0.25, velocities.get(2).z(), EPS);
		assertEquals(0.375, velocities.get(3).z(), EPS);
		assertEquals(2.0, position[0], EPS);
		assertEquals(2.0, position[1], EPS);
		// 32 frames: 18 in three ticks, 14 in five more, then the brake (the old 11 moving ticks less three).
		assertEquals(9, velocities.size());
	}

	@Test
	void aBacklogOfTwoLegsDrainsWithinACell() {
		// the Mach: three legs of one cell (2 blocks) at 4 frames, the last two waiting
		ForcedMover mover = new ForcedMover();
		double[] position = {0.0, 0.0};
		mover.add(2.0, 0.0, 4);
		mover.add(4.0, 0.0, 4);
		mover.add(6.0, 0.0, 4);
		assertEquals(3, mover.queued(), "none under way yet: all three wait");
		List<ForcedMover.Velocity> velocities = run(mover, position, 30, false);
		// tick 1: the first leg and half the second (the cap: 3 blocks), tick 2 the rest; the brake. Without the catch-up
		// the legs take 12 frames = 4 ticks.
		assertEquals(3, velocities.size());
		assertEquals(3.0, velocities.get(0).x(), EPS);
		assertEquals(3.0, velocities.get(1).x(), EPS);
		assertEquals(ForcedMover.Velocity.ZERO, velocities.get(2));
		assertEquals(6.0, position[0], EPS);
		assertFalse(mover.active());
		assertEquals(2, mover.catchUpTicks());
	}

	@Test
	void aCatchUpTickNeverMovesMoreThanTheCap() {
		ForcedMover mover = new ForcedMover();
		double[] position = {0.0, 0.0};
		for (int i = 1; i <= 12; i++) {
			mover.add(2.0 * i, 0.0, 4);
		}
		List<ForcedMover.Velocity> velocities = run(mover, position, 60, false);
		double fastest = 0.0;
		for (ForcedMover.Velocity v : velocities) {
			fastest = Math.max(fastest, Math.hypot(v.x(), v.z()));
		}
		assertTrue(fastest <= ForcedMover.MAX_CATCH_UP + EPS, "fastest " + fastest);
		assertEquals(24.0, position[0], EPS, "every leg walked, none skipped");
		// 48 frames: 8 catch-up ticks of 3 blocks (24 blocks) and the brake; at plain speed 16 ticks
		assertEquals(9, velocities.size());
	}

	@Test
	void aLegAddedLaterIsCaughtUpFromThen() {
		ForcedMover mover = new ForcedMover();
		double[] position = {0.0, 0.0};
		mover.add(2.0, 0.0, 16);
		ForcedMover.Velocity first = mover.tick(position[0], position[1]);
		assertEquals(0.375, first.x(), EPS, "a single leg keeps its frames");
		position[0] += first.x();
		mover.add(4.0, 0.0, 16);
		ForcedMover.Velocity second = mover.tick(position[0], position[1]);
		assertEquals(0.75, second.x(), EPS, "one leg waiting: twice the frames");
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
