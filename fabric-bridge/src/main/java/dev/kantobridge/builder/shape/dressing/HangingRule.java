package dev.kantobridge.builder.shape.dressing;

import dev.kantobridge.scene.SceneMap;

/**
 * One hanging rule (docs/specs/CAVES_AND_WOODS_PLAN.md §3): it runs last in the planner, over the final columns, so it
 * is deterministic and inside the shape index's hash. It only adds decoration in levels a column leaves free
 * ({@link Dress#free}); it never touches a column, a walkable cell's collision or any collision at all.
 */
public interface HangingRule {
	/** Whether the rule dresses this map. */
	boolean applies(SceneMap map);

	/** Adds the map's hangings. */
	void hang(SceneMap map, Dress dress);
}
