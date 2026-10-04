package dev.kantobridge.builder.shape.geom;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * From voxels to quads (docs/specs/VOXEL_BLOCKS_PLAN.md §5.5): one routine for objects, hulls and tufts (later
 * templates, {@code .vox} models and carved figures).
 *
 * <pre>
 * shell    keep the faces whose neighbour voxel in that direction is empty
 * merge    per direction and layer, grow rectangles greedily: along the plane's first axis while the next face is
 *          unused and shows the same tile's next texel (stepping by one, either way) or the same texel; then along the
 *          second axis while the whole row continues the pattern; never across a block boundary
 * emit     one quad per rectangle, a UV per corner, so the merged quad shows exactly the texels its faces showed; a
 *          cull face when it lies on the block's surface
 * slice    each quad filed under the block that holds it, in that block's coordinates (1/16 block)
 * planes   down/up (a = x, b = z); north/south (a = x, b = y); west/east (a = z, b = y); u along a, v along b
 * </pre>
 *
 * A quad's texture index is its texel's slot; the caller maps slots to textures. Deterministic: faces in order, layers
 * low to high, rectangles in scan order.
 */
public final class QuadMerger {
	/** Every face. */
	public static final int ALL_FACES = 0b111111;
	/** The south and north faces only: a standing cut-out's front and back. */
	public static final int FRONT_AND_BACK = 1 << ShapeQuad.NORTH | 1 << ShapeQuad.SOUTH;

	private QuadMerger() {
	}

	/** A block's key in {@link #merge}'s result: its position in blocks from the grid's corner. */
	public static int blockKey(int bx, int by, int bz) {
		return bx | by << 8 | bz << 16;
	}

	public static int blockX(int key) {
		return key & 0xFF;
	}

	public static int blockY(int key) {
		return key >> 8 & 0xFF;
	}

	public static int blockZ(int key) {
		return key >> 16 & 0xFF;
	}

	/** The merged shell of {@code grid}, per block ({@link #blockKey}); only the faces in {@code faces} (a bit per face). */
	public static Map<Integer, List<ShapeQuad>> merge(VoxelGrid grid, int faces, int flags) {
		Map<Integer, List<ShapeQuad>> out = new TreeMap<>();
		for (int face = 0; face < 6; face++) {
			if ((faces >> face & 1) != 0) {
				mergeFace(grid, face, flags, out);
			}
		}
		return out;
	}

	private static int layers(VoxelGrid g, int face) {
		return face <= ShapeQuad.UP ? g.height() : face <= ShapeQuad.SOUTH ? g.depth() : g.width();
	}

	private static int sizeA(VoxelGrid g, int face) {
		return face == ShapeQuad.WEST || face == ShapeQuad.EAST ? g.depth() : g.width();
	}

	private static int sizeB(VoxelGrid g, int face) {
		return face <= ShapeQuad.UP ? g.depth() : g.height();
	}

	/** The voxel {x, y, z} at layer {@code l} and plane cell (a, b) of a face direction. */
	private static int[] voxel(int face, int l, int a, int b) {
		return switch (face) {
			case ShapeQuad.DOWN, ShapeQuad.UP -> new int[] {a, l, b};
			case ShapeQuad.NORTH, ShapeQuad.SOUTH -> new int[] {a, b, l};
			default -> new int[] {l, b, a};
		};
	}

	/** The texel a face cell shows, or {@link VoxelGrid#EMPTY} where no face is exposed. */
	private static int faceTexel(VoxelGrid g, int face, int l, int a, int b) {
		int[] p = voxel(face, l, a, b);
		if (!g.solid(p[0], p[1], p[2]) || g.solid(p[0] + FaceMasks.DX[face], p[1] + FaceMasks.DY[face], p[2] + FaceMasks.DZ[face])) {
			return VoxelGrid.EMPTY;
		}
		return face == ShapeQuad.UP ? g.cap(p[0], p[1], p[2]) : g.at(p[0], p[1], p[2]);
	}

	private static void mergeFace(VoxelGrid g, int face, int flags, Map<Integer, List<ShapeQuad>> out) {
		int sa = sizeA(g, face);
		int sb = sizeB(g, face);
		int block = g.artPx();
		int[] cells = new int[sa * sb];
		boolean[] used = new boolean[sa * sb];
		for (int l = 0; l < layers(g, face); l++) {
			boolean any = false;
			for (int b = 0; b < sb; b++) {
				for (int a = 0; a < sa; a++) {
					int t = faceTexel(g, face, l, a, b);
					cells[b * sa + a] = t;
					any |= t != VoxelGrid.EMPTY;
				}
			}
			if (!any) {
				continue;
			}
			java.util.Arrays.fill(used, false);
			for (int b = 0; b < sb; b++) {
				for (int a = 0; a < sa; a++) {
					int t0 = cells[b * sa + a];
					if (t0 == VoxelGrid.EMPTY || used[b * sa + a]) {
						continue;
					}
					// Along a: the same tile and row, u stepping by a constant -1, 0 or +1, inside one block.
					int a1 = a + 1;
					int stepA = 0;
					while (a1 < sa && a1 / block == a / block) {
						int t = cells[b * sa + a1];
						if (t == VoxelGrid.EMPTY || used[b * sa + a1] || VoxelGrid.slot(t) != VoxelGrid.slot(t0) || VoxelGrid.v(t) != VoxelGrid.v(t0)) {
							break;
						}
						int step = VoxelGrid.u(t) - VoxelGrid.u(cells[b * sa + a1 - 1]);
						if (Math.abs(step) > 1 || (a1 > a + 1 && step != stepA)) {
							break;
						}
						stepA = step;
						a1++;
					}
					// Along b: whole rows that continue the pattern: the same columns, v stepping by a constant.
					int b1 = b + 1;
					int stepB = 0;
					while (b1 < sb && b1 / block == b / block) {
						int step = 0;
						boolean ok = true;
						for (int x = a; x < a1; x++) {
							int t = cells[b1 * sa + x];
							if (t == VoxelGrid.EMPTY || used[b1 * sa + x] || VoxelGrid.slot(t) != VoxelGrid.slot(t0)
									|| VoxelGrid.u(t) != VoxelGrid.u(cells[b * sa + x])) {
								ok = false;
								break;
							}
							int s = VoxelGrid.v(t) - VoxelGrid.v(cells[(b1 - 1) * sa + x]);
							if (Math.abs(s) > 1 || (x > a && s != step)) {
								ok = false;
								break;
							}
							step = s;
						}
						if (!ok || (b1 > b + 1 && step != stepB)) {
							break;
						}
						stepB = step;
						b1++;
					}
					for (int y = b; y < b1; y++) {
						for (int x = a; x < a1; x++) {
							used[y * sa + x] = true;
						}
					}
					emit(g, face, l, a, a1, b, b1, t0, stepA, stepB, flags, out);
				}
			}
		}
	}

	private static void emit(VoxelGrid g, int face, int l, int a0, int a1, int b0, int b1, int t0, int stepA, int stepB, int flags,
			Map<Integer, List<ShapeQuad>> out) {
		int block = g.artPx();
		int unit = 16 / block;
		int[] origin = voxel(face, l, a0, b0);
		int bx = origin[0] / block;
		int by = origin[1] / block;
		int bz = origin[2] / block;
		boolean far = face == ShapeQuad.UP || face == ShapeQuad.SOUTH || face == ShapeQuad.EAST;
		int normalBlock = face <= ShapeQuad.UP ? by : face <= ShapeQuad.SOUTH ? bz : bx;
		int aBlock = face == ShapeQuad.WEST || face == ShapeQuad.EAST ? bz : bx;
		int bBlock = face <= ShapeQuad.UP ? bz : by;
		// Block-local, in voxels: the face's plane (the voxel's far side for up, south and east), and the rectangle.
		int plane = (far ? l + 1 : l) - block * normalBlock;
		int la0 = a0 - block * aBlock;
		int la1 = a1 - block * aBlock;
		int lb0 = b0 - block * bBlock;
		int lb1 = b1 - block * bBlock;
		int[] lo;
		int[] hi;
		switch (face) {
			case ShapeQuad.DOWN, ShapeQuad.UP -> {
				lo = new int[] {la0, plane, lb0};
				hi = new int[] {la1, plane, lb1};
			}
			case ShapeQuad.NORTH, ShapeQuad.SOUTH -> {
				lo = new int[] {la0, lb0, plane};
				hi = new int[] {la1, lb1, plane};
			}
			default -> {
				lo = new int[] {plane, lb0, la0};
				hi = new int[] {plane, lb1, la1};
			}
		}
		boolean onSurface = far ? plane == block : plane == 0;
		ShapeQuad quad = ShapeQuad.face(face, lo[0] * unit, lo[1] * unit, lo[2] * unit, hi[0] * unit, hi[1] * unit, hi[2] * unit,
				VoxelGrid.slot(t0), 0, 0, 16, 16, onSurface ? face : -1, flags);
		// The texels' u along a and v along b, as texture edges in 1/16 of the texture.
		int texel = 16 / block;
		int uStart = stepA >= 0 ? VoxelGrid.u(t0) * texel : (VoxelGrid.u(t0) + 1) * texel;
		int uEnd = uStart + (stepA == 0 ? texel : stepA * (a1 - a0) * texel);
		int vStart = stepB >= 0 ? VoxelGrid.v(t0) * texel : (VoxelGrid.v(t0) + 1) * texel;
		int vEnd = vStart + (stepB == 0 ? texel : stepB * (b1 - b0) * texel);
		byte[] uv = new byte[8];
		for (int c = 0; c < 4; c++) {
			int ca;
			int cb;
			switch (face) {
				case ShapeQuad.DOWN, ShapeQuad.UP -> {
					ca = quad.x(c);
					cb = quad.z(c);
				}
				case ShapeQuad.NORTH, ShapeQuad.SOUTH -> {
					ca = quad.x(c);
					cb = quad.y(c);
				}
				default -> {
					ca = quad.z(c);
					cb = quad.y(c);
				}
			}
			uv[c * 2] = (byte) (ca == la0 * unit ? uStart : uEnd);
			uv[c * 2 + 1] = (byte) (cb == lb0 * unit ? vStart : vEnd);
		}
		out.computeIfAbsent(blockKey(bx, by, bz), k -> new ArrayList<>()).add(quad.withCornerUvs(uv));
	}
}
