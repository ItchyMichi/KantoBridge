package dev.kantobridge.character;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Rigged figures (docs/specs/VOXEL_BLOCKS_PLAN.md §5.11; report B5.4): a humanoid's standing front frame splits into a
 * head over the neck, a torso, arms beside it and two legs; the boxes cover the drawing; anything else isn't rigged.
 */
class RigSplitterTest {
	@Test
	void aPersonSplitsIntoSixParts() {
		RigSplitter.Rig rig = RigSplitter.split(SpriteFixtures.same(SpriteFixtures.PERSON, 6));
		assertTrue(rig.ok(), "failure " + rig.failure() + ", cover " + rig.cover());
		List<String> names = rig.parts().stream().map(RigSplitter.Part::name).toList();
		assertEquals(List.of("head", "torso", "right_arm", "left_arm", "right_leg", "left_leg"), names);
		RigSplitter.Part head = rig.parts().get(0);
		assertEquals(1, head.y0());
		assertEquals(6, head.h(), "rows 1 to 6, above the neck");
		RigSplitter.Part leg = rig.parts().get(4);
		assertTrue(leg.swings());
		assertEquals(12, leg.y0(), "the rows with two runs");
		assertTrue(rig.cover() >= RigSplitter.MIN_COVER);
	}

	@Test
	void whatIsNotAPersonIsNotRigged() {
		assertFalse(RigSplitter.split(SpriteFixtures.same(SpriteFixtures.BALL, 6)).ok(), "no neck, no legs");
		assertEquals("frames", RigSplitter.split(SpriteFixtures.same(SpriteFixtures.PERSON, 3)).failure(), "no walk frames: never rigged");
		assertFalse(RigSplitter.split(SpriteFixtures.same(SpriteFixtures.empty(), 6)).ok());
	}
}
