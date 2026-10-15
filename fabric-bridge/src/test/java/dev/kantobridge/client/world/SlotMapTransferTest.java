package dev.kantobridge.client.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.plan.ArtCatalog;
import dev.kantobridge.builder.plan.Warnings;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.ShapeDocs;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.WorldIndex;
import dev.kantobridge.world.WorldIndexParts;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * {@code world.slots} (M9): the world's slot map travels in parts like the WorldIndex, is put back together in any order,
 * and is accepted only when its text and the parsed map both carry the hash the parts name, the slot map the WorldIndex
 * names. Parts fit the relay and validate against the catalog.
 */
class SlotMapTransferTest {
	private static SlotMap synthetic() throws Exception {
		return ArtCatalog.of(ExportBundle.open(TestData.syntheticBundle()), new Warnings()).slotMap();
	}

	@Test
	void partsReassembleInAnyOrderIntoTheWorldsSlotMap() throws Exception {
		SlotMap map = synthetic();
		WorldIndex golden = WorldIndex.fromJson(Files.readString(TestData.goldenIndex(), StandardCharsets.UTF_8));
		assertEquals(golden.slotMapHash(), map.hash(), "the synthetic world's index names this slot map");

		List<WorldIndexParts.Part> parts = new ArrayList<>(WorldIndexParts.split(map.toJson(), 97));
		assertTrue(parts.size() > 3, "several parts");
		assertEquals(Hashes.sha256(map.toJson()), parts.get(0).hash(), "the parts' hash is the slot map's");
		Collections.shuffle(parts, new Random(9));
		// A duplicate part on the way changes nothing.
		parts.add(2, parts.get(0));
		WorldIndexParts.Assembler assembler = new WorldIndexParts.Assembler();
		SlotMap received = null;
		int done = 0;
		for (WorldIndexParts.Part part : parts) {
			SlotMap result = ClientWorldIndex.assembleSlots(assembler, part.index(), part.count(), part.hash(), part.text());
			if (result != null) {
				received = result;
				done++;
			}
		}
		assertEquals(1, done, "complete once, with the last missing part");
		assertNotNull(received);
		assertEquals(map.hash(), received.hash());
		assertEquals(map.slots(), received.slots(), "every key keeps the host's slot");
	}

	@Test
	void aCorruptedOrForeignSlotMapIsRefused() throws Exception {
		SlotMap map = synthetic();
		List<WorldIndexParts.Part> parts = WorldIndexParts.split(map.toJson(), 200);
		WorldIndexParts.Assembler broken = new WorldIndexParts.Assembler();
		for (WorldIndexParts.Part part : parts) {
			String text = part.index() == 0 ? part.text() + " " : part.text();
			if (part.index() < part.count() - 1) {
				assertNull(ClientWorldIndex.assembleSlots(broken, part.index(), part.count(), part.hash(), text));
			} else {
				assertThrows(DataException.class, () -> ClientWorldIndex.assembleSlots(broken, part.index(), part.count(), part.hash(), text),
						"a changed part fails the text hash");
			}
		}

		// Text whose hash is right but that isn't the canonical slot map (its keys reordered) parses to another hash: refused.
		String canonical = map.toJson();
		assertTrue(canonical.startsWith("{\"slots\":") && canonical.endsWith(",\"version\":1}"), "canonical: sorted keys");
		String array = canonical.substring("{\"slots\":".length(), canonical.length() - ",\"version\":1}".length());
		String reordered = "{\"version\":1,\"slots\":" + array + "}";
		assertEquals(map.hash(), SlotMap.fromJson(reordered).hash(), "the same slot map");
		String hash = Hashes.sha256(reordered);
		WorldIndexParts.Assembler assembler = new WorldIndexParts.Assembler();
		assertThrows(DataException.class, () -> ClientWorldIndex.assembleSlots(assembler, 0, 1, hash, reordered),
				"only the canonical slot map the index names is accepted");
	}

	@Test
	void anOfferedSlotMapComesDeflatedAndMustBeTheOfferedOne() throws Exception {
		// MG9 G2G: the parts that answer a world.slots.get are Base64 of the deflated canonical text; the map's own hash is
		// checked against the text it inflates to, and against the offer
		SlotMap map = synthetic();
		String wire = ShapeDocs.wire(map.toJson());
		List<WorldIndexParts.Part> parts = WorldIndexParts.split(wire, 50);
		assertTrue(parts.size() > 1);
		assertEquals(Hashes.sha256(wire), parts.get(0).hash(), "the parts' hash is the wire text's");
		WorldIndexParts.Assembler assembler = new WorldIndexParts.Assembler();
		String[] kept = new String[1];
		SlotMap received = null;
		for (WorldIndexParts.Part part : parts) {
			received = ClientWorldIndex.assembleSlots(assembler, part.index(), part.count(), part.hash(), part.text(), true, map.hash(), kept);
		}
		assertNotNull(received);
		assertEquals(map.hash(), received.hash());
		assertEquals(wire, kept[0], "the wire text, for the cache");
		// Another map than the offered one is refused, as a map whose text isn't canonical.
		WorldIndexParts.Assembler other = new WorldIndexParts.Assembler();
		WorldIndexParts.Part whole = WorldIndexParts.split(wire, 1 << 20).get(0);
		assertThrows(DataException.class, () -> ClientWorldIndex.assembleSlots(other, 0, 1, whole.hash(), whole.text(), true, "a".repeat(64), null));
		// Read back from the cache: the text must parse to the offered map.
		assertEquals(map.hash(), ClientWorldIndex.cachedSlots(map.hash(), map.toJson().getBytes(StandardCharsets.UTF_8)).hash());
		assertThrows(DataException.class, () -> ClientWorldIndex.cachedSlots("b".repeat(64), map.toJson().getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void theOffersAndGetsFitTheCatalog() throws Exception {
		Catalog catalog = Catalog.bundled();
		JsonObject offer = new JsonObject();
		offer.addProperty("hash", "a".repeat(64));
		offer.addProperty("chars", 402116);
		catalog.check(new Envelope(MsgTypes.WORLD_SLOTS_OFFER, 3, null, offer), Catalog.Leg.S2C);
		offer.addProperty("region", "johto");
		catalog.check(new Envelope(MsgTypes.WORLD_INDEX_OFFER, 4, null, offer), Catalog.Leg.S2C);
		JsonObject get = new JsonObject();
		get.addProperty("hash", "a".repeat(64));
		catalog.check(new Envelope(MsgTypes.WORLD_SLOTS_GET, 5, null, get), Catalog.Leg.C2S);
		get.addProperty("region", "johto");
		catalog.check(new Envelope(MsgTypes.WORLD_INDEX_GET, 6, null, get), Catalog.Leg.C2S);
	}

	@Test
	void aSlotsPartFitsTheRelayAndTheCatalog() throws Exception {
		Catalog catalog = Catalog.bundled();
		JsonObject data = new JsonObject();
		data.addProperty("index", 0);
		data.addProperty("count", 1);
		data.addProperty("hash", "a".repeat(64));
		data.addProperty("part", "\"".repeat(WorldIndexParts.PART_CHARS));
		Envelope message = new Envelope(MsgTypes.WORLD_SLOTS, 7, null, data);
		catalog.check(message, Catalog.Leg.S2C);
		int bytes = message.toJson().getBytes(StandardCharsets.UTF_8).length;
		assertTrue(bytes <= catalog.relayMaxBytes(), "a world.slots part of " + bytes + " bytes exceeds the relay limit");
	}
}
