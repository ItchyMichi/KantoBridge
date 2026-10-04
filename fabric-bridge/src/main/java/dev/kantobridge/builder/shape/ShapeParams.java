package dev.kantobridge.builder.shape;

import com.google.gson.JsonObject;
import dev.kantobridge.builder.plan.HeightRules;
import java.util.Objects;

/**
 * The planner's version and parameters (docs/specs/VOXEL_BLOCKS_PLAN.md §2.2, §4.2). Both are written into the shape
 * index, so they are inside its hash: changing any of them is a new planner version, and a world keeps the version it was
 * built with.
 *
 * @param planner       {@code kb-shapes/<n>}
 * @param heights       {@code classified} (today's 2 or 4 blocks; V2) or {@code measured} (1..6 blocks; from V3)
 * @param maxUnit       the tallest measured unit, in tile rows
 * @param minRepeatRows the shortest repeat that splits a run, in tile rows
 * @param hullRing      void-fill cells within this many cells of a map body are planned like map cells (from V4)
 * @param interiorWall  interior wall height in blocks
 * @param tierB         cell-sized units (from V4)
 * @param tierC         whole-structure templates (from V7)
 */
public record ShapeParams(String planner, String heights, int maxUnit, int minRepeatRows, int hullRing, int interiorWall, boolean tierB,
		boolean tierC) {
	public static final String CLASSIFIED = "classified";
	public static final String MEASURED = "measured";

	public ShapeParams {
		Objects.requireNonNull(planner, "planner");
		if (!CLASSIFIED.equals(heights) && !MEASURED.equals(heights)) {
			throw new IllegalArgumentException("unknown heights mode");
		}
	}

	/** V2: shape data only. Today's classified heights and cube keys, so no block changes. */
	public static ShapeParams v2() {
		return new ShapeParams("kb-shapes/1", CLASSIFIED, HeightRules.MAX_UNIT, HeightRules.MIN_REPEAT_ROWS, 2, HeightRules.INTERIOR_WALL,
				false, false);
	}

	/** The parameters as they appear in the shape index. */
	public JsonObject toJson() {
		JsonObject out = new JsonObject();
		out.addProperty("heights", this.heights);
		out.addProperty("maxUnit", this.maxUnit);
		out.addProperty("minRepeatRows", this.minRepeatRows);
		out.addProperty("hullRing", this.hullRing);
		out.addProperty("interiorWall", this.interiorWall);
		out.addProperty("tierB", this.tierB);
		out.addProperty("tierC", this.tierC);
		return out;
	}

	public boolean measured() {
		return MEASURED.equals(this.heights);
	}
}
