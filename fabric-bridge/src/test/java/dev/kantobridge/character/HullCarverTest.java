package dev.kantobridge.character;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import org.junit.jupiter.api.Test;

/**
 * Carved figures (docs/specs/VOXEL_BLOCKS_PLAN.md §5.11; report B5.3): the hull of the front, back and side views; its
 * projections equal the masks; a single-frame item revolves its outline; each check fails on its own counter-example.
 */
class HullCarverTest {
	@Test
	void aBoxCarvesToABoxWhoseProjectionsAreItsViews() {
		SpriteFrames frames = SpriteFixtures.same(SpriteFixtures.BOX, 6);
		HullCarver.Figure figure = HullCarver.carve(frames, SpriteFrames.STAND, false);
		assertTrue(figure.ok());
		assertEquals(6, figure.quads(), "one merged rectangle per side");
		boolean[] hull = HullCarver.hull(frames.mask(0, SpriteFrames.DOWN), frames.mask(0, SpriteFrames.UP), frames.mask(0, SpriteFrames.LEFT), 16);
		assertTrue(HullCarver.faithful(hull, frames.mask(0, SpriteFrames.DOWN), frames.mask(0, SpriteFrames.LEFT), 16));
		int voxels = 0;
		for (boolean v : hull) {
			voxels += v ? 1 : 0;
		}
		assertEquals(8 * 8 * 8, voxels);
		// Every vertex inside the figure's centred box, every UV inside the sheet.
		for (int i = 0; i < figure.vertices().length; i += 5) {
			assertTrue(Math.abs(figure.vertices()[i]) <= 8 && figure.vertices()[i + 1] >= 0 && figure.vertices()[i + 1] <= 16
					&& Math.abs(figure.vertices()[i + 2]) <= 8);
			assertTrue(figure.vertices()[i + 3] >= 0 && figure.vertices()[i + 3] <= 1 && figure.vertices()[i + 4] >= 0
					&& figure.vertices()[i + 4] <= 1);
		}
	}

	@Test
	void aSingleFrameBallRevolves() {
		SpriteFrames ball = SpriteFixtures.same(SpriteFixtures.BALL, 1);
		assertEquals(HullCarver.Failure.FRAMES, HullCarver.carve(ball, SpriteFrames.STAND, false).failure(), "not an item: no views");
		HullCarver.Figure revolved = HullCarver.carve(ball, SpriteFrames.STAND, true);
		assertTrue(revolved.ok());
		assertTrue(revolved.quads() > 6);
	}

	@Test
	void eachCheckFailsOnItsOwnCounterExample() {
		// Rows: the side view starts three rows lower than the front.
		SpriteFrames rows = SpriteFixtures.sheet(SpriteFixtures.BOX, SpriteFixtures.BOX, SpriteFixtures.box(4, 7, 12, 12), SpriteFixtures.BOX, 6);
		assertEquals(HullCarver.Failure.ROWS, HullCarver.carve(rows, SpriteFrames.STAND, false).failure());
		// Projection: one row the side lacks (allowed by the row check) loses more than 3% of the front.
		SpriteFrames projection = SpriteFixtures.sheet(SpriteFixtures.box(4, 4, 12, 13), SpriteFixtures.box(4, 4, 12, 13),
				SpriteFixtures.BOX, SpriteFixtures.BOX, 6);
		assertEquals(HullCarver.Failure.PROJECTION, HullCarver.carve(projection, SpriteFrames.STAND, false).failure());
		// Thin: two posts seen from the front and from the side make four one-voxel posts.
		String[] posts = new String[16];
		for (int y = 0; y < 16; y++) {
			posts[y] = y >= 4 && y < 12 ? "....#....#......" : "................";
		}
		assertEquals(HullCarver.Failure.THIN, HullCarver.carve(SpriteFixtures.same(posts, 6), SpriteFrames.STAND, false).failure());
		// The budget.
		assertEquals(HullCarver.Failure.QUADS, HullCarver.carve(SpriteFixtures.same(SpriteFixtures.BOX, 6), SpriteFrames.STAND, false, 5).failure());
	}

	@Test
	void aBackDrawnDifferentlyDoesNotErodeTheBody() {
		// The back view is narrower on one row by more than a pixel: that row ignores it.
		SpriteFrames frames = SpriteFixtures.sheet(SpriteFixtures.BOX, SpriteFixtures.box(6, 4, 10, 12), SpriteFixtures.BOX, SpriteFixtures.BOX, 6);
		BitSet front = frames.mask(0, SpriteFrames.DOWN);
		assertFalse(HullCarver.backMatches(front, frames.mask(0, SpriteFrames.UP), 16, 5));
		assertTrue(HullCarver.carve(frames, SpriteFrames.STAND, false).ok());
	}
}
