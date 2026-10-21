package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.shape.ShapeKey;
import dev.kantobridge.builder.shape.ShapePlanner;
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
 * could collide with is added: a carrier over rock is solid, as the cave area fills the air between rock and ceiling
 * (docs/specs/CAVES_3D_PLAN.md §3.3), but rock stops a player anyway. Built in 3D (every cave since H7), the house's
 * stairs are a real ladder down from its upper floor: the shaft's column takes no hanging.
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
		assertEquals(WorldPlan.BIOME_CAVE_3D, p.biome(), "a cave map goes to the cave area");
		MapModel model = plan.map("TEST_HOUSE").model();
		ShapePlanner.MapShapes planned = plan.shapes().shapes("TEST_HOUSE");
		assertNotNull(planned);
		ChunkCodesView view = new ChunkCodesView(plan);
		int feet = p.y();
		// The block under its ceiling (HEIGHTS_ALL_GAMES_PLAN D5: a cave's ceiling follows its floor, here a flat one)
		int level = WorldPlan.room(plan.index().layout("TEST_HOUSE"), 1, 1) - 1;
		assertTrue(level >= Headroom.MIN - 1, "at least 4 blocks of air");
		int cols = p.blockWidth();
		int hung = 0;
		int lit = 0;
		int shafted = 0;
		for (int tz = 0; tz < p.blockDepth(); tz++) {
			for (int tx = 0; tx < cols; tx++) {
				int i = tz * cols + tx;
				int x = p.x() + tx;
				int z = p.z() + tz;
				int code = view.code(x, feet + level, z);
				boolean lightSpot = Math.floorMod(tx, WorldPlan.LIGHT_SPACING) == 1 && Math.floorMod(tz, WorldPlan.LIGHT_SPACING) == 1;
				if ((planned.hungMask()[i] >> level & 1) == 0) {
					continue;
				}
				if (plan.index().shaftAt(x, feet + level, z) != null) {
					// The ladder's shaft (CAVES_3D_PLAN §3.5): its ladder and air are the builder's, and the shape map draws nothing
					assertTrue(BlockCode.kind(code) == BlockCode.KIND_LADDER || code == BlockCode.AIR, "the shaft at " + tx + "," + tz);
					assertEquals(0, plan.shapes().map().shapeAt(x, feet + level, z), "no hanging in the shaft");
					shafted++;
					continue;
				}
				hung++;
				assertEquals(BlockCode.KIND_DECOR, BlockCode.kind(code), "a decor carrier at " + tx + "," + tz);
				ShapeKey key = planned.hung()[i][level];
				int id = plan.shapes().index().shapeId(key, p.palette());
				assertEquals(id, plan.shapes().map().shapeAt(x, feet + level, z), "the shape map draws the piece there");
				if (lightSpot && level == WorldPlan.LIGHT_ABOVE_FEET) {
					assertEquals(BlockCode.decor(false, true, rock(model, tx, tz)), code, "the carrier on a light's position gives the light");
					lit++;
				} else {
					assertEquals(BlockCode.decor(false, false, rock(model, tx, tz)), code);
				}
				assertTrue(BlockCode.kind(view.code(x, feet + level + 1, z)) == BlockCode.KIND_FULL, "the ceiling stays");
			}
		}
		assertTrue(hung > 0, "the house is a cave now");
		assertEquals(1, plan.index().shafts().stream().filter(s -> s.bottom().map().equals("TEST_HOUSE")).count(), "its stairs, a ladder");
		assertTrue(shafted > 0, "the shaft's column took the hangings planned there");
		// Under a ceiling higher than the light's own block (D5), the hangings hang over the lights
		assertTrue(lit > 0 || level != WorldPlan.LIGHT_ABOVE_FEET, "some hangings took a light's position");
		assertTrue(plan.shapes().unitTally().getOrDefault("dress_rock_roof", 0) > 0);
	}

	private static boolean rock(MapModel model, int tx, int tz) {
		MapModel.Kind kind = model.kind(tx / 2, tz / 2);
		return kind == MapModel.Kind.SOLID || kind == MapModel.Kind.COUNTER;
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
