package dev.kantobridge.scene;

import org.jetbrains.annotations.Nullable;

/**
 * The pixels of a game's art, as shades (docs/specs/VOXEL_BLOCKS_PLAN.md §5.3, §5.5): 0 the lightest .. 3 the darkest,
 * -1 transparent. Only geometry reads it (on each client, or the headless build for counts and scores); the planner
 * never does, so no pixel influences a shape key (I8). Analysis works on shades, never colours, so its results are the
 * same in every palette.
 */
public interface ArtSource {
	/** An art source with no pixels: every tile is unknown. */
	ArtSource NONE = new ArtSource() {
		@Override
		public int @Nullable [] tileShades(String atlas, int tile) {
			return null;
		}

		@Override
		public int artPx() {
			return 8;
		}
	};

	/** The shades of a tile, {@code artPx × artPx} row-major; null when the atlas or the tile is unknown. */
	int @Nullable [] tileShades(String atlas, int tile);

	/** Pixels per art tile edge. */
	int artPx();

	/**
	 * The shades of each frame an animated tile cycles through (its {@code frames} entries' images), in order; empty for a
	 * tile that doesn't cycle or when they are unknown. A flower's tuft is the union of its frames' strokes.
	 */
	default java.util.List<int[]> frameShades(String atlas, int tile) {
		return java.util.List.of();
	}
}
