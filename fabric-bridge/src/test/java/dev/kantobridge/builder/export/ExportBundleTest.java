package dev.kantobridge.builder.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.TestData;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.Hashes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading an export bundle: every resource's size and SHA-256 is checked against the index before anything is used. */
class ExportBundleTest {
	@TempDir
	Path temp;

	private Path copyOfSynthetic() throws IOException {
		Path source = TestData.syntheticBundle();
		Path target = this.temp.resolve("red");
		try (Stream<Path> files = Files.walk(source)) {
			for (Path file : files.toList()) {
				Path out = target.resolve(source.relativize(file).toString());
				if (Files.isDirectory(file)) {
					Files.createDirectories(out);
				} else {
					Files.copy(file, out);
				}
			}
		}
		return target;
	}

	@Test
	void readsTheSyntheticBundle() throws Exception {
		ExportBundle bundle = ExportBundle.open(TestData.syntheticBundle());
		assertEquals("red", bundle.game());
		assertEquals(24, bundle.resources().size(), "with the cave's two maps and its tileset (CAVES_3D_PLAN §3.3)");
		// The profile plan: the synthetic world's made-up shape profile, as text.
		ExportProfile profile = bundle.profile();
		assertEquals(ExportProfile.MOD, profile.mod());
		assertEquals(ExportProfile.FILE, profile.file());
		assertEquals("0.0.0-synthetic", profile.version());
		assertTrue(!profile.enabled() && profile.text().startsWith("-- A made-up shape profile"));
		assertEquals(List.of("TEST_CAVE_1F", "TEST_CAVE_B1F", "TEST_HILL", "TEST_HOUSE", "TEST_HOUSE_2F", "TEST_TOWN"), bundle.ids("map"));
		String index = Files.readString(TestData.syntheticBundle().resolve("index.json"), StandardCharsets.UTF_8);
		assertEquals(Hashes.sha256(ProtoJson.write(ProtoJson.parse(index))), bundle.exportHash());

		ExportMap town = bundle.map("TEST_TOWN");
		assertEquals(10, town.width());
		assertEquals(20, town.cellWidth());
		assertEquals(18, town.cellHeight());
		assertTrue(town.surface());
		assertEquals(3, town.objects().size());
		assertEquals(CellFlags.WATER, town.flags(12, 12));
		assertEquals(CellFlags.LEDGE, town.flags(2, 13) & CellFlags.LEDGE);
		assertEquals(0, CellFlags.ledgeDir(town.flags(2, 13)), "the synthetic ledge hops down");
		ExportMap house = bundle.map("TEST_HOUSE");
		assertEquals(new ExportMap.Parent("TEST_TOWN", 1, 5, 5), house.parent());
		assertEquals("TEST_TOWN", house.lastMap());

		ExportTileset tileset = bundle.tileset("TEST");
		assertEquals("tilesets.test", tileset.image());
		assertTrue(tileset.animatesWater());
		assertEquals(25, tileset.blocks().length, "the town's and house's 17 blocks and the hill's 8");
		// MG1: the layout's id is the game's default region, the palettes' the Gen 1 family's
		assertEquals("kanto", bundle.layoutId());
		assertEquals(ExportBundle.GEN1_PALETTES_ID, bundle.palettesId());
		assertTrue(bundle.hasLayout() && bundle.hasPalettes());
		assertEquals("TEST_TOWN", bundle.layout().root());
		assertEquals(1, bundle.swaps().sites().size());
		assertEquals(4, bundle.palettes().colours("TEST_PALETTE").length);
		assertEquals(6, bundle.sprite("SPRITE_TEST_GIRL").frames());
		assertEquals(128, bundle.image("tilesets.test").w());
	}

	@Test
	void classpathAndDirectoryReadTheSame() throws Exception {
		Path root = TestData.syntheticBundle();
		ExportBundle fromFiles = ExportBundle.open(root);
		ExportBundle fromSource = ExportBundle.read(path -> {
			Path file = root.resolve(path);
			return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
		});
		assertEquals(fromFiles.exportHash(), fromSource.exportHash());
		assertEquals(fromFiles.resources(), fromSource.resources());
	}

	@Test
	void anAlteredResourceIsRejected() throws Exception {
		Path bundle = this.copyOfSynthetic();
		Path swaps = bundle.resolve("swaps/all.json");
		byte[] bytes = Files.readAllBytes(swaps);
		// Same length, different content: only the hash can catch it.
		bytes[bytes.length - 3] = (byte) (bytes[bytes.length - 3] == '5' ? '6' : '5');
		Files.write(swaps, bytes);
		BundleException e = assertThrows(BundleException.class, () -> ExportBundle.open(bundle));
		assertEquals("bundle.hash", e.code());
		assertTrue(e.getMessage().contains("swaps/all.json"));
	}

	@Test
	void aResizedResourceIsRejected() throws Exception {
		Path bundle = this.copyOfSynthetic();
		Files.writeString(bundle.resolve("field/all.json"), "{}", StandardCharsets.UTF_8);
		assertEquals("bundle.size", assertThrows(BundleException.class, () -> ExportBundle.open(bundle)).code());
	}

	@Test
	void aMissingResourceIsRejected() throws Exception {
		Path bundle = this.copyOfSynthetic();
		Files.delete(bundle.resolve("map/TEST_HOUSE.json"));
		assertEquals("bundle.missing", assertThrows(BundleException.class, () -> ExportBundle.open(bundle)).code());
		Files.delete(bundle.resolve("index.json"));
		assertEquals("bundle.missing", assertThrows(BundleException.class, () -> ExportBundle.open(bundle)).code());
	}

	@Test
	void unsafeIdsAndKindsAreRejected() throws Exception {
		Path bundle = this.copyOfSynthetic();
		Path index = bundle.resolve("index.json");
		String text = Files.readString(index, StandardCharsets.UTF_8);
		Files.writeString(index, text.replace("\"id\": \"all\"", "\"id\": \"../all\""), StandardCharsets.UTF_8);
		assertEquals("bundle.index", assertThrows(BundleException.class, () -> ExportBundle.open(bundle)).code());
		Files.writeString(index, text.replace("\"kind\": \"swaps\"", "\"kind\": \"secrets\""), StandardCharsets.UTF_8);
		assertEquals("bundle.index", assertThrows(BundleException.class, () -> ExportBundle.open(bundle)).code());
		Files.writeString(index, text.replace("\"game\": \"red\"", "\"game\": \"green\""), StandardCharsets.UTF_8);
		assertEquals("bundle.index", assertThrows(BundleException.class, () -> ExportBundle.open(bundle)).code());
	}

	@Test
	void missingOptionalResources() throws Exception {
		ExportBundle bundle = ExportBundle.open(TestData.syntheticBundle());
		assertFalse(bundle.has("image", "tilesets.flower4"));
		assertNull(bundle.json("map", "NOWHERE"));
		assertEquals("bundle.missing", assertThrows(BundleException.class, () -> bundle.map("NOWHERE")).code());
	}
}
