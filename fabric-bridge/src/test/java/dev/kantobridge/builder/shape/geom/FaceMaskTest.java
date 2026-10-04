package dev.kantobridge.builder.shape.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Face masks (docs/specs/VOXEL_BLOCKS_PLAN.md §5.5): a quad on a block face is dropped only when the neighbour's
 * opposite face hides it completely (its footprint inside the neighbour's mask, or the very same outline there); never
 * against a neighbour that covers less, so culling can't open a hole.
 */
class FaceMaskTest {
	private static final IntUnaryOperator SAME = tile -> tile;

	private static ShapeMesh cube() {
		return new ShapeMesh(KeyGeometry.cube(new int[] {1, 1, 1, 1, 1, 1}, SAME));
	}

	private static ShapeMesh slab(int h) {
		return new ShapeMesh(KeyGeometry.slab(2, h, SAME));
	}

	private static int quadOn(ShapeMesh mesh, int face) {
		for (int q = 0; q < mesh.size(); q++) {
			if (mesh.quads().get(q).cull() == face) {
				return q;
			}
		}
		throw new AssertionError("no quad on face " + face);
	}

	@Test
	void masksCoverWhatTheFacesCover() {
		ShapeMesh cube = cube();
		for (int face = 0; face < 6; face++) {
			assertEquals(FaceMasks.FULL, cube.faceMask(face), "a cube covers every face");
		}
		ShapeMesh slab = slab(4);
		assertEquals(FaceMasks.FULL, slab.faceMask(ShapeQuad.DOWN));
		assertEquals(0, slab.faceMask(ShapeQuad.UP), "its top lies inside the block");
		// 4/16 high: the two lowest rows of cells on each side.
		assertEquals(0xFFFFL, slab.faceMask(ShapeQuad.NORTH));
		assertEquals(0xFFFFL, slab.faceMask(ShapeQuad.EAST));
		assertEquals(0x00FFL, slab(2).faceMask(ShapeQuad.SOUTH));
		assertEquals(0x00FFL, slab.footprint(quadOn(slab, ShapeQuad.SOUTH)) & 0xFF, "the footprint starts at the bottom row");
		assertEquals(0xFFFFL, slab.footprint(quadOn(slab, ShapeQuad.SOUTH)));
	}

	@Test
	void aQuadIsDroppedOnlyWhenTheNeighbourHidesItCompletely() {
		ShapeMesh cube = cube();
		ShapeMesh slab = slab(4);
		ShapeMesh low = slab(2);
		// Two lips side by side: their touching sides are hidden.
		assertTrue(FaceMasks.culled(slab, quadOn(slab, ShapeQuad.EAST), slab));
		// A lip against a cube: hidden; the cube's side against the lip: not (the lip covers its bottom only).
		assertTrue(FaceMasks.culled(slab, quadOn(slab, ShapeQuad.EAST), cube));
		assertFalse(FaceMasks.culled(cube, quadOn(cube, ShapeQuad.WEST), slab));
		// A higher lip against a lower one stays; the lower against the higher goes.
		assertFalse(FaceMasks.culled(slab, quadOn(slab, ShapeQuad.EAST), low));
		assertTrue(FaceMasks.culled(low, quadOn(low, ShapeQuad.EAST), slab));
		// No neighbour shape, or a quad inside the block: never.
		assertFalse(FaceMasks.culled(slab, quadOn(slab, ShapeQuad.EAST), null));
		int top = 1;
		assertEquals(-1, slab.quads().get(top).cull());
		assertFalse(FaceMasks.culled(slab, top, cube));
	}

	@Test
	void triangularFlanksAreDroppedOnlyAgainstTheSameOutline() {
		// The roof's west flank is a triangle: it covers no whole cell along its slope.
		ShapeMesh roof = new ShapeMesh(KeyGeometry.roof(1, 2, 3, 0, 8, false, false, SAME));
		ShapeMesh same = new ShapeMesh(KeyGeometry.roof(1, 2, 3, 0, 8, false, false, SAME));
		ShapeMesh lower = new ShapeMesh(KeyGeometry.roof(1, 2, 3, 0, 4, false, false, SAME));
		int west = quadOn(roof, ShapeQuad.WEST);
		int east = quadOn(roof, ShapeQuad.EAST);
		assertTrue(FaceMasks.culled(roof, west, same), "the same gable next door: the flanks touch exactly");
		assertTrue(FaceMasks.culled(roof, east, same));
		assertFalse(FaceMasks.culled(roof, west, lower), "a lower roof next door leaves part of the flank in view");
		assertTrue(FaceMasks.culled(lower, quadOn(lower, ShapeQuad.WEST), cube()), "a full neighbour hides it all");
		// Consecutive roof rows: one's north face is the next one's south face.
		ShapeMesh front = new ShapeMesh(KeyGeometry.roof(1, 2, 3, 0, 8, false, false, SAME));
		ShapeMesh behind = new ShapeMesh(KeyGeometry.roof(1, 2, 3, 8, 16, false, false, SAME));
		assertTrue(FaceMasks.culled(front, quadOn(front, ShapeQuad.NORTH), behind));
		assertTrue(FaceMasks.culled(behind, quadOn(behind, ShapeQuad.SOUTH), front));
	}

	@Test
	void insideTestHandlesDegenerateOutlines() {
		assertTrue(FaceMasks.inside(new int[] {0, 0, 16, 16}, new int[] {0, 16, 16, 0}, 8, 8));
		assertTrue(FaceMasks.inside(new int[] {0, 0, 16, 16}, new int[] {0, 16, 16, 0}, 0, 0), "the boundary counts");
		assertFalse(FaceMasks.inside(new int[] {0, 0, 16, 16}, new int[] {0, 16, 16, 0}, 17, 8));
		assertTrue(FaceMasks.inside(new int[] {0, 0, 16, 16}, new int[] {0, 16, 0, 0}, 4, 4), "a triangle with a repeated corner");
		assertFalse(FaceMasks.inside(new int[] {0, 0, 16, 16}, new int[] {0, 16, 0, 0}, 12, 12));
		assertFalse(FaceMasks.inside(new int[] {0, 8, 16, 16}, new int[] {0, 0, 0, 0}, 8, 0), "no area: nothing inside");
		List<ShapeQuad> none = List.of();
		assertEquals(0, new ShapeMesh(none).faceMask(ShapeQuad.UP));
	}
}
