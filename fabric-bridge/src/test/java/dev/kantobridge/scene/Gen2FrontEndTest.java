package dev.kantobridge.scene;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.G2Tiles;
import dev.kantobridge.scene.gen2.Gen2FrontEnd;
import dev.kantobridge.scene.gen3.G3ArtSource;
import dev.kantobridge.scene.gen3.QuarterPages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * MG9 (G2E, docs/specs/GEN2_BRIDGE_PLAN.md §7): the Gen 2 front end on the synthetic Gen 2 export: the golden scene, its
 * one virtual atlas of baked tile ids in a baked palette, kinds and tags from the collision bytes' meanings, both areas'
 * maps on the surface, the border block, the objects (a sprite a variable chooses named by it), the swap sites' both
 * states; the art source reads the baked pages.
 */
class Gen2FrontEndTest {
	private static ExportBundle bundle;
	private static Scene scene;

	@BeforeAll
	static void describe() throws Exception {
		bundle = ExportBundle.open(TestData.syntheticGen2Bundle());
		scene = Gen2FrontEnd.INSTANCE.describe(bundle);
	}

	@Test
	void theSceneIsTheGolden() throws Exception {
		TestData.assertGolden(TestData.goldenG2Scene(), SceneJson.write(scene), "Gen 2 scene");
		assertEquals(SceneJson.write(scene), SceneJson.write(Gen2FrontEnd.INSTANCE.describe(ExportBundle.open(TestData.syntheticGen2Bundle()))),
				"the same bundle, the same scene");
	}

	@Test
	void theSceneDescribesTheWholeExport() throws Exception {
		assertSame(Gen2FrontEnd.INSTANCE, FrontEnds.forGame("crystal"));
		assertTrue(FrontEnds.artSource(bundle) instanceof G3ArtSource);
		assertEquals("gen2", scene.family());
		assertEquals("kb-frontend-gen2/1", scene.frontEnd());
		assertEquals(PaletteModel.BAKED, scene.paletteModel());
		assertEquals(16, scene.cellPx());
		assertEquals(8, scene.artPx());
		assertEquals("G2_TEST_TOWN", scene.layoutRoot());
		assertNull(scene.profile());
		SceneAtlas atlas = scene.atlas(QuarterPages.VIRTUAL);
		assertNotNull(atlas);
		G2Tiles tiles = bundle.g2tiles("TILESET_TEST_1");
		assertTrue(atlas.tiles() > tiles.tile(13, 3, 3), "every baked tile the variants name");
		assertEquals(32, atlas.tilesPerRow());
		assertEquals(6, scene.maps().size());
		SceneMap town = scene.map("G2_TEST_TOWN");
		assertEquals(QuarterPages.VIRTUAL, town.atlas());
		assertEquals(QuarterPages.PALETTE, town.palette());
		assertEquals("TILESET_TEST_1", town.tileset());
		assertEquals(2, town.perCell());
		assertEquals(10, town.w());
		assertTrue(town.surface());
		assertTrue(town.env().outdoor() && town.env().openAir());
		assertFalse(town.env().cave());
		assertEquals(CellKind.SOLID, town.kind(0, 0), "the trees");
		assertEquals(CellKind.LEDGE, town.kind(0, 7));
		assertEquals(0, town.dir(0, 7), "hops down");
		assertTrue(town.hasTag(4, 3, CellTag.DOOR) && town.hasTag(4, 3, CellTag.WARP));
		assertTrue(town.hasTag(6, 2, CellTag.SIGN));
		assertEquals(CellKind.WATER, town.kind(8, 4));
		assertEquals(4, town.borderSize(), "one border block: a 4 x 4 square of tiles");
		assertArrayEquals(town.ownBorder(), town.border());
		assertEquals(tiles.tile(7, 0, 0), town.ownBorder()[0]);
		assertEquals(5, town.objects().size());
		assertEquals("O1", town.objects().get(0).name());
		assertEquals(SceneMap.SceneObject.ITEM, town.objects().get(1).kind());
		assertEquals(SceneMap.SceneObject.TRAINER, town.objects().get(2).kind());
		assertFalse(town.objects().get(2).stays());
		assertEquals("SPRITE_VAR_243", town.objects().get(4).sprite(), "the doll's sprite is its variable's");
		// the swap sites: the Cut tree (open: its stump) and the whirlpool (open: water), both states of each changing cell
		assertEquals(2, town.swapSites().size());
		SwapSite cut = town.swapSites().get(0);
		assertEquals("G2_TEST_TOWN:cut:6:4", cut.id());
		assertEquals(4, cut.cells().size());
		assertEquals(CellKind.SOLID, cut.cells().get(0).closed().kind());
		assertTrue((cut.cells().get(0).closed().tags() & CellTag.CUT.bit()) != 0);
		assertEquals(CellKind.WALK, cut.cells().get(0).open().kind());
		assertEquals(tiles.tile(6, 0, 0), town.closedTile(12, 8), "the closed art shows the tree");
		assertEquals(tiles.tile(12, 0, 0), town.tile(12, 8), "built open: the stump");
		SwapSite whirlpool = town.swapSites().get(1);
		assertEquals("whirlpool", whirlpool.kind());
		assertEquals(CellKind.WATER, whirlpool.cells().get(0).closed().kind());
		SceneMap route = scene.map("G2_TEST_ROUTE");
		assertTrue(route.hasTag(0, 0, CellTag.GRASS));
		assertEquals(1, route.swapSites().size(), "the gate a script opens");
		assertEquals(CellKind.WALK, route.kind(2, 2), "built open");
		assertTrue(scene.map("G2_TEST_KANTO_TOWN").surface(), "Crystal's Kanto is a surface area too");
		SceneMap house = scene.map("G2_TEST_HOUSE");
		assertFalse(house.surface());
		assertFalse(house.env().outdoor());
		assertEquals(CellKind.COUNTER, house.kind(2, 2));
		assertTrue(house.hasTag(2, 5, CellTag.WARP_TILE));
		assertNull(house.closedTiles(), "no site changes the house");
		SceneMap cave = scene.map("G2_TEST_CAVE");
		assertTrue(cave.env().cave());
		assertEquals(Env.DARK_FLASH, cave.env().dark());
		// the tall grass's tiles are the atlas's grass; the outdoor border's, foliage
		assertTrue(atlas.grassTiles().contains(route.tile(0, 0)));
		assertEquals(SceneAtlas.HINT_FOLIAGE, atlas.hints().get(town.ownBorder()[0]));
	}

	@Test
	void theArtSourceReadsTheBakedPages() throws Exception {
		ArtSource art = FrontEnds.artSource(bundle);
		int ground = bundle.g2tiles("TILESET_TEST_1").tile(1, 0, 0);
		int[] virtual = art.tileShades(QuarterPages.VIRTUAL, ground);
		int[] paged = art.tileShades(QuarterPages.page(ground), QuarterPages.index(ground));
		assertNotNull(virtual);
		assertArrayEquals(virtual, paged, "the virtual atlas and the page are the same art");
		assertNull(art.tileShades("g3q.9", 0), "no such page");
		G3ArtSource g2 = (G3ArtSource) art;
		// synth2's ground: (150, 200, 120), its diagonal 30 darker
		assertEquals(0xFF78AA78, g2.tileArgb(QuarterPages.VIRTUAL, ground)[0]);
		assertEquals(0xFF96C878, g2.tileArgb(QuarterPages.VIRTUAL, ground)[1]);
	}
}
