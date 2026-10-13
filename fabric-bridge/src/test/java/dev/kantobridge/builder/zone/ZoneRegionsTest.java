package dev.kantobridge.builder.zone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.kantobridge.builder.plan.Box3;
import dev.kantobridge.world.RegionTable;
import org.junit.jupiter.api.Test;

/** MG3: a region's zone slots follow its own surface box, so a moved region's zones move with it. */
class ZoneRegionsTest {
	@Test
	void theSlotsFollowAMovedKantoBoxExactly() {
		Box3 kanto = new Box3(1000, 50, 2000, 1700, 90, 2740);
		ZoneRegions here = ZoneRegions.of(kanto, 0, 0);
		ZoneRegions there = ZoneRegions.of(new Box3(1000 + RegionTable.PITCH, 50, 2000, 1700 + RegionTable.PITCH, 90, 2740), RegionTable.PITCH, 0);
		for (int slot = 0; slot < 2 * ZoneRegions.PER_ROW; slot++) {
			assertEquals(here.slotOrigin(slot)[0] + RegionTable.PITCH, there.slotOrigin(slot)[0], "slot " + slot);
			assertEquals(here.slotOrigin(slot)[1], there.slotOrigin(slot)[1], "slot " + slot);
		}
	}

	@Test
	void theAreaCoversTheSlotsInUse() {
		ZoneRegions regions = ZoneRegions.of(new Box3(1000, 50, 2000, 1700, 90, 2740), 0, 0);
		assertNull(regions.area(0));
		Box3 three = regions.area(3);
		int[] first = regions.slotOrigin(0);
		assertEquals(first[0], three.minX());
		assertEquals(first[1], three.minZ());
		assertEquals(first[0] + 3 * ZoneRegions.SLOT, three.maxX());
		assertEquals(first[1] + ZoneRegions.SLOT, three.maxZ());
		assertEquals(ZonePlan.MIN_Y, three.minY());
		assertEquals(ZonePlan.MAX_Y, three.maxY());
		Box3 nine = regions.area(ZoneRegions.PER_ROW + 1);
		assertEquals(first[0] + ZoneRegions.PER_ROW * ZoneRegions.SLOT, nine.maxX(), "a full row wide");
		assertEquals(first[1] + 2 * ZoneRegions.SLOT, nine.maxZ(), "two rows deep");
	}
}
