package dev.kantobridge.builder.shape.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Units the profile pins (docs/specs/PROFILE_PLAN.md §5.4): the class and background in the key's variant, standees cut
 * out by their pool without the object test and at their pool's depth, the strict cutout, posts, and a pinned cylinder's
 * hull. Made-up drawings; no game art.
 */
class PinnedUnitTest {
	private static CellUnits.Context pinned(String cls, int background, boolean repeatsVertically) {
		return new CellUnits.Context(Background.SOUTH, false, repeatsVertically, false, false, cls, background);
	}

	private static int count(boolean[] mask) {
		int n = 0;
		for (boolean b : mask) {
			n += b ? 1 : 0;
		}
		return n;
	}

	@Test
	void theVariantCarriesTheClassAndTheBackground() {
		CellUnits.Context prop = new CellUnits.Context(2, false, true, false, true, "prop", 3);
		assertEquals("g2rt_prop_bg3", prop.variant());
		assertEquals(prop, CellUnits.Context.parse(prop.variant()));
		CellUnits.Context busy = new CellUnits.Context(10, true, true, true, true, "billboard", 15);
		assertEquals("gairst_billboard_bgf", busy.variant());
		assertEquals(busy, CellUnits.Context.parse(busy.variant()));
		CellUnits.Context cutout = CellUnits.Context.parse("g0_cutout");
		assertEquals("cutout", cutout.pinned());
		assertFalse(cutout.tree() || cutout.indoor(), "a class's letters are not flags");
		assertNull(CellUnits.Context.parse("g0rt").pinned(), "a variant from before the profile");
		assertEquals(new CellUnits.Context(0, false, true, false, true), CellUnits.Context.parse("g0rt_unknown"), "an unknown suffix is left out");
	}

	@Test
	void aPinnedStandeeNeedsNoObjectTestAndStandsAtItsPoolDepth() {
		int[][] tiles = ArtFixtures.tiles(ArtFixtures.sign());
		assertNotEquals(CellUnits.Kind.OBJECT, CellUnits.build(tiles, pinned(null, 0, true), 2, ArtFixtures.ART_PX).kind(),
				"a vertically repeating drawing is scenery to the object test");
		CellUnits.Unit billboard = CellUnits.build(tiles, pinned("billboard", 0, true), 2, ArtFixtures.ART_PX);
		assertEquals(CellUnits.Kind.OBJECT, billboard.kind());
		assertEquals(1.0, billboard.match());
		VoxelGrid voxels = billboard.voxels();
		int deep = 0;
		for (int z = 0; z < voxels.depth(); z++) {
			deep += voxels.solid(1, 15 - 1, z) ? 1 : 0;
		}
		assertEquals(10, deep, "a billboard is ten pixels deep");
		VoxelGrid signpost = CellUnits.build(tiles, pinned("signpost", 0, true), 2, ArtFixtures.ART_PX).voxels();
		int thin = 0;
		for (int z = 0; z < signpost.depth(); z++) {
			thin += signpost.solid(1, 15 - 1, z) ? 1 : 0;
		}
		assertEquals(2, thin, "a signpost two");
	}

	@Test
	void propBgDecidesWhichShadesAreBackground() {
		int[] drawing = ArtFixtures.shades(
				"1111111111111111",
				"1111111111111111",
				"1133333333333311",
				"1131111111111311",
				"1131111111111311",
				"1131111111111311",
				"1131111111111311",
				"1131111111111311",
				"1131111111111311",
				"1131111111111311",
				"1131111111111311",
				"1131111111111311",
				"1131111111111311",
				"1133333333333311",
				"1111111111111111",
				"1111111111111111");
		int[][] tiles = ArtFixtures.tiles(drawing);
		CellUnits.Unit byBorder = CellUnits.build(tiles, pinned("prop", 0, false), 2, ArtFixtures.ART_PX);
		assertEquals(CellUnits.Kind.OBJECT, byBorder.kind());
		assertEquals(12 * 12, count(byBorder.foreground()), "the light around it floods away; the light inside its outline stays");
		CellUnits.Unit whiteOnly = CellUnits.build(tiles, pinned("prop", 0b0001, false), 2, ArtFixtures.ART_PX);
		assertEquals(256, count(whiteOnly.foreground()), "prop_bg names white only: nothing here is background");
	}

	@Test
	void aCutoutDropsDarkAndLightAndKeepsOnlyItsLargestPiece() {
		int[] drawing = ArtFixtures.shades(
				"................",
				"..33333333......",
				"..3......3......",
				"..3..22..3......",
				"..3..22..3......",
				"..3......3......",
				"..33333333......",
				"................",
				"................",
				"............33..",
				"............33..",
				"................",
				"................",
				"................",
				"................",
				"................");
		CellUnits.Unit unit = CellUnits.build(ArtFixtures.tiles(drawing), pinned("cutout", 0, false), 2, ArtFixtures.ART_PX);
		boolean[] fg = unit.foreground();
		assertTrue(fg[1 * 16 + 2], "the outline");
		assertTrue(fg[2 * 16 + 4], "white its outline encloses stays");
		assertFalse(fg[3 * 16 + 5], "dark is background in a cutout");
		assertFalse(fg[9 * 16 + 12], "the smaller piece is dropped");
		assertEquals(8 * 6 - 4, count(fg));
	}

	@Test
	void aPostFloodsThroughWhiteOnly() {
		int[] drawing = ArtFixtures.shades(
				"................",
				"......33........",
				"......33........",
				"......33........",
				"......33........",
				"......33........",
				"......33........",
				"......33........",
				"......33........",
				"......33........",
				"......33........",
				"......33........",
				"......33........",
				"......33........",
				"11111133........",
				"11111133........");
		CellUnits.Unit post = CellUnits.build(ArtFixtures.tiles(drawing), pinned("post", 0, false), 2, ArtFixtures.ART_PX);
		assertTrue(post.foreground()[15 * 16], "light is solid to a post");
		CellUnits.Unit standee = CellUnits.build(ArtFixtures.tiles(drawing), pinned("prop", 0, false), 2, ArtFixtures.ART_PX);
		assertFalse(standee.foreground()[15 * 16], "a prop floods through every shade on its border");
	}

	@Test
	void aPinnedCylinderIsAHullWhereTheRoundTestSaysNo() {
		int[] crown = ArtFixtures.crown(7.0);
		for (int y = 0; y < 8; y++) {
			for (int x = 11; x < 16; x++) {
				crown[y * 16 + x] = 0;
			}
		}
		int[][] tiles = ArtFixtures.tiles(crown);
		assertFalse(Hulls.round(Hulls.mask(crown, new boolean[256], 16, 16), 16, 16), "a bitten crown isn't round");
		assertNotEquals(CellUnits.Kind.HULL, CellUnits.build(tiles, pinned(null, 0, false), 2, ArtFixtures.ART_PX).kind());
		assertEquals(CellUnits.Kind.HULL, CellUnits.build(tiles, pinned("cylinder", 0, false), 2, ArtFixtures.ART_PX).kind());
	}

	@Test
	void aPacksRuleStillWins() {
		int[][] tiles = ArtFixtures.tiles(ArtFixtures.sign());
		assertEquals(CellUnits.Kind.COLUMNS,
				CellUnits.build(tiles, pinned("billboard", 0, false), 2, ArtFixtures.ART_PX, CellUnits.MAX_QUADS, "columns", 0).kind());
	}
}
