package dev.kantobridge.builder.shape.dressing;

import dev.kantobridge.scene.SceneMap;

/**
 * One dressing rule (docs/specs/VOXEL_BLOCKS_PLAN.md §5.12): it runs after the shapes, in the planner, so it is
 * deterministic and inside the shape index's hash. It never touches a walkable cell or the collision of one.
 */
public interface DressingRule {
	/** Whether the rule dresses this map. */
	boolean applies(SceneMap map);

	/** Dresses the map's columns. */
	void dress(SceneMap map, Dress dress);
}
