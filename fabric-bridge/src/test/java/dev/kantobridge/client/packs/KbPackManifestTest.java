package dev.kantobridge.client.packs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kantobridge.builder.pack.PackKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The texture pack manifest (docs/specs/VOXEL_BLOCKS_PLAN.md §4.9, §5.10): the schema's synthetic example parses without
 * a warning; a manifest that can't be read is dropped whole ({@code pack.format}); an entry that breaks a rule is dropped
 * with its counted code and the rest kept. The codes that need the resource manager are {@link KbPackReloadTest}'s.
 */
class KbPackManifestTest {
	static String example() throws Exception {
		return Files.readString(Path.of(System.getProperty("kb.protocolDir")).resolve("packs").resolve("kb_pack.example.json"), StandardCharsets.UTF_8);
	}

	static JsonObject exampleJson() throws Exception {
		return JsonParser.parseString(example()).getAsJsonObject();
	}

	static String minimal(String sections) {
		return "{\"kantobridge_pack\":1,\"id\":\"t\",\"targets\":{\"games\":[\"red\"]}" + (sections.isEmpty() ? "" : "," + sections) + "}";
	}

	@Test
	void theSchemaExampleParsesWithoutAWarning() throws Exception {
		KbPackManifest.Result result = KbPackManifest.parse(example());
		assertEquals(Map.of(), result.warnings());
		KbPackManifest.Manifest manifest = result.manifest();
		assertNotNull(manifest);
		assertEquals("hd_test", manifest.id());
		assertEquals(java.util.Set.of("red", "blue"), manifest.games());
		assertEquals(3, manifest.textures().size());
		assertEquals(3, manifest.models().size());
		assertEquals(5, manifest.rules().size());
		assertEquals("carved", manifest.characterStyle());
		assertEquals(Map.of("SPRITE_TEST_BALL", "model"), manifest.characterSprites());
		assertEquals(4, manifest.dressing().get("TEST_HOUSE").headroom());
		KbPackManifest.Model ball = manifest.models().stream().filter(m -> m.key().kind() == PackKey.Kind.SPRITE).findFirst().orElseThrow();
		assertEquals(90, ball.turn());
		assertTrue(ball.flip());
		assertArrayEquals(new int[] {0, 0, 0}, ball.origin());
		KbPackManifest.Rule prop = manifest.rules().stream().filter(r -> r.key().text().equals("class:prop")).findFirst().orElseThrow();
		assertNull(prop.style());
		assertEquals(3, prop.depth());
	}

	@Test
	void aManifestThatCantBeReadIsDroppedWhole() {
		for (String text : java.util.List.of("not json", "[]", "{}", "{\"kantobridge_pack\":2,\"id\":\"t\",\"targets\":{\"games\":[\"red\"]}}",
				"{\"kantobridge_pack\":1,\"targets\":{\"games\":[\"red\"]}}", "{\"kantobridge_pack\":1,\"id\":\"Bad Id\",\"targets\":{\"games\":[\"red\"]}}",
				"{\"kantobridge_pack\":1,\"id\":\"t\"}", "{\"kantobridge_pack\":1,\"id\":\"t\",\"targets\":{\"games\":[]}}",
				"{\"kantobridge_pack\":1,\"id\":\"t\",\"targets\":{\"games\":[\"red\"],\"geometry\":2}}")) {
			KbPackManifest.Result result = KbPackManifest.parse(text);
			assertNull(result.manifest(), text);
			assertEquals(Map.of("pack.format", 1), result.warnings(), text);
		}
		assertNotNull(KbPackManifest.parse(minimal("")).manifest(), "the minimal manifest");
	}

	@Test
	void anEntryThatBreaksARuleIsDroppedWithItsCode() {
		String text = minimal(String.join(",",
				"\"textures\":{\"tile:t/4\":\"p:block/ok\",\"tile:t/04\":\"p:block/x\",\"cell:t/1-2-3-4\":\"p:block/x\",\"tile:t/5\":\"p:textures/block/x.png\","
						+ "\"sprite:S\":\"p:npc/x\",\"sprite:T\":\"p:textures/npc/t.png\"}",
				"\"models\":{\"cell:t/1-2-3-4\":{\"vox\":\"p:m/a.vox\"},\"cell:t/1-2-3-5\":{\"vox\":\"p:m/a.vox\",\"voxelsPerBlock\":12},"
						+ "\"cell:t/1-2-3-6\":{\"vox\":\"p:m/a.vox\",\"turn\":45},\"cell:t/1-2-3-7\":{\"vox\":\"p:m/a.obj\"},"
						+ "\"cell:t/1-2-3-8\":{\"vox\":\"p:m/a.vox\",\"origin\":[1,2]},\"map:M\":{\"vox\":\"p:m/a.vox\"}}",
				"\"rules\":{\"class:tree\":{\"style\":\"object\",\"depth\":2},\"class:wall\":{\"style\":\"hull\"},\"class:prop\":{\"style\":\"round\"},"
						+ "\"class:grass\":{\"style\":\"flat\"},\"class:flower\":{\"style\":\"hull\"},\"tile:t/9\":{\"class\":\"tree\"},"
						+ "\"tile:t/10\":{\"class\":\"castle\"},\"struct:t/0123456789abcdef\":{\"bands\":{\"roofRows\":7}},"
						+ "\"class:door\":{\"depth\":9},\"map:M\":{\"style\":\"hull\"}}",
				"\"characters\":{\"style\":\"model\",\"sprites\":{\"S\":\"thick\",\"T\":\"wobbly\"}}",
				"\"dressing\":{\"map:M\":{\"headroom\":4},\"map:N\":{\"headroom\":1},\"tile:t/4\":{\"headroom\":3}}"));
		KbPackManifest.Result result = KbPackManifest.parse(text);
		KbPackManifest.Manifest manifest = result.manifest();
		assertNotNull(manifest, "a broken entry never drops the manifest");
		// Rules 7, characters 2, dressing 2.
		assertEquals(Map.of("pack.unknown_key", 3, "pack.texture_missing", 2, "pack.vox_format", 4, "pack.rule", 11), result.warnings());
		assertEquals(java.util.List.of("sprite:T", "tile:t/4"), manifest.textures().stream().map(t -> t.key().text()).toList(), "in key order");
		assertEquals(java.util.List.of("cell:t/1-2-3-4"), manifest.models().stream().map(m -> m.key().text()).toList());
		assertEquals(8, manifest.models().get(0).voxelsPerBlock(), "8 voxels per block by default");
		assertEquals(java.util.List.of("class:grass", "class:tree", "tile:t/9"), manifest.rules().stream().map(r -> r.key().text()).toList());
		assertNull(manifest.characterStyle());
		assertEquals(Map.of("S", "thick"), manifest.characterSprites());
		assertEquals(java.util.Set.of("M"), manifest.dressing().keySet());
		assertFalse(manifest.games().isEmpty());
	}
}
