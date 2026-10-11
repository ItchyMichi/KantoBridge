package dev.kantobridge.dungeon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.plan.Box3;
import dev.kantobridge.world.WorldIndex;
import org.junit.jupiter.api.Test;

/** Where dungeon floors go (WR7): a region north of Kanto on whole chunks, slots that tile it, floors 22 apart. */
class DungeonSlotsTest {
	@Test
	void theRegionLiesNorthOfKantoAndItsSlotsTileIt() {
		Box3 kanto = new Box3(1000, 40, 5000, 1800, 120, 5900);
		WorldIndex.Dungeons region = DungeonSlots.region(kanto, 0, 0);
		assertEquals(0, region.x() & 15);
		assertEquals(0, region.z() & 15);
		assertTrue(region.z() + region.d() <= kanto.minZ() - DungeonSlots.GAP, "farther than any render distance");
		assertEquals(DungeonSlots.PER_ROW * DungeonSlots.SLOT_W, region.w());
		assertEquals(DungeonSlots.ROWS * DungeonSlots.SLOT_D, region.d());
		for (int i = 0; i < DungeonSlots.SLOTS; i++) {
			int[] o = DungeonSlots.slotOrigin(region, i);
			assertEquals(i, DungeonSlots.slotAt(region, o[0], o[1]));
			assertEquals(i, DungeonSlots.slotAt(region, o[0] + DungeonSlots.SLOT_W - 1, o[1] + DungeonSlots.SLOT_D - 1));
		}
		assertEquals(-1, DungeonSlots.slotAt(region, region.x() - 1, region.z()));
		assertThrows(IllegalArgumentException.class, () -> DungeonSlots.slotOrigin(region, DungeonSlots.SLOTS));
		assertEquals(64, DungeonSlots.feet(region, 1));
		assertEquals(64 + 22 * 8, DungeonSlots.feet(region, 9), "the deepest floor still fits under the build limit");
		WorldIndex.Dungeons fallback = DungeonSlots.region(null, 160, 320);
		assertTrue(fallback.z() + fallback.d() <= 320 - DungeonSlots.GAP);
	}
}
