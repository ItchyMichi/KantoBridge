package dev.kantobridge.scene;

import java.util.Objects;

/**
 * A map's surroundings (docs/specs/VOXEL_BLOCKS_PLAN.md §5.3): drawn outdoors, open to the sky (outdoors, or an
 * interior that is really outside: a forest), a cave, and how dark it is.
 *
 * @param dark {@code never}, {@code flash} (dark until lit) or {@code always}
 */
public record Env(boolean outdoor, boolean openAir, boolean cave, String dark) {
	public static final String DARK_NEVER = "never";
	public static final String DARK_FLASH = "flash";

	public Env {
		Objects.requireNonNull(dark, "dark");
	}
}
