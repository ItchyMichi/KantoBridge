package dev.kantobridge.builder.shape.geom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The background rule (docs/specs/VOXEL_BLOCKS_PLAN.md §5.5, Tier B): the lightest shade reachable from the apron on
 * the sides that border flat ground is not part of the thing drawn; white sealed behind dark trim stays.
 */
class BackgroundTest {
	private static boolean[] mask(String... rows) {
		int w = rows[0].length();
		boolean[] out = new boolean[w * rows.length];
		for (int y = 0; y < rows.length; y++) {
			for (int x = 0; x < w; x++) {
				out[y * w + x] = rows[y].charAt(x) == 'b';
			}
		}
		return out;
	}

	@Test
	void theLightestShadeReachableFromTheSouthIsBackground() {
		int[] art = ArtFixtures.shades(
				"....",
				".33.",
				".12.",
				".33.");
		assertArrayEquals(mask(
				"bbbb",
				"b..b",
				"b..b",
				"b..b"), Background.of(art, 4, 4, Background.SOUTH));
	}

	@Test
	void whiteSealedBehindDarkTrimStays() {
		// A window: its pane (the lightest shade) sealed by a dark frame is part of the wall, the ground around it isn't.
		int[] art = ArtFixtures.shades(
				"......",
				".3333.",
				".3..3.",
				".3..3.",
				".3333.",
				"......");
		boolean[] background = Background.of(art, 6, 6, Background.SOUTH);
		assertFalse(background[2 * 6 + 2], "the pane");
		assertFalse(background[3 * 6 + 3], "the pane");
		assertTrue(background[0], "the ground around the frame");
		assertEquals(20, Background.count(background));
	}

	@Test
	void outdoorsOnlyTheSouthSeeds() {
		// Light ground behind a wall, open to the north only.
		int[] art = ArtFixtures.shades(
				"....",
				"....",
				"3333",
				"2222");
		assertEquals(0, Background.count(Background.of(art, 4, 4, Background.SOUTH)), "nothing reaches from the south");
		assertEquals(8, Background.count(Background.of(art, 4, 4, Background.NORTH)), "indoors, a walkable north side seeds");
		assertEquals(0, Background.count(Background.of(art, 4, 4, 0)), "no flat ground, no background");
	}

	@Test
	void transparentPixelsAreCandidates() {
		int[] art = ArtFixtures.shades(
				"--",
				"3-");
		assertArrayEquals(mask(
				"bb",
				".b"), Background.of(art, 2, 2, Background.SOUTH));
	}
}
