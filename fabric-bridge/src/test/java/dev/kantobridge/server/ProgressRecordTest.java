package dev.kantobridge.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.DataException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The progress record (MG3): a {@code game.status} read like its schema, and the codec that keeps it with the player. */
class ProgressRecordTest {
	private static final String STATUS = "{\"game\":\"blue\",\"region\":\"kanto\",\"playthrough\":\"saved\",\"ingame\":true,\"started\":true,"
			+ "\"badges\":2,\"markers\":{\"starter\":true,\"champion\":false},\"name\":\"TESTER\",\"slot\":1}";

	private static JsonObject json(String text) {
		try {
			return ProtoJson.parseObject(text);
		} catch (dev.kantobridge.proto.ProtocolException e) {
			throw new AssertionError(e);
		}
	}

	@Test
	void theExampleStatusIsRead() throws DataException {
		ProgressRecord.Status status = ProgressRecord.read(json(STATUS), 1_790_000_000L);
		assertEquals("blue", status.game());
		assertEquals("kanto", status.region());
		assertEquals(1, status.slot());
		ProgressRecord progress = status.progress();
		assertEquals(ProgressRecord.SAVED, progress.playthrough());
		assertTrue(progress.ingame() && progress.started() && progress.isStarted());
		assertEquals(2, progress.badges());
		assertEquals(Map.of("champion", false, "starter", true), progress.markers());
		assertTrue(progress.marker("starter"));
		assertFalse(progress.marker("champion"));
		assertFalse(progress.marker("unknown"), "a marker never reported is not reached");
		assertEquals("TESTER", progress.name());
		assertEquals(1_790_000_000L, progress.at());
	}

	@Test
	void aTitleStatusHasNoNameOrRegion() throws DataException {
		ProgressRecord.Status status = ProgressRecord.read(json("{\"game\":\"emerald\",\"playthrough\":\"title\",\"ingame\":false,"
				+ "\"started\":false,\"badges\":0,\"markers\":{},\"slot\":2}"), 5);
		assertNull(status.region());
		assertNull(status.progress().name());
		assertFalse(status.progress().isStarted());
		assertEquals(ProgressRecord.NONE.playthrough(), status.progress().playthrough());
	}

	@Test
	void malformedStatusesAreRefused() {
		assertRefused(STATUS.replace("\"saved\"", "\"done\""), "an unknown playthrough");
		assertRefused(STATUS.replace("\"badges\":2", "\"badges\":17"), "too many badges");
		assertRefused(STATUS.replace("\"starter\":true", "\"starter\":1"), "a marker that isn't a boolean");
		assertRefused(STATUS.replace("\"starter\"", "\"Starter\""), "a marker name outside the pattern");
		assertRefused(STATUS.replace("\"TESTER\"", "\"\""), "an empty name");
		assertRefused(STATUS.replace("\"kanto\"", "\"Kanto\""), "a region outside the pattern");
		assertRefused(STATUS.replace("\"slot\":1", "\"slot\":1,\"extra\":true"), "an extra field");
		assertRefused(STATUS.replace("\"ingame\":true,", ""), "a missing field");
	}

	private static void assertRefused(String text, String what) {
		assertThrows(DataException.class, () -> ProgressRecord.read(json(text), 0), what);
	}

	@Test
	void theCodecRoundTripsAndFillsDefaults() throws DataException {
		ProgressRecord record = ProgressRecord.read(json(STATUS), 42).progress();
		var encoded = ProgressRecord.CODEC.encodeStart(JsonOps.INSTANCE, record).getOrThrow();
		ProgressRecord decoded = ProgressRecord.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
		assertEquals(record, decoded);
		ProgressRecord bare = ProgressRecord.CODEC.parse(JsonOps.INSTANCE, json("{\"playthrough\":\"fresh\"}")).getOrThrow();
		assertEquals(new ProgressRecord(ProgressRecord.FRESH, false, false, 0, Map.of(), null, -1), bare);
		assertTrue(bare.isStarted(), "fresh means started");
	}

	@Test
	void theRecordChecksItsOwnLimits() {
		assertThrows(IllegalArgumentException.class, () -> new ProgressRecord("elsewhere", false, false, 0, Map.of(), null, -1));
		assertThrows(IllegalArgumentException.class, () -> new ProgressRecord(ProgressRecord.TITLE, false, false, 17, Map.of(), null, -1));
	}

	@Test
	void progressIsKeptPerRegion() {
		ProgressService.Progress progress = ProgressService.Progress.NONE
				.with("hoenn", new ProgressRecord(ProgressRecord.TITLE, false, false, 0, Map.of(), null, 1))
				.with("kanto", new ProgressRecord(ProgressRecord.SAVED, true, true, 3, Map.of("starter", true), "TESTER", 2));
		assertEquals(3, progress.get("kanto").badges());
		assertEquals(ProgressRecord.NONE, progress.get("johto"), "a region never reported is at its title");
		var encoded = ProgressService.Progress.CODEC.encodeStart(JsonOps.INSTANCE, progress).getOrThrow();
		assertEquals(progress, ProgressService.Progress.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
	}
}
