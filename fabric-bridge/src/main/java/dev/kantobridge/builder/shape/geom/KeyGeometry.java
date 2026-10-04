package dev.kantobridge.builder.shape.geom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * The direct kinds' geometry (docs/specs/VOXEL_BLOCKS_PLAN.md §5.5), block-local in 1/16 block; one Game Boy pixel is
 * two units. Textures are tile ids, turned into the table's texture indices by {@code texture}. Face conventions as in
 * {@link ShapeQuad}: tops show the tile as on the map (texture top = north); vertical faces show it upright, a face shorter
 * than the block showing the tile's lowest rows (cropped, never stretched).
 *
 * <pre>
 * cube  a|u,d,n,s,w,e          six faces on the block's surface, each its own tile and cull face; tile -1: no face
 * slab  a|t|h                  a box 0..16 × 0..h × 0..16: top = t, sides = the lowest h/2 pixel rows of t
 * roof  a|u,s,n|h0,h1|hW,hE    a wedge: the top from h0 at the south edge to h1 at the north edge, wearing u; vertical
 *                              south and north faces up to h0 and h1 wearing s and n; gable flanks wearing s, or, on a
 *                              hipped side, the top's edge lowered to the eave (both sides hipped: a ridge along x = 8)
 * </pre>
 */
final class KeyGeometry {
	private KeyGeometry() {
	}

	/** Key order up, down, north, south, west, east; quads sorted by face. */
	static List<ShapeQuad> cube(int[] tiles, IntUnaryOperator texture) {
		int[] faceOfKey = {ShapeQuad.UP, ShapeQuad.DOWN, ShapeQuad.NORTH, ShapeQuad.SOUTH, ShapeQuad.WEST, ShapeQuad.EAST};
		List<ShapeQuad> quads = new ArrayList<>(6);
		for (int i = 0; i < 6; i++) {
			if (tiles[i] >= 0) {
				quads.add(ShapeQuad.fullFace(faceOfKey[i], texture.applyAsInt(tiles[i])));
			}
		}
		quads.sort(Comparator.comparingInt(ShapeQuad::face));
		return quads;
	}

	static List<ShapeQuad> slab(int tile, int h, IntUnaryOperator texture) {
		int t = texture.applyAsInt(tile);
		List<ShapeQuad> quads = new ArrayList<>(6);
		quads.add(ShapeQuad.face(ShapeQuad.DOWN, 0, 0, 0, 16, 0, 16, t, 0, 0, 16, 16, ShapeQuad.DOWN, 0));
		quads.add(ShapeQuad.face(ShapeQuad.UP, 0, h, 0, 16, h, 16, t, 0, 0, 16, 16, h == 16 ? ShapeQuad.UP : -1, 0));
		for (int face = ShapeQuad.NORTH; face <= ShapeQuad.EAST; face++) {
			quads.add(ShapeQuad.face(face, 0, 0, 0, 16, h, 16, t, 0, 16 - h, 16, 16, face, 0));
		}
		return quads;
	}

	static List<ShapeQuad> roof(int up, int south, int north, int h0, int h1, boolean hipW, boolean hipE, IntUnaryOperator texture) {
		int tu = texture.applyAsInt(up);
		int ts = texture.applyAsInt(south);
		int tn = texture.applyAsInt(north);
		List<ShapeQuad> quads = new ArrayList<>(8);
		// The top's corner heights: its west and east edges at the south (z 16) and the north (z 0).
		int wS = hipW ? 0 : h0;
		int wN = hipW ? 0 : h1;
		int eS = hipE ? 0 : h0;
		int eN = hipE ? 0 : h1;
		if (hipW && hipE) {
			int[] nm = {8, h1, 0};
			int[] sm = {8, h0, 16};
			top(quads, tu, new int[] {0, 0, 0}, new int[] {0, 0, 16}, sm, nm);
			top(quads, tu, nm, sm, new int[] {16, 0, 16}, new int[] {16, 0, 0});
		} else if (!hipW && !hipE && h0 == h1) {
			quads.add(ShapeQuad.face(ShapeQuad.UP, 0, h0, 0, 16, h0, 16, tu, 0, 0, 16, 16, h0 == 16 ? ShapeQuad.UP : -1, 0));
		} else {
			top(quads, tu, new int[] {0, wN, 0}, new int[] {0, wS, 16}, new int[] {16, eS, 16}, new int[] {16, eN, 0});
		}
		if (h0 > 0) {
			int[][] face = hipW && hipE ? new int[][] {{8, h0, 16}, {0, 0, 16}, {16, 0, 16}}
					: new int[][] {{0, wS, 16}, {0, 0, 16}, {16, 0, 16}, {16, eS, 16}};
			quads.add(ShapeQuad.polygon(ShapeQuad.SOUTH, face, ts, 0, 16 - h0, 16, 16, ShapeQuad.SOUTH, 0));
		}
		if (h1 > 0) {
			int[][] face = hipW && hipE ? new int[][] {{8, h1, 0}, {16, 0, 0}, {0, 0, 0}}
					: new int[][] {{16, eN, 0}, {16, 0, 0}, {0, 0, 0}, {0, wN, 0}};
			quads.add(ShapeQuad.polygon(ShapeQuad.NORTH, face, tn, 0, 16 - h1, 16, 16, ShapeQuad.NORTH, 0));
		}
		int high = Math.max(h0, h1);
		if (!hipW && high > 0) {
			quads.add(ShapeQuad.polygon(ShapeQuad.WEST, new int[][] {{0, h1, 0}, {0, 0, 0}, {0, 0, 16}, {0, h0, 16}}, ts, 0, 16 - high, 16, 16,
					ShapeQuad.WEST, 0));
		}
		if (!hipE && high > 0) {
			quads.add(ShapeQuad.polygon(ShapeQuad.EAST, new int[][] {{16, h0, 16}, {16, 0, 16}, {16, 0, 0}, {16, h1, 0}}, ts, 0, 16 - high, 16,
					16, ShapeQuad.EAST, 0));
		}
		return quads;
	}

	/**
	 * A top surface from four corners (north-west, south-west, south-east, north-east): one quad when they lie in a plane,
	 * else two triangles. The texture lies on it from above: u = x, v = z.
	 */
	private static void top(List<ShapeQuad> out, int texture, int[] nw, int[] sw, int[] se, int[] ne) {
		if (planar(nw, sw, se, ne)) {
			out.add(ShapeQuad.sloped(new int[][] {nw, sw, se, ne}, new int[][] {uv(nw), uv(sw), uv(se), uv(ne)}, texture, -1, 0));
			return;
		}
		out.add(ShapeQuad.sloped(new int[][] {nw, sw, se}, new int[][] {uv(nw), uv(sw), uv(se)}, texture, -1, 0));
		out.add(ShapeQuad.sloped(new int[][] {nw, se, ne}, new int[][] {uv(nw), uv(se), uv(ne)}, texture, -1, 0));
	}

	private static int[] uv(int[] p) {
		return new int[] {p[0], p[2]};
	}

	private static boolean planar(int[] a, int[] b, int[] c, int[] d) {
		long[] u = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
		long[] v = {c[0] - a[0], c[1] - a[1], c[2] - a[2]};
		long[] w = {d[0] - a[0], d[1] - a[1], d[2] - a[2]};
		long nx = u[1] * v[2] - u[2] * v[1];
		long ny = u[2] * v[0] - u[0] * v[2];
		long nz = u[0] * v[1] - u[1] * v[0];
		return nx * w[0] + ny * w[1] + nz * w[2] == 0;
	}
}
