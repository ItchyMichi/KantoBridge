package dev.kantobridge.builder;

import dev.kantobridge.builder.export.ExportMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Shared test data: the GameTests' synthetic export bundle (made up, safe to read and print) and the golden WorldIndex
 * of its build, both under {@code src/gametest/resources/kantobridge/} (Gradle passes the folder as
 * {@code kb.gametestResources}).
 */
public final class TestData {
	/** The synthetic GameTest build's origin; {@code SyntheticBuildGameTests.ORIGIN_X/Z} must be the same. */
	public static final int ORIGIN_X = 3_200_000;
	public static final int ORIGIN_Z = 3_200_000;
	/** The deep overworld's lowest block (P4). */
	public static final int MIN_BUILD_HEIGHT = -256;

	private TestData() {
	}

	public static Path gametestResources() {
		String dir = System.getProperty("kb.gametestResources");
		if (dir == null) {
			throw new IllegalStateException("kb.gametestResources is not set (run the tests through Gradle)");
		}
		return Path.of(dir);
	}

	public static Path syntheticBundle() {
		Path dir = gametestResources().resolve("kantobridge/synthetic_bundle/red");
		if (!Files.isRegularFile(dir.resolve("index.json"))) {
			throw new IllegalStateException("synthetic bundle missing");
		}
		return dir;
	}

	/** MG7: the synthetic Gen 3 build's origin; {@code Gen3BuildGameTests.ORIGIN_X/Z} must be the same. */
	public static final int G3_ORIGIN_X = 4_800_000;
	public static final int G3_ORIGIN_Z = 3_200_000;

	/** MG7: the synthetic Gen 3 export (tools/fake_gen1/synth3.py, a tiny made-up Hoenn). */
	public static Path syntheticGen3Bundle() {
		Path dir = gametestResources().resolve("kantobridge/synthetic_bundle/emerald");
		if (!Files.isRegularFile(dir.resolve("index.json"))) {
			throw new IllegalStateException("synthetic Gen 3 bundle missing");
		}
		return dir;
	}

	/** MG7: the synthetic Gen 3 export's scene (Gen3FrontEnd), build index and shapes. */
	public static Path goldenG3Scene() {
		return gametestResources().resolve("kantobridge/golden/synthetic_g3_scene.json");
	}

	public static Path goldenG3Index() {
		return gametestResources().resolve("kantobridge/golden/synthetic_g3_world_index.json");
	}

	public static Path goldenG3Shapes() {
		return gametestResources().resolve("kantobridge/golden/synthetic_g3_shapes.json");
	}

	public static Path goldenIndex() {
		return gametestResources().resolve("kantobridge/golden/synthetic_world_index.json");
	}

	/** The synthetic bundle's scene, in its serialised form (docs/specs/VOXEL_BLOCKS_PLAN.md §5.3). */
	public static Path goldenScene() {
		return gametestResources().resolve("kantobridge/golden/synthetic_scene.json");
	}

	/** The synthetic build's shape index and shape map hash (docs/specs/VOXEL_BLOCKS_PLAN.md §6.1). */
	public static Path goldenShapes() {
		return gametestResources().resolve("kantobridge/golden/synthetic_shapes.json");
	}

	/** Compares {@code actual} with a golden file, rewriting it first when the tests run with {@code -Pkb.updateGolden=true}. */
	public static void assertGolden(Path golden, String actual, String what) throws java.io.IOException {
		if (updateGolden()) {
			Files.createDirectories(golden.getParent());
			Files.writeString(golden, actual, java.nio.charset.StandardCharsets.UTF_8);
		}
		if (!Files.isRegularFile(golden)) {
			throw new AssertionError("golden " + what + " missing: run the tests with -Pkb.updateGolden=true once");
		}
		org.junit.jupiter.api.Assertions.assertEquals(Files.readString(golden, java.nio.charset.StandardCharsets.UTF_8), actual,
				"the synthetic " + what + " changed; review and update the golden");
	}

	public static boolean updateGolden() {
		return Boolean.parseBoolean(System.getProperty("kb.updateGolden", "false"));
	}

	/**
	 * A copy of the synthetic bundle in {@code dir} with extra resources (kind/id → data) added to it and its index, with
	 * correct hashes and sizes. A resource the bundle already has (e.g. {@code swaps/all}) is replaced.
	 */
	public static Path syntheticCopyWith(Path dir, Map<String, com.google.gson.JsonObject> extra) throws java.io.IOException {
		Path source = syntheticBundle();
		Path copy = dir.resolve("red");
		try (java.util.stream.Stream<Path> walk = Files.walk(source)) {
			for (Path file : walk.toList()) {
				Path out = copy.resolve(source.relativize(file).toString());
				if (Files.isDirectory(file)) {
					Files.createDirectories(out);
				} else {
					Files.copy(file, out);
				}
			}
		}
		com.google.gson.JsonObject index;
		try {
			index = dev.kantobridge.proto.ProtoJson.parseObject(Files.readString(copy.resolve("index.json")));
		} catch (dev.kantobridge.proto.ProtocolException e) {
			throw new java.io.IOException(e);
		}
		for (Map.Entry<String, com.google.gson.JsonObject> resource : extra.entrySet()) {
			byte[] bytes = dev.kantobridge.proto.ProtoJson.canonicalUtf8(resource.getValue());
			Path file = copy.resolve(resource.getKey() + ".json");
			Files.createDirectories(file.getParent());
			Files.write(file, bytes);
			com.google.gson.JsonObject entry = new com.google.gson.JsonObject();
			entry.addProperty("kind", resource.getKey().substring(0, resource.getKey().indexOf('/')));
			entry.addProperty("id", resource.getKey().substring(resource.getKey().indexOf('/') + 1));
			entry.addProperty("hash", dev.kantobridge.world.Hashes.sha256(bytes));
			entry.addProperty("size", bytes.length);
			com.google.gson.JsonArray resources = index.getAsJsonArray("resources");
			for (int i = resources.size() - 1; i >= 0; i--) {
				com.google.gson.JsonObject old = resources.get(i).getAsJsonObject();
				if (old.get("kind").getAsString().equals(entry.get("kind").getAsString())
						&& old.get("id").getAsString().equals(entry.get("id").getAsString())) {
					resources.remove(i);
				}
			}
			resources.add(entry);
		}
		Files.writeString(copy.resolve("index.json"), dev.kantobridge.proto.ProtoJson.write(index));
		return copy;
	}

	/** A bare map for layout and packing tests: all-zero blocks and flags, no warps or objects. */
	public static ExportMap map(String id, int width, int height, boolean surface, Map<ExportMap.Direction, ExportMap.Connection> connections) {
		int[][] cells = new int[height * 2][width * 2];
		return new ExportMap(id, 0, "TEST", width, height, new int[width * height], 0, surface, surface, connections, List.of(), List.of(),
				List.of(), cells, null, null, false, "TEST_PALETTE");
	}
}
