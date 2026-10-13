package dev.kantobridge.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.block.KbBlocks;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The slot map is a deterministic function of the used keys, per shape, and survives its JSON. */
class SlotMapTest {
	private static List<SlotMap.Key> keys() {
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

	@Test
	void assignmentDoesNotDependOnInputOrderOrDuplicates() throws Exception {
		List<SlotMap.Key> a = keys();
		List<SlotMap.Key> b = new ArrayList<>(a);
		Collections.shuffle(b, new Random(42));
		b.addAll(a.subList(0, 10));
		SlotMap first = SlotMap.enumerate(a);
		SlotMap second = SlotMap.enumerate(b);
		assertEquals(first.slots(), second.slots());
		assertEquals(first.toJson(), second.toJson());
		assertEquals(first.hash(), second.hash());
		assertEquals(a.size(), first.size());
	}

	@Test
	void slotsAreSortedPerShapeAndPackedIntoFamilies() throws Exception {
		SlotMap map = SlotMap.enumerate(keys());
		List<SlotMap.Key> sorted = new ArrayList<>(keys());
		Collections.sort(sorted);
		int full = 0;
		int half = 0;
		for (SlotMap.Key key : sorted) {
			SlotMap.Slot slot = map.slot(key);
			int expected = key.shape() == SlotMap.Shape.FULL ? full++ : half++;
			assertEquals(expected, slot.index(), "slots follow the sorted key order");
			assertEquals(slot.index() / 256, slot.family());
			assertEquals(slot.index() % 256, slot.hi() * 16 + slot.lo());
		}
		// Sorting: atlas first, then palette, tile, shape.
		assertEquals("tilesets.house", sorted.get(0).atlas());
		assertEquals("PALLET", sorted.get(0).palette());
		assertEquals(0, map.slot(sorted.get(0)).index());
		assertEquals("tile_full_00", map.slot(sorted.get(0)).blockPath());
	}

	@Test
	void familiesFillUpAndOverflowFails() throws Exception {
		List<SlotMap.Key> keys = new ArrayList<>();
		for (int tile = 0; tile < 300; tile++) {
			keys.add(new SlotMap.Key("tilesets.a", "P", tile, SlotMap.Shape.FULL));
		}
		SlotMap map = SlotMap.enumerate(keys);
		SlotMap.Slot last = map.slot(new SlotMap.Key("tilesets.a", "P", 299, SlotMap.Shape.FULL));
		assertEquals(1, last.family());
		assertEquals(299 - 256, last.slot());
		assertEquals("tile_full_01", last.blockPath());
		assertEquals(2, map.usedFamilies(SlotMap.Shape.FULL).size());

		List<SlotMap.Key> tooMany = new ArrayList<>();
		for (int p = 0; p < 5; p++) {
			for (int tile = 0; tile < 1000; tile++) {
				tooMany.add(new SlotMap.Key("tilesets.a", "P" + p, tile, SlotMap.Shape.FULL));
			}
		}
		SlotMap.FullException full = assertThrows(SlotMap.FullException.class, () -> SlotMap.enumerate(tooMany));
		assertEquals(5000, full.needed());
	}

	@Test
	void jsonRoundTrip() throws Exception {
		SlotMap map = SlotMap.enumerate(keys());
		SlotMap read = SlotMap.fromJson(map.toJson());
		assertEquals(map.slots(), read.slots());
		assertEquals(map.hash(), read.hash());
		assertThrows(DataException.class, () -> SlotMap.fromJson("{\"version\":2,\"slots\":[]}"));
		assertThrows(DataException.class, () -> SlotMap.fromJson(
				"{\"version\":1,\"slots\":[{\"atlas\":\"a\",\"palette\":\"P\",\"tile\":1,\"shape\":\"full\",\"family\":0,\"slot\":0},"
						+ "{\"atlas\":\"a\",\"palette\":\"P\",\"tile\":2,\"shape\":\"full\",\"family\":0,\"slot\":0}]}"),
				"two keys may not share a state");
	}

	@Test
	void differentKeysGiveDifferentHashes() throws Exception {
		List<SlotMap.Key> keys = keys();
		SlotMap a = SlotMap.enumerate(keys);
		keys.add(new SlotMap.Key("tilesets.zzz", "Z", 1, SlotMap.Shape.FULL));
		assertNotEquals(a.hash(), SlotMap.enumerate(keys).hash());
	}

	@Test
	void aLegacyMapFillsItsSixteenFamiliesAndClosedArtCanNameIt() throws Exception {
		// Closed art (M8) names full-shape slots, so it competes for the same 4,096 legacy states as every other full
		// tile; a version-1 map never grows past families 0..15 (MG3: more needs regions and a version-2 map).
		List<SlotMap.Key> keys = new ArrayList<>();
		for (int p = 0; p < 4; p++) {
			for (int tile = 0; tile < 1024; tile++) {
				keys.add(new SlotMap.Key("tilesets.a", "P" + p, tile, SlotMap.Shape.FULL));
			}
		}
		SlotMap full = SlotMap.enumerate(keys);
		SlotMap.Slot last = full.slot(new SlotMap.Key("tilesets.a", "P3", 1023, SlotMap.Shape.FULL));
		assertEquals(SlotMap.LEGACY_FAMILIES.endIndex() - 1, last.index());
		assertEquals("tile_full_15", last.blockPath());
		assertEquals(List.of(0, 1, 2, last.index()), new StoryCellTable.Closed(1, true, 2, List.of(0, 1, 2, last.index())).art());
		assertEquals(List.of(0, 1, 2, SlotMap.CAPACITY - 1), new StoryCellTable.Closed(1, true, 2, List.of(0, 1, 2, SlotMap.CAPACITY - 1)).art(),
				"a region's closed art may name every family");
		assertThrows(IllegalArgumentException.class, () -> new StoryCellTable.Closed(1, true, 2, List.of(0, 1, 2, SlotMap.CAPACITY)));
		keys.add(new SlotMap.Key("tilesets.b", "P0", 0, SlotMap.Shape.FULL));
		SlotMap.FullException overflow = assertThrows(SlotMap.FullException.class, () -> SlotMap.enumerate(keys));
		assertEquals(SlotMap.LEGACY_FAMILIES.endIndex() + 1, overflow.needed());
		assertEquals(4096, overflow.available());
		assertNull(overflow.region());
	}

	@Test
	void capacityMatchesTheRegisteredFamilies() {
		assertEquals(KbBlocks.FAMILIES_PER_SHAPE, SlotMap.FAMILIES_PER_SHAPE);
		assertEquals(32, SlotMap.FAMILIES_PER_SHAPE, "MG3 (D9): a second region's worth of families");
		assertEquals(8192, SlotMap.CAPACITY);
		assertEquals(4096, SlotMap.LEGACY_FAMILIES.endIndex(), "a legacy map keeps families 0..15");
		assertTrue(SlotMap.CAPACITY >= 4096);
	}
}
