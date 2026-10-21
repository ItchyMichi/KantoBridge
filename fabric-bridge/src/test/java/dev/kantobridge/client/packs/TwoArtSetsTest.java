package dev.kantobridge.client.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.pack.ResourcePackWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MG7 (G3E, docs/specs/GEN3_BRIDGE_PLAN.md §6 "Two art sets in one pack"): a player with Kanto's and Hoenn's exports gets
 * one pack with both art sets; a compatible game's export adds none; the pack info names its sets, its own first, and
 * which set draws a game; the texture sync takes a pack whose second set draws the world's game.
 */
class TwoArtSetsTest {
	@TempDir
	Path temp;

	private static void copy(Path from, Path to) throws IOException {
		try (Stream<Path> walk = Files.walk(from)) {
			for (Path path : walk.toList()) {
				Path target = to.resolve(from.relativize(path).toString());
				if (Files.isDirectory(path)) {
					Files.createDirectories(target);
				} else {
					Files.copy(path, target);
				}
			}
		}
	}

	@Test
	void kantoAndHoennShareOnePack() throws Exception {
		Path export = this.temp.resolve("export");
		copy(TestData.syntheticBundle(), export.resolve("red"));
		copy(TestData.syntheticGen3Bundle(), export.resolve("emerald"));
		Path pack = this.temp.resolve("pack");
		PackGenerator.Result result = PackGenerator.generateWithOthers(export.resolve("red"), pack);
		assertEquals(List.of("red", "emerald"), result.artSets());
		assertEquals("red", result.game(), "the asked-for export is the pack's own set");
		PackInfo info = PackInfo.read(pack);
		assertNotNull(info);
		assertTrue(info.usable());
		assertEquals("red", info.artSet());
		assertEquals(List.of("red", "emerald"), info.artSets());
		assertEquals("emerald", info.setFor("emerald").artSet());
		assertEquals("red", info.setFor("blue").artSet(), "Blue draws with Red's art");
		assertNull(info.setFor("gold"), "no Johto art");
		assertEquals(info.sets().get(0).tiles() + info.sets().get(1).tiles(), info.tiles(), "the counts are the sets' sum");
		assertEquals(49, info.sets().get(1).tiles(), "Hoenn's synthetic quarters (W3 of the 3D heights plan: its cave's too)");
		assertTrue(Files.isRegularFile(pack.resolve("assets/kantobridge/textures/block/emerald/tile/g3q.0/baked/0000.png")));
		try (Stream<Path> red = Files.walk(pack.resolve("assets/kantobridge/textures/block/red"))) {
			assertEquals(info.sets().get(0).tiles(), red.filter(p -> p.toString().endsWith(".png")).count());
		}
		// asked for Hoenn's export, the pack is Hoenn's own with Kanto's beside it
		Path other = this.temp.resolve("pack2");
		assertEquals(List.of("emerald", "red"), PackGenerator.generateWithOthers(export.resolve("emerald"), other).artSets());
		assertEquals("emerald", PackInfo.read(other).artSet());
	}

	@Test
	void aCompatibleGamesExportAddsNoSet() throws Exception {
		Path export = this.temp.resolve("export");
		copy(TestData.syntheticBundle(), export.resolve("red"));
		// the same Gen 1 export in another folder: its game is red's, whose art the pack already holds
		copy(TestData.syntheticBundle(), export.resolve("blue"));
		Path pack = this.temp.resolve("pack");
		assertEquals(List.of("red"), PackGenerator.generateWithOthers(export.resolve("red"), pack).artSets());
		String text = Files.readString(pack.resolve(ResourcePackWriter.INFO_FILE), StandardCharsets.UTF_8);
		assertFalse(text.contains("\"sets\""), "a one-set pack's info is what it always was");
		// and the single-set generator is byte for byte the one-set pack
		Path single = this.temp.resolve("single");
		assertEquals(PackGenerator.generate(export.resolve("red"), single).pack().hash(),
				PackGenerator.generateWithOthers(export.resolve("red"), this.temp.resolve("again")).pack().hash());
	}

	@Test
	void packInfoReadsItsSets() {
		String hash = "c".repeat(64);
		String other = "d".repeat(64);
		PackInfo info = PackInfo.parse("{\"artSet\":\"red\",\"exportHash\":\"" + hash + "\",\"game\":\"red\",\"layout\":2,\"sets\":["
				+ "{\"artSet\":\"red\",\"exportHash\":\"" + hash + "\",\"game\":\"red\",\"sprites\":4,\"tiles\":10},"
				+ "{\"artSet\":\"emerald\",\"exportHash\":\"" + other + "\",\"game\":\"emerald\",\"sprites\":0,\"tiles\":36}],"
				+ "\"sprites\":4,\"textures\":50,\"tiles\":46}");
		assertNotNull(info);
		assertEquals(List.of("red", "emerald"), info.artSets());
		assertEquals(other, info.setFor("emerald").exportHash());
		// a pack without sets holds its one set
		PackInfo one = PackInfo.parse("{\"artSet\":\"blue\",\"exportHash\":\"" + hash + "\",\"game\":\"blue\",\"layout\":2,\"tiles\":3}");
		assertEquals(List.of("blue"), one.artSets());
		assertEquals("blue", one.setFor("red").artSet());
		// the pack's own set must come first; a bad set refuses the whole info
		assertNull(PackInfo.parse("{\"artSet\":\"red\",\"exportHash\":\"" + hash + "\",\"game\":\"red\",\"layout\":2,\"sets\":["
				+ "{\"artSet\":\"emerald\",\"exportHash\":\"" + other + "\",\"game\":\"emerald\"}]}"));
		assertNull(PackInfo.parse("{\"artSet\":\"red\",\"exportHash\":\"" + hash + "\",\"game\":\"red\",\"layout\":2,\"sets\":["
				+ "{\"artSet\":\"../x\",\"exportHash\":\"" + hash + "\",\"game\":\"red\"}]}"));
		assertNull(PackInfo.parse("{\"artSet\":\"red\",\"exportHash\":\"" + hash + "\",\"game\":\"red\",\"layout\":2,\"sets\":[]}"));
	}

	@Test
	void eachKeyIsDrawnWithItsOwnGamesSet() {
		List<String> loaded = List.of("red", "emerald");
		assertEquals("emerald", PackInfo.drawSet(loaded, "emerald"));
		assertEquals("red", PackInfo.drawSet(loaded, "red"));
		assertEquals("red", PackInfo.drawSet(loaded, "blue"), "Blue's keys draw with Red's art");
		assertNull(PackInfo.drawSet(List.of("red"), "emerald"), "a region whose art this client hasn't got");
		assertNull(PackInfo.drawSet(List.of(), "red"));
	}

	@Test
	void theTextureSyncTakesAPackWhoseSecondSetDrawsTheWorld() {
		String hash = "c".repeat(64);
		PackInfo both = new PackInfo(2, "red", "red", hash, 46, 4, 50,
				List.of(new PackInfo.ArtSet("red", "red", hash, 10, 4), new PackInfo.ArtSet("emerald", "emerald", "d".repeat(64), 36, 0)));
		assertEquals(TextureSync.Decision.READY, TextureSync.decide(new TextureSync.Inputs("emerald", "e".repeat(64), both, true, 0, true), false));
		PackInfo kantoOnly = new PackInfo(2, "red", "red", hash, 10, 4, 14);
		assertEquals(TextureSync.Decision.REGENERATE, TextureSync.decide(new TextureSync.Inputs("emerald", "e".repeat(64), kantoOnly, true, 0, true),
				false), "a Kanto-only pack can't draw Hoenn");
	}
}
