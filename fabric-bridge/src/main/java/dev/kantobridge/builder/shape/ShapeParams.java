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
 * @param storyArt      the closed art of tile story cells as overlays of the shape map (from V5)
 * @param dressing      interior walls up to the ceiling, ceilings in the room's wall art, open-air interiors (from V7)
 * @param templateMax   the largest structure template, in art tiles along either side (with {@code tierC}; from V7)
 * @param profileUnits  units from the profile's pins, and the probe's repeating cells (docs/specs/PROFILE_PLAN.md §5.4;
 *                      from P1)
 * @param profileBuildings the profile's band-table buildings (docs/specs/PROFILE_PLAN.md §5.5; from P2)
 * @param profileBoxes  the profile's class boxes, furniture parts, hull variants, stairs and reliefs
 *                      (docs/specs/PROFILE_PLAN.md §5.6; from P3)
 * @param caveRock      the caves' sagging roof, stalactites and stalagmites (docs/specs/CAVES_AND_WOODS_PLAN.md §5.1;
 *                      from CW1)
 * @param canopy        Viridian Forest's canopy and vines (docs/specs/CAVES_AND_WOODS_PLAN.md §5.2-§5.3; from CW2)
 */
public record ShapeParams(String planner, String heights, int maxUnit, int minRepeatRows, int hullRing, int interiorWall, boolean tierB,
		boolean tierC, int roofRise, int ledgeLip, boolean storyArt, boolean dressing, int templateMax, boolean profileUnits,
		boolean profileBuildings, boolean profileBoxes, boolean caveRock, boolean canopy) {
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
		if (tierC ? templateMax < 4 || templateMax > 64 : templateMax != 0) {
			throw new IllegalArgumentException("a template size needs Tier C, and lies in 4..64");
		}
		if ((caveRock || canopy) && !dressing) {
			throw new IllegalArgumentException("hangings need dressing");
		}
	}

	/** Parameters before CW1 (no hangings). */
	public ShapeParams(String planner, String heights, int maxUnit, int minRepeatRows, int hullRing, int interiorWall, boolean tierB,
			boolean tierC, int roofRise, int ledgeLip, boolean storyArt, boolean dressing, int templateMax, boolean profileUnits,
			boolean profileBuildings, boolean profileBoxes) {
		this(planner, heights, maxUnit, minRepeatRows, hullRing, interiorWall, tierB, tierC, roofRise, ledgeLip, storyArt, dressing, templateMax,
				profileUnits, profileBuildings, profileBoxes, false, false);
	}

	/** Parameters before P3 (no class boxes). */
	public ShapeParams(String planner, String heights, int maxUnit, int minRepeatRows, int hullRing, int interiorWall, boolean tierB,
			boolean tierC, int roofRise, int ledgeLip, boolean storyArt, boolean dressing, int templateMax, boolean profileUnits,
			boolean profileBuildings) {
		this(planner, heights, maxUnit, minRepeatRows, hullRing, interiorWall, tierB, tierC, roofRise, ledgeLip, storyArt, dressing, templateMax,
				profileUnits, profileBuildings, false);
	}

	/** Parameters before P2 (no profile buildings). */
	public ShapeParams(String planner, String heights, int maxUnit, int minRepeatRows, int hullRing, int interiorWall, boolean tierB,
			boolean tierC, int roofRise, int ledgeLip, boolean storyArt, boolean dressing, int templateMax, boolean profileUnits) {
		this(planner, heights, maxUnit, minRepeatRows, hullRing, interiorWall, tierB, tierC, roofRise, ledgeLip, storyArt, dressing, templateMax,
				profileUnits, false);
	}

	/** Parameters before P1 (no profile units). */
	public ShapeParams(String planner, String heights, int maxUnit, int minRepeatRows, int hullRing, int interiorWall, boolean tierB,
			boolean tierC, int roofRise, int ledgeLip, boolean storyArt, boolean dressing, int templateMax) {
		this(planner, heights, maxUnit, minRepeatRows, hullRing, interiorWall, tierB, tierC, roofRise, ledgeLip, storyArt, dressing, templateMax, false);
	}

	/** Parameters before V7 (no dressing, no templates). */
	public ShapeParams(String planner, String heights, int maxUnit, int minRepeatRows, int hullRing, int interiorWall, boolean tierB,
			boolean tierC, int roofRise, int ledgeLip, boolean storyArt) {
		this(planner, heights, maxUnit, minRepeatRows, hullRing, interiorWall, tierB, tierC, roofRise, ledgeLip, storyArt, false, tierC ? TEMPLATE_MAX : 0);
	}

	/** The V2 parameters with another planner version or setting (tests). */
	public ShapeParams(String planner, String heights, int maxUnit, int minRepeatRows, int hullRing, int interiorWall, boolean tierB,
			boolean tierC) {
		this(planner, heights, maxUnit, minRepeatRows, hullRing, interiorWall, tierB, tierC, 16, 4, false);
	}

	/** Parameters before V5 (no story art overlays). */
	public ShapeParams(String planner, String heights, int maxUnit, int minRepeatRows, int hullRing, int interiorWall, boolean tierB,
			boolean tierC, int roofRise, int ledgeLip) {
		this(planner, heights, maxUnit, minRepeatRows, hullRing, interiorWall, tierB, tierC, roofRise, ledgeLip, false);
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

	/** V5: Tier B and the closed art of tile story cells as overlays of the shape map. */
	public static ShapeParams v5() {
		return new ShapeParams("kb-shapes/4", MEASURED, HeightRules.MAX_UNIT, HeightRules.MIN_REPEAT_ROWS, 2, HeightRules.INTERIOR_WALL,
				true, false, 16, 4, true);
	}

	/**
	 * V7: Tier C (bounded structures with a door as templates, at most {@link #TEMPLATE_MAX} tiles along either side) and
	 * dressing (interior walls up to the ceiling, ceilings in the room's wall art, open-air interiors).
	 */
	public static ShapeParams v7() {
		return new ShapeParams("kb-shapes/5", MEASURED, HeightRules.MAX_UNIT, HeightRules.MIN_REPEAT_ROWS, 2, HeightRules.INTERIOR_WALL,
				true, true, 16, 4, true, true, TEMPLATE_MAX);
	}

	/** The largest template V7 plans, in art tiles along either side (16 cells of 2 × 2 tiles: the biggest Kanto building). */
	public static final int TEMPLATE_MAX = 32;

	/**
	 * P1: V7's planner plus units from the profile's pins and the probe's repeating cells (docs/specs/PROFILE_PLAN.md
	 * §5.4). Without a profile and a probe it plans what V7 plans.
	 */
	public static ShapeParams p1() {
		return new ShapeParams("kb-shapes/6", MEASURED, HeightRules.MAX_UNIT, HeightRules.MIN_REPEAT_ROWS, 2, HeightRules.INTERIOR_WALL,
				true, true, 16, 4, true, true, TEMPLATE_MAX, true);
	}

	/** P2: P1's planner plus the profile's band-table buildings (docs/specs/PROFILE_PLAN.md §5.5). */
	public static ShapeParams p2() {
		return new ShapeParams("kb-shapes/7", MEASURED, HeightRules.MAX_UNIT, HeightRules.MIN_REPEAT_ROWS, 2, HeightRules.INTERIOR_WALL,
				true, true, 16, 4, true, true, TEMPLATE_MAX, true, true);
	}

	/**
	 * P3: P2's planner plus the profile's class boxes, furniture parts, hull variants, stairs and reliefs
	 * (docs/specs/PROFILE_PLAN.md §5.6).
	 */
	public static ShapeParams p3() {
		return new ShapeParams("kb-shapes/8", MEASURED, HeightRules.MAX_UNIT, HeightRules.MIN_REPEAT_ROWS, 2, HeightRules.INTERIOR_WALL,
				true, true, 16, 4, true, true, TEMPLATE_MAX, true, true, true);
	}

	/** CW1: P3's planner plus the caves' rock (docs/specs/CAVES_AND_WOODS_PLAN.md §5.1). */
	public static ShapeParams cw1() {
		return new ShapeParams("kb-shapes/9", MEASURED, HeightRules.MAX_UNIT, HeightRules.MIN_REPEAT_ROWS, 2, HeightRules.INTERIOR_WALL,
				true, true, 16, 4, true, true, TEMPLATE_MAX, true, true, true, true, false);
	}

	/** CW2: CW1's planner plus Viridian Forest's canopy and vines (docs/specs/CAVES_AND_WOODS_PLAN.md §5.2-§5.3). */
	public static ShapeParams cw2() {
		return new ShapeParams("kb-shapes/10", MEASURED, HeightRules.MAX_UNIT, HeightRules.MIN_REPEAT_ROWS, 2, HeightRules.INTERIOR_WALL,
				true, true, 16, 4, true, true, TEMPLATE_MAX, true, true, true, true, true);
	}

	/** The parameters a build plans with. */
	public static ShapeParams current() {
		return cw2();
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
		if (this.storyArt) {
			// Not in older indexes, whose planners have none.
			out.addProperty("storyArt", true);
		}
		if (this.tierC) {
			out.addProperty("templateMax", this.templateMax);
		}
		if (this.dressing) {
			out.addProperty("dressing", true);
		}
		if (this.profileUnits) {
			out.addProperty("profileUnits", true);
		}
		if (this.profileBuildings) {
			out.addProperty("profileBuildings", true);
		}
		if (this.profileBoxes) {
			out.addProperty("profileBoxes", true);
		}
		if (this.caveRock) {
			out.addProperty("caveRock", true);
		}
		if (this.canopy) {
			out.addProperty("canopy", true);
		}
		return out;
	}

	public boolean measured() {
		return MEASURED.equals(this.heights);
	}
}
