package dev.kantobridge.character;

import java.util.BitSet;

/**
 * A thick card (docs/specs/VOXEL_BLOCKS_PLAN.md §5.11; report B5.2): one frame of a sprite with depth. A front quad and a
 * back quad over the whole frame (the alpha test cuts the shape, as today's card does), and an edge strip around the
 * silhouette: for every row, each run of opaque texels whose upper (lower) neighbour is transparent gets a quad across
 * the depth wearing those texels; likewise each column's runs facing left and right. From the front it is today's card
 * exactly. Pure; built once per sprite and frame.
 *
 * <p>Coordinates: the card's local frame, in sprite pixels: x 0..w left to right as seen from the front, y 0..h bottom
 * to top, z from -depth/2 (back) to +depth/2 (front, toward the camera). UVs are fractions of the frame (0..1, v down),
 * mapped into the sheet by the renderer (which mirrors a frame by swapping its u edges).
 */
public final class ThickCardMesh {
	/** More quads than this: a flat card instead. */
	public static final int MAX_QUADS = 256;
	/** The card's depth in sprite pixels (one pixel is 1/8 block at the puppet's scale). */
	public static final float DEPTH = 2.0F;

	/**
	 * A card's quads.
	 *
	 * @param vertices per quad, 4 × (x, y, z, u, v) in the order the renderer emits them
	 * @param normals  per quad, (nx, ny, nz); the front and back keep the "up" normal today's card has
	 */
	public record Mesh(float[] vertices, float[] normals, int quads) {
	}

	private ThickCardMesh() {
	}

	/**
	 * The thick card of a frame, or null when it is empty or would take more than {@link #MAX_QUADS} quads (a flat card
	 * instead).
	 *
	 * @param mask  opaque texels, row-major from the frame's top-left: bit {@code y * w + x}
	 * @param depth the card's depth in sprite pixels
	 */
	public static Mesh build(BitSet mask, int w, int h, float depth) {
		if (mask.isEmpty()) {
			return null;
		}
		float zf = depth / 2.0F;
		float zb = -depth / 2.0F;
		Builder out = new Builder();
		// Front and back over the whole frame (the alpha test cuts the shape); the back mirrored in u.
		out.quad(0, 0, zf, w, 0, zf, w, h, zf, 0, h, zf, 0, 1, 1, 1, 1, 0, 0, 0, 0, 1, 0);
		out.quad(w, 0, zb, 0, 0, zb, 0, h, zb, w, h, zb, 0, 1, 1, 1, 1, 0, 0, 0, 0, 1, 0);
		// Top and bottom edges: per row, runs of opaque texels with a transparent neighbour above (below).
		for (int row = 0; row < h; row++) {
			for (int side = 0; side < 2; side++) {
				int x = 0;
				while (x < w) {
					if (!edge(mask, w, h, x, row, 0, side == 0 ? -1 : 1)) {
						x++;
						continue;
					}
					int x0 = x;
					while (x < w && edge(mask, w, h, x, row, 0, side == 0 ? -1 : 1)) {
						x++;
					}
					// In card coordinates y runs up: frame row `row` spans y from h - row - 1 to h - row.
					float y = side == 0 ? h - row : h - row - 1;
					float v = (row + 0.5F) / h;
					float u0 = x0 / (float) w;
					float u1 = x / (float) w;
					if (side == 0) {
						out.quad(x0, y, zf, x, y, zf, x, y, zb, x0, y, zb, u0, v, u1, v, u1, v, u0, v, 0, 1, 0);
					} else {
						out.quad(x0, y, zb, x, y, zb, x, y, zf, x0, y, zf, u0, v, u1, v, u1, v, u0, v, 0, -1, 0);
					}
				}
			}
		}
		// Left and right edges: per column, runs of opaque texels with a transparent neighbour to the left (right).
		for (int col = 0; col < w; col++) {
			for (int side = 0; side < 2; side++) {
				int row = 0;
				while (row < h) {
					if (!edge(mask, w, h, col, row, side == 0 ? -1 : 1, 0)) {
						row++;
						continue;
					}
					int r0 = row;
					while (row < h && edge(mask, w, h, col, row, side == 0 ? -1 : 1, 0)) {
						row++;
					}
					float x = side == 0 ? col : col + 1;
					float yTop = h - r0;
					float yBottom = h - row;
					float u = (col + 0.5F) / w;
					float v0 = r0 / (float) h;
					float v1 = row / (float) h;
					if (side == 0) {
						out.quad(x, yBottom, zb, x, yBottom, zf, x, yTop, zf, x, yTop, zb, u, v1, u, v1, u, v0, u, v0, -1, 0, 0);
					} else {
						out.quad(x, yBottom, zf, x, yBottom, zb, x, yTop, zb, x, yTop, zf, u, v1, u, v1, u, v0, u, v0, 1, 0, 0);
					}
				}
			}
		}
		return out.quads > MAX_QUADS ? null : out.build();
	}

	/** True when texel (x, y) is opaque and its neighbour (x + dx, y + dy) is not (or lies outside the frame). */
	static boolean edge(BitSet mask, int w, int h, int x, int y, int dx, int dy) {
		if (!mask.get(y * w + x)) {
			return false;
		}
		int nx = x + dx;
		int ny = y + dy;
		return nx < 0 || ny < 0 || nx >= w || ny >= h || !mask.get(ny * w + nx);
	}

	private static final class Builder {
		private float[] vertices = new float[20 * 16];
		private float[] normals = new float[3 * 16];
		private int quads;

		void quad(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, float u0,
				float v0, float u1, float v1, float u2, float v2, float u3, float v3, float nx, float ny, float nz) {
			if ((this.quads + 1) * 20 > this.vertices.length) {
				this.vertices = java.util.Arrays.copyOf(this.vertices, this.vertices.length * 2);
				this.normals = java.util.Arrays.copyOf(this.normals, this.normals.length * 2);
			}
			float[] v = {x0, y0, z0, u0, v0, x1, y1, z1, u1, v1, x2, y2, z2, u2, v2, x3, y3, z3, u3, v3};
			System.arraycopy(v, 0, this.vertices, this.quads * 20, 20);
			this.normals[this.quads * 3] = nx;
			this.normals[this.quads * 3 + 1] = ny;
			this.normals[this.quads * 3 + 2] = nz;
			this.quads++;
		}

		Mesh build() {
			return new Mesh(java.util.Arrays.copyOf(this.vertices, this.quads * 20), java.util.Arrays.copyOf(this.normals, this.quads * 3),
					this.quads);
		}
	}
}
