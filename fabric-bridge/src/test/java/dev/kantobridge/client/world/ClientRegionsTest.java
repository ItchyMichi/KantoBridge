package dev.kantobridge.client.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.world.DataException;
import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.RegionRecord;
import dev.kantobridge.world.RegionTable;
import dev.kantobridge.world.Regions;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.WorldIndex;
import dev.kantobridge.world.WorldIndexParts;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The client's regions (MULTI_GAME_PLAN §4.6, MG3) as {@link RegionDocuments} assembles them: a legacy world's one
 * untagged index makes the implicit region; a region world's {@code world.regions} document, then each region's tagged
 * index and shape pair; "which region am I in"; the followed region; epochs on every change.
 */
class ClientRegionsTest {
	private static final String HASH = "0123456789abcdef".repeat(4);
	private static final String HASH_B = "fedcba9876543210".repeat(4);

	/** Parts received, each with the listener's epoch at that moment. */
	private static final class Watch implements Runnable {
		final List<Long> epochs = new ArrayList<>();
		final RegionDocuments docs;

		Watch(RegionDocuments docs) {
			this.docs = docs;
			docs.addListener(this);
		}

		@Override
		public void run() {
			this.epochs.add(this.docs.epoch());
		}
	}

	private static WorldIndex index(String game, int x) {
		MapPlacement town = new MapPlacement("TEST_TOWN", x, 64, 200, 20, 18, 0, MapPlacement.Kind.SURFACE, "TEST_PALETTE",
				"kantobridge:kanto_outdoor", false, null);
		return new WorldIndex(game, HASH, List.of(town), HASH_B, StoryCellTable.of(List.of()), List.of());
	}

	private static RegionRecord kanto() {
		return new RegionRecord("kanto", "blue", "gen1", HASH, 0, new RegionRecord.Origin(0, 64, 0), new RegionRecord.Box(0, 0, 799, 799, 40, 80),
				new RegionRecord.Areas(new RegionRecord.Area(0, 0, 800, 800), new RegionRecord.Area(0, 3000, 5120, 640), RegionRecord.Area.NONE,
						RegionRecord.Area.NONE),
				SlotMap.LEGACY_FAMILIES, RegionRecord.Documents.of("kanto"), new RegionRecord.DocumentHashes(HASH, HASH, HASH), "kb-shapes/11",
				new RegionRecord.Rules("gen1", null, new RegionRecord.Compat(151, 165, 10, "leave_out")), RegionRecord.Namespaces.of("blue", "kanto"));
	}

	private static RegionRecord hoenn() {
		return new RegionRecord("hoenn", "emerald", "gen3", HASH, 1, new RegionRecord.Origin(8192, 64, 0),
				new RegionRecord.Box(7980, -9400, 13400, 4200, -256, 319),
				new RegionRecord.Areas(new RegionRecord.Area(8192, 0, 704, 744), new RegionRecord.Area(7980, 3000, 5120, 1280),
						new RegionRecord.Area(7980, -3700, 704, 700), RegionRecord.Area.NONE),
				new SlotMap.Range(16, 31), RegionRecord.Documents.of("hoenn"), new RegionRecord.DocumentHashes(HASH, HASH_B, HASH), "kb-shapes/11",
				new RegionRecord.Rules("gen3", new RegionRecord.Movement(16, 8), new RegionRecord.Compat(386, 354, 10, "leave_out")),
				RegionRecord.Namespaces.of("emerald", "hoenn"));
	}

	/** The {@code world.regions} document for a table and its records. */
	private static String document(RegionTable table, RegionRecord... records) {
		StringBuilder out = new StringBuilder("{\"version\":1,\"world\":\"kb-test-world\",\"table\":").append(table.toJson()).append(",\"regions\":[");
		for (int i = 0; i < records.length; i++) {
			out.append(i == 0 ? "" : ",").append(records[i].toJson());
		}
		return out.append("]}").toString();
	}

	private static RegionTable twoRegions() {
		return new RegionTable(List.of(new RegionTable.Entry("kanto", "blue", 0), new RegionTable.Entry("hoenn", "emerald", 1)));
	}

	private static void feedRegions(RegionDocuments docs, String json) throws DataException {
		for (WorldIndexParts.Part part : WorldIndexParts.split(json, 500)) {
			docs.onRegionsPart(part.index(), part.count(), part.hash(), part.text());
		}
	}

	private static String feedIndex(RegionDocuments docs, String region, WorldIndex index) throws DataException {
		String json = index.toJson();
		String hash = null;
		for (WorldIndexParts.Part part : WorldIndexParts.split(json, 300)) {
			hash = part.hash();
			docs.onIndexPart(region, part.index(), part.count(), part.hash(), part.text());
		}
		return hash;
	}

	private static boolean feedShapes(RegionDocuments docs, String region, String indexText, String mapWire) throws DataException {
		boolean complete = false;
		for (WorldIndexParts.Part part : WorldIndexParts.split(indexText, 7)) {
			complete = docs.onShapesPart(region, true, part.index(), part.count(), part.hash(), part.text());
		}
		for (WorldIndexParts.Part part : WorldIndexParts.split(mapWire, 7)) {
			complete = docs.onShapesPart(region, false, part.index(), part.count(), part.hash(), part.text());
		}
		return complete;
	}

	@Test
	void aLegacyWorldsUntaggedIndexMakesTheImplicitRegion() throws Exception {
		RegionDocuments docs = new RegionDocuments();
		Watch watch = new Watch(docs);
		assertNull(docs.set());
		assertNull(docs.followed());
		WorldIndex index = index("blue", 100);
		String hash = feedIndex(docs, null, index);

		RegionDocuments.RegionSet set = docs.set();
		assertNotNull(set);
		assertEquals(1, set.epoch());
		assertEquals(List.of(1L), watch.epochs, "published once, when the index was complete");
		assertTrue(docs.legacyWorld());
		assertFalse(docs.regionWorld());
		RegionRecord implicit = docs.followed();
		assertNotNull(implicit);
		assertEquals("kanto", implicit.id(), "Blue's world space");
		assertEquals("blue", implicit.game());
		assertTrue(implicit.isLegacy());
		assertEquals(0, implicit.slot());
		assertEquals(SlotMap.LEGACY_FAMILIES, implicit.families());
		assertEquals(index.toJson(), docs.indexOf("kanto").toJson());
		assertEquals(hash, docs.indexHash("kanto"));
		assertEquals(Hashes.sha256(index.toJson()), hash);
		assertSame(implicit, docs.byGame("blue"));
		assertNull(docs.byGame("emerald"));
		assertEquals("kanto", docs.at(100, 200).id(), "inside the town");
		assertEquals(List.of("kanto"), docs.indexed());
		// The tag the relay adds: none in a legacy world.
		assertFalse(docs.regionWorld());
	}

	@Test
	void aLegacyWorldsShapesReachTheListenerUnderTheImplicitRegion() throws Exception {
		RegionDocuments docs = new RegionDocuments();
		List<String> told = new ArrayList<>();
		docs.onShapes((region, indexText, mapWire) -> told.add(region + "|" + indexText + "|" + mapWire));
		feedIndex(docs, null, index("red", 100));
		assertTrue(feedShapes(docs, null, "{\"shape\":\"index\"}", "ABCDEFGHIJKLMNOP"));
		assertEquals(List.of("kanto|{\"shape\":\"index\"}|ABCDEFGHIJKLMNOP"), told);
		assertEquals("{\"shape\":\"index\"}", docs.shapeIndexText("kanto"));
		assertEquals("ABCDEFGHIJKLMNOP", docs.shapeMapWire("kanto"));
		// A new shape index alone makes the old map stale until its own arrives: nothing is told.
		for (WorldIndexParts.Part part : WorldIndexParts.split("{\"shape\":\"index2\"}", 7)) {
			assertFalse(docs.onShapesPart(null, true, part.index(), part.count(), part.hash(), part.text()));
		}
		assertEquals(1, told.size());
		assertEquals("{\"shape\":\"index2\"}", docs.shapeIndexText("kanto"));
		assertNull(docs.shapeMapWire("kanto"));
	}

	@Test
	void aRegionWorldsTableComesFirstThenEachRegionsDocuments() throws Exception {
		RegionDocuments docs = new RegionDocuments();
		Watch watch = new Watch(docs);
		List<String> told = new ArrayList<>();
		docs.onShapes((region, indexText, mapWire) -> told.add(region));
		feedRegions(docs, document(twoRegions(), kanto(), hoenn()));

		RegionDocuments.RegionSet set = docs.set();
		assertNotNull(set);
		assertEquals(1, set.epoch());
		assertTrue(docs.regionWorld());
		assertFalse(docs.legacyWorld());
		assertEquals("kb-test-world", docs.worldId());
		assertEquals(List.of("kanto", "hoenn"), docs.regions().ids());
		assertEquals("hoenn", docs.byGame("emerald").id());
		assertEquals("kanto", docs.byGame("blue").id());
		assertEquals("hoenn", docs.at(8192, 0).id());
		assertEquals("kanto", docs.at(2000, 3200).id(), "Kanto's zone area");
		assertNull(docs.at(13401, 4200), "between regions");
		assertNull(docs.followed(), "two regions, none followed yet: the glue asks the player's position");
		assertNull(docs.indexOf("kanto"));
		assertTrue(docs.indexed().isEmpty());

		// Kanto's index, tagged.
		WorldIndex kantoIndex = index("blue", 100);
		String kantoHash = feedIndex(docs, "kanto", kantoIndex);
		assertEquals(2, docs.epoch());
		assertEquals(kantoIndex.toJson(), docs.indexOf("kanto").toJson());
		assertEquals(kantoHash, docs.indexHash("kanto"));
		assertNull(docs.indexOf("hoenn"));
		assertEquals(List.of("kanto"), docs.indexed());

		// Hoenn's index arrives later: a new epoch; the followed region is bound by the link manager (MG4).
		feedIndex(docs, "hoenn", index("emerald", 8292));
		assertEquals(3, docs.epoch());
		assertEquals(List.of("kanto", "hoenn"), docs.indexed());
		docs.setFollowed("hoenn");
		assertEquals("hoenn", docs.followed().id());
		assertEquals(4, docs.epoch(), "binding the followed region is a change");
		docs.setFollowed("hoenn");
		assertEquals(4, docs.epoch(), "binding it again is not");
		assertEquals(List.of(1L, 2L, 3L, 4L), watch.epochs);

		// Shapes per region.
		assertTrue(feedShapes(docs, "hoenn", "{\"h\":1}", "HOENN-MAP"));
		assertTrue(feedShapes(docs, "kanto", "{\"k\":1}", "KANTO-MAP"));
		assertEquals(List.of("hoenn", "kanto"), told);
		assertEquals("KANTO-MAP", docs.shapeMapWire("kanto"));
		assertEquals("HOENN-MAP", docs.shapeMapWire("hoenn"));

		// A part for a region the table doesn't name is refused.
		WorldIndexParts.Part stray = WorldIndexParts.split(index("red", 0).toJson(), 100_000).get(0);
		assertThrows(DataException.class, () -> docs.onIndexPart("johto", stray.index(), stray.count(), stray.hash(), stray.text()));
	}

	@Test
	void aNewTableKeepsTheDocumentsOfTheRegionsItStillNames() throws Exception {
		RegionDocuments docs = new RegionDocuments();
		feedRegions(docs, document(new RegionTable(List.of(new RegionTable.Entry("kanto", "blue", 0))), kanto()));
		feedIndex(docs, "kanto", index("blue", 100));
		docs.setFollowed("kanto");
		long before = docs.epoch();
		// A rebuild added Hoenn: Kanto's index stays, Hoenn's is awaited.
		feedRegions(docs, document(twoRegions(), kanto(), hoenn()));
		assertTrue(docs.epoch() > before);
		assertNotNull(docs.indexOf("kanto"));
		assertNull(docs.indexOf("hoenn"));
		assertEquals("kanto", docs.followed().id(), "the followed region is still in the set");
		// A table without Kanto drops Kanto's documents and unbinds it.
		feedRegions(docs, document(new RegionTable(List.of(new RegionTable.Entry("hoenn", "emerald", 1))), hoenn()));
		assertNull(docs.indexOf("kanto"));
		assertNull(docs.followedId());
		assertEquals("hoenn", docs.followed().id(), "the only region");
	}

	@Test
	void aBrokenDocumentIsRefused() throws Exception {
		RegionDocuments docs = new RegionDocuments();
		RegionTable table = twoRegions();
		// A record the table lacks, a table entry without a record, a bad version: all refused, nothing adopted.
		for (String json : List.of(document(table, kanto()), document(table, kanto(), hoenn()).replace("\"version\":1,\"world\"", "\"version\":2,\"world\""),
				"{\"version\":1,\"table\":{},\"regions\":[]}", "not json")) {
			WorldIndexParts.Part part = WorldIndexParts.split(json, 100_000).get(0);
			assertThrows(DataException.class, () -> docs.onRegionsPart(part.index(), part.count(), part.hash(), part.text()), json);
			assertNull(docs.set());
		}
		assertEquals(0, docs.epoch());
	}

	@Test
	void clearingEmptiesEverythingAndPublishes() throws Exception {
		RegionDocuments docs = new RegionDocuments();
		Watch watch = new Watch(docs);
		feedRegions(docs, document(twoRegions(), kanto(), hoenn()));
		feedIndex(docs, "kanto", index("blue", 100));
		docs.clear();
		assertNull(docs.set());
		assertNull(docs.regions());
		assertNull(docs.indexOf("kanto"));
		assertFalse(docs.regionWorld());
		assertFalse(docs.legacyWorld());
		assertNull(docs.followed());
		assertEquals(3, docs.epoch());
		assertEquals(List.of(1L, 2L, 3L), watch.epochs);
		// A legacy world after a region world: the implicit region again.
		feedIndex(docs, null, index("red", 100));
		assertTrue(docs.legacyWorld());
		assertEquals("kanto", docs.followed().id());
	}

	@Test
	void theLegacyIdIsTheGamesWorldSpace() {
		assertEquals("kanto", RegionDocuments.legacyIdOf("red"));
		assertEquals("hoenn", RegionDocuments.legacyIdOf("emerald"));
		assertEquals(RegionRecord.LEGACY_ID, RegionDocuments.legacyIdOf("nope"));
		Regions legacy = Regions.legacy(RegionRecord.legacy(index("blue", 100), null, null, null));
		assertTrue(legacy.isLegacyWorld());
	}
}
