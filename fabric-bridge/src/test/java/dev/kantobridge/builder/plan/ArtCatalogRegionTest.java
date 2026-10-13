package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.world.SlotMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** MG3: the catalogue assigns a region's keys inside its family range of the one world-level slot map. */
class ArtCatalogRegionTest {
	private static ExportBundle bundle;

	@BeforeAll
	static void open() throws Exception {
		bundle = ExportBundle.open(TestData.syntheticBundle());
	}

	@Test
	void theLegacyRegionGetsTheLegacySlots() throws Exception {
		ArtCatalog legacy = ArtCatalog.of(bundle, new Warnings());
		ArtCatalog kanto = ArtCatalog.of(bundle, new Warnings(), new RegionBuild("kanto", 0, "red", SlotMap.LEGACY_FAMILIES, null, ""));
		assertEquals(legacy.slotMap().toJson(), kanto.slotMap().toJson(), "the slice is the legacy map");
		assertEquals(legacy.slotMap().hash(), kanto.slotMap().hash());
		assertEquals(1, legacy.worldSlotMap().version());
		assertEquals(2, kanto.worldSlotMap().version());
		assertEquals(legacy.slotMap().hash(), kanto.worldSlotMap().regionHash("kanto"));
		for (Map.Entry<SlotMap.Key, SlotMap.Slot> entry : legacy.slotMap().slots().entrySet()) {
			assertEquals(entry.getValue(), kanto.worldSlotMap().slot("kanto", entry.getKey()), "the same slot through the region lookup");
		}
	}

	@Test
	void anotherRegionJoinsTheWorldMapInItsOwnRange() throws Exception {
		ArtCatalog kanto = ArtCatalog.of(bundle, new Warnings(), new RegionBuild("kanto", 0, "red", SlotMap.LEGACY_FAMILIES, null, ""));
		SlotMap.Range range = new SlotMap.Range(16, 31);
		ArtCatalog second = ArtCatalog.of(bundle, new Warnings(), new RegionBuild("test2", 1, "red", range, kanto.worldSlotMap(), "test2_"));
		SlotMap world = second.worldSlotMap();
		assertEquals(Map.of("kanto", new SlotMap.RegionSlots("red", SlotMap.LEGACY_FAMILIES), "test2", new SlotMap.RegionSlots("red", range)),
				world.regions());
		assertEquals(kanto.worldSlotMap().regionHash("kanto"), world.regionHash("kanto"), "kanto's slice is untouched");
		assertEquals(second.slotMap().hash(), world.regionHash("test2"));
		assertNotEquals(world.regionHash("kanto"), world.regionHash("test2"), "other families, other slots");
		assertEquals(kanto.slotMap().size(), second.slotMap().size());
		for (SlotMap.Slot slot : second.slotMap().slots().values()) {
			assertTrue(range.contains(slot.family()), "family " + slot.family());
		}
		assertEquals(kanto.slotMap().size() + second.slotMap().size(), world.size());
	}

	@Test
	void aRangeAnotherRegionHoldsIsRefused() throws Exception {
		ArtCatalog kanto = ArtCatalog.of(bundle, new Warnings(), new RegionBuild("kanto", 0, "red", SlotMap.LEGACY_FAMILIES, null, ""));
		PlanException refused = assertThrows(PlanException.class, () -> ArtCatalog.of(bundle, new Warnings(),
				new RegionBuild("test2", 1, "red", new SlotMap.Range(8, 23), kanto.worldSlotMap(), "test2_")));
		assertEquals("slot_map.region", refused.code());
	}
}
