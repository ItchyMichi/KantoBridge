package dev.kantobridge.client.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.builder.plan.WorldPlanner;
import dev.kantobridge.builder.shape.ShapePlan;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.WorldIndexParts;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code world.shapes} on the client (docs/specs/VOXEL_BLOCKS_PLAN.md §4.4): both documents reassemble from parts in any
 * order; the pair is accepted only for the world index the client holds and the exact index text received; anything else
 * keeps cubes with a reason; a part fits the relay and the catalog.
 */
class ShapeCheckTest {
	private static WorldPlan plan;
	private static String worldIndexHash;

	@BeforeAll
	static void plan() throws Exception {
		plan = WorldPlanner.plan(ExportBundle.open(TestData.syntheticBundle()),
				new WorldPlanner.Options(null, TestData.ORIGIN_X, TestData.ORIGIN_Z, TestData.MIN_BUILD_HEIGHT));
		worldIndexHash = Hashes.sha256(plan.index().toJson());
	}

	private static String reassemble(String text, long seed) throws Exception {
		List<WorldIndexParts.Part> parts = new ArrayList<>(WorldIndexParts.split(text, 211));
		Collections.shuffle(parts, new Random(seed));
		WorldIndexParts.Assembler assembler = new WorldIndexParts.Assembler();
		String out = null;
		for (WorldIndexParts.Part part : parts) {
			String done = assembler.accept(part.index(), part.count(), part.hash(), part.text());
			if (done != null) {
				out = done;
			}
		}
		return out;
	}

	@Test
	void thePairOfThisWorldIsAccepted() throws Exception {
		ShapePlan shapes = plan.shapes();
		String index = reassemble(shapes.index().toJson(), 1);
		String map = reassemble(shapes.map().wire(), 2);
		ShapeCheck.Accepted accepted = ShapeCheck.check(index, map, worldIndexHash, 1 << 24);
		assertEquals(shapes.index().hash(), accepted.index().hash());
		assertEquals(shapes.map().hash(), accepted.map().hash());
	}

	@Test
	void anyOtherPairKeepsCubes() throws Exception {
		ShapePlan shapes = plan.shapes();
		String index = shapes.index().toJson();
		String map = shapes.map().wire();
		assertEquals(ShapeCheck.MISMATCH, rejected(index, map, "0".repeat(64)), "another world index");
		assertEquals(ShapeCheck.MISMATCH, rejected(index, map, null), "no world index yet");
		// The same index, written with its keys in another order: the same content, but not the text the map names.
		JsonObject reordered = ProtoJson.parseObject(index);
		JsonObject copy = new JsonObject();
		for (String key : new ArrayList<>(reordered.keySet()).reversed()) {
			copy.add(key, reordered.get(key));
		}
		String other = new com.google.gson.Gson().toJson(copy);
		assertEquals(ShapeCheck.MISMATCH, rejected(other, map, worldIndexHash));
		assertEquals(ShapeCheck.UNSUPPORTED, rejected(index.replace("\"version\":1", "\"version\":9"), map, worldIndexHash));
		assertEquals(ShapeCheck.UNSUPPORTED, rejected("{}", map, worldIndexHash));
		assertEquals(ShapeCheck.MISMATCH, rejected(index, "AAAA", worldIndexHash), "a broken map");
		assertEquals(ShapeCheck.MISMATCH, rejected(index, map, worldIndexHash, 100), "a map too large");
		// A map bound to another shape index.
		String foreign = shapes.map().withHeader(worldIndexHash, "e".repeat(64)).wire();
		assertEquals(ShapeCheck.MISMATCH, rejected(index, foreign, worldIndexHash));
	}

	private static String rejected(String index, String map, String worldHash) {
		return rejected(index, map, worldHash, 1 << 24);
	}

	private static String rejected(String index, String map, String worldHash, int max) {
		return assertThrows(ShapeCheck.Rejected.class, () -> ShapeCheck.check(index, map, worldHash, max)).code();
	}

	@Test
	void aShapesPartFitsTheRelayAndTheCatalog() throws Exception {
		Catalog catalog = Catalog.bundled();
		for (String doc : List.of("index", "map")) {
			JsonObject data = new JsonObject();
			data.addProperty("doc", doc);
			data.addProperty("index", 0);
			data.addProperty("count", 1);
			data.addProperty("hash", "a".repeat(64));
			data.addProperty("part", "\"".repeat(WorldIndexParts.PART_CHARS));
			Envelope message = new Envelope(MsgTypes.WORLD_SHAPES, 7, null, data);
			catalog.check(message, Catalog.Leg.S2C);
			int bytes = message.toJson().getBytes(StandardCharsets.UTF_8).length;
			assertEquals(true, bytes <= catalog.relayMaxBytes(), "a world.shapes part of " + bytes + " bytes exceeds the relay limit");
		}
	}
}
