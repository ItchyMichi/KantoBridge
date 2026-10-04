package dev.kantobridge.builder.shape.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.shape.SelfCheck;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tufts (docs/specs/VOXEL_BLOCKS_PLAN.md §5.5, Tier B): a tile's strokes stand as a cut-out at its south edge, so in
 * the game's view they cover exactly their own pixels; a flower stands with the union of its frames' strokes.
 */
class TuftTest {
	private static final int[] GRASS = ArtFixtures.shades(
			"........",
			".1...1..",
			".1..2.1.",
			"..1.2.1.",
			"........",
			"..33....",
			".3..3...",
			"........");

	@Test
	void strokesStandAtTheTilesSouthEdgeAndShowExactlyThemselves() {
		boolean[] mask = Tufts.mask(GRASS, List.of());
		List<ShapeQuad> quads = Tufts.quads(mask, ArtFixtures.ART_PX);
		assertTrue(quads.size() > 0);
		int[] shown = new int[64];
		Arrays.fill(shown, -1);
		for (ShapeQuad quad : quads) {
			assertTrue(quad.has(ShapeQuad.NO_AO), "a thin card casts no ambient occlusion");
			if (quad.face() == ShapeQuad.SOUTH) {
				assertEquals(16, quad.z(0), "the front on the block's south face");
				assertEquals(ShapeQuad.SOUTH, quad.cull());
				SelfCheck.project(quad, 0, 0, 0, 2, (sx, sy, nearness, u, v) -> {
					assertEquals(-1, shown[sy * 8 + sx], "one front per pixel");
					shown[sy * 8 + sx] = VoxelGrid.texel(quad.texture(), SelfCheck.texelOf(u, 8), SelfCheck.texelOf(v, 8));
				});
			} else {
				assertEquals(ShapeQuad.NORTH, quad.face(), "fronts and backs only");
				assertEquals(14, quad.z(0), "the back a pixel behind");
				assertEquals(-1, quad.cull());
			}
		}
		for (int i = 0; i < 64; i++) {
			assertEquals(mask[i] ? VoxelGrid.texel(0, i % 8, i / 8) : -1, shown[i], "pixel " + i);
		}
	}

	@Test
	void theBottomRowStandsOnTheGround() {
		boolean[] mask = new boolean[64];
		mask[7 * 8 + 3] = true;
		List<ShapeQuad> quads = Tufts.quads(mask, ArtFixtures.ART_PX);
		assertEquals(2, quads.size());
		for (ShapeQuad quad : quads) {
			int minY = Math.min(Math.min(quad.y(0), quad.y(1)), Math.min(quad.y(2), quad.y(3)));
			int maxY = Math.max(Math.max(quad.y(0), quad.y(1)), Math.max(quad.y(2), quad.y(3)));
			assertEquals(0, minY);
			assertEquals(2, maxY, "one pixel high");
		}
	}

	@Test
	void aFlowerStandsWithTheUnionOfItsFrames() {
		int[] frame1 = new int[64];
		int[] frame2 = new int[64];
		frame1[3 * 8 + 3] = 3;
		frame2[4 * 8 + 4] = 2;
		boolean[] mask = Tufts.mask(new int[64], List.of(frame1, frame2));
		assertEquals(2, Background.count(mask));
		assertTrue(mask[3 * 8 + 3] && mask[4 * 8 + 4]);
	}

	@Test
	void aTileWithoutStrokesHasNoTuft() {
		assertEquals(List.of(), Tufts.quads(Tufts.mask(new int[64], List.of()), ArtFixtures.ART_PX));
	}
}
