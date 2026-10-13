package dev.kantobridge.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The slot map's version 2 (MULTI_GAME_PLAN §4.6, MG3): one map per world, a family range per region, and a region's
 * slice hash that never moves when another region is rebuilt.
 */
class SlotMapV2Test {
	private static final SlotMap.Range SECOND = new SlotMap.Range(16, 31);

	private static List<SlotMap.Key> kantoKeys() {
		List<SlotMap.Key> keys = new ArrayList<>();
		for (String atlas : new String[] {"tilesets.overworld", "tilesets.house"}) {
			for (String palette : new String[] {"ROUTE", "PALLET"}) {
				for (int tile = 0; tile < 40; tile += 3) {
					keys.add(new SlotMap.Key(atlas, palette, tile, SlotMap.Shape.FULL));
					if (tile % 2 == 0) {
						keys.add(new SlotMap.Key(atlas, palette, tile, SlotMap.Shape.HALF));
					}
				}
			}
		}
		return keys;
	}

	private static List<SlotMap.Key> otherKeys(int tiles) {
		List<SlotMap.Key> keys = new ArrayList<>();
		for (int tile = 0; tile < tiles; tile++) {
			keys.add(new SlotMap.Key("tilesets.test2", "TEST", tile, SlotMap.Shape.FULL));
			if (tile % 5 == 0) {
				keys.add(new SlotMap.Key("tilesets.test2", "TEST", tile, SlotMap.Shape.HALF));
			}
		}
		return keys;
	}

	@Test
	void theLegacyRegionsSliceIsTheVersionOneMap() throws Exception {
		SlotMap v1 = SlotMap.enumerate(kantoKeys());
		SlotMap v2 = SlotMap.enumerateRegion(null, "kanto", "blue", SlotMap.LEGACY_FAMILIES, kantoKeys());
		assertEquals(2, v2.version());
		assertEquals(v1.hash(), v2.regionHash("kanto"), "the slice hash is today's hash");
		assertEquals(v1.toJson(), v2.regionSlice("kanto").toJson());
		assertNotEquals(v1.hash(), v2.hash(), "the whole version-2 document differs");
		assertEquals(Map.of("kanto", new SlotMap.RegionSlots("blue", SlotMap.LEGACY_FAMILIES)), v2.regions());
		for (SlotMap.Key key : kantoKeys()) {
			assertEquals(v1.slot(key).index(), v2.slot("kanto", key).index(), "same slots in the same order");
			assertNull(v2.slot(key), "a version-2 map holds region keys");
			assertEquals(v2.slot("kanto", key), v2.slot(key.withRegion("kanto", "blue")));
			assertNull(v2.slot("hoenn", key));
		}
		assertEquals(v1.size(), v2.size());
		assertEquals("blue", v2.artSet("kanto"));
		assertEquals(SlotMap.LEGACY_FAMILIES, v2.families("kanto"));
		assertNull(v2.families("hoenn"));
	}

	@Test
	void aSecondRegionNeverRenumbersTheFirst() throws Exception {
		SlotMap kanto = SlotMap.enumerateRegion(null, "kanto", "blue", SlotMap.LEGACY_FAMILIES, kantoKeys());
		SlotMap both = SlotMap.enumerateRegion(kanto, "test2", "red", SECOND, otherKeys(300));
		assertEquals(kanto.regionHash("kanto"), both.regionHash("kanto"));
		assertEquals(kanto.slots("kanto"), both.slots("kanto"));
		assertEquals(2, both.regions().size());
		assertEquals(new SlotMap.RegionSlots("red", SECOND), both.regions().get("test2"));
		SlotMap.Slot first = both.slot("test2", new SlotMap.Key("tilesets.test2", "TEST", 0, SlotMap.Shape.FULL));
		assertEquals(SECOND.firstIndex(), first.index(), "the second region starts at family 16");
		assertEquals(16, first.family());
		assertEquals("tile_full_16", first.blockPath());
		for (SlotMap.Slot slot : both.slots("test2").values()) {
			assertTrue(SECOND.contains(slot.family()), "every test2 slot lies in its range");
		}
		assertEquals(List.of(16, 17), new ArrayList<>(both.usedFamilies(SlotMap.Shape.FULL)).subList(1, 3), "300 tiles span two families");

		// rebuilding kanto with more keys leaves test2's slots and slice hash exactly as they were
		List<SlotMap.Key> more = kantoKeys();
		more.add(new SlotMap.Key("tilesets.zzz", "Z", 1, SlotMap.Shape.FULL));
		SlotMap again = SlotMap.enumerateRegion(both, "kanto", "blue", SlotMap.LEGACY_FAMILIES, more);
		assertEquals(both.regionHash("test2"), again.regionHash("test2"));
		assertEquals(both.slots("test2"), again.slots("test2"));
		assertNotEquals(both.regionHash("kanto"), again.regionHash("kanto"));
		assertEquals(both.slots("kanto").size() + 1, again.slots("kanto").size());
		// and the other way round
		SlotMap fewer = SlotMap.enumerateRegion(again, "test2", "red", SECOND, otherKeys(10));
		assertEquals(again.regionHash("kanto"), fewer.regionHash("kanto"));
		assertEquals(again.slots("kanto"), fewer.slots("kanto"));
	}

	@Test
	void versionTwoJsonRoundTrip() throws Exception {
		SlotMap kanto = SlotMap.enumerateRegion(null, "kanto", "blue", SlotMap.LEGACY_FAMILIES, kantoKeys());
		SlotMap both = SlotMap.enumerateRegion(kanto, "test2", "red", SECOND, otherKeys(300));
		String json = both.toJson();
		assertTrue(json.startsWith("{\"regions\":{\"kanto\":{\"families\":[0,15],\"set\":\"blue\"},\"test2\":{\"families\":[16,31],\"set\":\"red\"}},"
				+ "\"slots\":["), json.substring(0, 120));
		assertTrue(json.endsWith("\"version\":2}"));
		assertTrue(json.contains("\"region\":\"test2\"") && json.contains("\"region\":\"kanto\""));
		SlotMap read = SlotMap.fromJson(json);
		assertEquals(2, read.version());
		assertEquals(both.slots(), read.slots());
		assertEquals(both.regions(), read.regions());
		assertEquals(both.hash(), read.hash());
		assertEquals(both.regionHash("test2"), read.regionHash("test2"));
		assertEquals(both.toJson(), read.toJson());
	}

	@Test
	void aVersionOneMapReadsAsTheLegacyRegionAndMigrates() throws Exception {
		SlotMap v1 = SlotMap.enumerate(kantoKeys());
		SlotMap read = SlotMap.fromJson(v1.toJson());
		assertEquals(1, read.version());
		assertEquals(Map.of("kanto", new SlotMap.RegionSlots("", SlotMap.LEGACY_FAMILIES)), read.regions(), "the art set is unknown");
		assertEquals(v1.hash(), read.regionHash("kanto"));
		assertEquals(0, read.regionSlice("other").size());
		assertEquals(v1.slots(), read.slots("kanto"));
		assertEquals(Map.of(), read.slots("other"));
		SlotMap migrated = read.migrate("blue");
		assertEquals(2, migrated.version());
		assertEquals(v1.hash(), migrated.regionHash("kanto"), "the slots stay as they were");
		assertEquals("blue", migrated.artSet("kanto"));
		for (SlotMap.Key key : kantoKeys()) {
			assertEquals(v1.slot(key), migrated.slot("kanto", key));
		}
		assertEquals(migrated, migrated.migrate("red"), "a version-2 map is returned as it is");
		SlotMap joined = SlotMap.enumerateRegion(migrated, "test2", "red", SECOND, otherKeys(10));
		assertEquals(v1.hash(), joined.regionHash("kanto"));
		assertEquals(2, joined.regions().size());
	}

	@Test
	void refusals() throws Exception {
		SlotMap v1 = SlotMap.enumerate(kantoKeys());
		assertThrows(IllegalArgumentException.class, () -> SlotMap.enumerateRegion(v1, "test2", "red", SECOND, otherKeys(10)),
				"a version 1 map must be migrated before another region joins it");
		SlotMap legacyRebuilt = SlotMap.enumerateRegion(v1, "kanto", "blue", SlotMap.LEGACY_FAMILIES, kantoKeys());
		assertEquals(v1.hash(), legacyRebuilt.regionHash("kanto"), "the legacy region may be rebuilt straight from a version 1 map");
		SlotMap both = SlotMap.enumerateRegion(legacyRebuilt, "test2", "red", SECOND, otherKeys(10));
		assertThrows(IllegalArgumentException.class, () -> SlotMap.enumerateRegion(both, "johto", "gold", new SlotMap.Range(20, 25), otherKeys(1)),
				"the range overlaps test2's");
		assertThrows(IllegalArgumentException.class, () -> SlotMap.enumerateRegion(both, "Johto", "gold", SECOND, otherKeys(1)), "bad id");
		assertThrows(IllegalArgumentException.class, () -> SlotMap.enumerateRegion(both, "johto", "", SECOND, otherKeys(1)), "no set");
		assertThrows(IllegalArgumentException.class, () -> new SlotMap.Range(0, 32));
		assertThrows(IllegalArgumentException.class, () -> new SlotMap.Range(5, 4));
		assertThrows(IllegalArgumentException.class, () -> new SlotMap.Key("kanto", "", "tilesets.a", "P", 0, SlotMap.Shape.FULL), "a region key names its set");

		// a region's range holds 16 × 256 slots per shape: 5,120 full tiles don't fit
		List<SlotMap.Key> tooMany = new ArrayList<>();
		for (int p = 0; p < 5; p++) {
			for (int tile = 0; tile < 1024; tile++) {
				tooMany.add(new SlotMap.Key("tilesets.a", "P" + p, tile, SlotMap.Shape.FULL));
			}
		}
		SlotMap.FullException full = assertThrows(SlotMap.FullException.class,
				() -> SlotMap.enumerateRegion(legacyRebuilt, "test2", "red", SECOND, tooMany));
		assertEquals(5120, full.needed());
		assertEquals(4096, full.available());
		assertEquals("test2", full.region());
		assertEquals(SlotMap.Shape.FULL, full.shape());
		// exactly 4,096 fit, up to the last family
		List<SlotMap.Key> exact = new ArrayList<>(tooMany.subList(0, 4096));
		SlotMap filled = SlotMap.enumerateRegion(legacyRebuilt, "test2", "red", SECOND, exact);
		assertEquals(SlotMap.CAPACITY - 1, filled.slot("test2", exact.get(4095)).index());
		assertEquals("tile_full_31", filled.slot("test2", exact.get(4095)).blockPath());

		// reading: no regions table, overlapping ranges, a family outside its region's range, an unknown region
		assertThrows(DataException.class, () -> SlotMap.fromJson("{\"version\":2,\"slots\":[]}"));
		String entry = "{\"atlas\":\"a\",\"palette\":\"P\",\"tile\":1,\"shape\":\"full\",\"family\":16,\"slot\":0,\"region\":\"test2\"}";
		String ok = "{\"version\":2,\"regions\":{\"kanto\":{\"set\":\"blue\",\"families\":[0,15]},\"test2\":{\"set\":\"red\",\"families\":[16,31]}},"
				+ "\"slots\":[" + entry + "]}";
		assertEquals(16, SlotMap.fromJson(ok).slot("test2", new SlotMap.Key("a", "P", 1, SlotMap.Shape.FULL)).family());
		assertThrows(DataException.class, () -> SlotMap.fromJson(ok.replace("[16,31]", "[10,31]")), "overlapping ranges");
		assertThrows(DataException.class, () -> SlotMap.fromJson(ok.replace("\"family\":16", "\"family\":3")), "outside test2's range");
		assertThrows(DataException.class, () -> SlotMap.fromJson(ok.replace("\"region\":\"test2\"", "\"region\":\"johto\"")), "unknown region");
		assertThrows(DataException.class, () -> SlotMap.fromJson(ok.replace("\"family\":16", "\"family\":32")), "family 32");
		assertThrows(DataException.class, () -> SlotMap.fromJson(ok.replace("\"slot\":0,", "\"slot\":0,\"extra\":1,")), "extra field");
		assertThrows(DataException.class, () -> SlotMap.fromJson(
				"{\"version\":1,\"slots\":[{\"atlas\":\"a\",\"palette\":\"P\",\"tile\":1,\"shape\":\"full\",\"family\":16,\"slot\":0}]}"),
				"a version 1 map stops at family 15");
		assertThrows(DataException.class, () -> SlotMap.fromJson(
				"{\"version\":1,\"slots\":[{\"atlas\":\"a\",\"palette\":\"P\",\"tile\":1,\"shape\":\"full\",\"family\":0,\"slot\":0,\"region\":\"kanto\"}]}"),
				"a version 1 entry names no region");
	}
}
