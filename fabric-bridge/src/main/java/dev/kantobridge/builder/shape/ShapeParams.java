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
 * @param roofRise      the highest a roof rises above its eave, in 1/16 block (measured heights only)
 * @param ledgeLip      the ledge lip's height, in 1/16 block (measured heights only)
 */
public record ShapeParams(String planner, String heights, int maxUnit, int minRepeatRows, int hullRing, int interiorWall, boolean tierB,
		boolean tierC, int roofRise, int ledgeLip) {
	public static final String CLASSIFIED = "classified";
	public static final String MEASURED = "measured";

	public ShapeParams {
		Objects.requireNonNull(planner, "planner");
		if (!CLASSIFIED.equals(heights) && !MEASURED.equals(heights)) {
			throw new IllegalArgumentException("unknown heights mode");
		}
		if (roofRise < 0 || roofRise > 16 || ledgeLip < 1 || ledgeLip > 12) {
			throw new IllegalArgumentException("roof rise or ledge lip out of range");
		}
	}

	/** The V2 parameters with another planner version or setting (tests). */
	public ShapeParams(String planner, String heights, int maxUnit, int minRepeatRows, int hullRing, int interiorWall, boolean tierB,
			boolean tierC) {
		this(planner, heights, maxUnit, minRepeatRows, hullRing, interiorWall, tierB, tierC, 16, 4);
	}

	/** V2: shape data only. Today's classified heights and cube keys, so no block changes. */
	public static ShapeParams v2() {
		return new ShapeParams("kb-shapes/1", CLASSIFIED, HeightRules.MAX_UNIT, HeightRules.MIN_REPEAT_ROWS, 2, HeightRules.INTERIOR_WALL,
				false, false);
	}

	/** V3: Tier A. Measured heights, the door fold, the region vote, roofs and ledge lips. */
	public static ShapeParams v3() {
		return new ShapeParams("kb-shapes/2", MEASURED, HeightRules.MAX_UNIT, HeightRules.MIN_REPEAT_ROWS, 2, HeightRules.INTERIOR_WALL,
				false, false, 16, 4);
	}

	/** V4: Tier B. Tufts, cell units (hulls, objects) and the void fill's ring of border trees. */
	public static ShapeParams v4() {
		return new ShapeParams("kb-shapes/3", MEASURED, HeightRules.MAX_UNIT, HeightRules.MIN_REPEAT_ROWS, 2, HeightRules.INTERIOR_WALL,
				true, false, 16, 4);
	}

	/** The parameters a build plans with. */
	public static ShapeParams current() {
		return v4();
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
		if (this.measured()) {
			// Not in kb-shapes/1's index, whose planner has neither.
			out.addProperty("roofRise", this.roofRise);
			out.addProperty("ledgeLip", this.ledgeLip);
		}
		return out;
	}

	public boolean measured() {
		return MEASURED.equals(this.heights);
	}
}
