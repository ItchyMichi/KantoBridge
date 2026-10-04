package dev.kantobridge.builder.shape.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * From voxels to quads (docs/specs/VOXEL_BLOCKS_PLAN.md §5.5, §6.1 "the greedy merge keeps the texel mapping exact"): a
 * rasterised merged shell equals the unmerged one. Every exposed voxel face is covered by exactly one quad, which shows
 * there exactly the voxel's texel (its cap on top); no quad covers anything else; no quad crosses a block.
 */
class QuadMergerTest {
	@Test
	void theMergedShellShowsExactlyItsVoxelsTexels() {
		Random random = new Random(20261004);
		for (int trial = 0; trial < 120; trial++) {
			VoxelGrid grid = new VoxelGrid(16, 16, 16, 8);
			fill(grid, random, trial % 3);
			check(grid);
		}
	}

	/** Mode 0: texels laid like a drawing (merge-friendly); 1: random texels; 2: runs that repeat a texel in depth. */
	private static void fill(VoxelGrid grid, Random random, int mode) {
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					if (random.nextInt(3) == 0) {
						continue;
					}
					int texel = switch (mode) {
						case 0 -> VoxelGrid.texel((y / 8) * 2 + x / 8, x % 8, (15 - y) % 8);
						case 1 -> VoxelGrid.texel(random.nextInt(3), random.nextInt(8), random.nextInt(8));
						default -> VoxelGrid.texel(1, x % 8, 7 - y % 8);
					};
					grid.set(x, y, z, texel);
					if (random.nextInt(4) == 0) {
						grid.setCap(x, y, z, VoxelGrid.texel(2, random.nextInt(8), random.nextInt(8)));
					}
				}
			}
		}
	}

	private static void check(VoxelGrid grid) {
		int[][] covered = new int[6][16 * 16 * 16];
		for (Map.Entry<Integer, List<ShapeQuad>> e : QuadMerger.merge(grid, QuadMerger.ALL_FACES, 0).entrySet()) {
			int bx = QuadMerger.blockX(e.getKey());
			int by = QuadMerger.blockY(e.getKey());
			int bz = QuadMerger.blockZ(e.getKey());
			for (ShapeQuad quad : e.getValue()) {
				rasterise(grid, quad, bx, by, bz, covered);
			}
		}
		for (int face = 0; face < 6; face++) {
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						boolean exposed = grid.solid(x, y, z) && !grid.solid(x + FaceMasks.DX[face], y + FaceMasks.DY[face], z + FaceMasks.DZ[face]);
						assertEquals(exposed ? 1 : 0, covered[face][(y * 16 + z) * 16 + x], "face " + face + " of " + x + "," + y + "," + z);
					}
				}
			}
		}
	}

	/** Marks every voxel face a quad covers, checking the texel it shows there. */
	private static void rasterise(VoxelGrid grid, ShapeQuad quad, int bx, int by, int bz, int[][] covered) {
		int face = quad.face();
		int minX = 16;
		int minY = 16;
		int minZ = 16;
		int maxX = 0;
		int maxY = 0;
		int maxZ = 0;
		for (int c = 0; c < 4; c++) {
			minX = Math.min(minX, quad.x(c));
			minY = Math.min(minY, quad.y(c));
			minZ = Math.min(minZ, quad.z(c));
			maxX = Math.max(maxX, quad.x(c));
			maxY = Math.max(maxY, quad.y(c));
			maxZ = Math.max(maxZ, quad.z(c));
			assertTrue(quad.x(c) >= 0 && quad.x(c) <= 16 && quad.y(c) >= 0 && quad.y(c) <= 16 && quad.z(c) >= 0 && quad.z(c) <= 16, "inside its block");
		}
		// In voxels (2 units each), block-local; the face's plane is one of the bounds.
		for (int vy = minY / 2; vy < Math.max(minY / 2 + 1, maxY / 2); vy++) {
			for (int vz = minZ / 2; vz < Math.max(minZ / 2 + 1, maxZ / 2); vz++) {
				for (int vx = minX / 2; vx < Math.max(minX / 2 + 1, maxX / 2); vx++) {
					int x = bx * 8 + vx;
					int y = by * 8 + vy;
					int z = bz * 8 + vz;
					// The voxel behind the face: below an up face's plane, north of a south face's, west of an east face's.
					switch (face) {
						case ShapeQuad.UP -> y = by * 8 + maxY / 2 - 1;
						case ShapeQuad.DOWN -> y = by * 8 + minY / 2;
						case ShapeQuad.SOUTH -> z = bz * 8 + maxZ / 2 - 1;
						case ShapeQuad.NORTH -> z = bz * 8 + minZ / 2;
						case ShapeQuad.EAST -> x = bx * 8 + maxX / 2 - 1;
						default -> x = bx * 8 + minX / 2;
					}
					covered[face][(y * 16 + z) * 16 + x]++;
					int expected = face == ShapeQuad.UP ? grid.cap(x, y, z) : grid.at(x, y, z);
					assertEquals(expected, shownAt(quad, face, vx, vy, vz, minX, minY, minZ, maxX, maxY, maxZ), "the texel at " + x + "," + y + "," + z);
				}
			}
		}
	}

	/** The texel the quad shows at the centre of a voxel face: its corners' UVs interpolated. */
	private static int shownAt(ShapeQuad quad, int face, int vx, int vy, int vz, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		float u = 0;
		float v = 0;
		for (int c = 0; c < 4; c++) {
			float wa;
			float wb;
			switch (face) {
				case ShapeQuad.UP, ShapeQuad.DOWN -> {
					wa = weight(vx, minX, maxX, quad.x(c));
					wb = weight(vz, minZ, maxZ, quad.z(c));
				}
				case ShapeQuad.NORTH, ShapeQuad.SOUTH -> {
					wa = weight(vx, minX, maxX, quad.x(c));
					wb = weight(vy, minY, maxY, quad.y(c));
				}
				default -> {
					wa = weight(vz, minZ, maxZ, quad.z(c));
					wb = weight(vy, minY, maxY, quad.y(c));
				}
			}
			u += wa * wb * quad.cornerU(c);
			v += wa * wb * quad.cornerV(c);
		}
		return VoxelGrid.texel(quad.texture(), (int) Math.floor(u / 2), (int) Math.floor(v / 2));
	}

	/** The bilinear weight of a corner at {@code corner} for the centre of voxel {@code cell} along an axis [min, max]. */
	private static float weight(int cell, int min, int max, int corner) {
		float f = (cell * 2 + 1 - min) / (float) (max - min);
		return corner == max ? f : 1 - f;
	}
}
