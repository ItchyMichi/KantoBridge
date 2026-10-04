package dev.kantobridge.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.shape.ShapeKey;
import dev.kantobridge.builder.shape.ShapeParams;
import dev.kantobridge.proto.ProtoJson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The shape index (docs/specs/VOXEL_BLOCKS_PLAN.md §4.2): sorted keys and instances, geom id = index, shape id = index
 * + 1; the planner and its parameters inside the hash; canonical JSON that reads back to the same hash; anything
 * malformed refused.
 */
class ShapeIndexTest {
	private static final List<String> ATLASES = List.of("tilesets.b", "tilesets.a");
	private static final List<String> PALETTES = List.of("PAL_B", "PAL_A");

	private static List<ShapeIndex.Use> uses() {
		// tilesets.a is atlas 0 once sorted, tilesets.b atlas 1.
		return List.of(new ShapeIndex.Use(ShapeKey.cube(1, 3, 3, 3, 5, 3, 3), "PAL_A"), new ShapeIndex.Use(ShapeKey.cube(0, 9, 9, 9, 9, 9, 10), "PAL_B"),
				new ShapeIndex.Use(ShapeKey.cube(0, 9, 9, 9, 9, 9, 10), "PAL_A"), new ShapeIndex.Use(ShapeKey.slab(0, 4, 8), "PAL_B"),
				new ShapeIndex.Use(ShapeKey.cube(1, 3, 3, 3, 5, 3, 3), "PAL_A"));
	}

	private static ShapeIndex index(ShapeParams params, List<ShapeIndex.Use> uses) {
		return ShapeIndex.of(params.planner(), params.toJson(), ATLASES, PALETTES, uses, new JsonArray());
	}

	@Test
	void keysAndInstancesAreSortedAndNumbered() {
		ShapeIndex index = index(ShapeParams.v2(), uses());
		assertEquals(List.of("tilesets.a", "tilesets.b"), index.atlases());
		assertEquals(List.of("PAL_A", "PAL_B"), index.palettes());
		assertEquals(List.of("cube:0|9,9,9,9,9,10", "cube:1|3,3,3,5,3,3", "slab:0|4|8"), index.geoms().stream().map(ShapeKey::text).toList());
		assertEquals(List.of(new ShapeIndex.Instance(0, 0), new ShapeIndex.Instance(0, 1), new ShapeIndex.Instance(1, 0),
				new ShapeIndex.Instance(2, 1)), index.instances(), "duplicates merged, sorted by geom then palette");
		assertEquals(4, index.size());
		assertEquals(1, index.shapeId(ShapeKey.cube(0, 9, 9, 9, 9, 9, 10), "PAL_A"), "shape id = index + 1");
		assertEquals(2, index.shapeId(ShapeKey.cube(0, 9, 9, 9, 9, 9, 10), "PAL_B"));
		assertEquals(4, index.shapeId(ShapeKey.slab(0, 4, 8), "PAL_B"));
		assertEquals(0, index.shapeId(ShapeKey.slab(0, 4, 8), "PAL_A"), "a shape the index doesn't hold");
		assertEquals(0, index.shapeId(ShapeKey.slab(0, 4, 8), "PAL_C"));
		assertEquals("slab:0|4|8", index.key(4).text());
		assertEquals("PAL_B", index.palette(4));
		assertNull(index.key(0), "id 0 is \"no shape\"");
		assertNull(index.key(5));
		assertEquals("tilesets.b", index.atlas(index.key(3).atlas()));
	}

	@Test
	void theOrderOfUsesNeverMatters() {
		String hash = index(ShapeParams.v2(), uses()).hash();
		for (int seed = 0; seed < 5; seed++) {
			List<ShapeIndex.Use> shuffled = new ArrayList<>(uses());
			Collections.shuffle(shuffled, new Random(seed));
			assertEquals(hash, index(ShapeParams.v2(), shuffled).hash());
		}
	}

	@Test
	void jsonReadsBackToTheSameIndex() throws Exception {
		ShapeIndex index = index(ShapeParams.v2(), uses());
		String json = index.toJson();
		assertEquals(Hashes.sha256(json), index.hash());
		assertEquals(ProtoJson.write(ProtoJson.parse(json)), json, "canonical: sorted keys, no spaces");
		ShapeIndex read = ShapeIndex.fromJson(json);
		assertEquals(json, read.toJson());
		assertEquals(index.hash(), read.hash());
		assertEquals("kb-shapes/1", read.planner());
		assertEquals("classified", read.params().get("heights").getAsString());
		assertTrue(json.contains("\"geoms\":[\"cube:0|9,9,9,9,9,10\",\"cube:1|3,3,3,5,3,3\",\"slab:0|4|8\"]"));
		assertTrue(json.contains("\"instances\":[[0,0],[0,1],[1,0],[2,1]]"));
	}

	@Test
	void thePlannerAndItsParametersAreInsideTheHash() {
		ShapeParams v2 = ShapeParams.v2();
		String hash = index(v2, uses()).hash();
		ShapeParams otherPlanner = new ShapeParams("kb-shapes/2", v2.heights(), v2.maxUnit(), v2.minRepeatRows(), v2.hullRing(),
				v2.interiorWall(), v2.tierB(), v2.tierC());
		ShapeParams otherParams = new ShapeParams(v2.planner(), ShapeParams.MEASURED, v2.maxUnit(), v2.minRepeatRows(), v2.hullRing(),
				v2.interiorWall(), v2.tierB(), v2.tierC());
		ShapeParams otherRing = new ShapeParams(v2.planner(), v2.heights(), v2.maxUnit(), v2.minRepeatRows(), 3, v2.interiorWall(), v2.tierB(),
				v2.tierC());
		assertNotEquals(hash, index(otherPlanner, uses()).hash());
		assertNotEquals(hash, index(otherParams, uses()).hash());
		assertNotEquals(hash, index(otherRing, uses()).hash());
	}

	@Test
	void malformedIndexesAreRefused() throws Exception {
		JsonObject good = ProtoJson.parseObject(index(ShapeParams.v2(), uses()).toJson());
		assertThrows(DataException.class, () -> ShapeIndex.fromJson("{"));
		List<java.util.function.Consumer<JsonObject>> breakages = List.of(
				o -> o.addProperty("version", 2),
				o -> o.addProperty("planner", "Shapes 1"),
				o -> o.addProperty("extra", 1),
				o -> o.remove("geoms"),
				o -> {
					// Unsorted atlases would change what every key's atlas index means.
					JsonArray atlases = new JsonArray();
					atlases.add("tilesets.b");
					atlases.add("tilesets.a");
					o.add("atlases", atlases);
				},
				o -> o.getAsJsonArray("geoms").set(0, new com.google.gson.JsonPrimitive("cube:0|9,9,9,9,9")),
				o -> o.getAsJsonArray("geoms").set(0, new com.google.gson.JsonPrimitive("cube:5|9,9,9,9,9,10")),
				o -> o.getAsJsonArray("geoms").set(1, new com.google.gson.JsonPrimitive("cube:0|9,9,9,9,9,10")),
				o -> o.getAsJsonArray("instances").get(0).getAsJsonArray().set(0, new com.google.gson.JsonPrimitive(7)),
				o -> o.getAsJsonArray("instances").get(0).getAsJsonArray().set(1, new com.google.gson.JsonPrimitive(2)),
				o -> o.getAsJsonArray("instances").add(o.getAsJsonArray("instances").get(0).deepCopy()),
				o -> o.getAsJsonArray("instances").remove(3),
				o -> o.getAsJsonArray("instances").set(0, new com.google.gson.JsonPrimitive(0)));
		for (int i = 0; i < breakages.size(); i++) {
			JsonObject broken = good.deepCopy();
			breakages.get(i).accept(broken);
			String text = ProtoJson.write(broken);
			assertThrows(DataException.class, () -> ShapeIndex.fromJson(text), "breakage " + i);
		}
	}
}
