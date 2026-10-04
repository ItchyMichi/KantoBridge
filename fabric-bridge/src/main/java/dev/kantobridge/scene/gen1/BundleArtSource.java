package dev.kantobridge.scene.gen1;

import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportTileset;
import dev.kantobridge.builder.pack.Png;
import dev.kantobridge.builder.pack.Textures;
import dev.kantobridge.scene.ArtSource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

/**
 * The pixels of a Gen 1 export bundle as shades (docs/specs/VOXEL_BLOCKS_PLAN.md §5.5): the atlases and the animation
 * frames its tilesets name, decoded on first use. Each client reads its own export with it; the headless build reads the
 * bundle it builds from, for counts and scores only. Nothing here is written or logged.
 */
public final class BundleArtSource implements ArtSource {
	private final ExportBundle bundle;
	private final Map<String, Integer> tilesPerRow;
	private final Map<String, List<ExportTileset.Animated>> animated;
	private final Map<String, Optional<Png.Image>> images = new ConcurrentHashMap<>();

	private BundleArtSource(ExportBundle bundle, Map<String, Integer> tilesPerRow, Map<String, List<ExportTileset.Animated>> animated) {
		this.bundle = bundle;
		this.tilesPerRow = tilesPerRow;
		this.animated = animated;
	}

	/** Reads the bundle's tilesets (their atlases, tiles per row and animated tiles). */
	public static BundleArtSource of(ExportBundle bundle) throws BundleException {
		Map<String, Integer> perRow = new HashMap<>();
		Map<String, List<ExportTileset.Animated>> animated = new HashMap<>();
		for (String id : bundle.ids("tileset")) {
			ExportTileset tileset = bundle.tileset(id);
			perRow.putIfAbsent(tileset.image(), tileset.tilesPerRow());
			animated.computeIfAbsent(tileset.image(), k -> new ArrayList<>()).addAll(tileset.animated());
		}
		return new BundleArtSource(bundle, perRow, animated);
	}

	@Override
	public int @Nullable [] tileShades(String atlas, int tile) {
		Integer perRow = this.tilesPerRow.get(atlas);
		Png.Image image = this.image(atlas);
		if (perRow == null || image == null || !Textures.inAtlas(image, perRow, tile)) {
			return null;
		}
		return Textures.tileShades(image, perRow, tile);
	}

	@Override
	public List<int[]> frameShades(String atlas, int tile) {
		List<int[]> out = new ArrayList<>();
		for (ExportTileset.Animated entry : this.animated.getOrDefault(atlas, List.of())) {
			if (!"frames".equals(entry.kind()) || !covers(entry.tiles(), tile)) {
				continue;
			}
			for (String id : entry.images()) {
				Png.Image frame = this.image(id);
				if (frame != null && frame.width() >= 8 && frame.height() >= 8) {
					out.add(Textures.tileShades(frame, Math.max(1, frame.width() / 8), 0));
				}
			}
		}
		return out;
	}

	@Override
	public int artPx() {
		return 8;
	}

	private static boolean covers(int[] tiles, int tile) {
		for (int t : tiles) {
			if (t == tile) {
				return true;
			}
		}
		return false;
	}

	private @Nullable Png.Image image(String id) {
		return this.images.computeIfAbsent(id, key -> {
			try {
				return this.bundle.has("image", key) ? Optional.of(Png.decode(this.bundle.image(key).png())) : Optional.empty();
			} catch (BundleException | IOException e) {
				return Optional.empty();
			}
		}).orElse(null);
	}
}
