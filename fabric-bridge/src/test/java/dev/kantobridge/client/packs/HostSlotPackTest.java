package dev.kantobridge.client.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.pack.ResourcePackWriter;
import dev.kantobridge.builder.plan.ArtCatalog;
import dev.kantobridge.builder.plan.Warnings;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.SlotMap;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A joining player's pack (docs/PLAN.md §4.3 "Multiplayer clients", M9): their own art, built into the <b>host's</b> slot
 * map. Every slot the host's blocks name shows this player's texture of the same (atlas, palette, tile); the pack says
 * it was made for the host's slot map; a key this export lacks gets a blank tile and nothing fails.
 */
class HostSlotPackTest {
	/** The host's map: the same keys, other slots (reversed), plus a key this export doesn't have. */
	private static SlotMap hostMap(SlotMap own) throws Exception {
		List<Map.Entry<SlotMap.Key, SlotMap.Slot>> entries = new ArrayList<>(own.slots().entrySet());
		JsonArray slots = new JsonArray();
		int full = (int) entries.stream().filter(e -> e.getKey().shape() == SlotMap.Shape.FULL).count() + 1;
		int half = (int) entries.stream().filter(e -> e.getKey().shape() == SlotMap.Shape.HALF).count();
		for (Map.Entry<SlotMap.Key, SlotMap.Slot> entry : entries) {
			SlotMap.Key key = entry.getKey();
			int count = key.shape() == SlotMap.Shape.FULL ? full : half;
			slots.add(item(key, count - 1 - entry.getValue().index()));
		}
		// The host's export has one more tile; it takes the slot reversing left free (index 0).
		slots.add(item(new SlotMap.Key("tilesets.host_only", "TEST_PALETTE", 7, SlotMap.Shape.FULL), 0));
		JsonObject root = new JsonObject();
		root.addProperty("version", SlotMap.VERSION);
		root.add("slots", slots);
		return SlotMap.fromJson(ProtoJson.write(root));
	}

	private static JsonObject item(SlotMap.Key key, int index) {
		JsonObject item = new JsonObject();
		item.addProperty("atlas", key.atlas());
		item.addProperty("palette", key.palette());
		item.addProperty("tile", key.tile());
		item.addProperty("shape", key.shape().id());
		item.addProperty("family", index / SlotMap.SLOTS_PER_FAMILY);
		item.addProperty("slot", index % SlotMap.SLOTS_PER_FAMILY);
		return item;
	}

	private static String variantModel(Path packDir, SlotMap.Slot slot) throws Exception {
		JsonObject blockstate = ProtoJson.parseObject(Files.readString(
				packDir.resolve("assets/kantobridge/blockstates/" + slot.blockPath() + ".json"), StandardCharsets.UTF_8));
		return blockstate.getAsJsonObject("variants").getAsJsonObject("hi=" + slot.hi() + ",lo=" + slot.lo()).get("model").getAsString();
	}

	private static String modelTexture(Path packDir, String model) throws Exception {
		String path = model.substring(model.indexOf(':') + 1);
		JsonObject json = ProtoJson.parseObject(Files.readString(packDir.resolve("assets/kantobridge/models/" + path + ".json"),
				StandardCharsets.UTF_8));
		JsonObject textures = json.getAsJsonObject("textures");
		return (textures.has("all") ? textures.get("all") : textures.get("tile")).getAsString();
	}

	@Test
	void ownArtIsDrawnIntoTheHostsSlots(@TempDir Path gameDir) throws Exception {
		ExportBundle bundle = ExportBundle.open(TestData.syntheticBundle());
		SlotMap own = ArtCatalog.of(bundle, new Warnings()).slotMap();
		SlotMap host = hostMap(own);
		assertNotEquals(own.hash(), host.hash(), "the host's slot map differs");

		Path packDir = GeneratedPack.directory(gameDir);
		PackGenerator.Result result = PackGenerator.generate(TestData.syntheticBundle(), packDir, host);
		assertEquals(host.hash(), result.slotMapHash(), "built against the host's slot map, never the export's own");
		PackInfo info = GeneratedPack.info(gameDir);
		assertNotNull(info);
		assertEquals(host.hash(), info.slotMapHash(), "kantobridge.json names the host's slot map");
		assertEquals(bundle.exportHash(), info.exportHash(), "the art is still this player's own export");
		assertEquals(result.pack().textures(), info.textures());

		// Every slot the host's blocks name shows this export's texture of its key.
		int checked = 0;
		for (Map.Entry<SlotMap.Key, SlotMap.Slot> entry : host.slots().entrySet()) {
			String model = variantModel(packDir, entry.getValue());
			assertEquals(String.format(Locale.ROOT, "kantobridge:block/tile/%s/%03d", entry.getValue().blockPath(), entry.getValue().slot()), model);
			assertEquals(ResourcePackWriter.texture(entry.getKey()), modelTexture(packDir, model));
			assertTrue(Files.isRegularFile(packDir.resolve("assets/kantobridge/textures/"
					+ ResourcePackWriter.texture(entry.getKey()).substring("kantobridge:".length()) + ".png")), "texture file");
			checked++;
		}
		assertEquals(host.size(), checked);

		// The same art as the player's own pack: a tile's texture file is byte for byte the same.
		Path ownDir = gameDir.resolve("own");
		PackGenerator.generate(TestData.syntheticBundle(), ownDir);
		SlotMap.Key sample = own.slots().keySet().iterator().next();
		String file = "assets/kantobridge/textures/" + ResourcePackWriter.texture(sample).substring("kantobridge:".length()) + ".png";
		assertTrue(java.util.Arrays.equals(Files.readAllBytes(ownDir.resolve(file)), Files.readAllBytes(packDir.resolve(file))),
				"the host's slot numbers change where the art goes, not the art");
	}
}
