package dev.kantobridge.review;

import dev.kantobridge.builder.shape.geom.ShapeMesh;
import java.util.function.IntPredicate;

/** Pure ray/mesh picking for model reviews. Coordinates are relative to the mesh's carrier block. */
public final class ReviewRaycast {
	private static final double EPS = 1.0E-9;

	private ReviewRaycast() {
	}

	/** Nearest surface distance, or infinity. A ray may pass through empty parts of a carrier's cube. */
	public static double mesh(ShapeMesh mesh, double ex, double ey, double ez, double dx, double dy, double dz,
			double limit, IntPredicate visible) {
		double best = Double.POSITIVE_INFINITY;
		for (int q = 0; q < mesh.size(); q++) {
			if (!visible.test(q)) {
				continue;
			}
			for (int second = 1; second <= 2; second++) {
				double hit = triangle(ex, ey, ez, dx, dy, dz,
						mesh.x(q, 0) / 16.0, mesh.y(q, 0) / 16.0, mesh.z(q, 0) / 16.0,
						mesh.x(q, second) / 16.0, mesh.y(q, second) / 16.0, mesh.z(q, second) / 16.0,
						mesh.x(q, second + 1) / 16.0, mesh.y(q, second + 1) / 16.0, mesh.z(q, second + 1) / 16.0);
				if (hit <= limit + EPS && hit < best) {
					best = hit;
				}
			}
		}
		return best;
	}

	/** Moller-Trumbore intersection, both windings, with degenerate triangles rejected. */
	static double triangle(double ex, double ey, double ez, double dx, double dy, double dz,
			double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz) {
		double ux = bx - ax, uy = by - ay, uz = bz - az;
		double vx = cx - ax, vy = cy - ay, vz = cz - az;
		double px = dy * vz - dz * vy, py = dz * vx - dx * vz, pz = dx * vy - dy * vx;
		double determinant = ux * px + uy * py + uz * pz;
		if (Math.abs(determinant) < EPS) {
			return Double.POSITIVE_INFINITY;
		}
		double tx = ex - ax, ty = ey - ay, tz = ez - az;
		double u = (tx * px + ty * py + tz * pz) / determinant;
		if (u < -EPS || u > 1 + EPS) {
			return Double.POSITIVE_INFINITY;
		}
		double qx = ty * uz - tz * uy, qy = tz * ux - tx * uz, qz = tx * uy - ty * ux;
		double v = (dx * qx + dy * qy + dz * qz) / determinant;
		if (v < -EPS || u + v > 1 + EPS) {
			return Double.POSITIVE_INFINITY;
		}
		double distance = (vx * qx + vy * qy + vz * qz) / determinant;
		return distance >= EPS ? distance : Double.POSITIVE_INFINITY;
	}
}
