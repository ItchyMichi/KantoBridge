package dev.kantobridge.scene;

import java.util.Objects;

/**
 * A map's surroundings (docs/specs/VOXEL_BLOCKS_PLAN.md §5.3): drawn outdoors, open to the sky (outdoors, or an
 * interior that is really outside: a forest), a cave, how dark it is, and whether a leaf canopy roofs it (the caves and
 * woods plan's CW2: a wood whose trees close overhead).
 *
 * @param dark   {@code never}, {@code flash} (dark until lit) or {@code always}
 * @param canopy a wood under a leaf canopy (docs/specs/CAVES_AND_WOODS_PLAN.md §5.2), as the front end knows its game
 */
public record Env(boolean outdoor, boolean openAir, boolean cave, String dark, boolean canopy) {
	public static final String DARK_NEVER = "never";
	public static final String DARK_FLASH = "flash";

	public Env {
		Objects.requireNonNull(dark, "dark");
	}

	/** Surroundings without a canopy. */
	public Env(boolean outdoor, boolean openAir, boolean cave, String dark) {
		this(outdoor, openAir, cave, dark, false);
	}
}
