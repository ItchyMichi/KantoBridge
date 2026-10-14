package dev.kantobridge.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The region record (MULTI_GAME_PLAN §4.6): canonical JSON, strict reading, the implicit legacy record. */
class RegionRecordTest {
	private static final String HASH = "0123456789abcdef".repeat(4);
	private static final String HASH_B = "fedcba9876543210".repeat(4);

	static RegionRecord hoenn() {
		return new RegionRecord("hoenn", "emerald", "gen3", HASH, 1, new RegionRecord.Origin(8192, 64, 0),
				new RegionRecord.Box(7980, -9400, 13400, 4200, -256, 319),
				new RegionRecord.Areas(new RegionRecord.Area(8192, 0, 704, 744), new RegionRecord.Area(7980, 3000, 5120, 1280),
						new RegionRecord.Area(7980, -3700, 704, 700), RegionRecord.Area.NONE),
				new SlotMap.Range(16, 31), RegionRecord.Documents.of("hoenn"), new RegionRecord.DocumentHashes(HASH, HASH_B, HASH),
				"kb-shapes/11", new RegionRecord.Rules("gen3", new RegionRecord.Movement(16, 8), new RegionRecord.Compat(386, 354, 10, "leave_out")),
				RegionRecord.Namespaces.of("emerald", "hoenn"));
	}

	private static WorldIndex index() {
		MapPlacement town = new MapPlacement("TOWN", 100, 64, 200, 20, 18, 0, MapPlacement.Kind.SURFACE, "PAL", "kantobridge:kanto_outdoor", false,
				null);
		MapPlacement house = new MapPlacement("HOUSE", 300, Scale.feetY(1), 400, 8, 8, 1, MapPlacement.Kind.INTERIOR, "PAL",
				"kantobridge:kanto_interior", false, "TOWN");
		return new WorldIndex("red", HASH, List.of(town, house), HASH_B, StoryCellTable.of(List.of()), List.of());
	}

	@Test
	void roundTripsThroughCanonicalJson() throws Exception {
		RegionRecord record = hoenn();
		String json = record.toJson();
		for (String key : new String[] {"\"version\":1", "\"id\":\"hoenn\"", "\"game\":\"emerald\"", "\"family\":\"gen3\"", "\"slot\":1",
				"\"origin\":{\"x\":8192,\"y\":64,\"z\":0}", "\"families\":[16,31]", "\"index\":\"regions/hoenn/world_index.json\"",
				"\"shapeMap\":\"regions/hoenn/shape_map.bin\"", "\"generator\":{\"shapes\":\"kb-shapes/11\"}", "\"movement\":{\"bike\":8,\"walk\":16}",
				"\"onIncompatible\":\"leave_out\"", "\"artSet\":\"emerald\"", "\"pools\":\"kantobridge:hoenn\"", "\"caves\":{\"d\":0,\"w\":0,\"x\":0,\"z\":0}"}) {
			assertTrue(json.contains(key), key);
		}
		RegionRecord read = RegionRecord.fromJson(json);
		assertEquals(record, read);
		assertEquals(record.toJson(), read.toJson());
		assertEquals(record.hash(), read.hash());
		assertEquals(Hashes.sha256(json), record.hash());
		assertFalse(record.isLegacy());
		assertEquals("regions/hoenn/", record.dir());
		assertEquals("hoenn_", record.poolPrefix());
		assertEquals(new SlotMap.Range(16, 31), record.families());
		assertEquals(Integer.valueOf(10), record.rules().compat().nick());
		assertNotEquals(record, record.withBounds(new RegionRecord.Box(0, 0, 1, 1, 0, 1)));
		assertEquals(record.hash(), RegionRecord.fromJson(record.withHashes(record.hashes()).toJson()).hash());
	}

	@Test
	void theLegacyRecordIsReadFromTheIndex() {
		WorldIndex index = index();
		RegionRecord legacy = RegionRecord.legacy(index, null, null, null);
		assertEquals("kanto", legacy.id(), "Red's world space");
		assertEquals("red", legacy.game());
		assertEquals("gen1", legacy.family());
		assertEquals(0, legacy.slot());
		assertEquals(new RegionRecord.Origin(0, 64, 0), legacy.origin());
		assertEquals(SlotMap.LEGACY_FAMILIES, legacy.families());
		assertTrue(legacy.isLegacy());
		assertEquals("", legacy.dir());
		assertEquals("", legacy.poolPrefix(), "the legacy region's pools stay kantobridge:<map>");
		assertEquals(RegionRecord.Documents.root(), legacy.files());
		assertEquals(index.hash(), legacy.hashes().index());
		assertEquals(Hashes.sha256(""), legacy.hashes().shapeIndex(), "no shape documents: the hash of nothing");
		assertEquals(RegionRecord.NO_GENERATOR, legacy.generator());
		assertEquals(new RegionRecord.Compat(151, 165, 10, "leave_out"), legacy.rules().compat());
		assertEquals("gen1", legacy.rules().profile());
		assertNull(legacy.rules().movement());
		assertEquals(new RegionRecord.Namespaces("red", "kantobridge:kanto"), legacy.namespaces());
		// the town's 20×18 cells are blocks x 100..139, z 200..235; the house at layer 1 is x 300..315, z 400..415
		RegionRecord.Box bounds = legacy.bounds();
		assertEquals(100, bounds.minX());
		assertEquals(200, bounds.minZ());
		assertEquals(315, bounds.maxX());
		assertEquals(415, bounds.maxZ());
		assertEquals(Scale.feetY(1) - 4, bounds.minY(), "the deepest cell's reach below its feet");
		assertEquals(64 + 5, bounds.maxY(), "the surface's reach above its feet");
		assertTrue(bounds.contains(100, 200) && bounds.contains(315, 415) && !bounds.contains(316, 415) && !bounds.contains(99, 200));
		assertEquals(new RegionRecord.Area(100, 200, 40, 36), legacy.areas().surface());
		assertTrue(legacy.areas().zones().isEmpty() && legacy.areas().dungeons().isEmpty() && legacy.areas().caves().isEmpty());
		assertTrue(legacy.areas().contains(139, 235) && !legacy.areas().contains(300, 400), "interiors are in the bounds, not an area");
		RegionRecord shaped = RegionRecord.legacy(index, "kb-shapes/11", HASH, HASH_B);
		assertEquals("kb-shapes/11", shaped.generator());
		assertEquals(new RegionRecord.DocumentHashes(index.hash(), HASH, HASH_B), shaped.hashes());
		assertEquals(legacy.toJson(), RegionRecord.legacy(index, null, null, null).toJson(), "deterministic");
	}

	@Test
	void readingIsStrict() {
		String json = hoenn().toJson();
		assertThrows(DataException.class, () -> RegionRecord.fromJson(json.replace("\"id\":\"hoenn\"", "\"id\":\"johto\"")),
				"the documents belong to another region");
		assertThrows(DataException.class, () -> RegionRecord.fromJson(json.replace("\"families\":[16,31]", "\"families\":[31,16]")), "lo > hi");
		assertThrows(DataException.class, () -> RegionRecord.fromJson(json.replace("\"families\":[16,31]", "\"families\":[16,96]")), "family 96");
		assertThrows(DataException.class, () -> RegionRecord.fromJson(json.replace("\"onIncompatible\":\"leave_out\"", "\"onIncompatible\":\"drop\"")));
		assertThrows(DataException.class, () -> RegionRecord.fromJson(json.replace("\"family\":\"gen3\"", "\"family\":\"gen9\"")));
		assertThrows(DataException.class, () -> RegionRecord.fromJson(json.replace("\"game\":\"emerald\"", "\"game\":\"nope\"")));
		assertThrows(DataException.class, () -> RegionRecord.fromJson(json.replace("\"version\":1", "\"version\":2")));
		assertThrows(DataException.class, () -> RegionRecord.fromJson(json.replace("\"version\":1", "\"version\":1,\"extra\":true")));
		assertThrows(DataException.class, () -> RegionRecord.fromJson(json.replace("\"pools\":\"kantobridge:hoenn\"", "\"pools\":\"other:hoenn\"")));
		assertThrows(DataException.class, () -> RegionRecord.fromJson(json.replace("kb-shapes/11", "shapes/11")));
		assertThrows(IllegalArgumentException.class, () -> new RegionRecord.Documents("regions/hoenn/../x", "a", "b"));
		assertThrows(IllegalArgumentException.class, () -> new RegionRecord.Box(1, 0, 0, 0, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new RegionRecord.Area(0, 0, -1, 0));
		assertThrows(IllegalArgumentException.class, () -> new RegionRecord.Compat(0, 1, null, "leave_out"));
		assertThrows(IllegalArgumentException.class, () -> new RegionRecord.Rules("gen4", null, new RegionRecord.Compat(1, 1, null, "leave_out")));
	}

	@Test
	void boxesAndAreas() {
		RegionRecord.Box a = new RegionRecord.Box(0, 0, 10, 10, 0, 5);
		RegionRecord.Box b = new RegionRecord.Box(10, 10, 20, 20, 5, 9);
		RegionRecord.Box c = new RegionRecord.Box(11, 0, 20, 10, 0, 5);
		assertTrue(a.contains(0, 0) && a.contains(10, 10) && !a.contains(11, 10));
		assertTrue(a.overlaps(b) && b.overlaps(a), "inclusive edges touch");
		assertFalse(a.overlaps(c));
		assertEquals(new RegionRecord.Box(0, 0, 20, 20, 0, 9), a.union(b));
		RegionRecord.Area area = new RegionRecord.Area(5, 5, 10, 4);
		assertTrue(area.contains(5, 5) && area.contains(14, 8) && !area.contains(15, 8) && !area.contains(14, 9));
		assertTrue(RegionRecord.Area.NONE.isEmpty() && !RegionRecord.Area.NONE.contains(0, 0));
		assertEquals(area, area.union(RegionRecord.Area.NONE));
		assertEquals(area, RegionRecord.Area.NONE.union(area));
		assertEquals(new RegionRecord.Area(0, 5, 15, 10), area.union(new RegionRecord.Area(0, 10, 2, 5)));
	}
}
