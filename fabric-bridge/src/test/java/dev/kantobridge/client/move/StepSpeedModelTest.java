package dev.kantobridge.client.move;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;

/**
 * STEP_SPEED_PLAN §2.4: why a 45° walk outran the hidden player, next to the client code that sends the steps. A
 * Minecraft player walking at the hidden game's pace (one 2-block cell per 16 frames, {@code Rules.WALK_SPEED}) on a
 * diagonal crosses a cell boundary every 16/√2 ≈ 11.3 frames, and the client sends one {@code move.step} per crossing
 * ({@code MovementSync.crossings}). The hidden game takes one step at a time and keeps two waiting
 * ({@code kb_move.lua Move.MAX_QUEUE}); a third drops the oldest ({@code player.blocked{script}}, a snap-back). With
 * steps of 16 frames the queue fills; with STEP SPEED's 8 it never does. A model, no production code.
 */
class StepSpeedModelTest {
	/** Steps dropped from a full queue over {@code cells} boundary crossings, one every {@code framesPerCrossing} frames. */
	private static int drops(double framesPerCrossing, int stepFrames, int cells) {
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		int dropped = 0;
		double busyUntil = 0.0;
		double next = 0.0;
		int sent = 0;
		for (int frame = 0; frame < 20_000 && (sent < cells || !queue.isEmpty()); frame++) {
			while (sent < cells && frame >= next) {
				queue.add(sent++);
				next += framesPerCrossing;
			}
			if (frame >= busyUntil && !queue.isEmpty()) {
				queue.poll();
				busyUntil = frame + stepFrames;
			}
			while (queue.size() > 2) {
				queue.poll();
				dropped++;
			}
		}
		return dropped;
	}

	@Test
	void aDiagonalAtWalkingSpeedOutrunsSixteenFrameSteps() {
		double diagonal = 16.0 / Math.sqrt(2.0);
		assertEquals(10, drops(diagonal, 16, 40), "Gen 1's own 16 frames fall behind (S0 measured 10 of 40 on Blue)");
		assertEquals(0, drops(diagonal, 8, 40), "STEP SPEED 2X: 8 frames keep up");
		assertEquals(0, drops(16.0, 16, 40), "a straight walk has no margin but keeps up");
	}

	@Test
	void runningHasTheSameRatio() {
		double diagonal = 8.0 / Math.sqrt(2.0);
		assertTrue(drops(diagonal, 8, 40) > 0, "Emerald's run (8 frames) on a diagonal");
		assertEquals(0, drops(diagonal, 4, 40), "2X: 4 frames");
		assertTrue(drops(diagonal, 6, 40) > 0, "1.5X: 6 frames is slower than a diagonal run's 5.7 (why D1 is 2X)");
	}
}
