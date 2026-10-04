package dev.kantobridge.scene;

import dev.kantobridge.scene.gen1.Gen1FrontEnd;
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
		return null;
	}
}
