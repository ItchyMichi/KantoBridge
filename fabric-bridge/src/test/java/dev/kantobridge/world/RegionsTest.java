package dev.kantobridge.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The regions of one world (MULTI_GAME_PLAN §4.6): "which region am I in", the legacy world. */
class RegionsTest {
	private static final String HASH = "0123456789abcdef".repeat(4);

	private static RegionRecord kanto() {
		// the legacy region: documents at the root, bounds around a small Kanto, a zone area south of it
		return new RegionRecord("kanto", "blue", "gen1", HASH, 0, new RegionRecord.Origin(0, 64, 0), new RegionRecord.Box(0, 0, 799, 799, 40, 80),
				new RegionRecord.Areas(new RegionRecord.Area(0, 0, 800, 800), new RegionRecord.Area(0, 3000, 5120, 640), RegionRecord.Area.NONE,
						RegionRecord.Area.NONE),
				SlotMap.LEGACY_FAMILIES, RegionRecord.Documents.root(), new RegionRecord.DocumentHashes(HASH, HASH, HASH), "kb-shapes/11",
				new RegionRecord.Rules("gen1", null, new RegionRecord.Compat(151, 165, 10, "leave_out")), RegionRecord.Namespaces.of("blue", "kanto"));
	}

	private static Regions two() {
		RegionRecord hoenn = RegionRecordTest.hoenn();
		RegionTable table = new RegionTable(List.of(new RegionTable.Entry("kanto", "blue", 0), new RegionTable.Entry("hoenn", "emerald", 1)));
		return new Regions(table, Map.of("kanto", kanto(), "hoenn", hoenn));
	}

	@Test
	void findsTheRegionByBoundsThenByAreas() {
		Regions regions = two();
		assertEquals("kanto", regions.at(100, 100).id(), "inside Kanto's bounds");
		assertEquals("kanto", regions.at(2000, 3200).id(), "in Kanto's zone area, outside its bounds");
		assertEquals("hoenn", regions.at(8192, 0).id(), "inside Hoenn's bounds");
		assertEquals("hoenn", regions.at(13400, 4200).id(), "the bounds are inclusive");
		assertNull(regions.at(13401, 4200), "between regions");
		assertNull(regions.at(4000, 1000));
		assertEquals(List.of("kanto", "hoenn"), regions.ids());
		assertEquals(2, regions.size());
		assertSame(regions.byId("hoenn"), regions.records().get("hoenn"));
		assertNull(regions.byId("johto"));
		assertEquals("kanto", regions.legacy().id(), "slot 0");
		assertFalse(regions.isLegacyWorld(), "two regions make a region world");
	}

	@Test
	void aLegacyWorldIsOneImplicitRegion() {
		Regions legacy = Regions.legacy(kanto());
		assertTrue(legacy.isLegacyWorld());
		assertEquals("kanto", legacy.legacy().id());
		assertEquals(List.of("kanto"), legacy.ids());
		assertEquals("kanto", legacy.at(10, 10).id());
		assertEquals(0, legacy.table().bySlot(0).slot());
		Regions hoennOnly = new Regions(new RegionTable(List.of(new RegionTable.Entry("hoenn", "emerald", 1))),
				Map.of("hoenn", RegionRecordTest.hoenn()));
		assertEquals("hoenn", hoennOnly.legacy().id(), "the only region, though not at slot 0");
		assertFalse(hoennOnly.isLegacyWorld(), "its documents live under regions/");
	}

	@Test
	void recordsMustMatchTheTable() {
		RegionTable table = new RegionTable(List.of(new RegionTable.Entry("kanto", "blue", 0), new RegionTable.Entry("hoenn", "emerald", 1)));
		assertThrows(IllegalArgumentException.class, () -> new Regions(table, Map.of("kanto", kanto())), "hoenn has no record");
		RegionTable wrongSlot = new RegionTable(List.of(new RegionTable.Entry("kanto", "blue", 0), new RegionTable.Entry("hoenn", "emerald", 2)));
		assertThrows(IllegalArgumentException.class, () -> new Regions(wrongSlot, Map.of("kanto", kanto(), "hoenn", RegionRecordTest.hoenn())));
		RegionTable wrongGame = new RegionTable(List.of(new RegionTable.Entry("kanto", "red", 0), new RegionTable.Entry("hoenn", "emerald", 1)));
		assertThrows(IllegalArgumentException.class, () -> new Regions(wrongGame, Map.of("kanto", kanto(), "hoenn", RegionRecordTest.hoenn())));
		RegionTable kantoOnly = new RegionTable(List.of(new RegionTable.Entry("kanto", "blue", 0)));
		assertThrows(IllegalArgumentException.class, () -> new Regions(kantoOnly, Map.of("kanto", kanto(), "hoenn", RegionRecordTest.hoenn())),
				"a record the table lacks");
	}

	@Test
	void parsesTheFiles() throws Exception {
		Regions regions = two();
		Regions read = Regions.parse(regions.table().toJson(), Map.of("kanto", kanto().toJson(), "hoenn", RegionRecordTest.hoenn().toJson()));
		assertEquals(regions.ids(), read.ids());
		assertEquals(regions.byId("hoenn"), read.byId("hoenn"));
		assertThrows(DataException.class, () -> Regions.parse(regions.table().toJson(), Map.of("kanto", kanto().toJson())), "a missing record");
		assertThrows(DataException.class, () -> Regions.parse("{}", Map.of()));
	}
}
