package dev.kantobridge.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** WORLD_SESSIONS_PLAN WS2: the world record (kantobridge/world.json), strict reading, round trips, the legacy fallback. */
class WorldRecordTest {
	private static final WorldRecord KANTO_JOHTO = new WorldRecord("johto",
			List.of(new WorldRecord.Game("blue", "kanto"), new WorldRecord.Game("crystal", "johto")));

	@Test
	void aRecordRoundTripsCanonically() throws Exception {
		String text = KANTO_JOHTO.toJson();
		assertEquals("{\"games\":[{\"game\":\"blue\",\"region\":\"kanto\"},{\"game\":\"crystal\",\"region\":\"johto\"}],\"start\":\"johto\",\"version\":1}",
				text);
		assertEquals(KANTO_JOHTO, WorldRecord.fromJson(text));
		assertEquals("crystal", KANTO_JOHTO.startGame());
		assertEquals("johto", KANTO_JOHTO.regionOf("crystal"));
		assertNull(KANTO_JOHTO.gameOf("hoenn"));
	}

	@Test
	void theRulesAreTheSchemas() {
		assertThrows(DataException.class, () -> WorldRecord.fromJson("{\"version\":1,\"start\":\"hoenn\",\"games\":[{\"game\":\"blue\",\"region\":\"kanto\"}]}"),
				"the start must be a game's region");
		assertThrows(DataException.class, () -> WorldRecord.fromJson("{\"version\":1,\"start\":\"kanto\",\"games\":[{\"game\":\"blue\",\"region\":\"kanto\"},"
				+ "{\"game\":\"red\",\"region\":\"kanto\"}]}"), "one game per world space");
		assertThrows(DataException.class, () -> WorldRecord.fromJson("{\"version\":1,\"start\":\"kanto\",\"games\":[]}"), "at least one game");
		assertThrows(DataException.class, () -> WorldRecord.fromJson("{\"version\":2,\"start\":\"kanto\",\"games\":[{\"game\":\"blue\",\"region\":\"kanto\"}]}"));
		assertThrows(DataException.class, () -> WorldRecord.fromJson("{\"version\":1,\"start\":\"kanto\",\"extra\":1,\"games\":[{\"game\":\"blue\",\"region\":\"kanto\"}]}"));
	}

	@Test
	void aLegacyWorldsRecordComesFromItsTable() {
		RegionTable table = new RegionTable(List.of(new RegionTable.Entry("johto", "crystal", 1), new RegionTable.Entry("kanto", "blue", 0)));
		WorldRecord legacy = WorldRecord.legacy(table);
		assertEquals("kanto", legacy.start(), "slot 0's region");
		assertEquals(List.of(new WorldRecord.Game("blue", "kanto"), new WorldRecord.Game("crystal", "johto")), legacy.games());
		assertNull(WorldRecord.legacy(new RegionTable()));
	}

	@Test
	void addingARegionAppendsAndKeepsTheStart() {
		WorldRecord more = KANTO_JOHTO.with(new WorldRecord.Game("emerald", "hoenn"));
		assertEquals("johto", more.start());
		assertEquals(3, more.games().size());
		assertThrows(IllegalArgumentException.class, () -> KANTO_JOHTO.with(new WorldRecord.Game("red", "kanto")), "kanto is taken");
	}

	@Test
	void itIsWrittenIntoTheWorldsDataFolderAndReadBack(@TempDir Path dir) throws Exception {
		assertNull(WorldRecord.read(dir), "no file: a legacy world");
		KANTO_JOHTO.write(dir);
		assertEquals(KANTO_JOHTO, WorldRecord.read(dir));
		assertEquals(KANTO_JOHTO.toJson() + "\n", Files.readString(dir.resolve(WorldRecord.FILE)));
	}
}
