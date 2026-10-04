package dev.kantobridge.scene.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.world.Hashes;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The profile as read (docs/specs/PROFILE_PLAN.md §4.2): heights, pins, conditional pins, prop grounds and backgrounds,
 * hull parameters, building entries; unknown names counted, masks skipped. A made-up profile, no game data.
 */
class ProfileTest {
	static final String TEXT = """
			return {
			  version = 1,
			  heights = { wall = 18, cylinder = 20, nonsense = 3 },
			  tilesets = {
			    TOWN = {
			      cylinder = { 10, 11, 12, 13 },
			      prop = { 20, 21, 13 },
			      post = { 30 },
			      wall = { 40 },
			      heights = { prop = 6 },
			      when_above = { [50] = { { above = { 40 }, class = "wall" }, { above = { 41 }, class = "nonsense" } } },
			      when_below = { [50] = { { below = { 10 }, class = "post" } } },
			      prop_ground = { [20] = 1, [21] = 1 },
			      prop_bg = { { tiles = { 20, 21 }, shades = { "white", "light" } } },
			      can_cap = 7,
			      bookcase_relief = false,
			      bookcase_backfill = "above",
			      figures = { { w = 1, tiles = { 99 }, under = { 1 }, pixels = { "X......." } } },
			      companion = { tree_support = { 10 } },
			      gazebo = { 60 },
			    },
			    ROOM = { stool = { 5 } },
			  },
			  buildings = {
			    TOWN = { { tiles = { { 1, 2 }, { 3, 4 } }, roofRows = 8, slab = 2 }, { claimOnly = true, tiles = { { 5 } } } },
			  },
			}
			""";

	@Test
	void readsHeightsPinsAndTheirExtras() throws Exception {
		Profile p = Profile.parse(TEXT);
		assertEquals(1, p.version());
		assertEquals(Hashes.sha256(TEXT), p.hash());
		Profile.Tileset town = p.tileset("TOWN");
		assertEquals("cylinder", town.pin(10));
		assertEquals("prop", town.pin(20));
		assertEquals("cylinder", town.pin(13), "the first group naming a tile wins");
		assertNull(town.pin(60), "an unknown class pins nothing");
		assertEquals(18, town.height("wall"), "the file's heights over the defaults");
		assertEquals(20, town.height("cylinder"));
		assertEquals(6, town.height("prop"), "a tileset's own heights last");
		assertEquals(20, p.tileset("ROOM").height("cylinder"), "other tilesets keep the file's heights");
		assertEquals(28, town.height("roof"), "a class the file leaves out keeps its default");
		assertEquals(Map.of(20, 1, 21, 1), town.propGround());
		assertEquals(Map.of(20, 0b0011, 21, 0b0011), town.propBg(), "white and light: shades 0 and 1");
		assertEquals(7, town.hull().canCap());
		assertEquals(Profile.HullParams.DEFAULTS.stumpCap(), town.hull().stumpCap());
		assertFalse(town.bookcaseRelief());
		assertEquals("above", town.bookcaseBackfill());
		assertTrue(p.tileset("ROOM").bookcaseRelief());
	}

	@Test
	void conditionalPinsKeepAboveBeforeBelowAndDropUnknownClasses() throws Exception {
		Profile.Tileset town = Profile.parse(TEXT).tileset("TOWN");
		List<Profile.Cond> conds = town.conds().get(50);
		assertEquals(2, conds.size());
		assertEquals(new Profile.Cond(true, Set.of(40), "wall"), conds.get(0));
		assertEquals(new Profile.Cond(false, Set.of(10), "post"), conds.get(1));
	}

	@Test
	void buildingsStayInListOrderAndCountsAreCounts() throws Exception {
		Profile p = Profile.parse(TEXT);
		assertEquals(2, p.buildings("TOWN").size());
		assertEquals(8, p.buildings("TOWN").get(0).integer("roofRows", 0));
		assertTrue(p.buildings("ROOM").isEmpty());
		Profile.Counts counts = p.counts();
		assertEquals(2, counts.tilesets());
		assertEquals(4 + 2 + 1 + 1 + 1, counts.pins(), "every tile pinned once; the repeated 13 is not counted again");
		assertEquals(1, counts.duplicatePins());
		assertEquals(2, counts.conds());
		assertEquals(2, counts.buildingEntries());
		assertEquals(Map.of("class", 1, "heights.?", 1, "when_above", 1), counts.unknown(), "gazebo, nonsense height, nonsense class");
	}

	@Test
	void anEmptyProfileIsReadable() throws Exception {
		Profile p = Profile.parse("return {}");
		assertTrue(p.tilesets().isEmpty());
		assertNull(p.tileset("TOWN"));
		assertEquals(0, p.counts().pins());
	}

	@Test
	void aSourceThatIsNotDataIsUnreadableNotFatal() {
		ProfileSource source = ProfileSource.read("return { x = run() }", "2.0.4", false);
		assertNull(source.profile());
		assertEquals("unreadable", source.status());
		assertEquals("1:14", source.problem());
		assertEquals("ok", ProfileSource.read(TEXT, "2.0.4", false).status());
	}
}
