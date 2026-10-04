package dev.kantobridge.client.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.pack.PackKey;
import dev.kantobridge.builder.shape.geom.ShapeOverrides;
import dev.kantobridge.builder.shape.geom.ShapeTable;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The merged overrides (docs/specs/VOXEL_BLOCKS_PLAN.md §5.10): for one face the palette's entry beats the tile's, a
 * later pack beats an earlier one per key; a tile pinned to a class takes that class's rule; only what changes geometry
 * (rules, models) has a hash, the same for the same content whatever the order it was added in.
 */
class PackOverridesTest {
	static KbPackManifest.Texture texture(String key, String texture) {
		return new KbPackManifest.Texture(PackKey.parse(key), texture);
	}

	static KbPackManifest.Rule style(String clazz, String style, int depth) {
		return new KbPackManifest.Rule(PackKey.clazz(clazz), style, depth, null, 0);
	}

	static KbPackManifest.Rule pin(String tile, String clazz) {
		return new KbPackManifest.Rule(PackKey.parse(tile), null, 0, clazz, 0);
	}

	static ShapeOverrides.Model model() {
		return new ShapeOverrides.Model(java.util.Collections.nCopies(8, List.of()), FakeResources.palette());
	}

	@Test
	void aPalettesEntryBeatsItsTilesAndALaterPackBeatsAnEarlierOne() {
		PackOverrides packs = new PackOverrides.Builder()
				.texture(texture("tile:t/4@P", "a:block/palette"))
				.texture(texture("tile:t/4", "a:block/tile"))
				.texture(texture("sprite:S", "a:textures/npc/s.png"))
				// A later pack:
				.texture(texture("tile:t/4", "b:block/tile"))
				.build();
		assertEquals("a:block/palette", packs.tileTexture("t", "P", 4), "the palette's entry, though the tile's came later");
		assertEquals("b:block/tile", packs.tileTexture("t", "Q", 4), "the later pack's tile entry");
		assertNull(packs.tileTexture("t", "P", 5), "the generated texture");
		assertEquals("a:textures/npc/s.png", packs.spriteTexture("S"));
		assertNull(packs.spriteTexture("T"));
		assertEquals(3, packs.applied(), "each key once");
	}

	@Test
	void aPinnedTileTakesItsClassRule() {
		PackOverrides packs = new PackOverrides.Builder()
				.rule(style("tree", "columns", 0))
				.rule(style("prop", "object", 3))
				.rule(style("grass", "flat", 0))
				.rule(pin("tile:t/9", "tree"))
				.rule(pin("tile:t/7", "grass"))
				.build();
		int[] plain = {1, 2, 3, 4};
		int[] pinned = {1, 9, 3, 4};
		assertEquals("columns", packs.unitStyle("t", plain, true), "foliage is a tree");
		assertEquals("object", packs.unitStyle("t", plain, false), "anything else a prop");
		assertEquals(3, packs.objectDepth("t", plain, false));
		assertEquals("columns", packs.unitStyle("t", pinned, false), "its tile 9 is pinned to tree");
		assertEquals(0, packs.objectDepth("t", pinned, false));
		assertFalse(packs.tuft("t", 5, false), "grass lies flat");
		assertTrue(packs.tuft("t", 5, true), "flowers keep their tufts: no rule");
		assertFalse(packs.tuft("t", 7, true), "a tile pinned to grass follows the grass rule");
		assertNull(PackOverrides.NONE.unitStyle("t", plain, true));
		assertTrue(PackOverrides.NONE.tuft("t", 5, false));
	}

	@Test
	void onlyGeometryHasAHashTheSameForTheSameContent() {
		assertEquals(ShapeTable.NO_OVERRIDES, PackOverrides.NONE.geometryHash());
		PackOverrides textures = new PackOverrides.Builder().texture(texture("tile:t/4", "a:block/x")).build();
		assertEquals(ShapeTable.NO_OVERRIDES, textures.geometryHash(), "a texture needs no rebuild");
		PackOverrides one = new PackOverrides.Builder().rule(style("tree", "hull", 0)).rule(pin("tile:t/9", "prop"))
				.cellModel(PackKey.parse("cell:t/1-2-3-4"), model(), "hash-a|8").build();
		PackOverrides same = new PackOverrides.Builder().cellModel(PackKey.parse("cell:t/1-2-3-4"), model(), "hash-a|8")
				.rule(pin("tile:t/9", "prop")).rule(style("tree", "hull", 0)).build();
		PackOverrides otherModel = new PackOverrides.Builder().rule(style("tree", "hull", 0)).rule(pin("tile:t/9", "prop"))
				.cellModel(PackKey.parse("cell:t/1-2-3-4"), model(), "hash-b|8").build();
		PackOverrides otherRule = new PackOverrides.Builder().rule(style("tree", "object", 0)).rule(pin("tile:t/9", "prop"))
				.cellModel(PackKey.parse("cell:t/1-2-3-4"), model(), "hash-a|8").build();
		assertNotEquals(ShapeTable.NO_OVERRIDES, one.geometryHash());
		assertEquals(one.geometryHash(), same.geometryHash(), "the order it was added in doesn't matter");
		assertNotEquals(one.geometryHash(), otherModel.geometryHash(), "another .vox file");
		assertNotEquals(one.geometryHash(), otherRule.geometryHash());
		assertTrue(one.geometryHash().matches("[0-9a-f]{64}"));
	}

	@Test
	void cellModelsApplyToEveryVariantOfTheirTiles() {
		ShapeOverrides.Model model = model();
		PackOverrides packs = new PackOverrides.Builder().cellModel(PackKey.parse("cell:t/1-2-3-4"), model, "x").build();
		assertSame(model, packs.unitModel("t", new int[] {1, 2, 3, 4}));
		assertNull(packs.unitModel("t", new int[] {4, 3, 2, 1}));
		assertNull(packs.unitModel("u", new int[] {1, 2, 3, 4}), "another atlas");
	}

	@Test
	void whatLaterMilestonesUseIsKept() {
		KbPackManifest.Manifest manifest = new KbPackManifest.Manifest("m", "M", "1", java.util.Set.of("red"), List.of(), List.of(), List.of(), "carved",
				Map.of("S", "model"), Map.of("TEST_HOUSE", new KbPackManifest.Dressing("wall", 4)));
		PackOverrides packs = new PackOverrides.Builder()
				.rule(new KbPackManifest.Rule(PackKey.parse("struct:t/0123456789abcdef"), null, 0, null, 2))
				.model(new KbPackManifest.Model(PackKey.parse("place:TEST_TOWN/1"), "p:m/a.vox", 16, new int[3], 0, false))
				.manifest(manifest)
				.warn("pack.rule").warnings(Map.of("pack.rule", 2, "pack.vox_size", 1))
				.build();
		assertEquals(2, packs.roofRows("t", "0123456789abcdef"));
		assertEquals(-1, packs.roofRows("t", "fedcba9876543210"));
		assertEquals("p:m/a.vox", packs.model(PackKey.parse("place:TEST_TOWN/1")).vox());
		assertEquals("carved", packs.characterStyle());
		assertEquals("model", packs.characterStyle("S"));
		assertEquals(4, packs.dressing("TEST_HOUSE").headroom());
		assertEquals(List.of("m"), packs.packs());
		assertEquals(Map.of("pack.rule", 3, "pack.vox_size", 1), packs.warnings());
		assertEquals(ShapeTable.NO_OVERRIDES, packs.geometryHash(), "structures change geometry from V7, through their own key");
	}
}
