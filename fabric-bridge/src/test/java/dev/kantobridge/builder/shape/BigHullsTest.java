package dev.kantobridge.builder.shape;

import static dev.kantobridge.builder.shape.ShapeFixtures.interior;
import static dev.kantobridge.builder.shape.ShapeFixtures.surface;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.scene.Env;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.scene.profile.Profile;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The profile's two-cell hulls in the planner (docs/specs/PROFILE_PLAN.md §5.6, P3): a canopy over 2 × 2 cells, a planter
 * two cells tall, their heights under the map's headroom. Made-up maps.
 */
class BigHullsTest {
	static final String[] GROVE = {
			"........",
			"........",
			"..ABCD..",
			"..EFGH..",
			"..IJKL..",
			"..MNOP..",
			"........",
			"........"};
	static final String PINS = "canopy = { `A` }, cylinder = { `C`, `I`, `K` }";

	private static ShapePlanner.MapShapes plan(SceneMap map, String pins, Map<String, Integer> tally) throws Exception {
		Profile.Tileset tileset = ProfileUnitsTest.tileset(pins);
		return ShapePlanner.planMap(map, ShapeFixtures.TIER_B, 0, ShapeParams.p3(), tileset, null, tally);
	}

	/** {@code map} as an interior under the open sky (a forest). */
	static SceneMap openAir(SceneMap map) {
		return new SceneMap(map.id(), map.index(), map.tileset(), map.atlas(), map.palette(), map.w(), map.h(), map.perCell(), map.surface(),
				new Env(false, true, false, Env.DARK_NEVER), map.tiles(), map.closedTiles(), map.kinds(), map.tags(), map.dirs(), map.border(),
				map.ownBorder(), map.objects(), map.swapSites());
	}

	@Test
	void aCanopyTakesItsFourCells() throws Exception {
		SceneMap map = surface("GROVE", GROVE);
		Map<String, Integer> tally = new TreeMap<>();
		ShapePlanner.MapShapes shapes = plan(map, PINS, tally);
		assertEquals(1, tally.get("canopy"));
		assertEquals(1, shapes.templates().size());
		JsonObject entry = shapes.templates().get(0).entry();
		assertEquals(4, entry.get("w").getAsInt());
		assertEquals("canopy", entry.getAsJsonObject("hull").get("kind").getAsString());
		int cols = map.artCols();
		for (int ty = 2; ty < 6; ty++) {
			for (int tx = 2; tx < 6; tx++) {
				ShapeKey[] column = shapes.open()[ty * cols + tx];
				assertEquals(4, column.length, "32 pixels: four blocks");
				assertEquals(ShapeKey.Kind.TMPL, column[3].kind());
			}
		}
		assertTrue(shapes.units().isEmpty(), "its cylinders are the canopy's, not units of their own");
	}

	@Test
	void aCanopyStopsUnderACeilingAndReachesItsLevelUnderTheSky() throws Exception {
		SceneMap room = interior("GROVE", GROVE);
		assertEquals(3, plan(room, PINS, new TreeMap<>()).blocks()[2 * room.artCols() + 2]);
		SceneMap forest = openAir(room);
		assertEquals(4, plan(forest, PINS, new TreeMap<>()).blocks()[2 * forest.artCols() + 2]);
	}

	@Test
	void aCanopyWithoutItsPartnersIsNone() throws Exception {
		SceneMap map = surface("GROVE", GROVE);
		Map<String, Integer> tally = new TreeMap<>();
		ShapePlanner.MapShapes shapes = plan(map, "canopy = { `A` }, cylinder = { `C`, `I` }", tally);
		assertEquals(1, tally.get("canopy_alone"));
		assertTrue(shapes.templates().stream().noneMatch(p -> p.entry().has("hull")));
	}

	@Test
	void aPlanterStandsOnItsSouthCell() throws Exception {
		SceneMap map = surface("PATIO",
				"......",
				"......",
				"..AB..",
				"..EF..",
				"..IJ..",
				"..MN..",
				"......",
				"......");
		Map<String, Integer> tally = new TreeMap<>();
		ShapePlanner.MapShapes shapes = plan(map, "planter = { `A`, `I` }", tally);
		assertEquals(1, tally.get("planter"));
		int cols = map.artCols();
		assertEquals(2, shapes.blocks()[2 * cols + 2], "the crown's cell: two blocks of collision");
		assertEquals(4, shapes.blocks()[4 * cols + 2], "the pot's: the model's four");
		assertNull(shapes.open()[0], "nothing else");
	}
}
