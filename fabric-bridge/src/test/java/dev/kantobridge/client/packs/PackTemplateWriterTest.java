package dev.kantobridge.client.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.pack.PackKey;
import dev.kantobridge.client.shape.vox.VoxFile;
import dev.kantobridge.client.shape.vox.VoxMesher;
import dev.kantobridge.world.Hashes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The key index and the pack template (docs/specs/VOXEL_BLOCKS_PLAN.md §5.10, V6 step 4), on the synthetic bundle: the
 * index names the export's tiles, sprites, cells and maps and reads back; the template holds a manifest skeleton that
 * parses, the index, reference textures and units, a calibration model whose post stands north-east, and
 * {@code generated.json} with the hash of every file made from the game; "prepare for sharing" leaves all of those out,
 * even moved and renamed, and keeps the pack maker's own files.
 */
class PackTemplateWriterTest {
	@TempDir
	Path gameDir;

	@Test
	void theKeyIndexNamesTheExportsKeys() throws Exception {
		KeyIndex keys = KeyIndex.of(ExportBundle.open(TestData.syntheticBundle()));
		assertEquals("red", keys.game());
		assertTrue(keys.tiles() > 0);
		JsonObject json = keys.toJson();
		String tile = json.getAsJsonObject("tiles").keySet().iterator().next();
		assertTrue(keys.contains(PackKey.parse("tile:" + tile)));
		assertTrue(keys.contains(PackKey.parse("tile:" + tile + "@TEST_PALETTE")));
		assertFalse(keys.contains(PackKey.parse("tile:" + tile + "@NO_PALETTE")));
		assertFalse(keys.contains(PackKey.parse("tile:tilesets.test/1023")));
		assertTrue(keys.contains(PackKey.parse("sprite:SPRITE_TEST_GIRL")));
		assertFalse(keys.contains(PackKey.parse("sprite:SPRITE_NOBODY")));
		assertTrue(keys.contains(PackKey.parse("map:TEST_TOWN")));
		assertTrue(keys.contains(PackKey.parse("class:tree")));
		int placed = 0;
		for (String cell : json.getAsJsonObject("cells").keySet()) {
			assertTrue(keys.contains(PackKey.parse("cell:" + cell)), cell);
			JsonObject entry = json.getAsJsonObject("cells").getAsJsonObject(cell);
			assertTrue(Set.of("tree", "prop").contains(entry.get("class").getAsString()));
			placed += entry.get("uses").getAsInt();
		}
		assertTrue(placed > 0, "the units' cells are counted");
		assertTrue(json.getAsJsonObject("tiles").getAsJsonObject(tile).get("uses").getAsInt() >= 0);
		assertTrue(json.getAsJsonObject("tiles").entrySet().stream().anyMatch(e -> e.getValue().getAsJsonObject().get("maps").getAsInt() > 1),
				"a tile several maps share");
		Path file = this.gameDir.resolve("keys.json");
		Files.writeString(file, keys.toText(), StandardCharsets.UTF_8);
		KeyIndex read = KeyIndex.read(file);
		assertNotNull(read);
		assertEquals(keys.toText(), read.toText(), "reads back exactly");
		Files.writeString(file, "{\"keys\": 99}", StandardCharsets.UTF_8);
		assertNull(KeyIndex.read(file), "another format");
	}

	@Test
	void theTemplateMarksEveryGeneratedFileAndSharingLeavesThemOut() throws Exception {
		Path bundle = TestData.syntheticCopyWith(this.gameDir.resolve("kantobridge").resolve("export"), Map.of());
		PackGenerator.generate(bundle, GeneratedPack.directory(this.gameDir));
		PackTemplateWriter.Result result = PackTemplateWriter.write(this.gameDir);
		Path dir = result.dir();
		assertEquals(this.gameDir.resolve("kantobridge").resolve("pack-template").resolve("red"), dir);
		assertTrue(result.textures() > 0, "reference textures");
		assertTrue(Files.isRegularFile(dir.resolve("pack.mcmeta")));
		assertTrue(Files.isRegularFile(dir.resolve("README.txt")));

		KbPackManifest.Result skeleton = KbPackManifest.parse(Files.readString(dir.resolve("assets/kantobridge/kb_pack/template.json")));
		assertEquals(Map.of(), skeleton.warnings(), "the skeleton parses as it is");
		assertEquals(Set.of("red"), skeleton.manifest().games());

		assertEquals(Files.readString(KeyIndex.file(this.gameDir, "red")), Files.readString(dir.resolve("keys.json")),
				"the key index, also beside the generated pack for the pack check");

		JsonObject listed = JsonParser.parseString(Files.readString(dir.resolve(PackTemplateWriter.GENERATED))).getAsJsonObject()
				.getAsJsonObject("files");
		Set<String> generated = new TreeSet<>();
		try (Stream<Path> files = Files.walk(dir.resolve("reference"))) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				generated.add(dir.relativize(file).toString().replace('\\', '/'));
			}
		}
		generated.add("keys.json");
		assertEquals(generated, listed.keySet(), "every reference file and the key index, nothing else");
		for (String path : generated) {
			assertEquals(Hashes.sha256(Files.readAllBytes(dir.resolve(path))), listed.get(path).getAsString(), path);
		}
		long models = generated.stream().filter(path -> path.startsWith("reference/models/")).count();
		assertEquals(result.models(), models);
		for (String path : generated) {
			if (path.endsWith(".vox")) {
				VoxFile.read(Files.readAllBytes(dir.resolve(path)));
			}
		}

		// The calibration model, meshed at 8 voxels per block: its red post north-east, its blue strip on the south edge.
		VoxMesher.Mesh calibration = VoxMesher.mesh(VoxFile.read(Files.readAllBytes(dir.resolve("reference/calibration.vox"))), 8, 0, false);
		int[] post = VoxMesherTestBounds.of(calibration, 5);
		assertEquals(List.of(14, 16, 0, 2), List.of(post[0], post[3], post[2], post[5]), "x 14..16 (east), z 0..2 (north)");
		int[] strip = VoxMesherTestBounds.of(calibration, 6);
		assertEquals(List.of(14, 16), List.of(strip[2], strip[5]), "z 14..16: the south edge");

		// A pack made from the template: the pack maker's own texture, a reference texture moved and renamed.
		Path pack = this.gameDir.resolve("resourcepacks").resolve("mine");
		copy(dir, pack);
		Path own = pack.resolve("assets/mine/textures/block/own.png");
		Files.createDirectories(own.getParent());
		Files.write(own, FakeResources.png(16, 16));
		String reference = generated.stream().filter(path -> path.startsWith("reference/textures/")).findFirst().orElseThrow();
		Files.move(pack.resolve(reference), pack.resolve("assets/mine/textures/block/traced.png"));
		PackTemplateWriter.Share share = PackTemplateWriter.share(pack, this.gameDir.resolve("kantobridge").resolve("shared").resolve("mine"));
		Set<String> copied = new TreeSet<>();
		try (Stream<Path> files = Files.walk(share.dir())) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				copied.add(share.dir().relativize(file).toString().replace('\\', '/'));
			}
		}
		assertEquals(Set.of("README.txt", "pack.mcmeta", "assets/kantobridge/kb_pack/template.json", "assets/mine/textures/block/own.png"), copied,
				"no generated file, even renamed; no key index; no generated.json");
		assertEquals(4, share.copied());
		assertEquals(generated.size() + 1, share.skipped(), "the generated files and generated.json");
	}

	private static void copy(Path from, Path to) throws java.io.IOException {
		try (Stream<Path> files = Files.walk(from)) {
			for (Path file : files.toList()) {
				Path target = to.resolve(from.relativize(file).toString());
				if (Files.isDirectory(file)) {
					Files.createDirectories(target);
				} else {
					Files.copy(file, target);
				}
			}
		}
	}

	/** The box of a colour's quads in the first block (1/16 block: x y z min, then max). */
	static final class VoxMesherTestBounds {
		static int[] of(VoxMesher.Mesh mesh, int colour) {
			int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
			for (var quad : mesh.blocks().get(dev.kantobridge.builder.shape.geom.QuadMerger.blockKey(0, 0, 0))) {
				if (quad.texture() != colour) {
					continue;
				}
				for (int corner = 0; corner < 4; corner++) {
					int[] p = {quad.x(corner), quad.y(corner), quad.z(corner)};
					for (int a = 0; a < 3; a++) {
						box[a] = Math.min(box[a], p[a]);
						box[a + 3] = Math.max(box[a + 3], p[a]);
					}
				}
			}
			return box;
		}
	}
}
