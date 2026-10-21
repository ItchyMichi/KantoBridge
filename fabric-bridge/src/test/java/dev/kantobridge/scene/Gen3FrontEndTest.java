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
import dev.kantobridge.builder.shape.geom.TextureKey;
import dev.kantobridge.scene.gen3.G3ArtSource;
import dev.kantobridge.scene.gen3.Gen3FrontEnd;
import dev.kantobridge.scene.gen3.QuarterPages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * MG7 (G3E, docs/specs/GEN3_BRIDGE_PLAN.md §6): the Gen 3 front end on the synthetic Gen 3 export: the golden scene, its
 * one virtual atlas of quarter ids in a baked palette, kinds and tags from the collision bytes, the square border, the
 * objects; the art source reads the quarter pages; texture keys name a quarter's page.
 */
class Gen3FrontEndTest {
	private static ExportBundle bundle;
	private static Scene scene;

	@BeforeAll
	static void describe() throws Exception {
		bundle = ExportBundle.open(TestData.syntheticGen3Bundle());
		scene = Gen3FrontEnd.INSTANCE.describe(bundle);
	}

	@Test
	void theSceneIsTheGolden() throws Exception {
		TestData.assertGolden(TestData.goldenG3Scene(), SceneJson.write(scene), "Gen 3 scene");
		assertEquals(SceneJson.write(scene), SceneJson.write(Gen3FrontEnd.INSTANCE.describe(ExportBundle.open(TestData.syntheticGen3Bundle()))),
				"the same bundle, the same scene");
	}

	@Test
	void theSceneDescribesTheWholeExport() throws Exception {
		assertSame(Gen3FrontEnd.INSTANCE, FrontEnds.forGame("emerald"));
		assertTrue(FrontEnds.artSource(bundle) instanceof G3ArtSource);
		assertEquals("gen3", scene.family());
		assertEquals("kb-frontend-gen3/1", scene.frontEnd());
		assertEquals(PaletteModel.BAKED, scene.paletteModel());
		assertEquals(16, scene.cellPx());
		assertEquals(8, scene.artPx());
		assertEquals("EM_TEST_TOWN", scene.layoutRoot());
		assertNull(scene.profile());
		SceneAtlas atlas = scene.atlas(QuarterPages.VIRTUAL);
		assertNotNull(atlas);
		assertEquals(57, atlas.tiles(), "every quarter of the export (item 8: the water's frames too; W3: the cave's)");
		assertEquals(32, atlas.tilesPerRow());
		assertEquals(5, scene.maps().size());
		SceneMap town = scene.map("EM_TEST_TOWN");
		assertEquals(QuarterPages.VIRTUAL, town.atlas());
		assertEquals(QuarterPages.PALETTE, town.palette());
		assertEquals(2, town.perCell());
		assertTrue(town.surface());
		assertTrue(town.env().outdoor() && town.env().openAir());
		assertFalse(town.env().cave());
		assertEquals(CellKind.LEDGE, town.kind(0, 4));
		assertEquals(0, town.dir(0, 4), "jumps south: down");
		assertTrue(town.hasTag(3, 3, CellTag.DOOR) && town.hasTag(3, 3, CellTag.WARP));
		assertEquals(4, town.borderSize(), "2 x 2 metatiles: a 4 x 4 square of quarters");
		assertArrayEquals(town.ownBorder(), town.border());
		assertEquals(3, town.objects().size());
		assertTrue(town.objects().get(0).stays());
		assertFalse(town.objects().get(2).stays());
		assertEquals("O1", town.objects().get(0).name());
		SceneMap route = scene.map("EM_TEST_ROUTE");
		assertTrue(route.hasTag(0, 0, CellTag.GRASS));
		assertEquals(CellKind.WATER, route.kind(5, 0));
		// SE3: the route's site (4, 0), water that a layout turns to land, is described as built: walkable
		assertEquals(CellKind.WALK, route.kind(4, 0));
		SceneMap house = scene.map("EM_TEST_HOUSE");
		assertFalse(house.surface());
		assertFalse(house.env().outdoor());
		// the tall grass's quarters are the atlas's grass; the outdoor border's, foliage
		int grassQuarter = route.tile(0, 0);
		assertTrue(atlas.grassTiles().contains(grassQuarter));
		assertEquals(SceneAtlas.HINT_FOLIAGE, atlas.hints().get(town.ownBorder()[0]));
	}

	@Test
	void theArtSourceReadsTheQuarterPages() throws Exception {
		ArtSource art = FrontEnds.artSource(bundle);
		int tree = bundle.g3tiles("TEST_PAIR_A").quarters(5)[0];
		int[] virtual = art.tileShades(QuarterPages.VIRTUAL, tree);
		int[] paged = art.tileShades(QuarterPages.page(tree), QuarterPages.index(tree));
		assertNotNull(virtual);
		assertArrayEquals(virtual, paged, "the virtual atlas and the page are the same art");
		assertEquals(3, virtual[0], "the tree's dark over-plane pixel");
		assertNull(art.tileShades("g3q.9", 0), "no such page");
		assertNull(art.tileShades("tilesets.overworld", 0), "not Gen 3 art");
		G3ArtSource g3 = (G3ArtSource) art;
		assertEquals(0xFF0A280A, g3.tileArgb(QuarterPages.VIRTUAL, tree)[0]);
	}

	@Test
	void textureKeysNameAQuartersPage() {
		assertEquals(new TextureKey("g3q.2", QuarterPages.PALETTE, 5), TextureKey.of(QuarterPages.VIRTUAL, QuarterPages.PALETTE, 2 * 1024 + 5));
		assertEquals(new TextureKey("tilesets.overworld", "PAL", 5), TextureKey.of("tilesets.overworld", "PAL", 5), "Gen 1 keys stay as they are");
		assertEquals("tile:g3q.2/5@BAKED", TextureKey.of(QuarterPages.VIRTUAL, QuarterPages.PALETTE, 2053).text());
		assertEquals(TextureKey.parse("tile:g3q.2/5@BAKED"), TextureKey.of(QuarterPages.VIRTUAL, QuarterPages.PALETTE, 2053));
		assertEquals(2053, QuarterPages.quarter("g3q.2", 5));
		assertEquals(-1, QuarterPages.quarter("tilesets.overworld", 5));
	}
}
