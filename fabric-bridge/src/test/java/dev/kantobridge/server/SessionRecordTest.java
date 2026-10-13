package dev.kantobridge.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.proto.Ids;
import dev.kantobridge.proto.ProtocolException;
import dev.kantobridge.world.DataException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The world's session record: a stable id across restarts, the pairing per player, and broken files kept aside. */
class SessionRecordTest {
	private static final UUID ALICE = UUID.fromString("0f8e9a1c-2b3d-4e5f-8a9b-0c1d2e3f4a5b");
	private static final UUID BOB = UUID.fromString("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d");

	@TempDir
	Path dir;

	private Path file() {
		return this.dir.resolve("kantobridge").resolve(SessionRecord.FILE);
	}

	@Test
	void theFirstStartCreatesAnIdThatLaterStartsRead() throws IOException {
		SessionRecord.Loaded first = SessionRecord.loadOrCreate(this.file());
		assertEquals(SessionRecord.Origin.CREATED, first.origin());
		assertTrue(Files.isRegularFile(this.file()), "the record is written at once, so a crash can't lose the id");
		String world = first.record().world();
		assertTrue(Ids.ID.matcher(world).matches(), "the id must be a protocol id");
		assertTrue(Ids.UID.matcher(world).matches(), "a lowercase UUID");
		// A restart (and another, and a copy of the world folder) reads the same id.
		for (int i = 0; i < 3; i++) {
			SessionRecord.Loaded again = SessionRecord.loadOrCreate(this.file());
			assertEquals(SessionRecord.Origin.READ, again.origin());
			assertEquals(world, again.record().world());
		}
		Path copy = this.dir.resolve("copy").resolve(SessionRecord.FILE);
		Files.createDirectories(copy.getParent());
		Files.copy(this.file(), copy);
		assertEquals(world, SessionRecord.loadOrCreate(copy).record().world());
		// Another world gets another id.
		assertNotEquals(world, SessionRecord.loadOrCreate(this.dir.resolve("other").resolve(SessionRecord.FILE)).record().world());
	}

	@Test
	void slotsArePairedPerPlayerAndSurviveARestart() throws IOException {
		SessionRecord record = SessionRecord.loadOrCreate(this.file()).record();
		assertNull(record.slot(ALICE));
		assertTrue(record.put(ALICE, 2));
		assertFalse(record.put(ALICE, 2), "the same slot again changes nothing");
		assertTrue(record.put(BOB, 0));
		assertTrue(record.put(ALICE, 5), "a new slot replaces the old one");
		record.write(this.file());
		SessionRecord read = SessionRecord.loadOrCreate(this.file()).record();
		assertEquals(record.world(), read.world());
		assertEquals(5, read.slot(ALICE));
		assertEquals(0, read.slot(BOB));
		assertEquals(2, read.players());
		assertThrows(IllegalArgumentException.class, () -> record.put(ALICE, 256));
		assertThrows(IllegalArgumentException.class, () -> record.put(ALICE, -1));
		assertFalse(Files.exists(this.file().resolveSibling(SessionRecord.FILE + ".tmp")), "no temporary file is left behind");
	}

	@Test
	void theFileIsCanonicalJson() throws IOException {
		SessionRecord record = SessionRecord.loadOrCreate(this.file()).record();
		record.put(ALICE, 3);
		record.write(this.file());
		String text = Files.readString(this.file(), StandardCharsets.UTF_8);
		assertEquals("{\"format\":1,\"players\":{\"" + ALICE + "\":{\"slot\":3}},\"world\":\"" + record.world() + "\"}\n", text);
	}

	@Test
	void regionsPairTheirOwnSlotsInFormatTwo() throws IOException, DataException, ProtocolException {
		// MG3: the legacy region keeps format 1's one slot; another region's slot goes under `regions`
		SessionRecord record = SessionRecord.loadOrCreate(this.file()).record();
		assertTrue(record.put(ALICE, 2));
		assertEquals(SessionRecord.FORMAT, record.format(), "a legacy pairing alone keeps format 1");
		assertTrue(record.put(ALICE, "hoenn", 4));
		assertFalse(record.put(ALICE, "hoenn", 4), "the same slot again changes nothing");
		assertTrue(record.put(BOB, "hoenn", 1));
		assertEquals(SessionRecord.FORMAT_2, record.format());
		assertEquals(2, record.slot(ALICE));
		assertEquals(2, record.slot(ALICE, null), "null is the legacy region");
		assertEquals(4, record.slot(ALICE, "hoenn"));
		assertNull(record.slot(ALICE, "johto"));
		assertNull(record.slot(BOB), "Bob never paired the legacy region");
		assertEquals(java.util.Map.of("hoenn", 4), record.regions(ALICE));
		record.write(this.file());
		String text = Files.readString(this.file(), StandardCharsets.UTF_8);
		assertEquals("{\"format\":2,\"players\":{\"" + ALICE + "\":{\"regions\":{\"hoenn\":{\"slot\":4}},\"slot\":2},\"" + BOB
				+ "\":{\"regions\":{\"hoenn\":{\"slot\":1}}}},\"world\":\"" + record.world() + "\"}\n", text);
		SessionRecord read = SessionRecord.parse(text);
		assertEquals(2, read.slot(ALICE));
		assertEquals(4, read.slot(ALICE, "hoenn"));
		assertEquals(1, read.slot(BOB, "hoenn"));
		assertNull(read.slot(BOB));
		assertEquals(2, read.players());
		assertThrows(IllegalArgumentException.class, () -> record.put(ALICE, "Hoenn", 1));
	}

	@Test
	void formatTwoIsReadStrictly() {
		String world = "\"world\":\"0f8e9a1c-2b3d-4e5f-8a9b-0c1d2e3f4a5b\"";
		assertRejected("{\"format\":2," + world + ",\"players\":{\"" + ALICE + "\":{}}}");
		assertRejected("{\"format\":2," + world + ",\"players\":{\"" + ALICE + "\":{\"regions\":{\"Hoenn\":{\"slot\":1}}}}}");
		assertRejected("{\"format\":2," + world + ",\"players\":{\"" + ALICE + "\":{\"regions\":{\"hoenn\":{\"slot\":300}}}}}");
		assertRejected("{\"format\":2," + world + ",\"players\":{\"" + ALICE + "\":{\"regions\":{\"hoenn\":{\"slot\":1,\"x\":2}}}}}");
		assertRejected("{\"format\":1," + world + ",\"players\":{\"" + ALICE + "\":{\"slot\":1,\"regions\":{}}}}");
		assertRejected("{\"format\":3," + world + ",\"players\":{}}");
	}

	@Test
	void anUnreadableRecordIsMovedAsideAndReplaced() throws IOException {
		Files.createDirectories(this.file().getParent());
		Files.writeString(this.file(), "{\"format\":1,\"world\":", StandardCharsets.UTF_8);
		SessionRecord.Loaded loaded = SessionRecord.loadOrCreate(this.file());
		assertEquals(SessionRecord.Origin.REPLACED, loaded.origin());
		Path broken = this.file().resolveSibling(SessionRecord.FILE + SessionRecord.BROKEN_SUFFIX);
		assertEquals("{\"format\":1,\"world\":", Files.readString(broken, StandardCharsets.UTF_8), "the broken file is kept for recovery");
		assertEquals(loaded.record().world(), SessionRecord.loadOrCreate(this.file()).record().world(), "the new id sticks");
	}

	@Test
	void recordsAreReadStrictly() {
		String world = "\"world\":\"0f8e9a1c-2b3d-4e5f-8a9b-0c1d2e3f4a5b\"";
		assertRejected("{\"format\":3," + world + ",\"players\":{}}"); // format 2 exists since MG3 (regions)
		assertRejected("{\"format\":1,\"world\":\"not an id!\",\"players\":{}}");
		assertRejected("{\"format\":1," + world + ",\"players\":{\"someone\":{\"slot\":1}}}");
		assertRejected("{\"format\":1," + world + ",\"players\":{\"" + ALICE + "\":{\"slot\":300}}}");
		assertRejected("{\"format\":1," + world + ",\"players\":{\"" + ALICE + "\":{\"slot\":1,\"x\":2}}}");
		assertRejected("{\"format\":1," + world + ",\"players\":{},\"extra\":true}");
		assertRejected("{\"format\":1," + world + "}");
		assertRejected("[]");
	}

	private static void assertRejected(String json) {
		try {
			SessionRecord.parse(json);
		} catch (DataException | ProtocolException e) {
			return;
		}
		throw new AssertionError("accepted a bad record");
	}
}
