package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.world.RegionRecord;
import dev.kantobridge.world.RegionTable;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.WorldIndex;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * MG3 (docs/specs/MULTI_GAME_PLAN.md §4.6): the synthetic bundle planned as a legacy build, as the legacy region at slot 0
 * (the same bytes) and as a second region at slot 1 (everything shifted by the slot's origin, its slots in its own
 * family range, its pools prefixed).
 */
class RegionBoundsTest {
	private static final int SHIFT = RegionTable.PITCH;
	private static ExportBundle bundle;
	private static WorldPlan legacy;
	private static WorldPlan kanto;
	private static WorldPlan second;

	@BeforeAll
	static void plan() throws Exception {
		bundle = ExportBundle.open(TestData.syntheticBundle());
		WorldPlanner.Options at0 = new WorldPlanner.Options(null, TestData.ORIGIN_X, TestData.ORIGIN_Z, TestData.MIN_BUILD_HEIGHT);
		legacy = WorldPlanner.plan(bundle, at0);
		kanto = WorldPlanner.plan(bundle, at0, new RegionBuild("kanto", 0, "red", SlotMap.LEGACY_FAMILIES, null, RegionBuild.poolPrefix("kanto", 0)));
		WorldPlanner.Options at1 = new WorldPlanner.Options(null, TestData.ORIGIN_X + SHIFT, TestData.ORIGIN_Z, TestData.MIN_BUILD_HEIGHT);
		second = WorldPlanner.plan(bundle, at1,
				new RegionBuild("test2", 1, "red", new SlotMap.Range(16, 31), kanto.worldSlotMap(), RegionBuild.poolPrefix("test2", 1)));
	}

	@Test
	void theLegacyRegionAtSlotZeroIsTodaysBuild() {
		assertEquals(legacy.index().toJson(), kanto.index().toJson(), "the same index, byte for byte");
		assertEquals(legacy.slotMap().hash(), kanto.slotMap().hash(), "the region's slice hashes like the legacy map");
		assertEquals(legacy.slotMap().toJson(), kanto.slotMap().toJson());
		assertEquals(legacy.pools().keySet(), kanto.pools().keySet(), "no prefix at slot 0");
		assertEquals(1, legacy.worldSlotMap().version());
		assertEquals(2, kanto.worldSlotMap().version());
		assertEquals(Map.of("kanto", new SlotMap.RegionSlots("red", SlotMap.LEGACY_FAMILIES)), kanto.worldSlotMap().regions());
		assertEquals(kanto.slotMap().hash(), kanto.worldSlotMap().regionHash("kanto"));
		assertEquals("", kanto.poolPrefix());
		assertNotNull(kanto.region());
	}

	@Test
	void aSecondRegionIsTheSamePlanMovedToItsSlot() {
		RegionRecord.Box here = kanto.regionBounds();
		RegionRecord.Box there = second.regionBounds();
		assertNotNull(here);
		assertNotNull(there);
		assertEquals(here.minX() + SHIFT, there.minX());
		assertEquals(here.maxX() + SHIFT, there.maxX());
		assertEquals(here.minZ(), there.minZ());
		assertEquals(here.maxZ(), there.maxZ());
		assertEquals(here.minY(), there.minY());
		assertEquals(here.maxY(), there.maxY());
		assertFalse(here.overlaps(there), "8,192 blocks apart, the two never overlap");
		RegionRecord.Areas areas = second.regionAreas();
		assertEquals(kanto.regionAreas().surface().x() + SHIFT, areas.surface().x());
		assertEquals(kanto.regionAreas().surface().w(), areas.surface().w());
		assertEquals(second.surfaceBox().minX(), areas.surface().x(), "the surface area is the surface box");
		assertEquals(second.surfaceBox().maxX() - second.surfaceBox().minX(), areas.surface().w());
		assertTrue(areas.zones().isEmpty(), "the synthetic bundle lists no zones");
		assertTrue(areas.dungeons().isEmpty());
		// every volume lies inside the bounds
		for (Box3 v : second.volumes()) {
			assertTrue(there.contains(v.minX(), v.minZ()) && there.contains(v.maxX() - 1, v.maxZ() - 1), "volume inside the bounds");
			assertTrue(v.minY() >= there.minY() && v.maxY() - 1 <= there.maxY());
		}
		for (WorldIndex.Habitat habitat : second.index().habitats()) {
			assertTrue(there.contains(habitat.x(), habitat.z()));
		}
	}

	@Test
	void aSecondRegionTakesItsOwnFamiliesAndPrefixes() {
		SlotMap world = second.worldSlotMap();
		assertEquals(2, world.version());
		assertEquals(new SlotMap.Range(16, 31), world.families("test2"));
		assertEquals(SlotMap.LEGACY_FAMILIES, world.families("kanto"), "kanto's entries came along untouched");
		assertEquals(kanto.worldSlotMap().regionHash("kanto"), world.regionHash("kanto"));
		assertEquals(second.slotMap().hash(), world.regionHash("test2"));
		assertEquals(second.slotMap().hash(), second.index().slotMapHash(), "the index names the region's slice");
		for (SlotMap.Slot slot : second.slotMap().slots().values()) {
			assertTrue(slot.family() >= 16 && slot.family() <= 31, "family " + slot.family());
		}
		assertEquals(kanto.slotMap().size(), second.slotMap().size(), "the same keys, other slots");
		assertEquals("test2_", second.poolPrefix());
		assertFalse(second.pools().isEmpty());
		for (String pool : second.pools().keySet()) {
			assertTrue(pool.startsWith("kantobridge:test2_"), pool);
		}
		for (WorldIndex.Habitat habitat : second.index().habitats()) {
			assertTrue(habitat.pool().startsWith("kantobridge:test2_"), habitat.pool());
		}
		assertEquals(kanto.pools().size(), second.pools().size());
	}
}
