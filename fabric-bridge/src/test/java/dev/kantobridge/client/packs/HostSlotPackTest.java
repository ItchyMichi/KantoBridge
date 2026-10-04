package dev.kantobridge.client.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.pack.ArtKeys;
import dev.kantobridge.builder.plan.ArtCatalog;
import dev.kantobridge.builder.plan.Warnings;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.SlotMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A joining player's pack against a host's slot map (docs/specs/VOXEL_BLOCKS_PLAN.md §4.7, §5.2): since layout 2 the pack
 * holds textures by key and art set only, so the same pack fits a host whose slots differ. Every key the host's slot map
 * names that this export has is a texture of the pack under this player's art set; a key the export lacks is simply
 * missing (drawn as a placeholder, counted), and nothing is rebuilt for the host.
 */
class HostSlotPackTest {
	/** The host's map: the same keys, other slots (reversed), plus a key this export doesn't have. */
	static SlotMap hostMap(SlotMap own) throws Exception {
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

	@Test
	void onePackFitsAHostWithOtherSlots(@TempDir Path gameDir) throws Exception {
		ExportBundle bundle = ExportBundle.open(TestData.syntheticBundle());
		SlotMap own = ArtCatalog.of(bundle, new Warnings()).slotMap();
		SlotMap host = hostMap(own);
		assertNotEquals(own.hash(), host.hash(), "the host's slot map differs");

		Path packDir = GeneratedPack.directory(gameDir);
		PackGenerator.Result result = PackGenerator.generate(TestData.syntheticBundle(), packDir);
		PackInfo info = GeneratedPack.info(gameDir);
		assertNotNull(info);
		assertTrue(info.usable(), "layout 2 with an art set");
		assertEquals("red", info.artSet(), "the art set is the export's game");
		assertEquals(bundle.exportHash(), info.exportHash());
		assertEquals(result.pack().textures(), info.textures());

		// Every key of the host's map: this player's texture of it under their art set, whatever slot the host gave it.
		int present = 0;
		int missing = 0;
		for (SlotMap.Key key : host.slots().keySet()) {
			Path file = packDir.resolve(ArtKeys.textureFile(info.artSet(), key.atlas(), key.palette(), key.tile()));
			if (Files.isRegularFile(file)) {
				present++;
			} else {
				missing++;
				assertEquals("tilesets.host_only", key.atlas(), "only the key this export lacks is missing");
			}
		}
		assertEquals(own.size(), present);
		assertEquals(1, missing);

		// No file of the pack depends on a slot map: no blockstates, no models, nothing that names a slot.
		try (var files = Files.walk(packDir)) {
			for (Path path : (Iterable<Path>) files::iterator) {
				String rel = packDir.relativize(path).toString().replace('\\', '/');
				assertFalse(rel.startsWith("assets/kantobridge/blockstates"), rel);
				assertFalse(rel.startsWith("assets/kantobridge/models"), rel);
			}
		}

		// The texture flow keeps that pack for the host's world: no export while it lacks nothing but the host-only key...
		TextureSync.Inputs host1 = new TextureSync.Inputs("red", host.hash(), info, true, missing, false);
		assertEquals(TextureSync.Decision.INCOMPLETE, TextureSync.decide(host1, false), "placeholders for the one key, no export");
		TextureSync.Inputs host2 = new TextureSync.Inputs("blue", host.hash(), info, true, 0, false);
		assertEquals(TextureSync.Decision.READY, TextureSync.decide(host2, false), "a Red pack draws a Blue world");
	}
}
