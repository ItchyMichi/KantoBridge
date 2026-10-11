package dev.kantobridge.builder.shape.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.shape.ShapeKey;
import java.util.List;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The {@code hang} kind (docs/specs/CAVES_AND_WOODS_PLAN.md §4.1, CW1): a box hanging from the block's top, its sides
 * culled where a neighbour hangs at least as low (only the steps show), and a spike of four outward triangles that
 * never leaves the block by more than its overflow.
 */
class HangGeometryTest {
	private static final IntUnaryOperator SAME = tile -> tile;

	private static ShapeMesh hang(int y0) {
		return new ShapeMesh(KeyGeometry.hang(ShapeKey.hang(0, 3, y0, ShapeKey.SPIKE_NONE, 0, 0, 0, 0, 0).numbers(), SAME));
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
	void aBoxHangsFromTheTopWithoutATop() {
		List<ShapeQuad> quads = KeyGeometry.hang(ShapeKey.hang(0, 3, 6, ShapeKey.SPIKE_NONE, 0, 0, 0, 0, 0).numbers(), SAME);
		assertEquals(5, quads.size(), "a bottom and four sides");
		for (ShapeQuad quad : quads) {
			assertTrue(quad.face() != ShapeQuad.UP, "no top: the ceiling is there");
			for (int c = 0; c < 4; c++) {
				assertTrue(quad.y(c) >= 6 && quad.y(c) <= 16, "between y0 and the block's top");
			}
			if (quad.face() != ShapeQuad.DOWN) {
				assertEquals(quad.face(), quad.cull(), "the sides lie on the block's faces");
				assertEquals(0f, quad.cornerV(0) < quad.cornerV(1) ? quad.cornerV(0) : quad.cornerV(1), 0f, "the tile's top rows");
			}
		}
		assertEquals(5, KeyGeometry.hang(ShapeKey.hang(0, 3, 0, ShapeKey.SPIKE_NONE, 0, 0, 0, 0, 0).numbers(), SAME).stream()
				.filter(q -> q.cull() >= 0).count(), "a whole block's bottom lies on its face too");
		assertTrue(KeyGeometry.hang(ShapeKey.hang(0, 3, 16, ShapeKey.SPIKE_NONE, 0, 0, 0, 0, 0).numbers(), SAME).isEmpty(), "y0 16: nothing");
	}

	@Test
	void onlyTheStepsBetweenDepthsShow() {
		ShapeMesh deep = hang(4);
		ShapeMesh shallow = hang(8);
		// The shallow box's side is inside the deep neighbour's; the deep box's side shows below the shallow one.
		assertTrue(FaceMasks.culled(shallow, quadOn(shallow, ShapeQuad.EAST), deep));
		assertFalse(FaceMasks.culled(deep, quadOn(deep, ShapeQuad.WEST), shallow));
		assertTrue(FaceMasks.culled(deep, quadOn(deep, ShapeQuad.WEST), deep), "a cell's own blocks hide each other");
	}

	@Test
	void spikesAreFourOutwardTriangles() {
		for (int[] spike : new int[][] {{ShapeKey.SPIKE_DOWN, 8, 0, 4, 12, 3}, {ShapeKey.SPIKE_UP, 0, 10, 5, 2, 15}}) {
			ShapeKey key = ShapeKey.hang(0, 3, 12, spike[0], spike[1], spike[2], spike[3], spike[4], spike[5]);
			List<ShapeQuad> quads = KeyGeometry.hang(key.numbers(), SAME);
			assertEquals(9, quads.size());
			int sloped = 0;
			for (ShapeQuad quad : quads) {
				if (quad.face() != ShapeQuad.SLOPED) {
					continue;
				}
				sloped++;
				assertEquals(-1, quad.cull());
				// Vanilla's front: the normal (v1 - v0) × (v2 - v0) points away from the spike's axis.
				long ux = quad.x(1) - quad.x(0);
				long uy = quad.y(1) - quad.y(0);
				long uz = quad.z(1) - quad.z(0);
				long vx = quad.x(2) - quad.x(0);
				long vy = quad.y(2) - quad.y(0);
				long vz = quad.z(2) - quad.z(0);
				long nx = uy * vz - uz * vy;
				long nz = ux * vy - uy * vx;
				long outX = quad.x(0) + quad.x(1) - 2L * spike[4];
				long outZ = quad.z(0) + quad.z(1) - 2L * spike[5];
				assertTrue(nx * outX + nz * outZ > 0, "facing out: " + key);
				assertEquals(quad.x(2), quad.x(3), "a triangle repeats its apex");
				assertEquals(spike[2], quad.y(2), "the apex");
			}
			assertEquals(4, sloped);
		}
	}

	@Test
	void keysRefuseWhatCannotBeDrawn() {
		assertThrows(IllegalArgumentException.class, () -> ShapeKey.hang(0, 3, 17, 0, 0, 0, 0, 0, 0), "below the block");
		assertThrows(IllegalArgumentException.class, () -> ShapeKey.hang(0, 3, 8, ShapeKey.SPIKE_DOWN, 4, 6, 4, 8, 8), "a hanging spike points down");
		assertThrows(IllegalArgumentException.class, () -> ShapeKey.hang(0, 3, 8, ShapeKey.SPIKE_UP, 6, 4, 4, 8, 8), "a standing one up");
		assertThrows(IllegalArgumentException.class, () -> ShapeKey.hang(0, 3, 8, ShapeKey.SPIKE_NONE, 0, 1, 0, 0, 0), "no spike, no numbers");
		ShapeKey key = ShapeKey.hang(0, 3, 6, ShapeKey.SPIKE_DOWN, 6, 0, 4, 12, 3).withTint(1);
		assertEquals("hang:0|3|6|1,6,0,4,12,3|tint=1", key.text());
		assertEquals(key, ShapeKey.parse(key.text()));
	}
}
