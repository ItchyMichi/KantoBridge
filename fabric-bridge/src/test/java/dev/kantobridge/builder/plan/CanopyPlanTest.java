package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.shape.ShapePlanner;
import dev.kantobridge.builder.shape.dressing.Canopy;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.MapPlacement;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Viridian Forest's canopy in a built world (docs/specs/CAVES_AND_WOODS_PLAN.md §5.2, CW2): the synthetic bundle with
 * its house renamed {@code VIRIDIAN_FOREST} and open to the sky. The packer gives it the sky; its volume reaches the
 * canopy's top; every hanging stands on a foliage carrier; the enclosure ring keeps its collision below and carries the
 * leaf curtain above.
 */
class CanopyPlanTest {
	@TempDir
	static Path dir;
	private static WorldPlan plan;

	@BeforeAll
	static void plan() throws Exception {
		Path source = TestData.syntheticBundle();
		Path copy = dir.resolve("red");
		try (Stream<Path> walk = Files.walk(source)) {
			for (Path file : walk.toList()) {
				String rel = source.relativize(file).toString().replace("TEST_HOUSE.json", "VIRIDIAN_FOREST.json");
				Path out = copy.resolve(rel);
				if (Files.isDirectory(file)) {
					Files.createDirectories(out);
					continue;
				}
				String text = Files.readString(file, StandardCharsets.UTF_8).replace("\"TEST_HOUSE\"", "\"VIRIDIAN_FOREST\"");
				if (rel.endsWith("VIRIDIAN_FOREST.json")) {
					// Open to the sky, as the forest tileset makes Viridian Forest.
					JsonObject house = ProtoJson.parseObject(text);
					house.addProperty("outside", true);
					text = new String(ProtoJson.canonicalUtf8(house), StandardCharsets.UTF_8);
				}
				Files.writeString(out, text, StandardCharsets.UTF_8);
			}
		}
		JsonObject index = ProtoJson.parseObject(Files.readString(copy.resolve("index.json")));
		for (var element : index.getAsJsonArray("resources")) {
			JsonObject entry = element.getAsJsonObject();
			byte[] bytes = Files.readAllBytes(copy.resolve(entry.get("kind").getAsString() + "/" + entry.get("id").getAsString() + ".json"));
			entry.addProperty("hash", Hashes.sha256(bytes));
			entry.addProperty("size", bytes.length);
		}
		Files.writeString(copy.resolve("index.json"), ProtoJson.write(index));
		plan = WorldPlanner.plan(ExportBundle.open(copy),
				new WorldPlanner.Options(null, TestData.ORIGIN_X, TestData.ORIGIN_Z, TestData.MIN_BUILD_HEIGHT));
	}

	@Test
	void theForestGetsItsCanopyUnderTheSky() {
		MapPlacement p = plan.index().map("VIRIDIAN_FOREST");
		assertNotNull(p);
		assertTrue(WorldPlan.openSky(p), "the packer found it the sky");
		assertEquals(p.y() + Canopy.TOP_LEVEL + 1, WorldPlan.interiorVolume(p).maxY(), "the volume reaches the canopy's top");
		ShapePlanner.MapShapes planned = plan.shapes().shapes("VIRIDIAN_FOREST");
		ChunkCodesView view = new ChunkCodesView(plan);
		int cols = p.blockWidth();
		int pieces = 0;
		for (int tz = 0; tz < p.blockDepth(); tz++) {
			for (int tx = 0; tx < cols; tx++) {
				int i = tz * cols + tx;
				int mask = planned.hungMask()[i];
				for (int k = 0; k < Canopy.TOP_LEVEL; k++) {
					if ((mask >> k & 1) == 0) {
						continue;
					}
					int code = view.code(p.x() + tx, p.y() + k, p.z() + tz);
					if (BlockCode.kind(code) != BlockCode.KIND_DECOR) {
						// Something of the map's own stands there (a tuft, a story block); the plan leaves it.
						continue;
					}
					pieces++;
					assertEquals(1, BlockCode.value(code) & 1, "a foliage carrier");
					int id = plan.shapes().index().shapeId(planned.hung()[i][k], p.palette());
					assertEquals(id, plan.shapes().map().shapeAt(p.x() + tx, p.y() + k, p.z() + tz));
				}
			}
		}
		assertTrue(pieces > p.blockWidth() * p.blockDepth(), "leaves over every column, and vines");
		assertEquals(BlockCode.AIR, view.code(p.x() + 2, p.y() + Canopy.TOP_LEVEL, p.z() + 2), "nothing above the top");
	}

	@Test
	void theRingKeepsItsCollisionAndCarriesTheCurtain() {
		MapPlacement p = plan.index().map("VIRIDIAN_FOREST");
		ShapePlanner.MapShapes planned = plan.shapes().shapes("VIRIDIAN_FOREST");
		assertNotNull(planned.curtain());
		ChunkCodesView view = new ChunkCodesView(plan);
		int curtain = plan.shapes().index().shapeId(planned.curtain(), p.palette());
		List<int[]> ring = WorldPlan.ringColumns(p);
		assertEquals(2 * (p.blockWidth() + 2) + 2 * p.blockDepth(), ring.size());
		for (int[] xz : ring) {
			for (int y = p.y() - 1; y <= p.y() + WorldPlan.CEILING_ABOVE_FEET; y++) {
				assertEquals(BlockCode.KIND_FULL, view.kind(xz[0], y, xz[1]), "the ring's own blocks");
			}
			for (int k = Canopy.CURTAIN_FROM; k < Canopy.TOP_LEVEL; k++) {
				assertEquals(BlockCode.decor(true), view.code(xz[0], p.y() + k, xz[1]), "the curtain on decor carriers");
				assertEquals(curtain, plan.shapes().map().shapeAt(xz[0], p.y() + k, xz[1]));
			}
		}
	}

	@Test
	void nothingElseChanges() {
		for (String id : new String[] {"TEST_TOWN", "TEST_HILL", "TEST_HOUSE_2F"}) {
			for (int mask : plan.shapes().shapes(id).hungMask()) {
				assertEquals(0, mask, id);
			}
		}
	}
}
