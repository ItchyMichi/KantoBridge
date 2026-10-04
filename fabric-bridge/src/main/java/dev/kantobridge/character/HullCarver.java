package dev.kantobridge.character;

import java.util.BitSet;

/**
 * A carved figure (docs/specs/VOXEL_BLOCKS_PLAN.md §5.11; report B5.3): the visual hull of a pose's three drawn views,
 * front ("down"), back ("up") and side ("left"), in a {@code size × size × size} grid, painted through UVs into the same
 * sheet, so palettes, resolutions and packs apply as they do to the card. Pure; built once per sprite and pose.
 *
 * <pre>
 * axes     x left to right as the front shows it, y the row from the top, z 0 at the front .. size-1 at the back;
 *          the side view faces screen-left, so its column s is z
 * hull     F(x, y) and L(z, y) and (useBack(y) ? B(size-1-x, y) : true); useBack(y): the back row's extent matches the
 *          front row's, mirrored, within 1 pixel (a back drawn a little differently must not erode the body)
 * revolve  a 1-frame item or boulder: F(x, y) and F(z, y), its outline turned about its axis
 * paint    front faces: down (x, y); back faces: up (size-1-x, y); side faces: left (z, y); top and bottom: down
 *          (x, y), stretched over the depth
 * mesh     the shell's faces merged per direction and layer into rectangles (each maps onto a rectangle of a frame)
 * checks   (2) rows: more than 1 row drawn in F and not in L, or the reverse; (3) projections: the hull seen from the
 *          front differs from F, or from the side from L, in more than 3% of their pixels (retried once without the
 *          back); (4) thin: more than 3 rows 1 voxel thick in x or z where F and L are both 2 or more wide; (5) more
 *          than MAX_QUADS quads. Any failure: no figure (the thick card).
 * </pre>
 */
public final class HullCarver {
	public static final int MAX_QUADS = 600;
	/** Why a figure wasn't carved (counted for the build report). */
	public enum Failure {
		FRAMES,
		ROWS,
		PROJECTION,
		THIN,
		QUADS
	}

	/** A carved pose: quads with sheet UVs (0..1 over the whole sheet), or why there is none. */
	public record Figure(float[] vertices, float[] normals, int quads, Failure failure) {
		public boolean ok() {
			return this.failure == null;
		}
	}

	private HullCarver() {
	}

	/**
	 * Carves one pose.
	 *
	 * @param row      the pose's sheet row ({@link SpriteFrames#STAND} or {@link SpriteFrames#WALK})
	 * @param revolve  a 1-frame item or boulder: its outline turned about its axis
	 */
	public static Figure carve(SpriteFrames frames, int row, boolean revolve) {
		return carve(frames, row, revolve, MAX_QUADS);
	}

	/** {@link #carve} with another quad budget (the budget's test). */
	static Figure carve(SpriteFrames frames, int row, boolean revolve, int maxQuads) {
		int n = frames.size();
		BitSet front = frames.mask(row, SpriteFrames.DOWN);
		BitSet back = frames.mask(row, SpriteFrames.UP);
		BitSet side = frames.mask(row, SpriteFrames.LEFT);
		if (revolve) {
			side = front;
			back = null;
		} else if (frames.frames() < 3) {
			return failed(Failure.FRAMES);
		}
		if (!revolve && rowsDisagree(front, side, n)) {
			return failed(Failure.ROWS);
		}
		boolean[] hull = hull(front, back, side, n);
		if (!revolve && !faithful(hull, front, side, n)) {
			hull = hull(front, null, side, n);
			if (!faithful(hull, front, side, n)) {
				return failed(Failure.PROJECTION);
			}
		}
		if (!revolve && thin(hull, front, side, n)) {
			return failed(Failure.THIN);
		}
		Figure figure = mesh(hull, n, row, revolve);
		return figure.quads() > maxQuads ? failed(Failure.QUADS) : figure;
	}

	private static Figure failed(Failure failure) {
		return new Figure(new float[0], new float[0], 0, failure);
	}

	static int index(int x, int y, int z, int n) {
		return (z * n + y) * n + x;
	}

	static boolean[] hull(BitSet front, BitSet back, BitSet side, int n) {
		boolean[] out = new boolean[n * n * n];
		for (int y = 0; y < n; y++) {
			boolean useBack = back != null && backMatches(front, back, n, y);
			for (int x = 0; x < n; x++) {
				if (!front.get(y * n + x) || (useBack && !back.get(y * n + n - 1 - x))) {
					continue;
				}
				for (int z = 0; z < n; z++) {
					if (side.get(y * n + z)) {
						out[index(x, y, z, n)] = true;
					}
				}
			}
		}
		return out;
	}

	/** The back row's extent equals the front row's, mirrored, within one pixel at each end. */
	static boolean backMatches(BitSet front, BitSet back, int n, int y) {
		int[] f = extent(front, n, y, false);
		int[] b = extent(back, n, y, true);
		if (f == null || b == null) {
			return f == null && b == null;
		}
		return Math.abs(f[0] - b[0]) <= 1 && Math.abs(f[1] - b[1]) <= 1;
	}

	/** The first and last drawn column of a row (mirrored when asked), or null for an empty row. */
	static int[] extent(BitSet mask, int n, int y, boolean mirrored) {
		int first = -1;
		int last = -1;
		for (int x = 0; x < n; x++) {
			if (mask.get(y * n + x)) {
				int at = mirrored ? n - 1 - x : x;
				first = first < 0 ? at : Math.min(first, at);
				last = Math.max(last, at);
			}
		}
		return first < 0 ? null : new int[] {first, last};
	}

	static boolean rowsDisagree(BitSet front, BitSet side, int n) {
		int bad = 0;
		for (int y = 0; y < n; y++) {
			boolean f = extent(front, n, y, false) != null;
			boolean s = extent(side, n, y, false) != null;
			bad += f != s ? 1 : 0;
		}
		return bad > 1;
	}

	/** The hull's front and side projections each differ from their view in at most 3% of the view's pixels. */
	static boolean faithful(boolean[] hull, BitSet front, BitSet side, int n) {
		int frontDiff = 0;
		int sideDiff = 0;
		for (int y = 0; y < n; y++) {
			for (int a = 0; a < n; a++) {
				boolean seenFront = false;
				boolean seenSide = false;
				for (int b = 0; b < n; b++) {
					seenFront |= hull[index(a, y, b, n)];
					seenSide |= hull[index(b, y, a, n)];
				}
				frontDiff += seenFront != front.get(y * n + a) ? 1 : 0;
				sideDiff += seenSide != side.get(y * n + a) ? 1 : 0;
			}
		}
		return frontDiff * 100 <= 3 * Math.max(1, front.cardinality()) && sideDiff * 100 <= 3 * Math.max(1, side.cardinality());
	}

	/** More than 3 rows where the hull is one voxel thick in x or z although the views are both 2 or more wide there. */
	static boolean thin(boolean[] hull, BitSet front, BitSet side, int n) {
		int rows = 0;
		for (int y = 0; y < n; y++) {
			int[] f = extent(front, n, y, false);
			int[] s = extent(side, n, y, false);
			if (f == null || s == null || f[1] - f[0] < 1 || s[1] - s[0] < 1) {
				continue;
			}
			int maxX = 0;
			int maxZ = 0;
			for (int z = 0; z < n; z++) {
				int run = 0;
				for (int x = 0; x < n; x++) {
					run = hull[index(x, y, z, n)] ? run + 1 : 0;
					maxX = Math.max(maxX, run);
				}
			}
			for (int x = 0; x < n; x++) {
				int run = 0;
				for (int z = 0; z < n; z++) {
					run = hull[index(x, y, z, n)] ? run + 1 : 0;
					maxZ = Math.max(maxZ, run);
				}
			}
			rows += maxX <= 1 || maxZ <= 1 ? 1 : 0;
		}
		return rows > 3;
	}

	/**
	 * The hull's shell as merged quads. Local coordinates in sprite pixels: x centred (-n/2..n/2), y from the feet up
	 * (0..n), z centred with the front at -n/2 (the front looks along -z). UVs over the whole sheet.
	 */
	static Figure mesh(boolean[] hull, int n, int row, boolean revolve) {
		Quads out = new Quads();
		// dir: 0 front (-z), 1 back (+z), 2 left (-x), 3 right (+x), 4 top (-y row), 5 bottom (+y row)
		for (int dir = 0; dir < 6; dir++) {
			for (int layer = 0; layer < n; layer++) {
				boolean[] face = new boolean[n * n];
				boolean any = false;
				for (int b = 0; b < n; b++) {
					for (int a = 0; a < n; a++) {
						int[] v = voxel(dir, layer, a, b);
						int[] o = neighbour(dir, v);
						boolean solid = hull[index(v[0], v[1], v[2], n)];
						boolean open = o[0] < 0 || o[1] < 0 || o[2] < 0 || o[0] >= n || o[1] >= n || o[2] >= n || !hull[index(o[0], o[1], o[2], n)];
						face[b * n + a] = solid && open;
						any |= face[b * n + a];
					}
				}
				if (!any) {
					continue;
				}
				boolean[] used = new boolean[n * n];
				for (int b = 0; b < n; b++) {
					for (int a = 0; a < n; a++) {
						if (!face[b * n + a] || used[b * n + a]) {
							continue;
						}
						int a1 = a;
						while (a1 + 1 < n && face[b * n + a1 + 1] && !used[b * n + a1 + 1]) {
							a1++;
						}
						int b1 = b;
						grow:
						while (b1 + 1 < n) {
							for (int i = a; i <= a1; i++) {
								if (!face[(b1 + 1) * n + i] || used[(b1 + 1) * n + i]) {
									break grow;
								}
							}
							b1++;
						}
						for (int j = b; j <= b1; j++) {
							for (int i = a; i <= a1; i++) {
								used[j * n + i] = true;
							}
						}
						out.face(dir, layer, a, b, a1 + 1, b1 + 1, n, row, revolve);
					}
				}
			}
		}
		return new Figure(out.vertices(), out.normals(), out.count, null);
	}

	/** The voxel at plane position (a, b) of a layer, per direction. */
	static int[] voxel(int dir, int layer, int a, int b) {
		return switch (dir) {
			case 0, 1 -> new int[] {a, b, layer};          // front/back: plane (x, y), layer z
			case 2, 3 -> new int[] {layer, b, a};          // left/right: plane (z, y), layer x
			default -> new int[] {a, layer, b};            // top/bottom: plane (x, z), layer y
		};
	}

	static int[] neighbour(int dir, int[] v) {
		return switch (dir) {
			case 0 -> new int[] {v[0], v[1], v[2] - 1};
			case 1 -> new int[] {v[0], v[1], v[2] + 1};
			case 2 -> new int[] {v[0] - 1, v[1], v[2]};
			case 3 -> new int[] {v[0] + 1, v[1], v[2]};
			case 4 -> new int[] {v[0], v[1] - 1, v[2]};
			default -> new int[] {v[0], v[1] + 1, v[2]};
		};
	}

	/** The quads, built up. */
	private static final class Quads {
		private float[] vertices = new float[20 * 64];
		private float[] normals = new float[3 * 64];
		int count;

		/**
		 * One merged rectangle of plane cells [a0, a1) × [b0, b1) on a layer, its corners in local coordinates and its UVs
		 * in the sheet (frames of {@code n} pixels; 4 columns, 2 rows).
		 */
		void face(int dir, int layer, int a0, int b0, int a1, int b1, int n, int row, boolean revolve) {
			float half = n / 2.0F;
			float[][] corners = new float[4][];
			float[][] uvs = new float[4][];
			int[][] plane = {{a0, b0}, {a1, b0}, {a1, b1}, {a0, b1}};
			for (int c = 0; c < 4; c++) {
				int a = plane[c][0];
				int b = plane[c][1];
				float x;
				float y;
				float z;
				switch (dir) {
					case 0 -> {
						x = a - half;
						y = n - b;
						z = layer - half;
					}
					case 1 -> {
						x = a - half;
						y = n - b;
						z = layer + 1 - half;
					}
					case 2 -> {
						x = layer - half;
						y = n - b;
						z = a - half;
					}
					case 3 -> {
						x = layer + 1 - half;
						y = n - b;
						z = a - half;
					}
					case 4 -> {
						x = a - half;
						y = n - layer;
						z = b - half;
					}
					default -> {
						x = a - half;
						y = n - layer - 1;
						z = b - half;
					}
				}
				corners[c] = new float[] {x, y, z};
				// The frame and texel each corner shows.
				int column;
				float u;
				float v;
				switch (dir) {
					case 0 -> {
						column = SpriteFrames.DOWN;
						u = a;
						v = b;
					}
					case 1 -> {
						column = revolve ? SpriteFrames.DOWN : SpriteFrames.UP;
						u = revolve ? a : n - a;
						v = b;
					}
					case 2, 3 -> {
						column = revolve ? SpriteFrames.DOWN : SpriteFrames.LEFT;
						u = a;
						v = b;
					}
					default -> {
						// Top and bottom: the front's texel of that row, stretched over the depth.
						column = SpriteFrames.DOWN;
						u = a;
						v = layer + 0.5F;
					}
				}
				uvs[c] = new float[] {(column * n + u) / (4.0F * n), (row * n + v) / (2.0F * n)};
			}
			float[] normal = switch (dir) {
				case 0 -> new float[] {0, 0, -1};
				case 1 -> new float[] {0, 0, 1};
				case 2 -> new float[] {-1, 0, 0};
				case 3 -> new float[] {1, 0, 0};
				case 4 -> new float[] {0, 1, 0};
				default -> new float[] {0, -1, 0};
			};
			if ((this.count + 1) * 20 > this.vertices.length) {
				this.vertices = java.util.Arrays.copyOf(this.vertices, this.vertices.length * 2);
				this.normals = java.util.Arrays.copyOf(this.normals, this.normals.length * 2);
			}
			for (int c = 0; c < 4; c++) {
				int i = this.count * 20 + c * 5;
				this.vertices[i] = corners[c][0];
				this.vertices[i + 1] = corners[c][1];
				this.vertices[i + 2] = corners[c][2];
				this.vertices[i + 3] = uvs[c][0];
				this.vertices[i + 4] = uvs[c][1];
			}
			System.arraycopy(normal, 0, this.normals, this.count * 3, 3);
			this.count++;
		}

		float[] vertices() {
			return java.util.Arrays.copyOf(this.vertices, this.count * 20);
		}

		float[] normals() {
			return java.util.Arrays.copyOf(this.normals, this.count * 3);
		}
	}
}
