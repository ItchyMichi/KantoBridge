package dev.kantobridge.builder.shape;

import dev.kantobridge.builder.shape.geom.ShapeQuad;
import dev.kantobridge.builder.shape.geom.TextureKey;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Re-projects the 3D result into the game's own view and scores it against the 2D map (docs/specs/VOXEL_BLOCKS_PLAN.md
 * §5.6). Every up- and south-facing texel is projected with {@code (x, y, z) → (x, z − y)} (a point {@code y} pixels high
 * shows {@code y} pixels further up the screen), the nearest sample (largest {@code z + y}) wins each screen pixel, and
 * it is compared by texel identity (tile and texel coordinates, never colour) with {@link Reference}.
 *
 * <ul>
 * <li>{@code holes}: map-body pixels nothing lands on;</li>
 * <li>{@code ground_match}: of the ground pixels not hidden by a structure, the share showing the same texel;</li>
 * <li>{@code structure_match}: of the structure pixels, the share showing the same texel;</li>
 * <li>{@code occluded_ground}: the share of ground pixels a structure hides (inherent to 3D, only reported);</li>
 * <li>{@code back_faces}: north faces nothing hides from the north (the "second front" problem, only reported).</li>
 * </ul>
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
	static long texel(int tile, int tx, int ty) {
		return ((long) tile << 16) | ((long) tx << 8) | ty;
	}

	/** Scores one map's geometry against its reference. */
	public static Scores score(String id, Reference reference, String atlas, Geometry geometry, int artPx) {
		int w = reference.width();
		int h = reference.height();
		int[] near = new int[w * h];
		Arrays.fill(near, Integer.MIN_VALUE);
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
			if (quad.face() != ShapeQuad.UP && quad.face() != ShapeQuad.SOUTH) {
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
				boolean ground = reference.ground(sx, sy);
				if (ground) {
					groundPixels++;
				} else {
					structurePixels++;
				}
				if (near[i] == Integer.MIN_VALUE) {
					holes++;
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

	private static int texelOf(float uv, int artPx) {
		return Math.max(0, Math.min(artPx - 1, (int) Math.floor(uv * artPx / 16)));
	}

	private static double ratio(long part, long whole) {
		return whole == 0 ? 1.0 : part / (double) whole;
	}

	/** A projected texel: screen pixel, nearness, and the texel's (u, v) in 1/16 of the texture. */
	@FunctionalInterface
	interface PixelSink {
		void pixel(int sx, int sy, int nearness, float u, float v);
	}

	/**
	 * Projects every texel-sized cell of an up- or south-facing axis-aligned quad. Coordinates are 1/16 block; one screen pixel is
	 * {@code unitsPerPixel} of them. The texel's centre picks the screen pixel.
	 */
	static void project(ShapeQuad quad, int bx, int by, int bz, int unitsPerPixel, PixelSink sink) {
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
		// Up and south faces both run u along +x; v runs north to south (up) or top to bottom (south).
		byte[] rect = quad.uv();
		float u0 = rect[0];
		float v0 = rect[1];
		float u1 = rect[2];
		float v1 = rect[3];
		int ox = bx * 16;
		int oy = by * 16;
		int oz = bz * 16;
		int half = unitsPerPixel / 2;
		if (quad.face() == ShapeQuad.UP) {
			int y = oy + maxY;
			for (int x = minX; x < maxX; x += unitsPerPixel) {
				for (int z = minZ; z < maxZ; z += unitsPerPixel) {
					int wx = ox + x + half;
					int wz = oz + z + half;
					float fu = (x + half - minX) / (float) (maxX - minX);
					float fv = (z + half - minZ) / (float) (maxZ - minZ);
					sink.pixel(Math.floorDiv(wx, unitsPerPixel), Math.floorDiv(wz - y, unitsPerPixel), wz + y, lerp(u0, u1, fu), lerp(v0, v1, fv));
				}
			}
		} else {
			int z = oz + maxZ;
			for (int x = minX; x < maxX; x += unitsPerPixel) {
				for (int y = minY; y < maxY; y += unitsPerPixel) {
					int wx = ox + x + half;
					int wy = oy + y + half;
					float fu = (x + half - minX) / (float) (maxX - minX);
					float fv = (maxY - (y + half)) / (float) (maxY - minY);
					sink.pixel(Math.floorDiv(wx, unitsPerPixel), Math.floorDiv(z - wy, unitsPerPixel), z + wy, lerp(u0, u1, fu), lerp(v0, v1, fv));
				}
			}
		}
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}
}
