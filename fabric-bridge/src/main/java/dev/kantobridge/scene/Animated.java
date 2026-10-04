package dev.kantobridge.scene;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * One animated-tile entry of an atlas (docs/specs/VOXEL_BLOCKS_PLAN.md §4.6, the export's {@code animated} list):
 *
 * <ul>
 * <li>{@code hshift}: the tile's pixels shift sideways by {@code offsets} (one per step);</li>
 * <li>{@code frames}: the tile cycles through {@code images} in the order of {@code sequence} (1-based);</li>
 * <li>{@code toggle}: the tiles switch to their slice of the strip {@code image} ({@code stripOffsets}, one per tile)
 * while {@code gate} holds.</li>
 * </ul>
 *
 * @param period steps between frames (in the engine's ticks), 0 when not given
 */
public record Animated(int[] tiles, String kind, int period, int[] offsets, List<String> images, int[] sequence, @Nullable String image,
		int[] stripOffsets, @Nullable String gate) {
	public static final String HSHIFT = "hshift";
	public static final String FRAMES = "frames";
	public static final String TOGGLE = "toggle";

	public Animated {
		tiles = tiles.clone();
		offsets = offsets.clone();
		images = List.copyOf(images);
		sequence = sequence.clone();
		stripOffsets = stripOffsets.clone();
	}

	public boolean covers(int tile) {
		for (int t : this.tiles) {
			if (t == tile) {
				return true;
			}
		}
		return false;
	}
}
