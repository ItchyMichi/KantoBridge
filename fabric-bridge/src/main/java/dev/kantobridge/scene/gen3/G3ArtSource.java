package dev.kantobridge.scene.gen3;

import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.pack.BakedTextures;
import dev.kantobridge.builder.pack.Png;
import dev.kantobridge.scene.ArtSource;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

/**
 * The pixels of a Gen 3 export bundle as shades (MG7, docs/specs/GEN3_BRIDGE_PLAN.md §6; the voxel plan's §5.5): its
 * quarter pages ({@code g3q.<n>}), decoded on first use. It answers for the whole art's virtual atlas
 * ({@link QuarterPages#VIRTUAL}, tiles are quarter ids) and for a page atlas (tiles are indices in the page). Each client
 * reads its own export with it; the headless build reads the bundle it builds from, for counts and scores only. Nothing
 * here is written or logged.
 */
public final class G3ArtSource implements ArtSource {
	private final ExportBundle bundle;
	private final Map<String, Optional<Png.Image>> pages = new ConcurrentHashMap<>();

	private G3ArtSource(ExportBundle bundle) {
		this.bundle = bundle;
	}

	public static G3ArtSource of(ExportBundle bundle) {
		return new G3ArtSource(bundle);
	}

	@Override
	public int @Nullable [] tileShades(String atlas, int tile) {
		int[] argb = this.tileArgb(atlas, tile);
		return argb == null ? null : BakedTextures.shades(argb);
	}

	/** A quarter's {@code 0xAARRGGBB} pixels, 8×8 row-major; null when the atlas or the quarter is unknown. */
	public int @Nullable [] tileArgb(String atlas, int tile) {
		if (tile < 0) {
			return null;
		}
		String page = QuarterPages.atlasOf(atlas, tile);
		if (QuarterPages.pageNumber(page) < 0) {
			return null;
		}
		Png.Image image = this.page(page);
		return image == null ? null : BakedTextures.quarter(image, QuarterPages.PER_ROW, QuarterPages.tileOf(atlas, tile));
	}

	@Override
	public int artPx() {
		return 8;
	}

	private @Nullable Png.Image page(String id) {
		return this.pages.computeIfAbsent(id, key -> {
			if (!this.bundle.has("g3image", key)) {
				return Optional.empty();
			}
			try {
				return Optional.of(Png.decode(this.bundle.g3image(key).png()));
			} catch (IOException | BundleException e) {
				return Optional.empty();
			}
		}).orElse(null);
	}
}
