package dev.kantobridge.scene.profile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
			    TOWN = {
			      { tiles = { { 1, 2 }, { 3, 4 } }, roofRows = 8, roofBack = 2, roofFront = 2, roofCycle = { 2, 5 }, slab = 2, frontEave = 1,
			        ledge = { 9, 10 }, seal = "s", scrub = { { 0, 0, 1, 1 } }, keep = { 4 } },
			      { claimOnly = true, tiles = { { 5 } } },
			      { tiles = { { 6 } }, roofRows = 8, slab = 2 },
			      { tiles = { { 7 } }, parts = { { x = { 0, 1 } } }, roofRows = 0, slab = 0 },
			    },
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
		assertEquals(4, p.buildings("TOWN").size());
		assertEquals(8, p.buildings("TOWN").get(0).integer("roofRows", 0));
		assertTrue(p.buildings("ROOM").isEmpty());
		Profile.Counts counts = p.counts();
		assertEquals(2, counts.tilesets());
		assertEquals(4 + 2 + 1 + 1 + 1, counts.pins(), "every tile pinned once; the repeated 13 is not counted again");
		assertEquals(1, counts.duplicatePins());
		assertEquals(2, counts.conds());
		assertEquals(4, counts.buildingEntries());
		assertEquals(Map.of("class", 1, "heights.?", 1, "when_above", 1, "buildings.band", 1, "buildings.parts.upright.top", 1), counts.unknown(),
				"gazebo, nonsense height, nonsense class; a roofed entry without its roof rows; an upright part without its rows");
	}

	@Test
	void buildingEntriesReadIntoTheirBandTables() throws Exception {
		Profile p = Profile.parse(TEXT);
		List<Profile.Building> read = p.tileset("TOWN").buildings();
		assertEquals(2, read.size(), "the band table and the claim-only twin");
		Profile.Building house = read.get(0);
		assertEquals(0, house.index());
		assertEquals(2, house.width());
		assertEquals(Set.of(4), house.keep());
		Profile.Band band = house.band();
		assertEquals(8, band.roofRows());
		assertEquals(2, band.roofBack());
		assertEquals(2, band.cycle0());
		assertEquals(5, band.cycle1());
		assertEquals(1, band.frontEave());
		assertEquals(9, band.ledge()[0]);
		assertEquals("s", band.seal());
		assertEquals(16, band.depthPx(), "eight voxels per grid row");
		assertEquals(1, band.scrub().size());
		Profile.Building twin = read.get(1);
		assertTrue(twin.claimOnly());
		assertNull(twin.band());
		assertEquals(1, twin.index());
		assertTrue(p.tileset("ROOM").buildings().isEmpty());
	}

	@Test
	void furnitureEntriesReadTheirPiecesWithTheirDefaults() throws Exception {
		Profile p = Profile.parse("""
				return { buildings = { ROOM = {
				  { tiles = { { 1, 2 }, { 3, 4 } }, roofRows = 0, slab = 0,
				    desk = { fascia = { 6, 7 }, base = { 13, 15 }, depth = 1 },
				    wall = { cycle = { 0, 1 }, h = 20, depthPx = 3 },
				    parts = { { kind = "flat", x = { 2, 5 }, rows = { 0, 2 } },
				              { kind = "box", x = { 8, 11 }, rows = { 4, 9 }, depth = 3 },
				              { kind = "iso", x = { 0, 7 }, rows = { 0, 7 }, z = 4 },
				              { x = { 9, 14 }, top = { 0, 3 }, facade = { 4, 10 }, depth = 5, rise = 1, inset = { x = { 10, 12 }, rows = { 5, 6 } } } } },
				  { tiles = { { 5 } }, roofRows = 0, slab = 0, tray = { top = { 0, 2 }, front = { 5, 7 }, x = { 0, 7 }, inner = { 2, 5 } },
				    parts = { } },
				  { tiles = { { 6 } }, roofRows = 0, slab = 0, parts = { { kind = "box", x = { 0, 9 }, rows = { 0, 1 }, depth = 2 } } },
				} } }""");
		List<Profile.Building> read = p.tileset("ROOM").buildings();
		assertEquals(2, read.size(), "the box part's columns run past the one-tile drawing");
		Profile.Parts desk = read.get(0).parts();
		assertEquals(3 + 2, desk.plane(), "base rows and fascia rows");
		assertEquals(8, desk.desk().depthPx(), "one tile row");
		assertEquals(0, desk.desk().x0());
		assertEquals(15, desk.desk().x1(), "the drawing's width by default");
		assertNull(desk.desk().top());
		assertEquals(20, desk.wall().h());
		Profile.FlatPart flat = (Profile.FlatPart) desk.list().get(0);
		assertEquals(0, flat.z(), "a flat part's depth starts at its first row");
		assertEquals(5, flat.at(), "and it lies on the plane");
		Profile.BoxPart box = (Profile.BoxPart) desk.list().get(1);
		assertEquals(-1, box.base(), "the ground line's");
		assertEquals(9, box.c0(), "the last row cycles by default");
		Profile.IsoPart iso = (Profile.IsoPart) desk.list().get(2);
		assertEquals(2, iso.plan(), "rx 4, rz 2");
		assertEquals(7 - 4, iso.height());
		Profile.UprightPart upright = (Profile.UprightPart) desk.list().get(3);
		assertEquals(1, upright.rise());
		assertArrayEquals(new int[] {10, 12, 5, 6}, upright.inset());
		assertEquals(5 + 1 + 6, upright.top(desk.plane(), 16));
		assertEquals(5 + 1 + 6, desk.ytop(16), "the upright part's lid is the highest");
		Profile.Parts tray = read.get(1).parts();
		assertEquals(3, tray.plane(), "the front wall's rows");
		assertEquals(0, tray.tray().floor());
		assertNull(read.get(0).band().ledge(), "a furniture entry has no awning");
		assertEquals(1, p.counts().unknown().get("buildings.parts.box"), "the box part's columns run past the drawing");
	}

	@Test
	void aPartMaySinkUnderItsPlane() throws Exception {
		Profile p = Profile.parse("""
				return { buildings = { ROOM = {
				  { tiles = { { 1, 2 } }, roofRows = 0, slab = 0, desk = { fascia = { 0, 1 }, base = { 2, 7 } },
				    parts = { { x = { 0, 7 }, top = { 0, 1 }, facade = { 2, 5 }, depth = 3, rise = -2 } } },
				} } }""");
		Profile.UprightPart part = (Profile.UprightPart) p.tileset("ROOM").buildings().get(0).parts().list().get(0);
		assertEquals(-2, part.rise(), "lowered two voxels under the desk's lid plane");
		assertTrue(p.counts().unknown().isEmpty());
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
