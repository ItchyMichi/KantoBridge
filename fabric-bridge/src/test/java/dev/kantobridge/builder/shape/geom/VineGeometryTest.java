package dev.kantobridge.builder.shape.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.shape.ShapeKey;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The {@code vine} and {@code strand} kinds (docs/specs/CAVES_AND_WOODS_PLAN.md §4.1, CW2): each block draws its own
 * part of a strand that spans several, the parts meeting at the block edges; cards seen from both sides; the strand's
 * pattern in its three greens.
 */
class VineGeometryTest {
	private static final IntUnaryOperator SAME = tile -> tile;

	private static int minY(List<ShapeQuad> quads) {
		int min = Integer.MAX_VALUE;
		for (ShapeQuad quad : quads) {
			for (int c = 0; c < 4; c++) {
				min = Math.min(min, quad.y(c));
			}
		}
		return min;
	}

	private static int maxY(List<ShapeQuad> quads) {
		int max = Integer.MIN_VALUE;
		for (ShapeQuad quad : quads) {
			for (int c = 0; c < 4; c++) {
				max = Math.max(max, quad.y(c));
			}
		}
		return max;
	}

	@Test
	void aVineSpansItsBlocks() {
		// From 12 in its top block down 30: 12..0 in block 0, 16..0 in block 1, 16..14 in block 2.
		List<ShapeQuad> top = KeyGeometry.vine(ShapeKey.vine(0, 5, 8, 8, 3, 2, 12, 30, 0, 1, 20, 0, 1, 0).numbers(), SAME);
		List<ShapeQuad> middle = KeyGeometry.vine(ShapeKey.vine(0, 5, 8, 8, 3, 2, 12, 30, 1, 1, 20, 0, 1, 0).numbers(), SAME);
		List<ShapeQuad> tail = KeyGeometry.vine(ShapeKey.vine(0, 5, 8, 8, 3, 2, 12, 30, 2, 1, 20, 0, 1, 0).numbers(), SAME);
		// Two crossed cards, both sides; the stub 20 above the tail (-18 + 20 = 2 in the top block) two more there.
		assertEquals(6, top.size());
		assertEquals(12, maxY(top.subList(0, 4)), "the cards start at the top");
		assertEquals(0, minY(top));
		assertEquals(16, maxY(middle));
		assertEquals(0, minY(middle));
		assertEquals(4, middle.size());
		assertEquals(14, minY(tail));
		assertEquals(4, tail.size());
		for (ShapeQuad quad : top) {
			assertEquals(ShapeQuad.SLOPED, quad.face());
			assertTrue(quad.has(ShapeQuad.NO_AO));
		}
		assertThrows(IllegalArgumentException.class, () -> ShapeKey.vine(0, 5, 8, 8, 3, 2, 12, 30, 3, 1, 20, 0, 1, 0),
				"block 3 is past the tail");
		assertThrows(IllegalArgumentException.class, () -> ShapeKey.vine(0, 5, 8, 8, 3, 2, 12, 30, 0, 1, 20, 5, 1, 0),
				"one stub: the second's numbers are 0");
	}

	@Test
	void theTextureRunsAlongTheWholeStrand() {
		List<ShapeQuad> top = KeyGeometry.vine(ShapeKey.vine(0, 5, 8, 8, 3, 0, 16, 32, 0, 1, 0, 0, 0, 0).numbers(), SAME);
		List<ShapeQuad> next = KeyGeometry.vine(ShapeKey.vine(0, 5, 8, 8, 3, 0, 16, 32, 1, 1, 0, 0, 0, 0).numbers(), SAME);
		// Half the strand in each block: the top block shows the tile's upper half, the next its lower half.
		assertEquals(0f, top.get(0).cornerV(0));
		assertEquals(8f, top.get(0).cornerV(1));
		assertEquals(8f, next.get(0).cornerV(0));
		assertEquals(16f, next.get(0).cornerV(1));
	}

	@Test
	void aStrandWearsItsThreeGreens() {
		Set<Integer> colours = new HashSet<>();
		List<ShapeQuad> quads = KeyGeometry.strand(ShapeKey.strand(0, 8, 8, 0, 16, 40, 0).numbers(), rgb -> {
			colours.add(rgb);
			return rgb;
		});
		assertEquals(Set.of(KeyGeometry.STEM, KeyGeometry.STEM_SHADE, KeyGeometry.STUB_LIGHT), colours);
		// In this block (16 of 40 units, rows 0..6): stem 2 columns, stubs at rows 2 and 4 (2 columns each), per plane and
		// side.
		assertEquals((2 + 2 + 2) * 2 * 2, quads.size());
		for (ShapeQuad quad : quads) {
			assertTrue(minY(List.of(quad)) >= 0 && maxY(List.of(quad)) <= 16);
		}
		List<ShapeQuad> diagonal = KeyGeometry.strand(ShapeKey.strand(0, 8, 8, 4, 16, 40, 0).numbers(), rgb -> rgb);
		assertEquals(quads.size(), diagonal.size());
		ShapeQuad card = diagonal.get(0);
		assertTrue(card.x(0) != card.x(2) && card.z(0) != card.z(2), "a diagonal card turns in x and z");
	}
}
