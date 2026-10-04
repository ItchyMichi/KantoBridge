package dev.kantobridge.builder.shape;

import dev.kantobridge.builder.shape.geom.ShapeQuad;
import dev.kantobridge.builder.shape.geom.TextureKey;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Re-projects the 3D result into the game's own view and scores it against the 2D map (docs/specs/VOXEL_BLOCKS_PLAN.md
 * §5.6). Every texel of a face that looks up or south (a top, a south face, a roof's slopes) is projected with
 * {@code (x, y, z) → (x, z − y)} (a point {@code y} pixels high shows {@code y} pixels further up the screen), the nearest
 * sample (largest {@code z + y}) wins each screen pixel, and it is compared by texel identity (tile and texel coordinates,
 * never colour) with {@link Reference}.
 *
 * <ul>
 * <li>{@code holes}: map-body pixels nothing lands on;</li>
 * <li>{@code ground_match}: of the ground pixels not hidden by a structure, the share showing the same texel;</li>
 * <li>{@code structure_match}: of the structure pixels, the share showing the same texel;</li>
 * <li>{@code occluded_ground}: the share of ground pixels a structure hides (inherent to 3D, only reported);</li>
 * <li>{@code back_faces}: north faces nothing hides from the north (the "second front" problem, only reported).</li>
 * </ul>
 * The drawn pixels of cell units built as hulls or objects are scored per unit ({@code CellUnits}); their background
 * counts as ground ({@link Reference#excluded}, {@link Reference#ground}).
 * Pure; the reference and the projection exist in memory only. Only the five numbers per map leave it.
 */
public final class SelfCheck {
	/** One map's scores. Ratios are 1 where there is nothing to score. */
	public record Scores(String id, long groundPixels, long structurePixels, long holes, double groundMatch, double structureMatch,
			double occludedGround, long backFaces) {
	}

	/** A quad of the map's geometry: map-local block (art column, blocks above the floor block's top, art row). */
	@FunctionalInterface
	public interface QuadVisitor {
		void quad(int bx, int by, int bz, ShapeQuad quad, TextureKey texture);
	}

	/** The geometry of one map, block by block; {@code by} -1 is the floor block. */
	public interface Geometry {
		void forEach(QuadVisitor visitor);

		/** True when a block of geometry fills (bx, by, bz) completely (its faces hide what touches them). */
		boolean solid(int bx, int by, int bz);
	}

	private SelfCheck() {
	}

	/** A texel's identity: the tile, and its column and row in the tile. */
	public static long texel(int tile, int tx, int ty) {
		return ((long) tile << 16) | ((long) tx << 8) | ty;
	}

	/** Scores one map's geometry against its reference. */
	public static Scores score(String id, Reference reference, String atlas, Geometry geometry, int artPx) {
		int w = reference.width();
		int h = reference.height();
		float[] near = new float[w * h];
		Arrays.fill(near, Float.NEGATIVE_INFINITY);
		long[] source = new long[w * h];
		boolean[] floor = new boolean[w * h];
		int unitsPerPixel = 16 / artPx;
		long[] backFaces = new long[1];
		Set<Long> seenBack = new HashSet<>();
		geometry.forEach((bx, by, bz, quad, texture) -> {
			if (quad.face() == ShapeQuad.NORTH && by >= 0 && quad.cull() == ShapeQuad.NORTH && !geometry.solid(bx, by, bz - 1)
					&& seenBack.add(((long) bx << 40) ^ ((long) by << 20) ^ bz)) {
				backFaces[0]++;
			}
			if (quad.face() != ShapeQuad.UP && quad.face() != ShapeQuad.SOUTH && quad.face() != ShapeQuad.SLOPED) {
				return;
			}
			boolean sameAtlas = atlas.equals(texture.atlas());
			project(quad, bx, by, bz, unitsPerPixel, (sx, sy, nearness, u, v) -> {
				if (sx < 0 || sy < 0 || sx >= w || sy >= h) {
					return;
				}
				int i = sy * w + sx;
				if (nearness > near[i]) {
					near[i] = nearness;
					source[i] = sameAtlas ? texel(texture.tile(), texelOf(u, artPx), texelOf(v, artPx)) : -1;
					floor[i] = by == -1 && quad.face() == ShapeQuad.UP;
				}
			});
		});
		long groundPixels = 0;
		long structurePixels = 0;
		long holes = 0;
		long groundSeen = 0;
		long groundMatched = 0;
		long structureMatched = 0;
		long occluded = 0;
		for (int sy = 0; sy < h; sy++) {
			for (int sx = 0; sx < w; sx++) {
				int i = sy * w + sx;
				if (near[i] == Float.NEGATIVE_INFINITY) {
					holes++;
				}
				if (reference.excluded(sx, sy)) {
					// A unit's drawn pixel: its unit's own self-check scores it.
					continue;
				}
				boolean ground = reference.ground(sx, sy);
				if (ground) {
					groundPixels++;
				} else {
					structurePixels++;
				}
				if (near[i] == Float.NEGATIVE_INFINITY) {
					continue;
				}
				boolean match = source[i] == reference.texel(sx, sy);
				if (ground) {
					if (floor[i]) {
						groundSeen++;
						groundMatched += match ? 1 : 0;
					} else {
						occluded++;
					}
				} else {
					structureMatched += match ? 1 : 0;
				}
			}
		}
		return new Scores(id, groundPixels, structurePixels, holes, ratio(groundMatched, groundSeen), ratio(structureMatched, structurePixels),
				groundPixels == 0 ? 0 : occluded / (double) groundPixels, backFaces[0]);
	}

	public static int texelOf(float uv, int artPx) {
		return Math.max(0, Math.min(artPx - 1, (int) Math.floor(uv * artPx / 16)));
	}

	private static double ratio(long part, long whole) {
		return whole == 0 ? 1.0 : part / (double) whole;
	}

	/** A projected texel: screen pixel, nearness, and the texel's (u, v) in 1/16 of the texture. */
	@FunctionalInterface
	public interface PixelSink {
		void pixel(int sx, int sy, float nearness, float u, float v);
	}

	/** Samples per edge of a quad that isn't an axis-aligned rectangle: two per 1/16 block. */
	private static final int SAMPLES = 32;

	/**
	 * Projects every texel-sized cell of a quad that looks up or south. Coordinates are 1/16 block; one screen pixel is
	 * {@code unitsPerPixel} of them; the cell's centre picks the screen pixel. An axis-aligned rectangle is projected cell
	 * by cell, its (u, v) interpolated between its corners' (a rectangle of UVs, or one per corner); anything else is
	 * sampled.
	 */
	public static void project(ShapeQuad quad, int bx, int by, int bz, int unitsPerPixel, PixelSink sink) {
		if ((quad.face() != ShapeQuad.UP && quad.face() != ShapeQuad.SOUTH) || !rectangle(quad)) {
			sample(quad, bx, by, bz, unitsPerPixel, sink);
			return;
		}
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (int c = 0; c < 4; c++) {
			minX = Math.min(minX, quad.x(c));
			minY = Math.min(minY, quad.y(c));
			minZ = Math.min(minZ, quad.z(c));
			maxX = Math.max(maxX, quad.x(c));
			maxY = Math.max(maxY, quad.y(c));
			maxZ = Math.max(maxZ, quad.z(c));
		}
		// The corners' UVs, and which end of each axis each corner sits at: the bilinear weights of a cell's centre.
		float[] cu = new float[4];
		float[] cv = new float[4];
		boolean[] farA = new boolean[4];
		boolean[] farB = new boolean[4];
		boolean up = quad.face() == ShapeQuad.UP;
		for (int c = 0; c < 4; c++) {
			cu[c] = quad.cornerU(c);
			cv[c] = quad.cornerV(c);
			farA[c] = quad.x(c) == maxX;
			farB[c] = up ? quad.z(c) == maxZ : quad.y(c) == maxY;
		}
		int ox = bx * 16;
		int oy = by * 16;
		int oz = bz * 16;
		int half = unitsPerPixel / 2;
		int minB = up ? minZ : minY;
		int maxB = up ? maxZ : maxY;
		for (int x = minX; x < maxX; x += unitsPerPixel) {
			float fa = (x + half - minX) / (float) (maxX - minX);
			for (int b = minB; b < maxB; b += unitsPerPixel) {
				float fb = (b + half - minB) / (float) (maxB - minB);
				float u = 0;
				float v = 0;
				for (int c = 0; c < 4; c++) {
					float w = (farA[c] ? fa : 1 - fa) * (farB[c] ? fb : 1 - fb);
					u += w * cu[c];
					v += w * cv[c];
				}
				int wx = ox + x + half;
				if (up) {
					int y = oy + maxY;
					int wz = oz + b + half;
					sink.pixel(Math.floorDiv(wx, unitsPerPixel), Math.floorDiv(wz - y, unitsPerPixel), wz + y, u, v);
				} else {
					int z = oz + maxZ;
					int wy = oy + b + half;
					sink.pixel(Math.floorDiv(wx, unitsPerPixel), Math.floorDiv(z - wy, unitsPerPixel), z + wy, u, v);
				}
			}
		}
	}

	/** True when the quad's four corners are the four corners of its bounding rectangle. */
	public static boolean rectangle(ShapeQuad quad) {
		int minX = Math.min(Math.min(quad.x(0), quad.x(1)), Math.min(quad.x(2), quad.x(3)));
		int maxX = Math.max(Math.max(quad.x(0), quad.x(1)), Math.max(quad.x(2), quad.x(3)));
		int minY = Math.min(Math.min(quad.y(0), quad.y(1)), Math.min(quad.y(2), quad.y(3)));
		int maxY = Math.max(Math.max(quad.y(0), quad.y(1)), Math.max(quad.y(2), quad.y(3)));
		int minZ = Math.min(Math.min(quad.z(0), quad.z(1)), Math.min(quad.z(2), quad.z(3)));
		int maxZ = Math.max(Math.max(quad.z(0), quad.z(1)), Math.max(quad.z(2), quad.z(3)));
		Set<Long> corners = new HashSet<>();
		for (int c = 0; c < 4; c++) {
			boolean x = quad.x(c) == minX || quad.x(c) == maxX;
			boolean y = quad.y(c) == minY || quad.y(c) == maxY;
			boolean z = quad.z(c) == minZ || quad.z(c) == maxZ;
			if (!x || !y || !z) {
				return false;
			}
			corners.add(((long) quad.x(c) << 32) ^ ((long) quad.y(c) << 16) ^ quad.z(c));
		}
		return corners.size() == 4;
	}

	/**
	 * Projects any quad that looks up or south (its normal against the view direction (0, 1, 1)) by sampling it: a
	 * bilinear grid over its corners (a triangle repeats its last corner), each sample's (u, v) from the corners' UVs.
	 */
	static void sample(ShapeQuad quad, int bx, int by, int bz, int unitsPerPixel, PixelSink sink) {
		// The normal's y and z by Newell's method over all four corners (counter-clockwise seen from the front), so a
		// triangle works whichever of its corners repeats.
		float ny = 0;
		float nz = 0;
		for (int i = 0; i < 4; i++) {
			int j = (i + 1) & 3;
			ny += (quad.z(i) - quad.z(j)) * (float) (quad.x(i) + quad.x(j));
			nz += (quad.x(i) - quad.x(j)) * (float) (quad.y(i) + quad.y(j));
		}
		if (ny + nz <= 0) {
			return;
		}
		int ox = bx * 16;
		int oy = by * 16;
		int oz = bz * 16;
		for (int i = 0; i < SAMPLES; i++) {
			float s = (i + 0.5F) / SAMPLES;
			for (int j = 0; j < SAMPLES; j++) {
				float t = (j + 0.5F) / SAMPLES;
				// Bilinear over corners 0, 1, 2, 3: (1-s)(1-t) p0 + (1-s) t p1 + s t p2 + s (1-t) p3.
				float w0 = (1 - s) * (1 - t);
				float w1 = (1 - s) * t;
				float w2 = s * t;
				float w3 = s * (1 - t);
				float x = ox + w0 * quad.x(0) + w1 * quad.x(1) + w2 * quad.x(2) + w3 * quad.x(3);
				float y = oy + w0 * quad.y(0) + w1 * quad.y(1) + w2 * quad.y(2) + w3 * quad.y(3);
				float z = oz + w0 * quad.z(0) + w1 * quad.z(1) + w2 * quad.z(2) + w3 * quad.z(3);
				float u = w0 * quad.cornerU(0) + w1 * quad.cornerU(1) + w2 * quad.cornerU(2) + w3 * quad.cornerU(3);
				float v = w0 * quad.cornerV(0) + w1 * quad.cornerV(1) + w2 * quad.cornerV(2) + w3 * quad.cornerV(3);
				sink.pixel((int) Math.floor(x / unitsPerPixel), (int) Math.floor((z - y) / unitsPerPixel), z + y, u, v);
			}
		}
	}
}
