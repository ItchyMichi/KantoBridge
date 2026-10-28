package dev.kantobridge.builder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.kantobridge.world.RegionTable;
import dev.kantobridge.world.WorldRecord;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

/** WORLD_SESSIONS_PLAN WS6: which build sets the world spawn (D2): the record's start region, else slot 0 as MG3 did. */
class BuildTaskSpawnTest {
	private static final WorldRecord START_IN_JOHTO = new WorldRecord("johto",
			List.of(new WorldRecord.Game("blue", "kanto"), new WorldRecord.Game("crystal", "johto")));

	@Test
	void theStartRegionSetsTheSpawnAtAnySlot() {
		BuildOptions kanto = BuildOptions.defaults().withRegion("kanto", 0);
		BuildOptions johto = BuildOptions.defaults().withRegion("johto", 1);
		assertFalse(BuildTask.spawnHere(kanto, START_IN_JOHTO), "slot 0 isn't the start");
		assertTrue(BuildTask.spawnHere(johto, START_IN_JOHTO), "the start region at slot 1");
	}

	@Test
	void withoutARecordSlotZeroAndALegacyBuildDoAsBefore() {
		assertTrue(BuildTask.spawnHere(BuildOptions.defaults().withRegion("kanto", 0), null));
		assertFalse(BuildTask.spawnHere(BuildOptions.defaults().withRegion("johto", 1), null));
		assertTrue(BuildTask.spawnHere(BuildOptions.defaults(), START_IN_JOHTO), "a legacy build");
	}

	@Test
	void addingARegionAppendsItsGameAndKeepsTheStart(@TempDir Path data) throws Exception {
		RegionTable table = new RegionTable(List.of(new RegionTable.Entry("kanto", "blue", 0), new RegionTable.Entry("johto", "crystal", 1)));
		START_IN_JOHTO.write(data);
		BuildTask.appendToRecord(data, table.with(new RegionTable.Entry("hoenn", "emerald", 2)), "hoenn", "emerald");
		assertEquals(START_IN_JOHTO.with(new WorldRecord.Game("emerald", "hoenn")), WorldRecord.read(data));
		BuildTask.appendToRecord(data, table, "kanto", "blue");
		assertEquals(3, WorldRecord.read(data).games().size(), "a rebuilt region isn't listed twice");
	}

	@Test
	void aRegionWorldWithoutARecordGetsOneFromItsTable(@TempDir Path data) throws Exception {
		BuildTask.appendToRecord(data, new RegionTable(List.of(new RegionTable.Entry("kanto", "blue", 0))), "kanto", "blue");
		assertEquals(new WorldRecord("kanto", List.of(new WorldRecord.Game("blue", "kanto"))), WorldRecord.read(data));
	}
}
