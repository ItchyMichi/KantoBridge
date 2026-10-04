package dev.kantobridge.scene;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * One atlas of art tiles (docs/specs/VOXEL_BLOCKS_PLAN.md §5.3): its size, its animated tiles, and what the front end
 * knows about single tiles. Keys refer to tiles by their index in an atlas.
 *
 * @param id          the atlas image id (the export's image resource)
 * @param tilesPerRow tiles per row of the image
 * @param tiles       tiles in the image
 * @param artPx       pixels per art tile edge
 * @param animated    animated tiles as data (water, flowers, spinner arrows)
 * @param grassTiles  tiles the game treats as tall grass on a walkable cell
 * @param hints       per tile, a class the front end knows (e.g. {@code tree} for the tiles of a cut tree)
 */
public record SceneAtlas(String id, int tilesPerRow, int tiles, int artPx, List<Animated> animated, Set<Integer> grassTiles,
		Map<Integer, String> hints) {
	/** The hint of a tile that draws foliage (a cuttable tree, the tree wall around outdoor maps). */
	public static final String HINT_FOLIAGE = "tree";

	public SceneAtlas {
		animated = List.copyOf(animated);
		grassTiles = Collections.unmodifiableSet(new TreeSet<>(grassTiles));
		hints = Collections.unmodifiableMap(new TreeMap<>(hints));
	}

	/** Tiles that cycle through frames (flowers): their class on a walkable cell is {@code flower}. */
	public Set<Integer> frameTiles() {
		Set<Integer> out = new TreeSet<>();
		for (Animated entry : this.animated) {
			if (Animated.FRAMES.equals(entry.kind())) {
				for (int tile : entry.tiles()) {
					out.add(tile);
				}
			}
		}
		return out;
	}
}
