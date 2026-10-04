package dev.kantobridge.builder.shape;

import static dev.kantobridge.builder.shape.ShapeFixtures.surface;
import static dev.kantobridge.builder.shape.ShapeFixtures.tile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.scene.profile.Profile;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * P3's pinned units in the planner (docs/specs/PROFILE_PLAN.md §5.6): a rising stair on a walkable cell stands on decor
 * carriers, a sunken one stays flat (counted), a stump and a can carry their tileset's parameters. Made-up maps.
 */
class P3UnitsTest {
	private static ShapePlanner.MapShapes plan(SceneMap map, String pins, ShapeParams params, Map<String, Integer> tally) throws Exception {
		Profile.Tileset tileset = ProfileUnitsTest.tileset(pins);
		return ShapePlanner.planMap(map, ShapeFixtures.TIER_B, 0, params, tileset, null, tally);
	}

	/** A walkable cell whose top-left tile is s. */
	static final String[] STAIRS = {
			"......",
			"......",
			"..st..",
			"..uv..",
			"......",
			"......"};

	@Test
	void aRisingStairStandsOnDecorCarriers() throws Exception {
		SceneMap map = surface("CAVE", STAIRS);
		Map<String, Integer> tally = new TreeMap<>();
		ShapePlanner.MapShapes shapes = plan(map, "stair_e = { `s` }", ShapeParams.p3(), tally);
		int cols = map.artCols();
		int i = 2 * cols + 3;
		ShapeKey[] column = shapes.open()[i];
		assertEquals(2, column.length, "sixteen pixels: two blocks");
		assertEquals(ShapeKey.Kind.CELL, column[0].kind());
		assertEquals("g0_stair_e_k16", column[0].variant());
		assertEquals(0b11, shapes.decor()[i], "on decor carriers: no collision");
		assertEquals(0, shapes.blocks()[i]);
		assertEquals(tile('.'), shapes.floor()[i], "its floor the ground around it");
		assertEquals(1, tally.get("pin"));
	}

	@Test
	void aSunkenStairStaysFlat() throws Exception {
		SceneMap map = surface("CAVE", STAIRS);
		Map<String, Integer> tally = new TreeMap<>();
		ShapePlanner.MapShapes shapes = plan(map, "stair_down_e = { `s` }", ShapeParams.p3(), tally);
		assertNull(shapes.open()[2 * map.artCols() + 3]);
		assertEquals(1, tally.get("stair_down"));
	}

	@Test
	void beforeP3AStairPinIsNoUnit() throws Exception {
		SceneMap map = surface("CAVE", STAIRS);
		ShapePlanner.MapShapes shapes = plan(map, "stair_e = { `s` }", ShapeParams.p2(), new TreeMap<>());
		assertNull(shapes.open()[2 * map.artCols() + 3]);
	}

	@Test
	void hullVariantsCarryTheirTilesetsParameters() throws Exception {
		SceneMap map = surface("YARD",
				"......",
				"......",
				"..AB..",
				"..CD..",
				"......",
				"......");
		ShapePlanner.MapShapes stump = plan(map, "stump = { `A` }, stump_cap = 5", ShapeParams.p3(), new TreeMap<>());
		assertEquals("g2_stump_k05", stump.open()[2 * map.artCols() + 2][0].variant());
		ShapePlanner.MapShapes can = plan(map, "can = { `A` }", ShapeParams.p3(), new TreeMap<>());
		assertEquals("g2_can_k0904090504", can.open()[2 * map.artCols() + 2][0].variant(), "the code's defaults");
	}
}
