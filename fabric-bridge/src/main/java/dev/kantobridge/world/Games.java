package dev.kantobridge.world;

import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * The games an export can come from, and which can draw each other's worlds (docs/specs/VOXEL_BLOCKS_PLAN.md §5.2): a
 * client draws a world with the art of its own export when both games share their tiles, i.e. belong to one family.
 * The one place that names the games; everything else asks it.
 */
public final class Games {
	/** Game → family. Red and Blue share every tile, palette name and sprite. */
	private static final Map<String, String> FAMILY = Map.of("red", "gen1", "blue", "gen1");

	private Games() {
	}

	/** The family of a game, or null for a game this mod doesn't know. */
	public static @Nullable String family(@Nullable String game) {
		return game == null ? null : FAMILY.get(game);
	}

	/** True when art of {@code art}'s export can draw a world built from {@code world}'s: the same game, or one family. */
	public static boolean compatible(@Nullable String art, @Nullable String world) {
		if (art == null || world == null) {
			return false;
		}
		String family = family(art);
		return art.equals(world) || (family != null && family.equals(family(world)));
	}
}
