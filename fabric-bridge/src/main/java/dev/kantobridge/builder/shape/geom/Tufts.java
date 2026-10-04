package dev.kantobridge.builder.shape.geom;

import java.util.List;
import java.util.Map;

/**
 * Tufts (docs/specs/VOXEL_BLOCKS_PLAN.md §5.5, Tier B): tall grass and flowers stand up from their tile. The stroke
 * pixels (every shade but the lightest; a flower's: the union over its animation frames, so every frame's strokes have
 * somewhere to show) stand as a cut-out one pixel thick at the tile's south edge, each pixel row at its own height with
 * the bottom row on the ground. In the game's view the cut-out covers exactly the strokes of the flat drawing, and the
 * southern row of a grass cell hides a player's feet. Front and back faces only, without ambient occlusion (a thin
 * card would darken its own neighbours); the ground is the block below.
 */
public final class Tufts {
	private Tufts() {
	}

	/** A stroke: any shade but the lightest (and not transparent). */
	public static boolean stroke(int shade) {
		return shade > 0;
	}

	/** The cut-out's pixels: the tile's strokes, or the union of its animation frames' strokes when it has frames. */
	public static boolean[] mask(int[] shades, List<int[]> frames) {
		boolean[] mask = new boolean[shades.length];
		if (frames.isEmpty()) {
			for (int i = 0; i < shades.length; i++) {
				mask[i] = stroke(shades[i]);
			}
			return mask;
		}
		for (int[] frame : frames) {
			for (int i = 0; i < Math.min(mask.length, frame.length); i++) {
				mask[i] |= stroke(frame[i]);
			}
		}
		return mask;
	}

	/** The tuft of one tile (texture index 0): its quads in one block, none when the tile has no stroke. */
	public static List<ShapeQuad> quads(boolean[] mask, int artPx) {
		VoxelGrid grid = new VoxelGrid(artPx, artPx, artPx, artPx);
		for (int py = 0; py < artPx; py++) {
			for (int px = 0; px < artPx; px++) {
				if (mask[py * artPx + px]) {
					grid.set(px, artPx - 1 - py, artPx - 1, VoxelGrid.texel(0, px, py));
				}
			}
		}
		Map<Integer, List<ShapeQuad>> merged = QuadMerger.merge(grid, QuadMerger.FRONT_AND_BACK, ShapeQuad.NO_AO);
		return merged.getOrDefault(QuadMerger.blockKey(0, 0, 0), List.of());
	}
}
