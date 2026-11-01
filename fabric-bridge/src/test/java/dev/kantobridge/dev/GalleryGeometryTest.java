package dev.kantobridge.dev;

import static org.junit.jupiter.api.Assertions.*;

import dev.kantobridge.dev.GalleryGeometry.Bounds;
import org.junit.jupiter.api.Test;

class GalleryGeometryTest {
	@Test
	void acceptsLargestEmeraldBuildingAndRejectsUnboundedInput() {
		GalleryGeometry.validateDimensions(34, 30, 30);
		GalleryGeometry.validateDimensions(64, 40, 64);
		GalleryGeometry.validateDimensions(32, 2, 104);
		GalleryGeometry.validateDimensions(34, 2, 94);
		for (double bad : new double[] {0, -1, 129, Double.NaN, Double.POSITIVE_INFINITY}) {
			assertThrows(IllegalArgumentException.class, () -> GalleryGeometry.validateDimensions(bad, 10, 10));
		}
		GalleryGeometry.validateDimensions(36, 41, 38);
		GalleryGeometry.validateDimensions(32, 64, 32);
		assertThrows(IllegalArgumentException.class, () -> GalleryGeometry.validateDimensions(10, 65, 10));
		assertThrows(IllegalArgumentException.class, () -> GalleryGeometry.validateDimensions(64, 41, 64), "preserve the old maximum placement volume");
		assertThrows(IllegalArgumentException.class, () -> GalleryGeometry.validateDimensions(65, 2, 64));
		assertThrows(IllegalArgumentException.class, () -> GalleryGeometry.validateDimensions(128, 2, 32), "long pad must retain the old maximum padded-area budget");
	}

	@Test
	void padsNeverOverlapForLargestBuildingAndNegativeMeshMargins() {
		int min = GalleryGeometry.padMin(-0.5);
		int max = GalleryGeometry.padMax(34, 34.5);
		int spacing = GalleryGeometry.spacing(min, max);
		assertTrue(spacing + min - max >= 8, "adjacent pads need a real walking gap");
		assertEquals(24, GalleryGeometry.spacing(GalleryGeometry.padMin(0), GalleryGeometry.padMax(8, 8)));
	}

	@Test
	void everyCornerFitsFrontSideAndElevatedViews() {
		for (Bounds bounds : new Bounds[] {new Bounds(0, 0, 0, 34, 30, 30),
				new Bounds(0, 0, 0, 64, 40, 64), new Bounds(2, 0, 4, 8, 0.2, 10),
				new Bounds(0, 0, 0, 32, 2, 104), new Bounds(0, 0, 0, 34, 2, 94),
				new Bounds(0, 0, 0, 104, 2, 32), new Bounds(0, 0, 0, 128, 40, 29),
				new Bounds(0, 0, 0, 36, 41, 38), new Bounds(0, 0, 0, 32, 64, 32)}) {
			for (double aspect : new double[] {4.0 / 3, 16.0 / 9, 0.75}) {
				for (String view : new String[] {"front", "side", "three_quarter", "north"}) assertFramed(bounds, view, aspect);
			}
		}
	}

	@Test
	void completeLongFencePadsAndCamerasKeepThePreviousResourceGuards() {
		for (Bounds b : new Bounds[] {new Bounds(-0.5, 0, -0.5, 32.5, 2, 104.5), new Bounds(0, 0, 0, 34, 2, 94)}) {
			int minX=GalleryGeometry.padMin(b.minX()), maxX=GalleryGeometry.padMax(b.maxX(),b.maxX());
			int minZ=GalleryGeometry.padMin(b.minZ()), maxZ=GalleryGeometry.padMax(b.maxZ(),b.maxZ());
			assertTrue((maxX-minX)*(maxZ-minZ)<=74*74);
			assertTrue(GalleryGeometry.spacing(minX,maxX)+minX-maxX>=8);
			assertTrue(GalleryGeometry.spacing(minZ,maxZ)+minZ-maxZ>=8);
			for(double aspect:new double[]{4.0/3,16.0/9,0.75}) {
				int chunks=GalleryGeometry.renderDistance(b,aspect);assertTrue(chunks<=32);
				for(String view:new String[]{"front","side","three_quarter"}) {
					var camera=GalleryGeometry.camera(b,view,aspect);
					assertTrue(chunks*16>camera.distance(b)+b.radius());
					assertTrue(b.distanceSquared(camera.x(),camera.y(),camera.z())<Math.pow(chunks*16,2));
				}
			}
		}
		assertThrows(IllegalArgumentException.class,()->GalleryGeometry.renderDistance(new Bounds(0,0,0,32,2,104),0.2));
	}

	@Test
	void supplementalNorthViewUsesOppositeCameraAndTheSameBoundedReach() {
		Bounds b=new Bounds(0,0,0,6,10,8);var front=GalleryGeometry.camera(b,"front",4.0/3);var north=GalleryGeometry.camera(b,"north",4.0/3);
		assertEquals(front.x(),north.x());assertEquals(front.y(),north.y());assertEquals(b.centreZ()*2-front.z(),north.z());
		assertEquals(0,north.yaw());assertEquals(0,north.pitch());assertTrue(north.z()<b.minZ());
		assertTrue(GalleryGeometry.renderDistance(b,4.0/3)*16>north.distance(b)+b.radius());
	}

	@Test
	void tallNativeContextKeepsPadSpacingCullingAndCameraReachBounded() {
		for (Bounds b : new Bounds[]{new Bounds(0,0,0,36,41,38),new Bounds(0,0,0,32,64,32)}) {
			GalleryGeometry.validateDimensions(b.width(),b.height(),b.depth());
			for(double size:new double[]{b.width(),b.depth()}) {
				int low=GalleryGeometry.padMin(0),high=GalleryGeometry.padMax(size,size);
				assertTrue(GalleryGeometry.spacing(low,high)+low-high>=8);
			}
			for(double aspect:new double[]{4.0/3,16.0/9,0.75}) {
				int chunks=GalleryGeometry.renderDistance(b,aspect);assertTrue(chunks<=32);
				for(String view:new String[]{"front","side","three_quarter","north"}) {
					var camera=GalleryGeometry.camera(b,view,aspect);
					assertTrue(chunks*16>camera.distance(b)+b.radius());
					assertTrue(b.distanceSquared(camera.x(),camera.y(),camera.z())<Math.pow(chunks*16,2));
				}
			}
		}
	}

	@Test
	void largeViewHasEnoughRenderReachAndInteractiveCullingUsesNearestFace() {
		Bounds bounds = new Bounds(0, 0, 0, 64, 40, 64);
		var camera = GalleryGeometry.camera(bounds, "three_quarter", 4.0 / 3);
		assertTrue(camera.distance(bounds) > 100, "this reproduces the old centre-distance culling defect");
		assertTrue(GalleryGeometry.renderDistance(bounds, 4.0 / 3) * 16 > camera.distance(bounds) + bounds.radius());
		assertEquals(0, bounds.distanceSquared(0, 20, 32));
		assertEquals(100, bounds.distanceSquared(-10, 20, 32));
		assertEquals(300, bounds.distanceSquared(-10, -10, -10));
	}

	private static void assertFramed(Bounds b, String view, double aspect) {
		var camera = GalleryGeometry.camera(b, view, aspect);
		double[] forward = {b.centreX() - camera.x(), b.centreY() - camera.y(), b.centreZ() - camera.z()};
		normalise(forward);
		double[] right = {-forward[2], 0, forward[0]};
		normalise(right);
		double[] up = {right[1] * forward[2] - right[2] * forward[1],
				right[2] * forward[0] - right[0] * forward[2], right[0] * forward[1] - right[1] * forward[0]};
		double tangent = Math.tan(Math.toRadians(GalleryGeometry.FOV / 2));
		for (double x : new double[] {b.minX(), b.maxX()}) for (double y : new double[] {b.minY(), b.maxY()})
			for (double z : new double[] {b.minZ(), b.maxZ()}) {
				double[] delta = {x - camera.x(), y - camera.y(), z - camera.z()};
				double depth = dot(delta, forward);
				assertTrue(depth > 0);
				assertTrue(Math.abs(dot(delta, right)) < depth * tangent * aspect, view + " clips a horizontal corner");
				assertTrue(Math.abs(dot(delta, up)) < depth * tangent, view + " clips a vertical corner");
			}
	}

	private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
	private static void normalise(double[] vector) {
		double length = Math.sqrt(dot(vector, vector));
		for (int i = 0; i < 3; i++) vector[i] /= length;
	}
}
