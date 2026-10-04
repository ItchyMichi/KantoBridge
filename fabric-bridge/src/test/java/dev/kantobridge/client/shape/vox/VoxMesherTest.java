package dev.kantobridge.client.shape.vox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.shape.geom.QuadMerger;
import dev.kantobridge.builder.shape.geom.ShapeQuad;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * From {@code .vox} to quads (docs/specs/VOXEL_BLOCKS_PLAN.md §5.10): vox z is up, vox x east, vox y north, so the
 * model's front (its low vox y) faces south; {@code turn} is clockwise seen from above, {@code flip} mirrors east-west
 * first; 8 or 16 voxels per block, 32 halved; the origin moves it; palette index 0 is empty; one colour merges.
 */
class VoxMesherTest {
	/** An asymmetric 8³ model, one voxel in four corners: 1 at the origin, 2 east, 3 north (vox y), 4 up (vox z). */
	static VoxFile.Model marker() {
		byte[] voxels = {0, 0, 0, 1, 7, 0, 0, 2, 0, 7, 0, 3, 0, 0, 7, 4};
		return new VoxFile.Model(8, 8, 8, voxels, new int[256], 1);
	}

	/** The box (in 1/16 block, x y z min then max) of a colour's quads in one block. */
	static int[] bounds(VoxMesher.Mesh mesh, int block, int colour) {
		int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		for (ShapeQuad quad : mesh.blocks().getOrDefault(block, List.of())) {
			if (quad.texture() != colour) {
				continue;
			}
			for (int corner = 0; corner < 4; corner++) {
				int[] p = {quad.x(corner), quad.y(corner), quad.z(corner)};
				for (int a = 0; a < 3; a++) {
					box[a] = Math.min(box[a], p[a]);
					box[a + 3] = Math.max(box[a + 3], p[a]);
				}
			}
		}
		return box;
	}

	static final int ONE = QuadMerger.blockKey(0, 0, 0);

	@Test
	void voxZIsUpVoxXEastVoxYNorth() {
		VoxMesher.Mesh mesh = VoxMesher.mesh(marker(), 8, 0, false);
		assertEquals(1, mesh.bw());
		assertEquals(1, mesh.bh());
		assertEquals(1, mesh.bd());
		// One voxel is 2/16 block.
		assertBox(new int[] {0, 0, 14, 2, 2, 16}, bounds(mesh, ONE, 1), "the origin voxel: south-west, at the bottom");
		assertBox(new int[] {14, 0, 14, 16, 2, 16}, bounds(mesh, ONE, 2), "vox x is east");
		assertBox(new int[] {0, 0, 0, 2, 2, 2}, bounds(mesh, ONE, 3), "vox y is north");
		assertBox(new int[] {0, 14, 14, 2, 16, 16}, bounds(mesh, ONE, 4), "vox z is up");
	}

	@Test
	void turnIsClockwiseFromAboveAndFlipMirrorsEastWest() {
		VoxMesher.Mesh turned = VoxMesher.mesh(marker(), 8, 90, false);
		assertBox(new int[] {0, 0, 14, 2, 2, 16}, bounds(turned, ONE, 2), "south-east turns to south-west");
		assertBox(new int[] {14, 0, 0, 16, 2, 2}, bounds(turned, ONE, 3), "north-west turns to north-east");
		VoxMesher.Mesh half = VoxMesher.mesh(marker(), 8, 180, false);
		assertBox(new int[] {0, 0, 0, 2, 2, 2}, bounds(half, ONE, 2), "south-east turns to north-west");
		VoxMesher.Mesh back = VoxMesher.mesh(marker(), 8, 270, false);
		assertBox(new int[] {14, 0, 0, 16, 2, 2}, bounds(back, ONE, 2), "south-east turns to north-east");
		VoxMesher.Mesh flipped = VoxMesher.mesh(marker(), 8, 0, true);
		assertBox(new int[] {0, 0, 14, 2, 2, 16}, bounds(flipped, ONE, 2), "east becomes west");
		assertBox(new int[] {14, 0, 14, 16, 2, 16}, bounds(flipped, ONE, 1), "west becomes east");
		assertThrows(IllegalArgumentException.class, () -> VoxMesher.mesh(marker(), 8, 45, false));
		assertThrows(IllegalArgumentException.class, () -> VoxMesher.mesh(marker(), 12, 0, false));
	}

	@Test
	void scalesAndTheOrigin() {
		VoxMesher.Mesh fine = VoxMesher.mesh(marker(), 16, 0, false);
		assertBox(new int[] {7, 0, 7, 8, 1, 8}, bounds(fine, ONE, 2), "16 per block: a voxel is 1/16");
		VoxMesher.Mesh solid = VoxMesher.mesh(box(32, 32, 32, 1), 32, 0, false);
		assertEquals(1, solid.bw(), "32 per block is halved to 16");
		assertEquals(6, solid.blocks().get(ONE).size(), "one solid colour: its six faces, merged");
		VoxMesher.Mesh moved = VoxMesher.mesh(box(8, 8, 8, 1), 8, 0, false, new int[] {8, 0, 0});
		assertEquals(2, moved.bw());
		assertTrue(!moved.blocks().containsKey(ONE), "nothing left in the first block");
		assertEquals(6, moved.blocks().get(QuadMerger.blockKey(1, 0, 0)).size());
		assertThrows(IllegalArgumentException.class, () -> VoxMesher.mesh(box(8, 8, 8, 1), 8, 0, false, new int[] {-1, 0, 0}));
		VoxMesher.Mesh tall = VoxMesher.mesh(box(8, 8, 12, 1), 8, 0, false);
		assertEquals(2, tall.bh(), "12 voxels up: a block and a half");
	}

	@Test
	void indexZeroIsEmpty() {
		byte[] voxels = {0, 0, 0, 0, 1, 0, 0, 0};
		VoxMesher.Mesh mesh = VoxMesher.mesh(new VoxFile.Model(2, 1, 1, voxels, new int[256], 1), 8, 0, false);
		assertTrue(mesh.blocks().isEmpty());
		assertEquals(0, mesh.maxQuads());
	}

	@Test
	void halvingKeepsAGroupHalfFilledInItsMostFrequentColour() {
		List<Byte> voxels = new ArrayList<>();
		// A 2×2×2 group: three of colour 5, one of colour 3; the rest of the 4³ model empty.
		int[][] cells = {{0, 0, 0, 5}, {1, 0, 0, 5}, {0, 1, 0, 5}, {1, 1, 0, 3}, {2, 2, 2, 7}};
		for (int[] c : cells) {
			for (int v : c) {
				voxels.add((byte) v);
			}
		}
		byte[] bytes = new byte[voxels.size()];
		for (int i = 0; i < bytes.length; i++) {
			bytes[i] = voxels.get(i);
		}
		VoxMesher.Mesh mesh = VoxMesher.mesh(new VoxFile.Model(4, 4, 4, bytes, new int[256], 1), 32, 0, false);
		List<ShapeQuad> quads = mesh.blocks().get(ONE);
		assertTrue(quads.stream().allMatch(q -> q.texture() == 5), "four of eight filled, mostly 5; the lone 7 is dropped");
		assertEquals(6, quads.size());
	}

	static VoxFile.Model box(int sx, int sy, int sz, int colour) {
		byte[] voxels = new byte[sx * sy * sz * 4];
		int i = 0;
		for (int z = 0; z < sz; z++) {
			for (int y = 0; y < sy; y++) {
				for (int x = 0; x < sx; x++) {
					voxels[i++] = (byte) x;
					voxels[i++] = (byte) y;
					voxels[i++] = (byte) z;
					voxels[i++] = (byte) colour;
				}
			}
		}
		return new VoxFile.Model(sx, sy, sz, voxels, new int[256], 1);
	}

	private static void assertBox(int[] expected, int[] actual, String what) {
		assertEquals(java.util.Arrays.toString(expected), java.util.Arrays.toString(actual), what);
	}
}
