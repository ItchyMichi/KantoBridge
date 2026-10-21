package dev.kantobridge.builder.shape;

import dev.kantobridge.builder.shape.geom.CellUnits;

/**
 * The questions the planner asks of pixels (docs/specs/PROFILE_PLAN.md §3, PD3): would a repeating cell's drawing, as a
 * unit in this context, stand as a hull or an object on the builder's own art? And (docs/specs/HEIGHTS_ALL_GAMES_PLAN.md
 * D8) is a tile drawn as one flat black, the filler round a room that ends its depth? The answers go into the plan; each
 * client then builds from its own art, the same way. A null probe asks nothing.
 */
@FunctionalInterface
public interface UnitProbe {
	/** True when a unit of {@code tiles} (row-major) on {@code atlas}, in {@code context}, is a hull or an object. */
	boolean shaped(String atlas, int[] tiles, CellUnits.Context context);

	/** True when tile {@code tile} of {@code atlas} is one flat black (none, unless the probe sees pixels). */
	default boolean blank(String atlas, int tile) {
		return false;
	}
}
