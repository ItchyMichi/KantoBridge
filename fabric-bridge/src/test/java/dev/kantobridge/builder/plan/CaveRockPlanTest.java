package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.shape.ShapeKey;
import dev.kantobridge.builder.shape.ShapePlanner;
import dev.kantobridge.builder.shape.dressing.CaveRock;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.MapPlacement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cave rock in a built world (docs/specs/CAVES_AND_WOODS_PLAN.md §5.1, CW1): the synthetic bundle with its house drawn
 * as a cave. Every hanging stands on a decor carrier in the block under the ceiling, the carrier on a light's position
 * gives the light (and no light block is placed there), the shape map draws the piece there, and nothing a player
 * could collide with is added.
 */
class CaveRockPlanTest {
	@TempDir
	static Path dir;
	private static WorldPlan plan;

	@BeforeAll
	static void plan() throws Exception {
		Path red = TestData.syntheticBundle();
		JsonObject tileset = ProtoJson.parseObject(Files.readString(red.resolve("tileset/TEST.json")));
		tileset.addProperty("id", "CAVERN");
		JsonObject house = ProtoJson.parseObject(Files.readString(red.resolve("map/TEST_HOUSE.json")));
		house.addProperty("tileset", "CAVERN");
		Path copy = TestData.syntheticCopyWith(dir, Map.of("tileset/CAVERN", tileset, "map/TEST_HOUSE", house));
		plan = WorldPlanner.plan(ExportBundle.open(copy),
				new WorldPlanner.Options(null, TestData.ORIGIN_X, TestData.ORIGIN_Z, TestData.MIN_BUILD_HEIGHT));
	}

	@Test
	void hangingsStandOnDecorCarriersUnderTheCeiling() {
		MapPlacement p = plan.index().map("TEST_HOUSE");
		assertEquals(WorldPlan.BIOME_CAVE, p.biome());
		ShapePlanner.MapShapes planned = plan.shapes().shapes("TEST_HOUSE");
		assertNotNull(planned);
		ChunkCodesView view = new ChunkCodesView(plan);
		int feet = p.y();
		int cols = p.blockWidth();
		int hung = 0;
		int lit = 0;
		for (int tz = 0; tz < p.blockDepth(); tz++) {
			for (int tx = 0; tx < cols; tx++) {
				int i = tz * cols + tx;
				int x = p.x() + tx;
				int z = p.z() + tz;
				int code = view.code(x, feet + CaveRock.LEVEL, z);
				boolean lightSpot = Math.floorMod(tx, WorldPlan.LIGHT_SPACING) == 1 && Math.floorMod(tz, WorldPlan.LIGHT_SPACING) == 1;
				if ((planned.hungMask()[i] >> CaveRock.LEVEL & 1) == 0) {
					continue;
				}
				hung++;
				assertEquals(BlockCode.KIND_DECOR, BlockCode.kind(code), "a decor carrier at " + tx + "," + tz);
				ShapeKey key = planned.hung()[i][CaveRock.LEVEL];
				int id = plan.shapes().index().shapeId(key, p.palette());
				assertEquals(id, plan.shapes().map().shapeAt(x, feet + CaveRock.LEVEL, z), "the shape map draws the piece there");
				if (lightSpot) {
					assertEquals(BlockCode.decor(false, true), code, "the carrier on a light's position gives the light");
					lit++;
				} else {
					assertEquals(BlockCode.decor(false, false), code);
				}
				assertTrue(BlockCode.kind(view.code(x, feet + WorldPlan.CEILING_ABOVE_FEET, z)) == BlockCode.KIND_FULL, "the ceiling stays");
			}
		}
		assertTrue(hung > 0, "the house is a cave now");
		assertTrue(lit > 0, "some hangings took a light's position");
		assertTrue(plan.shapes().unitTally().getOrDefault("dress_rock_roof", 0) > 0);
	}

	@Test
	void noOtherMapGetsRock() {
		for (String id : new String[] {"TEST_TOWN", "TEST_HILL", "TEST_HOUSE_2F"}) {
			ShapePlanner.MapShapes planned = plan.shapes().shapes(id);
			for (int mask : planned.hungMask()) {
				assertEquals(0, mask, id);
			}
		}
	}
}
