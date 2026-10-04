package dev.kantobridge.client.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.pack.PackKey;
import dev.kantobridge.builder.shape.geom.CellUnits;
import dev.kantobridge.builder.shape.geom.ShapeOverrides;
import dev.kantobridge.builder.shape.geom.ShapeQuad;
import dev.kantobridge.builder.shape.geom.ShapeTable;
import dev.kantobridge.client.shape.vox.VoxFile;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The pack reload's checks that need the resource manager and the key index (docs/specs/VOXEL_BLOCKS_PLAN.md §5.10):
 * textures exist and are sized for the atlas, a pack that would crowd the atlas is refused, {@code .vox} files read,
 * fit their target and stay within the quad budget, unknown keys are counted and skipped, never fatal; packs merge
 * lowest priority first, a later one winning per key; a manifest for another game is skipped silently.
 */
class KbPackReloadTest {
	@TempDir
	Path dir;

	static String manifest(String id, String sections) {
		return "{\"kantobridge_pack\":1,\"id\":\"" + id + "\",\"targets\":{\"games\":[\"red\",\"blue\"]}," + sections + "}";
	}

	@Test
	void texturesMustExistAndFitTheAtlas() throws Exception {
		FakeResources manager = new FakeResources().pack("p")
				.file("kantobridge:kb_pack/a.json", manifest("a", "\"textures\":{\"tile:t/4\":\"p:block/ok\",\"tile:t/5\":\"p:block/missing\","
						+ "\"tile:t/9\":\"p:block/odd\",\"tile:t/4@P\":\"p:block/strip\",\"tile:t/10\":\"p:block/anim\","
						+ "\"sprite:S\":\"p:textures/npc/s.png\",\"sprite:Z\":\"p:textures/npc/z.png\",\"tile:t/77\":\"p:block/ok\"}"))
				.file("p:textures/block/ok.png", FakeResources.png(16, 16))
				.file("p:textures/block/odd.png", FakeResources.png(24, 24))
				.file("p:textures/block/strip.png", FakeResources.png(16, 64))
				.file("p:textures/block/anim.png", FakeResources.png(32, 128))
				.file("p:textures/block/anim.png.mcmeta", "{\"animation\":{}}")
				.file("p:textures/npc/s.png", FakeResources.png(128, 64))
				.file("p:textures/npc/z.png", FakeResources.png(64, 32));
		PackOverrides packs = KbPackReload.read(manager, "red", FakeResources.keys(this.dir));
		assertEquals(Map.of("pack.texture_missing", 1, "pack.texture_size", 2, "pack.unknown_key", 2), packs.warnings(),
				"missing, 24 px and a strip without its animation; a sprite and a tile the export doesn't have");
		assertEquals("p:block/ok", packs.tileTexture("t", "P", 4));
		assertEquals("p:block/anim", packs.tileTexture("t", "P", 10), "an animation strip with its .mcmeta");
		assertNull(packs.tileTexture("t", "P", 9));
		assertEquals("p:textures/npc/s.png", packs.spriteTexture("S"), "a sheet twice the generated size");
		assertEquals(java.util.List.of("a"), packs.packs());
		assertEquals(ShapeTable.NO_OVERRIDES, packs.geometryHash(), "textures change no geometry");
	}

	@Test
	void aPackThatWouldCrowdTheAtlasLosesAllItsBlockTextures() throws Exception {
		FakeResources manager = new FakeResources().pack("p")
				.file("kantobridge:kb_pack/a.json", manifest("a", "\"textures\":{\"tile:t/4\":\"p:block/huge\",\"tile:t/5\":\"p:block/small\","
						+ "\"sprite:S\":\"p:textures/npc/s.png\"}"))
				.file("p:textures/block/huge.png", FakeResources.png(8192, 8192))
				.file("p:textures/block/small.png", FakeResources.png(16, 16))
				.file("p:textures/npc/s.png", FakeResources.png(64, 32));
		PackOverrides packs = KbPackReload.read(manager, "red", FakeResources.keys(this.dir));
		assertEquals(Map.of("pack.atlas_size", 2), packs.warnings(), "a stitch failure is a crash: none of them");
		assertNull(packs.tileTexture("t", "P", 5));
		assertEquals("p:textures/npc/s.png", packs.spriteTexture("S"), "sheets aren't in the block atlas");
	}

	@Test
	void modelsReadFitAndStayWithinTheBudget() throws Exception {
		FakeResources manager = new FakeResources().pack("p")
				.file("kantobridge:kb_pack/a.json", manifest("a", "\"models\":{"
						+ "\"cell:t/1-2-3-4\":{\"vox\":\"p:kb_models/tree.vox\"},"
						+ "\"cell:t/1-2-3-5\":{\"vox\":\"p:kb_models/garbage.vox\"},"
						+ "\"cell:t/1-2-3-6\":{\"vox\":\"p:kb_models/missing.vox\"},"
						+ "\"cell:t/1-2-3-7\":{\"vox\":\"p:kb_models/wide.vox\"},"
						+ "\"cell:t/1-2-3-8\":{\"vox\":\"p:kb_models/busy.vox\"},"
						+ "\"cell:t/9-9-9-9\":{\"vox\":\"p:kb_models/tree.vox\"},"
						+ "\"struct:t/0123456789abcdef\":{\"vox\":\"p:kb_models/tree.vox\",\"voxelsPerBlock\":16},"
						+ "\"sprite:S\":{\"vox\":\"p:kb_models/tree.vox\"},\"sprite:Z\":{\"vox\":\"p:kb_models/tree.vox\"}}"))
				.file("p:kb_models/tree.vox", VoxFile.write(FakeResources.box(16, 16, 12, 1)))
				.file("p:kb_models/garbage.vox", "not a vox file")
				.file("p:kb_models/wide.vox", VoxFile.write(FakeResources.box(24, 8, 8, 1)))
				.file("p:kb_models/busy.vox", VoxFile.write(FakeResources.checkerboard(16)));
		PackOverrides packs = KbPackReload.read(manager, "red", FakeResources.keys(this.dir));
		assertEquals(Map.of("pack.vox_format", 2, "pack.vox_size", 1, "pack.model_budget", 1, "pack.unknown_key", 2), packs.warnings());
		ShapeOverrides.Model tree = packs.unitModel("t", new int[] {1, 2, 3, 4});
		assertNotNull(tree);
		assertEquals(8, tree.blocks().size(), "the unit's 2 × 2 × 2 blocks");
		// 16 × 16 × 12 voxels at 8 per block: two blocks each way, 1.5 high.
		assertTrue(tree.blocks().get(CellUnits.block(2, 1, 1, 1)).size() > 0);
		for (ShapeQuad quad : tree.blocks().get(CellUnits.block(2, 0, 1, 0))) {
			for (int corner = 0; corner < 4; corner++) {
				assertTrue(quad.y(corner) <= 8, "the upper blocks hold the model's top half block only");
			}
		}
		assertEquals(0xFF336699, tree.colours()[1]);
		assertNull(packs.unitModel("t", new int[] {1, 2, 3, 8}));
		assertNotNull(packs.templateModel("t", "0123456789abcdef"), "a structure's model, meshed for its template (V7)");
		assertNotNull(packs.model(PackKey.parse("sprite:S")), "kept for the characters (V8)");
		assertNotEquals(ShapeTable.NO_OVERRIDES, packs.geometryHash(), "a cell model changes geometry");
	}

	@Test
	void laterPacksWinPerKeyWhateverTheFileNames() throws Exception {
		FakeResources manager = new FakeResources()
				.pack("low")
				.file("kantobridge:kb_pack/z.json", manifest("low", "\"textures\":{\"tile:t/4\":\"p:block/low\",\"tile:t/5\":\"p:block/low\"},"
						+ "\"rules\":{\"class:tree\":{\"style\":\"columns\"},\"class:prop\":{\"style\":\"hull\"}}"))
				.pack("high")
				.file("kantobridge:kb_pack/a.json", manifest("high", "\"textures\":{\"tile:t/4\":\"p:block/high\"},"
						+ "\"rules\":{\"class:tree\":{\"style\":\"object\"}}"))
				.pack("other_game")
				.file("kantobridge:kb_pack/b.json", "{\"kantobridge_pack\":1,\"id\":\"gold\",\"targets\":{\"games\":[\"gold\"]},"
						+ "\"textures\":{\"tile:t/4\":\"p:block/gold\"}}")
				.pack("broken")
				.file("kantobridge:kb_pack/c.json", "{")
				.pack("art")
				.file("p:textures/block/low.png", FakeResources.png(16, 16))
				.file("p:textures/block/high.png", FakeResources.png(16, 16))
				.file("p:textures/block/gold.png", FakeResources.png(16, 16));
		PackOverrides packs = KbPackReload.read(manager, "red", FakeResources.keys(this.dir));
		assertEquals(java.util.List.of("low", "high"), packs.packs(), "the gold manifest is skipped silently");
		assertEquals(Map.of("pack.format", 1), packs.warnings(), "the broken one is counted");
		assertEquals("p:block/high", packs.tileTexture("t", "P", 4), "the higher pack, though its file name sorts first");
		assertEquals("p:block/low", packs.tileTexture("t", "P", 5), "what only the lower pack names stays");
		assertEquals("object", packs.unitStyle("t", new int[] {1, 2, 3, 4}, true));
		assertEquals("hull", packs.unitStyle("t", new int[] {1, 2, 3, 4}, false));
		// Without a key index (no export yet) keys can't be checked: everything that parses applies.
		PackOverrides unchecked = KbPackReload.read(new FakeResources().pack("p")
				.file("kantobridge:kb_pack/a.json", manifest("a", "\"textures\":{\"tile:t/77\":\"p:block/ok\"}"))
				.file("p:textures/block/ok.png", FakeResources.png(16, 16)), null, null);
		assertEquals("p:block/ok", unchecked.tileTexture("t", "P", 77));
	}
}
