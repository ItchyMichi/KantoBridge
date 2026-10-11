package dev.kantobridge.builder.zone;

/**
 * The land's height around a zone (docs/specs/WILD_ROUTES_PLAN.md §4.4): Minecraft's own overworld noise for the
 * world's seed in a real build ({@link VanillaTerrain}), something simple in tests. Never asked for the maze's own
 * cells, which are flat at the zone's floor.
 */
@FunctionalInterface
public interface TerrainSampler {
	/** The block Y of the top solid block at column (x, z). */
	int height(int x, int z);

	/** The same height everywhere. */
	static TerrainSampler flat(int y) {
		return (x, z) -> y;
	}
}
