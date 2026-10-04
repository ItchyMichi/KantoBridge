package dev.kantobridge.builder.shape.geom;

import dev.kantobridge.builder.shape.ShapeKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntUnaryOperator;
import org.jetbrains.annotations.Nullable;

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
 * box   a|u,n,s,w,e|h          a box 0..16 × 0..h × 0..16 without a bottom: the top wearing u, the sides n, s, w, e, each
 *                              the lowest h/2 pixel rows of its tile; -1: no face (all -1: nothing at all)
 * box   ...|v=p                its south face's panes sunk one pixel (a bookcase's, P3; needs the south tile's art): a
 *                              pane is a 4-connected region of non-black pixels the face's edges don't touch; its reveals
 *                              wear the pixels beside them; more than PANE_QUADS quads: the flat face
 * </pre>
 */
final class KeyGeometry {
	private KeyGeometry() {
	}

	/** A key drawn without pixels ({@code cube}, {@code slab}, {@code roof}), or null for any other kind. */
	static @Nullable List<ShapeQuad> direct(ShapeKey key, IntUnaryOperator texture) {
		return switch (key.kind()) {
			case CUBE -> cube(key.numbers(), texture);
			case SLAB -> slab(key.number(0), key.number(1), texture);
			case ROOF -> roof(key.number(0), key.number(1), key.number(2), key.number(3), key.number(4), key.number(5) == 1, key.number(6) == 1,
					texture);
			case BOX -> key.variant() == null ? box(key.numbers(), texture) : null;
			default -> null;
		};
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

	/** Key order up, north, south, west, east, then h; quads sorted by face. */
	static List<ShapeQuad> box(int[] n, IntUnaryOperator texture) {
		int h = n[5];
		List<ShapeQuad> quads = new ArrayList<>(5);
		if (n[0] >= 0) {
			quads.add(ShapeQuad.face(ShapeQuad.UP, 0, h, 0, 16, h, 16, texture.applyAsInt(n[0]), 0, 0, 16, 16, h == 16 ? ShapeQuad.UP : -1, 0));
		}
		int[] faces = {ShapeQuad.NORTH, ShapeQuad.SOUTH, ShapeQuad.WEST, ShapeQuad.EAST};
		for (int i = 0; i < faces.length; i++) {
			if (n[1 + i] >= 0) {
				quads.add(ShapeQuad.face(faces[i], 0, 0, 0, 16, h, 16, texture.applyAsInt(n[1 + i]), 0, 16 - h, 16, 16, faces[i], 0));
			}
		}
		quads.sort(Comparator.comparingInt(ShapeQuad::face));
		return quads;
	}

	/** The most quads a sunk-pane face may take; more draws the flat face. */
	static final int PANE_QUADS = 48;
	private static final int BLACK = 3;

	/**
	 * A box whose south face's panes sink one pixel (P3's bookcases): {@code shades} the south tile's ({@code artPx²},
	 * row-major). The face shows the tile's lowest h/2 rows, as {@link #box} does.
	 */
	static List<ShapeQuad> paneBox(int[] n, int[] shades, int artPx, IntUnaryOperator texture) {
		List<ShapeQuad> plain = box(n, texture);
		int h = n[5];
		int south = n[2];
		int rows = Math.min(artPx, h * artPx / 16);
		if (south < 0 || rows < 3 || artPx != 8) {
			return plain;
		}
		int top = artPx - rows;
		boolean[] sunk = panes(shades, artPx, top);
		boolean any = false;
		for (boolean b : sunk) {
			any |= b;
		}
		if (!any) {
			return plain;
		}
		int t = texture.applyAsInt(south);
		int unit = 16 / artPx;
		List<ShapeQuad> face = new ArrayList<>();
		// The face as rectangles of one state, proud on the box's plane, sunk one pixel in.
		for (int state = 0; state < 2; state++) {
			boolean want = state == 1;
			boolean[] done = new boolean[artPx * artPx];
			for (int v = top; v < artPx; v++) {
				for (int u = 0; u < artPx; u++) {
					if (done[v * artPx + u] || sunk[v * artPx + u] != want) {
						continue;
					}
					int u1 = u;
					while (u1 + 1 < artPx && !done[v * artPx + u1 + 1] && sunk[v * artPx + u1 + 1] == want) {
						u1++;
					}
					int v1 = v;
					while (v1 + 1 < artPx && rowIs(sunk, done, artPx, v1 + 1, u, u1, want)) {
						v1++;
					}
					for (int vv = v; vv <= v1; vv++) {
						for (int uu = u; uu <= u1; uu++) {
							done[vv * artPx + uu] = true;
						}
					}
					int z = want ? 16 - unit : 16;
					face.add(ShapeQuad.face(ShapeQuad.SOUTH, u * unit, (artPx - 1 - v1) * unit, 0, (u1 + 1) * unit, (artPx - v) * unit, z, t, u * unit,
							v * unit, (u1 + 1) * unit, (v1 + 1) * unit, want ? -1 : ShapeQuad.SOUTH, 0));
				}
			}
		}
		// The reveals: where a sunk pixel meets a proud one, one pixel deep, wearing the proud pixel; runs merged.
		for (int u = 0; u <= artPx; u++) {
			for (int v = top; v < artPx; v++) {
				int side = reveal(sunk, artPx, top, u, v);
				if (side == 0) {
					continue;
				}
				int v1 = v;
				while (v1 + 1 < artPx && reveal(sunk, artPx, top, u, v1 + 1) == side) {
					v1++;
				}
				// side > 0: sunk to the east of the edge (a proud pixel west); side < 0: sunk to the west.
				int pu = side > 0 ? u - 1 : u;
				int x = u * unit;
				face.add(ShapeQuad.face(side > 0 ? ShapeQuad.EAST : ShapeQuad.WEST, x, (artPx - 1 - v1) * unit, 16 - unit, x, (artPx - v) * unit, 16, t,
						pu * unit, v * unit, (pu + 1) * unit, (v1 + 1) * unit, -1, 0));
				v = v1;
			}
		}
		for (int v = top; v <= artPx; v++) {
			for (int u = 0; u < artPx; u++) {
				int side = ledge(sunk, artPx, top, u, v);
				if (side == 0) {
					continue;
				}
				int u1 = u;
				while (u1 + 1 < artPx && ledge(sunk, artPx, top, u1 + 1, v) == side) {
					u1++;
				}
				// side > 0: a sill (sunk above the edge, proud below); side < 0: a lintel (sunk below, proud above).
				int pv = side > 0 ? v : v - 1;
				int y = (artPx - v) * unit;
				face.add(ShapeQuad.face(side > 0 ? ShapeQuad.UP : ShapeQuad.DOWN, u * unit, y, 16 - unit, (u1 + 1) * unit, y, 16, t, u * unit,
						pv * unit, (u1 + 1) * unit, (pv + 1) * unit, -1, 0));
				u = u1;
			}
		}
		if (face.size() > PANE_QUADS) {
			return plain;
		}
		List<ShapeQuad> out = new ArrayList<>();
		for (ShapeQuad quad : plain) {
			if (quad.face() != ShapeQuad.SOUTH) {
				out.add(quad);
			}
		}
		out.addAll(face);
		out.sort(Comparator.comparingInt(ShapeQuad::face));
		return out;
	}

	/**
	 * Panes of a tile's face showing rows {@code top..artPx-1}: its 4-connected regions of non-black pixels that touch none
	 * of the face's edges.
	 */
	static boolean[] panes(int[] shades, int artPx, int top) {
		boolean[] out = new boolean[artPx * artPx];
		boolean[] seen = new boolean[artPx * artPx];
		int[] queue = new int[artPx * artPx];
		for (int start = top * artPx; start < artPx * artPx; start++) {
			if (seen[start] || shades[start] == BLACK) {
				continue;
			}
			int head = 0;
			int tail = 0;
			queue[tail++] = start;
			seen[start] = true;
			boolean edge = false;
			while (head < tail) {
				int i = queue[head++];
				int x = i % artPx;
				int y = i / artPx;
				edge |= x == 0 || y == top || x == artPx - 1 || y == artPx - 1;
				int[] next = {y > top ? i - artPx : -1, x > 0 ? i - 1 : -1, x < artPx - 1 ? i + 1 : -1, y < artPx - 1 ? i + artPx : -1};
				for (int nb : next) {
					if (nb >= 0 && !seen[nb] && shades[nb] != BLACK) {
						seen[nb] = true;
						queue[tail++] = nb;
					}
				}
			}
			if (!edge) {
				for (int k = 0; k < tail; k++) {
					out[queue[k]] = true;
				}
			}
		}
		return out;
	}

	private static boolean rowIs(boolean[] sunk, boolean[] done, int artPx, int v, int u0, int u1, boolean want) {
		for (int u = u0; u <= u1; u++) {
			if (done[v * artPx + u] || sunk[v * artPx + u] != want) {
				return false;
			}
		}
		return true;
	}

	/** The vertical edge at column boundary {@code u} on row {@code v}: 1 sunk east of it, -1 sunk west, 0 none. */
	private static int reveal(boolean[] sunk, int artPx, int top, int u, int v) {
		boolean west = u > 0 && sunk[v * artPx + u - 1];
		boolean east = u < artPx && sunk[v * artPx + u];
		if (east && !west && u > 0) {
			return 1;
		}
		if (west && !east && u < artPx) {
			return -1;
		}
		return 0;
	}

	/** The horizontal edge at row boundary {@code v} (above row v) in column {@code u}: 1 a sill, -1 a lintel, 0 none. */
	private static int ledge(boolean[] sunk, int artPx, int top, int u, int v) {
		boolean above = v > top && sunk[(v - 1) * artPx + u];
		boolean below = v < artPx && sunk[v * artPx + u];
		if (above && !below && v < artPx) {
			return 1;
		}
		if (below && !above && v > top) {
			return -1;
		}
		return 0;
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
