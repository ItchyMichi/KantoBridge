package dev.kantobridge.builder.shape;

import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.world.Scale;

/**
 * How high a map's shapes may stand, in blocks from feet level (docs/specs/PROFILE_PLAN.md §5.6): the surface's volume
 * outdoors; an interior's ceiling, which a column stops under; an open-air interior (a forest under the sky, V7) one
 * block more, up to the ceiling's own level, which nothing fills there. Dramaless Shape draws its rooms without a
 * ceiling; the clamp is ours.
 */
public final class ShapeHeights {
	/** Blocks above feet level the surface volume holds (feet is one above the ground level). */
	public static final int SURFACE = WorldPlan.SURFACE_MAX_Y - Scale.GROUND_Y - 1;

	private ShapeHeights() {
	}

	/** Blocks a column of {@code map} may hold from feet level. */
	public static int headroom(SceneMap map) {
		if (map.surface()) {
			return SURFACE;
		}
		return map.env().openAir() ? WorldPlan.CEILING_ABOVE_FEET + 1 : WorldPlan.CEILING_ABOVE_FEET;
	}

	/** The same in voxels (Game Boy pixels; eight to a block). */
	public static int headroomPx(SceneMap map) {
		return 8 * headroom(map);
	}
}
