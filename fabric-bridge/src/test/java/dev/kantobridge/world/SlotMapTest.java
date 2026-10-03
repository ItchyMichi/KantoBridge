package dev.kantobridge.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
	void capacityMatchesTheRegisteredFamilies() {
		assertEquals(KbBlocks.FAMILIES_PER_SHAPE, SlotMap.FAMILIES_PER_SHAPE);
		assertTrue(SlotMap.CAPACITY >= 4096);
	}
}
