package dev.kantobridge.builder.shape.geom;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Face masks (docs/specs/VOXEL_BLOCKS_PLAN.md §5.5): per block face, a 64-bit set of the 8×8 cells (2/16 block each)
 * a shape covers there. Minecraft culls a quad only against an opaque neighbour; two shaped blocks side by side would
 * draw their touching faces. A quad lying on a block face is dropped when its footprint (every cell it touches) is inside
 * the neighbour's opposite mask (every cell it covers completely), or when the neighbour's opposite face holds a quad
 * with exactly its outline (the touching flanks of two roofs alike), so a dropped quad is always hidden: no hole.
 *
 * <p>Cell coordinates on a face: down and up use (x, z), north and south (x, y), west and east (z, y), so a face and the
 * neighbour's opposite face index the same cells. Bit = row × 8 + column.
 */
public final class FaceMasks {
	/** A whole face. */
	public static final long FULL = -1L;
	/** Neighbour offsets by face: down, up, north, south, west, east. */
	public static final int[] DX = {0, 0, 0, 0, -1, 1};
	public static final int[] DY = {-1, 1, 0, 0, 0, 0};
	public static final int[] DZ = {0, 0, -1, 1, 0, 0};

	private FaceMasks() {
	}

	/** The face across the boundary from {@code face}. */
	public static int opposite(int face) {
		return face ^ 1;
	}

	/** True when quad {@code quad} of {@code mesh} is hidden by its neighbour's shape {@code neighbour} (or none). */
	public static boolean culled(ShapeMesh mesh, int quad, @Nullable ShapeMesh neighbour) {
		int cull = mesh.cull(quad);
		if (cull < 0 || neighbour == null) {
			return false;
		}
		long footprint = mesh.footprint(quad);
		if (footprint == 0) {
			return false;
		}
		return (footprint & ~neighbour.faceMask(opposite(cull))) == 0 || neighbour.hasOutline(opposite(cull), mesh.outline(quad));
	}

	/**
	 * A quad's outline on the face it lies on: its distinct corners in the face's coordinates, as a sorted set packed into
	 * a long; the matching quad of a neighbour across the face has the same one. 0 for a quad inside the block.
	 */
	static long outline(ShapeQuad quad) {
		if (quad.cull() < 0) {
			return 0;
		}
		int[] a = new int[4];
		int[] b = new int[4];
		plane(quad, a, b);
		int[] points = new int[4];
		int n = 0;
		for (int i = 0; i < 4; i++) {
			int p = ((a[i] + 8) & 0x3F) << 6 | ((b[i] + 8) & 0x3F);
			boolean seen = false;
			for (int j = 0; j < n; j++) {
				seen |= points[j] == p;
			}
			if (!seen) {
				points[n++] = p;
			}
		}
		java.util.Arrays.sort(points, 0, n);
		long out = n;
		for (int i = 0; i < n; i++) {
			out = out << 12 | points[i];
		}
		return out;
	}

	/** {@link #outline(ShapeQuad)} of a mesh's quad, read in place (the mesh threads' path: allocates nothing). */
	static long outline(ShapeMesh mesh, int q) {
		int cull = mesh.cull(q);
		if (cull < 0) {
			return 0;
		}
		// The four corners' points, sorted and deduplicated without an array: at most four, so a small insertion.
		long points = 0;
		int n = 0;
		for (int i = 0; i < 4; i++) {
			int p = ((planeA(mesh, q, cull, i) + 8) & 0x3F) << 6 | ((planeB(mesh, q, cull, i) + 8) & 0x3F);
			boolean seen = false;
			for (int j = 0; j < n; j++) {
				seen |= (int) (points >>> (12 * j) & 0xFFF) == p;
			}
			if (!seen) {
				points |= (long) p << (12 * n++);
			}
		}
		for (int i = 1; i < n; i++) {
			for (int j = i; j > 0; j--) {
				long a = points >>> (12 * (j - 1)) & 0xFFF;
				long b = points >>> (12 * j) & 0xFFF;
				if (a <= b) {
					break;
				}
				points = points & ~(0xFFFL << (12 * (j - 1))) & ~(0xFFFL << (12 * j)) | b << (12 * (j - 1)) | a << (12 * j);
			}
		}
		long out = n;
		for (int i = 0; i < n; i++) {
			out = out << 12 | (points >>> (12 * i) & 0xFFF);
		}
		return out;
	}

	/** {@link #footprint(ShapeQuad)} of a mesh's quad, read in place. */
	static long footprint(ShapeMesh mesh, int q) {
		int cull = mesh.cull(q);
		if (cull < 0) {
			return 0;
		}
		int minA = Integer.MAX_VALUE;
		int maxA = Integer.MIN_VALUE;
		int minB = Integer.MAX_VALUE;
		int maxB = Integer.MIN_VALUE;
		for (int i = 0; i < 4; i++) {
			int a = planeA(mesh, q, cull, i);
			int b = planeB(mesh, q, cull, i);
			minA = Math.min(minA, a);
			maxA = Math.max(maxA, a);
			minB = Math.min(minB, b);
			maxB = Math.max(maxB, b);
		}
		return box(minA, maxA, minB, maxB);
	}

	/** Every cell the box {@code minA..maxA × minB..maxB} touches; 0 for an empty box. */
	private static long box(int minA, int maxA, int minB, int maxB) {
		if (maxA <= minA || maxB <= minB) {
			return 0;
		}
		long mask = 0;
		for (int cb = Math.max(0, minB / 2); cb < Math.min(8, (maxB + 1) / 2); cb++) {
			for (int ca = Math.max(0, minA / 2); ca < Math.min(8, (maxA + 1) / 2); ca++) {
				mask |= 1L << (cb * 8 + ca);
			}
		}
		return mask;
	}

	private static int planeA(ShapeMesh mesh, int q, int cull, int i) {
		return cull <= ShapeQuad.SOUTH ? mesh.x(q, i) : mesh.z(q, i);
	}

	private static int planeB(ShapeMesh mesh, int q, int cull, int i) {
		return cull <= ShapeQuad.UP ? mesh.z(q, i) : mesh.y(q, i);
	}

	/** Per face, the cells the quads lying on it cover completely. */
	static long[] coverage(List<ShapeQuad> quads) {
		long[] out = new long[6];
		for (ShapeQuad quad : quads) {
			if (quad.cull() < 0) {
				continue;
			}
			int[] a = new int[4];
			int[] b = new int[4];
			plane(quad, a, b);
			long mask = 0;
			for (int cb = 0; cb < 8; cb++) {
				for (int ca = 0; ca < 8; ca++) {
					if (inside(a, b, ca * 2, cb * 2) && inside(a, b, ca * 2 + 2, cb * 2) && inside(a, b, ca * 2, cb * 2 + 2)
							&& inside(a, b, ca * 2 + 2, cb * 2 + 2)) {
						mask |= 1L << (cb * 8 + ca);
					}
				}
			}
			out[quad.cull()] |= mask;
		}
		return out;
	}

	/** The cells a quad lying on a block face touches (its bounding box); 0 for a quad inside the block. */
	static long footprint(ShapeQuad quad) {
		if (quad.cull() < 0) {
			return 0;
		}
		int[] a = new int[4];
		int[] b = new int[4];
		plane(quad, a, b);
		int minA = Math.min(Math.min(a[0], a[1]), Math.min(a[2], a[3]));
		int maxA = Math.max(Math.max(a[0], a[1]), Math.max(a[2], a[3]));
		int minB = Math.min(Math.min(b[0], b[1]), Math.min(b[2], b[3]));
		int maxB = Math.max(Math.max(b[0], b[1]), Math.max(b[2], b[3]));
		if (maxA <= minA || maxB <= minB) {
			return 0;
		}
		long mask = 0;
		for (int cb = Math.max(0, minB / 2); cb < Math.min(8, (maxB + 1) / 2); cb++) {
			for (int ca = Math.max(0, minA / 2); ca < Math.min(8, (maxA + 1) / 2); ca++) {
				mask |= 1L << (cb * 8 + ca);
			}
		}
		return mask;
	}

	/** The quad's corners in its cull face's plane coordinates. */
	private static void plane(ShapeQuad quad, int[] a, int[] b) {
		for (int i = 0; i < 4; i++) {
			switch (quad.cull()) {
				case ShapeQuad.DOWN, ShapeQuad.UP -> {
					a[i] = quad.x(i);
					b[i] = quad.z(i);
				}
				case ShapeQuad.NORTH, ShapeQuad.SOUTH -> {
					a[i] = quad.x(i);
					b[i] = quad.y(i);
				}
				default -> {
					a[i] = quad.z(i);
					b[i] = quad.y(i);
				}
			}
		}
	}

	/** True when (pa, pb) lies inside or on the convex polygon of the four corners (repeated corners allowed). */
	static boolean inside(int[] a, int[] b, int pa, int pb) {
		long area = 0;
		for (int i = 0; i < 4; i++) {
			int j = (i + 1) & 3;
			area += (long) a[i] * b[j] - (long) a[j] * b[i];
		}
		if (area == 0) {
			return false;
		}
		for (int i = 0; i < 4; i++) {
			int j = (i + 1) & 3;
			if (a[i] == a[j] && b[i] == b[j]) {
				continue;
			}
			long cross = (long) (a[j] - a[i]) * (pb - b[i]) - (long) (b[j] - b[i]) * (pa - a[i]);
			if ((area > 0 && cross < 0) || (area < 0 && cross > 0)) {
				return false;
			}
		}
		return true;
	}
}
