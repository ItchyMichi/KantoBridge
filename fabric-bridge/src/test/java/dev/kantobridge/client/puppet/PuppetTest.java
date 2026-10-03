package dev.kantobridge.client.puppet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.world.Dir;
import org.junit.jupiter.api.Test;

/** A puppet's step, walk frames and emote, in Gen 1 frames. */
class PuppetTest {
	static Puppet puppet() {
		return new Puppet("TEST_TOWN_obj_1", PuppetManager.FIRST_ID, "TEST_TOWN", "SPRITE_TEST_GIRL", "person", 3, 8, Dir.DOWN, false);
	}

	@Test
	void aStepSlidesFromCellToCellOverItsFrames() {
		Puppet p = puppet();
		p.move(4, 8, Dir.RIGHT, 16);
		assertEquals(4, p.x(), "the logical cell is the target at once");
		assertEquals(3.0, p.cellX(0), 1e-9);
		assertTrue(p.stepping());
		p.advance(Puppet.FRAMES_PER_TICK);
		assertEquals(3.0 + 3 / 16.0, p.cellX(0), 1e-9);
		assertEquals(3.0 + 4.5 / 16.0, p.cellX(1.5F), 1e-9, "partial frames interpolate between ticks");
		for (int i = 0; i < 6; i++) {
			p.advance(Puppet.FRAMES_PER_TICK);
		}
		assertEquals(4.0, p.cellX(0), 1e-9);
		assertFalse(p.stepping());
		assertEquals(Dir.RIGHT, p.facing());
	}

	@Test
	void aMoveWithoutFramesJumps() {
		Puppet p = puppet();
		p.move(9, 2, Dir.UP, 0);
		assertEquals(9.0, p.cellX(0), 1e-9);
		assertEquals(2.0, p.cellY(0), 1e-9);
		assertFalse(p.stepping());
	}

	@Test
	void aNewStepStartsWhereTheLastOneHadGot() {
		Puppet p = puppet();
		p.move(3, 9, Dir.DOWN, 16);
		p.advance(8);
		p.move(3, 10, Dir.DOWN, 16);
		assertEquals(8.5, p.cellY(0), 1e-9, "from mid-step");
		p.advance(16);
		assertEquals(10.0, p.cellY(0), 1e-9);
	}

	@Test
	void theWalkingFrameShowsForTheMiddleOfEachStep() {
		Puppet p = puppet();
		assertFalse(p.walkingFrame(0), "standing still");
		p.move(3, 9, Dir.DOWN, 16);
		boolean[] walking = new boolean[16];
		for (int frame = 0; frame < 16; frame++) {
			walking[frame] = p.walkingFrame(frame);
		}
		for (int frame = 0; frame < 16; frame++) {
			assertEquals(frame >= 4 && frame < 12, walking[frame], "frame " + frame);
		}
		// A bike step (8 frames) runs the same cycle twice as fast.
		Puppet fast = puppet();
		fast.move(3, 9, Dir.DOWN, 8);
		assertFalse(fast.walkingFrame(1));
		assertTrue(fast.walkingFrame(2));
		assertFalse(fast.walkingFrame(6));
	}

	@Test
	void upAndDownMirrorTheWalkOnEveryOtherStep() {
		Puppet p = puppet();
		p.move(3, 9, Dir.DOWN, 16);
		assertFalse(p.mirroredWalk(), "first step");
		p.advance(16);
		p.move(3, 10, Dir.DOWN, 16);
		assertTrue(p.mirroredWalk(), "second step");
		p.advance(16);
		p.move(4, 10, Dir.RIGHT, 16);
		assertFalse(p.mirroredWalk(), "sideways never mirrors (right is already left mirrored)");
	}

	@Test
	void walkingInPlaceCyclesWithoutMoving() {
		Puppet p = new Puppet("TEST_TOWN_obj_2", PuppetManager.FIRST_ID - 1, "TEST_TOWN", "SPRITE_TEST_GUARD", "trainer", 5, 5, Dir.UP, true);
		assertFalse(p.walkingFrame(0));
		p.advance(6);
		assertTrue(p.walkingFrame(0));
		p.advance(6);
		assertFalse(p.walkingFrame(0));
		assertEquals(5.0, p.cellX(0), 1e-9);
	}

	@Test
	void anEmoteLastsItsFrames() {
		Puppet p = puppet();
		p.emote("!", 60);
		assertEquals("!", p.bubble());
		for (int i = 0; i < 19; i++) {
			p.advance(Puppet.FRAMES_PER_TICK);
		}
		assertEquals("!", p.bubble());
		p.advance(Puppet.FRAMES_PER_TICK);
		assertNull(p.bubble());
		p.emote("?", 0);
		assertEquals(Puppet.DEFAULT_EMOTE_FRAMES, p.bubbleFrames(), "no length: a second");
		p.emote(null, 0);
		assertNull(p.bubble());
	}
}
