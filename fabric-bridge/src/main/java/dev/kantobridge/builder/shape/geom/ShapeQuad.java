package dev.kantobridge.builder.shape.geom;

/**
 * One quad of a shape (docs/specs/VOXEL_BLOCKS_PLAN.md §5.5). Block-local; positions in 1/16 block (they may leave the
 * block by up to 8 units, an overflow its neighbour draws); UVs in 1/16 of the texture.
 *
 * <p>An axis-aligned quad ({@code face} 0..5) gives its texture rectangle as four values {@code u0, v0, u1, v1}, which
 * the face's convention maps onto its corners ({@link #cornerUvs()}): top faces show the texture as on the map
 * (texture top = north); south faces upright; north faces seen from the north; west and east faces upright as seen from
 * outside (vanilla's default face mapping). A reversed pair ({@code u0 > u1}) mirrors. A sloped quad ({@code face} 6)
 * gives eight values, one (u, v) per corner; a face quad may too (merged voxel faces whose texels repeat or step).
 *
 * @param face    0..5 down, up, north, south, west, east; 6 sloped
 * @param xyz     4 corners × (x, y, z), in the vanilla vertex order of the face
 * @param texture an index into the shape table's texture keys
 * @param uv      4 values (a rectangle) or 8 (per corner)
 * @param cull    the face a neighbour may cull it by (it lies on the block's surface), or -1
 * @param flags   {@link #CUTOUT}, {@link #DARKEN}, {@link #EMISSIVE}, {@link #NO_AO}
 */
public record ShapeQuad(byte face, short[] xyz, int texture, byte[] uv, byte cull, byte flags) {
	public static final int DOWN = 0;
	public static final int UP = 1;
	public static final int NORTH = 2;
	public static final int SOUTH = 3;
	public static final int WEST = 4;
	public static final int EAST = 5;
	public static final int SLOPED = 6;
	public static final int CUTOUT = 1;
	public static final int DARKEN = 2;
	public static final int EMISSIVE = 4;
	public static final int NO_AO = 8;
	/** Two bits from here: a key's {@code tint} (0..3), a darkening drawn as a vertex colour (V7: ceilings). */
	public static final int TINT_SHIFT = 4;

	public ShapeQuad {
		if (face < 0 || face > SLOPED || xyz.length != 12 || (uv.length != 4 && uv.length != 8) || cull < -1 || cull > EAST) {
			throw new IllegalArgumentException("malformed quad");
		}
		if (face == SLOPED && uv.length != 8) {
			throw new IllegalArgumentException("a sloped quad gives one uv per corner");
		}
		for (short v : xyz) {
			if (v < -8 || v > 24) {
				throw new IllegalArgumentException("a quad may leave its block by 8/16 at most");
			}
		}
	}

	/**
	 * An axis-aligned quad covering the rectangle between {@code (x0, y0, z0)} and {@code (x1, y1, z1)} (flat along the
	 * face's axis), with the corners in the face's vanilla order.
	 */
	public static ShapeQuad face(int face, int x0, int y0, int z0, int x1, int y1, int z1, int texture, int u0, int v0, int u1, int v1,
			int cull, int flags) {
		short[] p = switch (face) {
			case UP -> corners(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
			case DOWN -> corners(x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1);
			case NORTH -> corners(x1, y1, z0, x1, y0, z0, x0, y0, z0, x0, y1, z0);
			case SOUTH -> corners(x0, y1, z1, x0, y0, z1, x1, y0, z1, x1, y1, z1);
			case WEST -> corners(x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1);
			case EAST -> corners(x1, y1, z1, x1, y0, z1, x1, y0, z0, x1, y1, z0);
			default -> throw new IllegalArgumentException("not a face");
		};
		return new ShapeQuad((byte) face, p, texture, new byte[] {(byte) u0, (byte) v0, (byte) u1, (byte) v1}, (byte) cull, (byte) flags);
	}

	/** The same quad with one (u, v) per corner. */
	public ShapeQuad withCornerUvs(byte[] uv8) {
		return new ShapeQuad(this.face, this.xyz, this.texture, uv8, this.cull, this.flags);
	}

	/** The same quad wearing another texture. */
	public ShapeQuad withTexture(int texture) {
		return texture == this.texture ? this : new ShapeQuad(this.face, this.xyz, texture, this.uv, this.cull, this.flags);
	}

	/** This quad darkened by a key's tint, 0..3 (it replaces any tint it had). */
	public ShapeQuad withTint(int tint) {
		if (tint < 0 || tint > 3) {
			throw new IllegalArgumentException("tint out of range");
		}
		return new ShapeQuad(this.face, this.xyz, this.texture, this.uv, this.cull, (byte) (this.flags & ~(3 << TINT_SHIFT) | tint << TINT_SHIFT));
	}

	/** The quad's tint, 0..3. */
	public int tint() {
		return this.flags >> TINT_SHIFT & 3;
	}

	/** One full face of the block, the whole texture on it, culled by that side. */
	public static ShapeQuad fullFace(int face, int texture) {
		return face(face, 0, 0, 0, 16, 16, 16, texture, 0, 0, 16, 16, face, 0);
	}

	/**
	 * A polygon of three or four corners ({@code {x, y, z}} each, in the face's vanilla winding; a triangle repeats its
	 * last corner) lying flat on axis-aligned face {@code face}, wearing the texture rectangle {@code u0, v0 → u1, v1}
	 * mapped over its bounding box by the face's convention.
	 */
	public static ShapeQuad polygon(int face, int[][] corners, int texture, int u0, int v0, int u1, int v1, int cull, int flags) {
		if (face == SLOPED || (corners.length != 3 && corners.length != 4)) {
			throw new IllegalArgumentException("a face polygon has three or four corners on an axis-aligned face");
		}
		return new ShapeQuad((byte) face, flatten(corners), texture, new byte[] {(byte) u0, (byte) v0, (byte) u1, (byte) v1}, (byte) cull,
				(byte) flags);
	}

	/**
	 * A sloped quad or triangle (three or four corners, counter-clockwise seen from its front; a triangle repeats its last
	 * corner) with one (u, v) per corner.
	 */
	public static ShapeQuad sloped(int[][] corners, int[][] uvs, int texture, int cull, int flags) {
		if ((corners.length != 3 && corners.length != 4) || uvs.length != corners.length) {
			throw new IllegalArgumentException("a sloped quad has three or four corners, each with a uv");
		}
		byte[] uv = new byte[8];
		for (int i = 0; i < 4; i++) {
			int[] one = uvs[Math.min(i, uvs.length - 1)];
			uv[i * 2] = (byte) one[0];
			uv[i * 2 + 1] = (byte) one[1];
		}
		return new ShapeQuad((byte) SLOPED, flatten(corners), texture, uv, (byte) cull, (byte) flags);
	}

	private static short[] flatten(int[][] corners) {
		short[] out = new short[12];
		for (int i = 0; i < 4; i++) {
			int[] c = corners[Math.min(i, corners.length - 1)];
			out[i * 3] = (short) c[0];
			out[i * 3 + 1] = (short) c[1];
			out[i * 3 + 2] = (short) c[2];
		}
		return out;
	}

	private static short[] corners(int... values) {
		short[] out = new short[values.length];
		for (int i = 0; i < values.length; i++) {
			out[i] = (short) values[i];
		}
		return out;
	}

	public int x(int corner) {
		return this.xyz[corner * 3];
	}

	public int y(int corner) {
		return this.xyz[corner * 3 + 1];
	}

	public int z(int corner) {
		return this.xyz[corner * 3 + 2];
	}

	public boolean has(int flag) {
		return (this.flags & flag) != 0;
	}

	/** Each corner's (u, v), in 1/16 of the texture: explicit for a sloped quad, else by the face's convention. */
	public float[] cornerUvs() {
		float[] out = new float[8];
		for (int i = 0; i < 4; i++) {
			out[i * 2] = this.cornerU(i);
			out[i * 2 + 1] = this.cornerV(i);
		}
		return out;
	}

	/** Corner {@code i}'s u in 1/16 of the texture, without allocating (the mesh threads' path). */
	public float cornerU(int i) {
		return this.cornerUv(i, 0);
	}

	/** Corner {@code i}'s v in 1/16 of the texture, without allocating. */
	public float cornerV(int i) {
		return this.cornerUv(i, 1);
	}

	/**
	 * The face conventions (vanilla's UV lock): down u = x, v = 1 − z; up u = x, v = z; north u = 1 − x, v = 1 − y;
	 * south u = x, v = 1 − y; west u = z, v = 1 − y; east u = 1 − z, v = 1 − y; each a fraction of the quad's extent
	 * along that axis, mapped onto the rectangle {@code u0, v0 → u1, v1}.
	 */
	private float cornerUv(int i, int axis) {
		if (this.uv.length == 8) {
			return this.uv[i * 2 + axis];
		}
		int minX = Math.min(Math.min(this.xyz[0], this.xyz[3]), Math.min(this.xyz[6], this.xyz[9]));
		int maxX = Math.max(Math.max(this.xyz[0], this.xyz[3]), Math.max(this.xyz[6], this.xyz[9]));
		int minY = Math.min(Math.min(this.xyz[1], this.xyz[4]), Math.min(this.xyz[7], this.xyz[10]));
		int maxY = Math.max(Math.max(this.xyz[1], this.xyz[4]), Math.max(this.xyz[7], this.xyz[10]));
		int minZ = Math.min(Math.min(this.xyz[2], this.xyz[5]), Math.min(this.xyz[8], this.xyz[11]));
		int maxZ = Math.max(Math.max(this.xyz[2], this.xyz[5]), Math.max(this.xyz[8], this.xyz[11]));
		float f;
		if (axis == 0) {
			f = switch (this.face) {
				case DOWN, UP, SOUTH -> frac(this.x(i), minX, maxX);
				case NORTH -> frac(maxX - this.x(i) + minX, minX, maxX);
				case WEST -> frac(this.z(i), minZ, maxZ);
				default -> frac(maxZ - this.z(i) + minZ, minZ, maxZ);
			};
			return this.uv[0] + f * (this.uv[2] - this.uv[0]);
		}
		f = switch (this.face) {
			case DOWN -> frac(maxZ - this.z(i) + minZ, minZ, maxZ);
			case UP -> frac(this.z(i), minZ, maxZ);
			default -> frac(maxY - this.y(i) + minY, minY, maxY);
		};
		return this.uv[1] + f * (this.uv[3] - this.uv[1]);
	}

	private static float frac(int value, int min, int max) {
		return max == min ? 0 : (value - min) / (float) (max - min);
	}
}
