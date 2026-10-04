package dev.kantobridge.builder.shape;

import dev.kantobridge.builder.shape.geom.CellUnits;

/**
 * The one question the planner asks of pixels (docs/specs/PROFILE_PLAN.md §3, PD3): would a repeating cell's drawing,
 * as a unit in this context, stand as a hull or an object on the builder's own art? The answer goes into the plan as a
 * unit (its key); each client then builds that unit from its own art, the same way. A null probe asks nothing.
 */
@FunctionalInterface
public interface UnitProbe {
	/** True when a unit of {@code tiles} (row-major) on {@code atlas}, in {@code context}, is a hull or an object. */
	boolean shaped(String atlas, int[] tiles, CellUnits.Context context);
}
