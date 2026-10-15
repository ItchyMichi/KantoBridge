package dev.kantobridge.scene;

import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.scene.gen1.BundleArtSource;
import dev.kantobridge.scene.gen1.Gen1FrontEnd;
import dev.kantobridge.scene.gen2.Gen2FrontEnd;
import dev.kantobridge.scene.gen3.G3ArtSource;
import dev.kantobridge.scene.gen3.Gen3FrontEnd;
import dev.kantobridge.world.Games;
import org.jetbrains.annotations.Nullable;

/** Which front end reads which game's export (docs/specs/VOXEL_BLOCKS_PLAN.md §5.3). */
public final class FrontEnds {
	private FrontEnds() {
	}

	/** The front end for a game, or null for a game no front end reads. */
	public static @Nullable GameFrontEnd forGame(String game) {
		String family = Games.family(game);
		if (Gen1FrontEnd.INSTANCE.family().equals(family)) {
			return Gen1FrontEnd.INSTANCE;
		}
		if (Gen3FrontEnd.INSTANCE.family().equals(family)) {
			return Gen3FrontEnd.INSTANCE;
		}
		if (Gen2FrontEnd.INSTANCE.family().equals(family)) {
			return Gen2FrontEnd.INSTANCE;
		}
		return null;
	}

	/**
	 * The pixels of an export as shades, as its family keeps its art: a Gen 3 export's quarter pages (MG7) or (MG9) a Gen
	 * 2 export's baked pages, which the same reader serves; else Gen 1's tileset atlases.
	 */
	public static ArtSource artSource(ExportBundle bundle) throws BundleException {
		return bundle.isGen3() || bundle.isGen2() ? G3ArtSource.of(bundle) : BundleArtSource.of(bundle);
	}
}
