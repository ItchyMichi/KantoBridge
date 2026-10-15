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
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.builder.plan.WorldPlanner;
import dev.kantobridge.scene.gen1.Gen1FrontEnd;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The Gen 1 front end (docs/specs/VOXEL_BLOCKS_PLAN.md §5.3) on the synthetic bundle: the scene equals the golden
 * {@code scene.json}; the export's cell flags become kinds, tags and directions; the art is exactly what the builder
 * builds; both states of the swap site are described.
 */
class Gen1FrontEndTest {
	private static ExportBundle bundle;
	private static Scene scene;

	@BeforeAll
	static void describe() throws Exception {
		bundle = ExportBundle.open(TestData.syntheticBundle());
		scene = Gen1FrontEnd.INSTANCE.describe(bundle);
	}

	@Test
	void theSceneIsTheGolden() throws Exception {
		TestData.assertGolden(TestData.goldenScene(), SceneJson.write(scene), "scene");
		assertEquals(SceneJson.write(scene), SceneJson.write(Gen1FrontEnd.INSTANCE.describe(ExportBundle.open(TestData.syntheticBundle()))),
				"the same bundle, the same scene");
	}

	@Test
	void theSceneDescribesTheWholeExport() {
		assertSame(Gen1FrontEnd.INSTANCE, FrontEnds.forGame("red"));
		assertSame(Gen1FrontEnd.INSTANCE, FrontEnds.forGame("blue"));
		assertNull(FrontEnds.forGame("nope"), "a game no front end reads");
		assertEquals("gen1", scene.family());
		assertEquals("kb-frontend-gen1/1", scene.frontEnd());
		assertEquals(bundle.exportHash(), scene.exportHash());
		assertEquals(16, scene.cellPx());
		assertEquals(8, scene.artPx());
		assertEquals("TEST_TOWN", scene.layoutRoot());
		assertEquals(List.of("TEST_CAVE_1F", "TEST_CAVE_B1F", "TEST_HILL", "TEST_HOUSE", "TEST_HOUSE_2F", "TEST_TOWN"),
				List.copyOf(scene.maps().keySet()));
		assertEquals(List.of("SPRITE_TEST_BALL", "SPRITE_TEST_GIRL", "SPRITE_TEST_GUARD", "SPRITE_TEST_MOM"),
				List.copyOf(scene.sprites().keySet()));
		SceneAtlas atlas = scene.atlas("tilesets.test");
		assertNotNull(atlas);
		assertEquals(2, atlas.animated().size(), "water (hshift) and flowers (frames), from the export");
		assertEquals(java.util.Set.of(7), atlas.frameTiles());
		assertTrue(atlas.grassTiles().contains(1));
		assertEquals(Gen1FrontEnd.HINT_TREE, atlas.hints().get(13), "the cut tree's tile is foliage");
		assertEquals(Gen1FrontEnd.HINT_TREE, atlas.hints().get(2), "and so is the tree wall the game draws around outdoor maps");
	}

	@Test
	void flagsBecomeKindsTagsAndDirections() {
		SceneMap town = scene.map("TEST_TOWN");
		assertEquals(20, town.w());
		assertEquals(2, town.perCell());
		assertTrue(town.env().outdoor() && town.env().openAir());
		assertEquals(CellKind.WALK, town.kind(2, 2));
		assertEquals(0, town.tags(2, 2));
		assertEquals(CellKind.WATER, town.kind(12, 12));
		assertEquals(CellKind.LEDGE, town.kind(2, 13));
		assertEquals(0, town.dir(2, 13), "the synthetic ledge hops down");
		assertEquals(SceneMap.NO_DIR, town.dir(2, 2));
		assertEquals(CellKind.SOLID, town.kind(11, 5), "the sign post");
		assertTrue(town.hasTag(11, 5, CellTag.SIGN));
		assertEquals(CellKind.WALK, town.kind(5, 5), "the door");
		assertTrue(town.hasTag(5, 5, CellTag.DOOR) && town.hasTag(5, 5, CellTag.WARP));
		assertTrue(town.hasTag(14, 4, CellTag.GRASS));
		assertEquals(CellKind.SOLID, town.kind(0, 0), "border trees");

		SceneMap hill = scene.map("TEST_HILL");
		assertEquals(CellKind.SOLID, hill.kind(10, 8), "the hill's sign");
		assertTrue(hill.hasTag(10, 8, CellTag.SIGN));

		SceneMap house = scene.map("TEST_HOUSE");
		assertFalse(house.surface());
		assertFalse(house.env().outdoor() || house.env().openAir());
		assertEquals(CellKind.COUNTER, house.kind(4, 3));
	}

	@Test
	void theArtIsWhatTheBuilderBuilds() throws Exception {
		WorldPlan plan = WorldPlanner.plan(bundle, new WorldPlanner.Options(null, 0, 0, TestData.MIN_BUILD_HEIGHT));
		for (SceneMap map : scene.maps().values()) {
			var model = plan.map(map.id()).model();
			for (int ty = 0; ty < map.artRows(); ty++) {
				for (int tx = 0; tx < map.artCols(); tx++) {
					assertEquals(model.tile(tx, ty), map.tile(tx, ty), map.id() + " " + tx + "," + ty);
				}
			}
		}
		SceneMap town = scene.map("TEST_TOWN");
		assertEquals(16, town.border().length);
		assertArrayEquals(town.ownBorder(), town.border(), "the synthetic maps draw their own border block");
		assertEquals(town.border()[0], town.borderTile(-4, -8), "the border repeats");
	}

	@Test
	void bothStatesOfTheSwapSite() {
		SceneMap town = scene.map("TEST_TOWN");
		assertEquals(1, town.swapSites().size());
		SwapSite site = town.swapSites().get(0);
		assertEquals("TEST_TOWN:cut:10:14", site.id());
		assertEquals("cut", site.kind());
		assertEquals(1, site.cells().size(), "only the tree's cell changes");
		SwapSite.SwapCell cell = site.cells().get(0);
		assertEquals(CellKind.SOLID, cell.closed().kind());
		assertTrue(CellTag.has(cell.closed().tags(), CellTag.CUT));
		assertArrayEquals(new int[] {13, 13, 13, 13}, cell.closed().art());
		assertEquals(CellKind.WALK, cell.open().kind());
		assertArrayEquals(new int[] {0, 0, 0, 0}, cell.open().art());
		assertEquals(0, town.tile(20, 28), "built open");
		assertEquals(13, town.closedTile(20, 28));
		SceneMap closed = town.closedVariant();
		assertEquals(CellKind.SOLID, closed.kind(10, 14));
		assertEquals(13, closed.tile(21, 29));
		assertEquals(CellKind.WALK, closed.kind(11, 14), "the rest of the block doesn't change");
		assertSame(scene.map("TEST_HOUSE"), scene.map("TEST_HOUSE").closedVariant(), "no site, no variant");
	}
}
