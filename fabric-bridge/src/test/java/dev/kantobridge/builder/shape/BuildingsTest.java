package dev.kantobridge.builder.shape;

import static dev.kantobridge.builder.shape.ShapeFixtures.interior;
import static dev.kantobridge.builder.shape.ShapeFixtures.surface;
import static dev.kantobridge.builder.shape.ShapeFixtures.tile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.scene.profile.Profile;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The profile plan's band-table buildings in the planner (docs/specs/PROFILE_PLAN.md §5.5): claims by exact tile grid
 * before Tier A, first claim wins, {@code keep}, swap sites refused, claim-only twins, and each claim's volume: solid
 * tiles on shape_full, doorways and the eave's row on decor, the doorway's floor the building's ground. Made-up maps and
 * profiles; pixel-free.
 */
class BuildingsTest {
	/** A house: roof rows A-D over E-H, a facade I-L over M N and a door ('+', walkable). */
	static final String[] HOUSE = {
			"..ABCD..",
			"..EFGH..",
			"..IJKL..",
			"..MN++..",
			"........",
			"........"};
	static final String HOUSE_ROWS = "{ `A`, `B`, `C`, `D` }, { `E`, `F`, `G`, `H` }, { `I`, `J`, `K`, `L` }, { `M`, `N`, `+`, `+` }";
	static final String BAND = "roofRows = 8, roofBack = 1, roofFront = 2, roofCycle = { 1, 5 }, slab = 2";

	@Test
	void aBuildingClaimsItsGridBeforeTierA() throws Exception {
		SceneMap map = surface("TOWN", HOUSE);
		Map<String, Integer> tally = new TreeMap<>();
		ShapePlanner.MapShapes shapes = plan(map, "{ tiles = { " + HOUSE_ROWS + " }, " + BAND + ", frontEave = 1 }", tally);
		assertEquals(Map.of("building", 1), tally);
		assertEquals(1, shapes.templates().size());
		Templates.Placed placed = shapes.templates().get(0);
		assertEquals(2, placed.x0());
		assertEquals(0, placed.y0(), "no awning: no row north");
		JsonObject entry = placed.entry();
		assertEquals(4, entry.get("w").getAsInt());
		assertEquals(5, entry.get("d").getAsInt(), "the four rows and the eave's row south");
		JsonObject band = entry.getAsJsonObject("band");
		assertEquals(8, band.get("roofRows").getAsInt());
		assertEquals(1, band.get("south").getAsInt());
		assertEquals(32, band.get("depthPx").getAsInt());
		// The bound: (32 drawn rows - 8 roof rows + 2 of slab) / 8, rounded up: 4 blocks.
		int cols = map.artCols();
		ShapeKey[] wall = shapes.open()[0 * cols + 2];
		assertEquals(4, wall.length);
		for (int k = 0; k < 4; k++) {
			assertEquals(ShapeKey.Kind.TMPL, wall[k].kind());
			assertEquals(placed.sig(), wall[k].sig());
		}
		assertEquals(4, shapes.blocks()[0 * cols + 2]);
		assertEquals(0b1111, shapes.shapeMask()[0 * cols + 2], "on shape_full");
		// The door ('+', walkable): a doorway on decor, its floor the building's ground ('.').
		int door = 3 * cols + 4;
		assertEquals(0, shapes.blocks()[door], "nothing collides in a doorway");
		assertEquals(0b1111, shapes.decor()[door]);
		assertEquals(tile('.'), shapes.floor()[door]);
		// The eave's row south of the house, walkable: decor, four high.
		assertEquals(0b1111, shapes.decor()[4 * cols + 3]);
		assertEquals(ShapeKey.Kind.TMPL, shapes.open()[4 * cols + 3][3].kind());
		assertTrue(shapes.units().isEmpty(), "the house is no unit");
	}

	@Test
	void theFirstClaimWinsAndKeepLeavesItsTiles() throws Exception {
		SceneMap map = surface("TOWN", HOUSE);
		Map<String, Integer> tally = new TreeMap<>();
		ShapePlanner.MapShapes shapes = plan(map, "{ tiles = { " + HOUSE_ROWS + " }, " + BAND + ", keep = { `A` } }, "
				+ "{ tiles = { { `E`, `F` } }, " + BAND + " }", tally);
		assertEquals(Map.of("building", 1), tally, "the second entry's grid lies inside the first's claim");
		JsonObject entry = shapes.templates().get(0).entry();
		assertEquals(-1, entry.getAsJsonArray("tiles").get(0).getAsInt(), "A is kept: not claimed");
		assertEquals(1, shapes.blocks()[2], "a kept tile stays Tier A's: a one-row run, one block");
		assertEquals(0, shapes.shapeMask()[2], "on tile_full");
	}

	@Test
	void aSwapSiteRefusesTheClaim() throws Exception {
		SceneMap map = ShapeFixtures.withSite(surface("TOWN", HOUSE), "script", 1, 1, "..", "..");
		Map<String, Integer> tally = new TreeMap<>();
		ShapePlanner.MapShapes shapes = plan(map, "{ tiles = { " + HOUSE_ROWS + " }, " + BAND + " }", tally);
		assertTrue(shapes.templates().stream().noneMatch(p -> p.entry().has("band")));
		assertEquals(1, tally.get("building_swap_site"));
	}

	@Test
	void aClaimOnlyTwinKeepsCollisionAndDrawsNothing() throws Exception {
		SceneMap map = surface("TOWN", HOUSE);
		Map<String, Integer> tally = new TreeMap<>();
		ShapePlanner.MapShapes shapes = plan(map, "{ claimOnly = true, tiles = { " + HOUSE_ROWS + " } }", tally);
		assertEquals(Map.of("building_claim_only", 1), tally);
		JsonObject band = shapes.templates().get(0).entry().getAsJsonObject("band");
		assertTrue(band.get("claimOnly").getAsBoolean());
		int cols = map.artCols();
		assertEquals(2, shapes.blocks()[0 * cols + 2], "two blocks of collision");
		assertEquals(0b11, shapes.shapeMask()[0 * cols + 2]);
		assertEquals(tile('.'), shapes.floor()[0 * cols + 2], "its floor wears the ground");
		assertEquals(0, shapes.decor()[3 * cols + 4], "no doorway");
	}

	@Test
	void aRowComparesAsManyIdsAsItHas() throws Exception {
		SceneMap map = surface("TOWN", HOUSE);
		Map<String, Integer> tally = new TreeMap<>();
		plan(map, "{ tiles = { { `A`, `B`, `C`, `D` }, { `E` } }, " + BAND + " }", tally);
		assertEquals(Map.of("building", 1), tally, "the short second row matches its one id");
		Map<String, Integer> none = new TreeMap<>();
		plan(map, "{ tiles = { { `A`, `B`, `C`, `D` }, { `X` } }, " + BAND + " }", none);
		assertNull(none.get("building"));
	}

	@Test
	void anInteriorsBuildingStopsUnderTheCeiling() throws Exception {
		SceneMap room = interior("ROOM",
				"..ABCD..",
				"..EFGH..",
				"..IJKL..",
				"..MNOP..",
				"..QRST..",
				"..UVWX..",
				"........",
				"........");
		ShapePlanner.MapShapes shapes = plan(room, "{ tiles = { { `A`, `B`, `C`, `D` }, { `E`, `F`, `G`, `H` }, { `I`, `J`, `K`, `L` }, "
				+ "{ `M`, `N`, `O`, `P` }, { `Q`, `R`, `S`, `T` }, { `U`, `V`, `W`, `X` } }, roofRows = 8, roofBack = 1, roofFront = 1, "
				+ "roofCycle = { 1, 2 }, slab = 2 }", new TreeMap<>());
		assertEquals(WorldPlan.CEILING_ABOVE_FEET, shapes.blocks()[2], "six drawn rows would stand six blocks: the ceiling stops them");
	}

	@Test
	void anOverhangOverSolidGroundIsCut() throws Exception {
		SceneMap map = surface("TOWN",
				"..ABCD..",
				"..EFGH..",
				"..IJKL..",
				"..MN++..",
				"..YYYY..",
				"..YYYY..");
		Map<String, Integer> tally = new TreeMap<>();
		plan(map, "{ tiles = { " + HOUSE_ROWS + " }, " + BAND + ", frontEave = 1 }", tally);
		assertEquals(4, tally.get("building_overhang_cut"), "the row south is a wall: no eave there");
	}

	private static ShapePlanner.MapShapes plan(SceneMap map, String entries, Map<String, Integer> tally) throws Exception {
		Profile.Tileset tileset = profile(entries).tileset("FIX");
		return ShapePlanner.planMap(map, ShapeFixtures.TIER_B, 0, ShapeParams.p2(), tileset, null, tally);
	}

	/** A profile whose FIX tileset has these building entries; a fixture character between backticks is its tile id. */
	static Profile profile(String entries) throws Exception {
		StringBuilder lua = new StringBuilder();
		for (int k = 0; k < entries.length(); k++) {
			char c = entries.charAt(k);
			if (c == '`') {
				lua.append(tile(entries.charAt(k + 1)));
				k = entries.indexOf('`', k + 1);
			} else {
				lua.append(c);
			}
		}
		return Profile.parse("return { buildings = { FIX = { " + lua + " } } }");
	}
}
