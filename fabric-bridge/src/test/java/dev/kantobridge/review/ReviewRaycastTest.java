package dev.kantobridge.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.shape.geom.ShapeMesh;
import dev.kantobridge.builder.shape.geom.ShapeQuad;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewRaycastTest {
	private static ShapeQuad face(int z) {
		return ShapeQuad.face(ShapeQuad.NORTH, 4, 4, z, 12, 12, z, 0, 0, 0, 16, 16, -1, 0);
	}

	@Test
	void selectsTheDrawnInsetSurfaceAndPassesThroughTheEmptyCarrierSpace() {
		ShapeMesh mesh = new ShapeMesh(List.of(face(8)));
		assertEquals(1.5, ReviewRaycast.mesh(mesh, 0.5, 0.5, -1, 0, 0, 1, 16, q -> true), 1.0E-9);
		assertTrue(Double.isInfinite(ReviewRaycast.mesh(mesh, 0.1, 0.5, -1, 0, 0, 1, 16, q -> true)));
	}

	@Test
	void respectsReachHiddenFacesAndNearestDepth() {
		ShapeMesh mesh = new ShapeMesh(List.of(face(12), face(4)));
		assertEquals(1.25, ReviewRaycast.mesh(mesh, 0.5, 0.5, -1, 0, 0, 1, 16, q -> true), 1.0E-9);
		assertEquals(1.75, ReviewRaycast.mesh(mesh, 0.5, 0.5, -1, 0, 0, 1, 16, q -> q == 0), 1.0E-9);
		assertTrue(Double.isInfinite(ReviewRaycast.mesh(mesh, 0.5, 0.5, -1, 0, 0, 1, 1.2, q -> true)));
	}

	@Test
	void picksSlopedTrianglesAndRejectsParallelRays() {
		ShapeQuad slope = ShapeQuad.sloped(new int[][] {{0, 0, 0}, {16, 0, 0}, {0, 16, 16}},
				new int[][] {{0, 0}, {16, 0}, {0, 16}}, 0, -1, 0);
		ShapeMesh mesh = new ShapeMesh(List.of(slope));
		assertEquals(1.25, ReviewRaycast.mesh(mesh, 0.25, 0.25, -1, 0, 0, 1, 16, q -> true), 1.0E-9);
		assertTrue(Double.isInfinite(ReviewRaycast.mesh(mesh, -1, 0.5, 0.1, 1, 0, 0, 16, q -> true)));
	}

	@Test
	void aThinCardCanBeSelectedFromEitherSideWithoutTreatingItsPlaneAsAVolume() {
		ShapeMesh mesh = new ShapeMesh(List.of(face(8)));
		assertEquals(1.5, ReviewRaycast.mesh(mesh, 0.5, 0.5, 2, 0, 0, -1, 16, q -> true), 1.0E-9);
		assertTrue(Double.isInfinite(ReviewRaycast.mesh(mesh, 0.5, 0.5, 2, 0, 0, 1, 16, q -> true)));
	}
}
