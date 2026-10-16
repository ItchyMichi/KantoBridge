package dev.kantobridge.builder.shape.fit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.kantobridge.builder.shape.geom.VoxelGrid;
import org.junit.jupiter.api.Test;

/**
 * FitTool's plumbing (docs/specs/FITTED_SHAPES.md): voxel bits survive their base64 round trip in the scripts' order, and
 * the IoU compares shapes whatever their depth in the cell (a slab at the south edge matches the same slab centred).
 */
class FitToolTest {
	private static final int W = FitTool.W;

	private static boolean[] slab(int z0, int depth) {
		boolean[] out = new boolean[W * W * W];
		for (int y = 0; y < W; y++) {
			for (int z = z0; z < z0 + depth; z++) {
				for (int x = 2; x < 14; x++) {
					out[(y * W + z) * W + x] = true;
				}
			}
		}
		return out;
	}

	private static VoxelGrid grid(boolean[] voxels) {
		VoxelGrid grid = new VoxelGrid(W, W, W, 8);
		for (int y = 0; y < W; y++) {
			for (int z = 0; z < W; z++) {
				for (int x = 0; x < W; x++) {
					if (voxels[(y * W + z) * W + x]) {
						grid.set(x, y, z, 0);
					}
				}
			}
		}
		return grid;
	}

	@Test
	void bitsRoundTripInTheScriptsOrder() {
		boolean[] voxels = slab(3, 5);
		assertArrayEquals(voxels, FitTool.unbits(FitTool.bits(voxels), voxels.length));
		assertEquals(FitTool.bits(voxels), FitTool.bits(grid(voxels)));
	}

	@Test
	void theIouComparesShapesAtAnyDepth() {
		assertEquals(1.0, FitTool.iou(slab(7, 2), grid(slab(14, 2))), 1e-9, "the same slab, centred or at the south edge");
		assertEquals(0.5, FitTool.iou(slab(6, 4), grid(slab(14, 2))), 1e-9, "half as deep");
		assertEquals(0.0, FitTool.iou(slab(6, 4), null));
	}
}
