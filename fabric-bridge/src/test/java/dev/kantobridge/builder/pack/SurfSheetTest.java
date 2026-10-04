package dev.kantobridge.builder.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.plan.ArtCatalog;
import dev.kantobridge.builder.plan.Warnings;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The surfing player's sheet (M8 item 5): the export's {@code field.playerSprites.surf} sprite, in the palette of every
 * map with water, at {@code textures/player/<palette>/surf.png}; without that table (like the synthetic export) nothing
 * changes.
 */
class SurfSheetTest {
	@TempDir
	Path temp;

	@Test
	void theSurfSpriteGoesToEveryWaterPalette() throws Exception {
		ExportBundle synthetic = ExportBundle.open(TestData.syntheticBundle());
		JsonObject field = synthetic.json("field", "all").deepCopy();
		JsonObject sprites = new JsonObject();
		sprites.addProperty("walk", "SPRITE_TEST_GIRL");
		sprites.addProperty("surf", "SPRITE_TEST_MOM");
		field.getAsJsonObject("tables").add("playerSprites", sprites);
		ExportBundle bundle = ExportBundle.open(TestData.syntheticCopyWith(this.temp, Map.of("field/all", field)));

		Warnings warnings = new Warnings();
		ArtCatalog art = ArtCatalog.of(bundle, warnings);
		assertEquals("SPRITE_TEST_MOM", art.surfSprite());
		assertEquals(Set.of("TEST_PALETTE"), art.surfPalettes(), "the town has water; the house shares its palette");
		assertEquals(ArtCatalog.of(synthetic, new Warnings()).slotMap().hash(), art.slotMap().hash(), "sheets never touch the slot map");

		PackFiles files = new PackFiles();
		ResourcePackWriter.Result result = ResourcePackWriter.generate(art, bundle, files, warnings);
		assertEquals(0, warnings.total(), warnings.warnings().toString());
		assertNotNull(files.files().get("assets/kantobridge/textures/player/red/test_palette/surf.png"));
		assertEquals(5, result.npcTextures(), "the four NPC sheets and the surf sheet");
		assertEquals("kantobridge:textures/player/red/test_palette/surf.png", ArtKeys.surfTexture("red", "TEST_PALETTE"));
	}

	@Test
	void withoutTheTableOrTheSpriteThereIsNoSheet() throws Exception {
		ExportBundle synthetic = ExportBundle.open(TestData.syntheticBundle());
		ArtCatalog plain = ArtCatalog.of(synthetic, new Warnings());
		assertNull(plain.surfSprite());
		assertTrue(plain.surfPalettes().isEmpty());

		JsonObject field = synthetic.json("field", "all").deepCopy();
		JsonObject sprites = new JsonObject();
		sprites.addProperty("surf", "SPRITE_NOT_EXPORTED");
		field.getAsJsonObject("tables").add("playerSprites", sprites);
		ExportBundle bundle = ExportBundle.open(TestData.syntheticCopyWith(this.temp, Map.of("field/all", field)));
		Warnings warnings = new Warnings();
		ArtCatalog art = ArtCatalog.of(bundle, warnings);
		assertNull(art.surfSprite());
		assertEquals(1, warnings.noteCount("surf_sprite_missing"), "a note, not a warning: the build stays clean");
		assertEquals(0, warnings.total());
	}
}
