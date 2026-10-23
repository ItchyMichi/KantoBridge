package dev.kantobridge.scene;

import java.util.Objects;

/**
 * A map's surroundings (docs/specs/VOXEL_BLOCKS_PLAN.md §5.3): drawn outdoors, open to the sky (outdoors, or an
 * interior that is really outside: a forest), a cave, how dark it is, and whether a leaf canopy roofs it (the caves and
 * woods plan's CW2: a wood whose trees close overhead).
 *
 * @param dark     {@code never}, {@code flash} (dark until lit) or {@code always}
 * @param canopy   a wood under a leaf canopy (docs/specs/CAVES_AND_WOODS_PLAN.md §5.2), as the front end knows its game
 * @param wood     a wood, as the front end knows its game: drawn by the indoor rules even where the game draws it outdoors
 *                 (Emerald's Petalburg Woods, a route by its map type; 2026-10-23, {@link SceneMap#outdoors()})
 * @param headroom blocks a column may stand over its floor under the map's ceiling as built
 *                 (docs/specs/HEIGHTS_ALL_GAMES_PLAN.md D5), which the shape planner sets from the map's layout; -1 for the
 *                 default. Not the front end's: the scene's JSON leaves it out.
 */
public record Env(boolean outdoor, boolean openAir, boolean cave, String dark, boolean canopy, boolean wood, int headroom) {
	public static final String DARK_NEVER = "never";
	public static final String DARK_FLASH = "flash";

	public Env {
		Objects.requireNonNull(dark, "dark");
	}

	/** Surroundings as the front end describes them: the default headroom. */
	public Env(boolean outdoor, boolean openAir, boolean cave, String dark, boolean canopy) {
		this(outdoor, openAir, cave, dark, canopy, false, -1);
	}

	/** Surroundings without a canopy. */
	public Env(boolean outdoor, boolean openAir, boolean cave, String dark) {
		this(outdoor, openAir, cave, dark, false);
	}

	/** The same with the map's headroom as built. */
	public Env withHeadroom(int blocks) {
		return new Env(this.outdoor, this.openAir, this.cave, this.dark, this.canopy, this.wood, blocks);
	}
}
