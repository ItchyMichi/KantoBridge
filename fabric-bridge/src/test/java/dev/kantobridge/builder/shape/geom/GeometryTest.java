package dev.kantobridge.builder.shape.geom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The direct kinds' geometry (docs/specs/VOXEL_BLOCKS_PLAN.md §5.5): quad counts, bounds, UVs inside the tile, the face
 * conventions, cull faces, and the roof's wedge heights and hips.
 */
class GeometryTest {
	/** Texture index = tile id, for readable assertions. */
	private static final IntUnaryOperator SAME = tile -> tile;

	private static void inBlock(ShapeQuad quad) {
		for (int i = 0; i < 4; i++) {
			assertTrue(quad.x(i) >= 0 && quad.x(i) <= 16 && quad.y(i) >= 0 && quad.y(i) <= 16 && quad.z(i) >= 0 && quad.z(i) <= 16, "bounds");
			assertTrue(quad.cornerU(i) >= 0 && quad.cornerU(i) <= 16 && quad.cornerV(i) >= 0 && quad.cornerV(i) <= 16, "uv inside the tile");
		}
	}

	@Test
	void aSlabIsALowBoxWearingItsTilesLowestRows() {
		List<ShapeQuad> quads = KeyGeometry.slab(9, 4, SAME);
		assertEquals(6, quads.size());
		for (ShapeQuad quad : quads) {
			inBlock(quad);
			assertEquals(9, quad.texture());
			int maxY = Math.max(Math.max(quad.y(0), quad.y(1)), Math.max(quad.y(2), quad.y(3)));
			assertTrue(maxY <= 4, "4/16 high");
		}
		ShapeQuad up = quads.get(1);
		assertEquals(ShapeQuad.UP, up.face());
		assertEquals(4, up.y(0), "its top at 4/16");
		assertEquals(-1, up.cull(), "the top lies inside the block");
		assertArrayEquals(new float[] {0, 0, 0, 16, 16, 16, 16, 0}, up.cornerUvs(), 1e-6F, "the whole tile on top, north up");
		ShapeQuad south = quads.stream().filter(q -> q.face() == ShapeQuad.SOUTH).findFirst().orElseThrow();
		assertEquals(ShapeQuad.SOUTH, south.cull());
		// 4/16 high: the tile's lowest two pixel rows (v 12..16), upright.
		assertArrayEquals(new float[] {0, 12, 0, 16, 16, 16, 16, 12}, south.cornerUvs(), 1e-6F);
		assertEquals(ShapeQuad.DOWN, quads.get(0).cull(), "the bottom lies on the block below");
		assertEquals(ShapeQuad.UP, KeyGeometry.slab(9, 16, SAME).get(1).cull(), "a full-height slab's top is on the surface");
	}

	@Test
	void aGableRoofRisesFromItsEaveAndHasFlanks() {
		// The front row of a roof four rows deep: from 0 at the south edge to 8/16 at the north edge.
		List<ShapeQuad> quads = KeyGeometry.roof(1, 2, 3, 0, 8, false, false, SAME);
		ShapeQuad top = quads.get(0);
		assertEquals(ShapeQuad.SLOPED, top.face());
		assertEquals(-1, top.cull());
		assertEquals(1, top.texture());
		assertEquals(4, quads.size(), "the slope, the north face, two flanks; no south face at the eave");
		ShapeQuad north = quads.stream().filter(q -> q.face() == ShapeQuad.NORTH).findFirst().orElseThrow();
		assertEquals(3, north.texture());
		assertEquals(8, Math.max(north.y(0), north.y(3)));
		// The tile's lowest 4 pixel rows (v 8..16) on the half-block north face.
		assertEquals(8.0F, Math.min(north.cornerV(0), north.cornerV(3)), 1e-6F);
		for (ShapeQuad quad : quads) {
			inBlock(quad);
		}
		ShapeQuad west = quads.stream().filter(q -> q.face() == ShapeQuad.WEST).findFirst().orElseThrow();
		assertEquals(2, west.texture(), "the flanks wear the wall band's tile");
		assertEquals(ShapeQuad.WEST, west.cull());
		// The slope's corners: north-west at 8, south-west at 0, with the tile laid flat (u = x, v = z).
		assertArrayEquals(new int[] {0, 8, 0}, new int[] {top.x(0), top.y(0), top.z(0)});
		assertArrayEquals(new int[] {0, 0, 16}, new int[] {top.x(1), top.y(1), top.z(1)});
		assertArrayEquals(new float[] {0, 0, 0, 16, 16, 16, 16, 0}, top.cornerUvs(), 1e-6F);
	}

	@Test
	void hipsLowerTheExposedEdges() {
		// Both sides hipped: a ridge along x = 8, two halves of two triangles each; no flanks.
		List<ShapeQuad> both = KeyGeometry.roof(1, 2, 3, 8, 16, true, true, SAME);
		long tops = both.stream().filter(q -> q.face() == ShapeQuad.SLOPED).count();
		assertEquals(4, tops);
		assertTrue(both.stream().noneMatch(q -> q.face() == ShapeQuad.WEST || q.face() == ShapeQuad.EAST));
		for (ShapeQuad quad : both) {
			inBlock(quad);
			if (quad.face() == ShapeQuad.SLOPED) {
				for (int i = 0; i < 4; i++) {
					if (quad.x(i) == 0 || quad.x(i) == 16) {
						assertEquals(0, quad.y(i), "the outer edges sit at the eave");
					}
				}
			}
		}
		// West only: no west flank, an east flank; the south face is a triangle rising to the east.
		List<ShapeQuad> west = KeyGeometry.roof(1, 2, 3, 8, 16, true, false, SAME);
		assertTrue(west.stream().noneMatch(q -> q.face() == ShapeQuad.WEST));
		assertEquals(1, west.stream().filter(q -> q.face() == ShapeQuad.EAST).count());
		ShapeQuad south = west.stream().filter(q -> q.face() == ShapeQuad.SOUTH).findFirst().orElseThrow();
		assertEquals(0, south.y(0), "its west top corner is lowered to the eave");
		assertEquals(8, south.y(3));
		// A flat top where both edges are equal and nothing is hipped.
		List<ShapeQuad> flat = KeyGeometry.roof(1, 2, 3, 6, 6, false, false, SAME);
		assertEquals(ShapeQuad.UP, flat.get(0).face());
	}

	@Test
	void slopesFaceUp() {
		for (ShapeQuad quad : KeyGeometry.roof(1, 2, 3, 4, 12, true, false, SAME)) {
			if (quad.face() != ShapeQuad.SLOPED) {
				continue;
			}
			int ax = quad.x(1) - quad.x(0);
			int az = quad.z(1) - quad.z(0);
			int bx = quad.x(2) - quad.x(0);
			int bz = quad.z(2) - quad.z(0);
			assertTrue(az * bx - ax * bz > 0, "a roof's top faces up (its normal's y, from its first three corners)");
		}
	}
}
