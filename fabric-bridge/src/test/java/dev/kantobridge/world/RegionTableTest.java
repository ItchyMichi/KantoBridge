package dev.kantobridge.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The region table (MULTI_GAME_PLAN §4.6): slots along +X, one id and one slot each, canonical JSON. */
class RegionTableTest {
	private static RegionTable kantoAndHoenn() {
		// given out of slot order: the table sorts by slot
		return new RegionTable(List.of(new RegionTable.Entry("hoenn", "emerald", 1), new RegionTable.Entry("kanto", "blue", 0)));
	}

	@Test
	void slotsHaveOriginsAlongXPitchApart() {
		RegionTable table = kantoAndHoenn();
		assertEquals(8192, table.pitch());
		assertEquals(0, table.originX(0));
		assertEquals(8192, table.originX(1));
		assertEquals(3 * 8192, table.originX(3));
		assertEquals(0, table.originZ(1), "one row of slots along +X (D8)");
		assertEquals(List.of("kanto", "hoenn"), table.entries().stream().map(RegionTable.Entry::id).toList(), "sorted by slot");
		assertEquals("kanto", table.bySlot(0).id());
		assertEquals("emerald", table.byId("hoenn").game());
		assertNull(table.byId("johto"));
		assertNull(table.bySlot(2));
		assertEquals(2, table.nextFreeSlot());
		assertEquals(2, table.size());
		assertTrue(RegionTable.empty().isEmpty());
		assertEquals(0, RegionTable.empty().nextFreeSlot());
	}

	@Test
	void withReplacesByIdAndRefusesASlotAnotherRegionHolds() {
		RegionTable table = kantoAndHoenn();
		RegionTable ruby = table.with(new RegionTable.Entry("hoenn", "ruby", 1));
		assertEquals("ruby", ruby.byId("hoenn").game());
		assertEquals(2, ruby.size());
		assertThrows(IllegalArgumentException.class, () -> table.with(new RegionTable.Entry("johto", "gold", 1)), "slot 1 is hoenn's");
		RegionTable moved = table.with(new RegionTable.Entry("kanto", "blue", 2));
		assertEquals(0, moved.nextFreeSlot(), "kanto left slot 0");
		assertEquals(List.of("hoenn", "kanto"), moved.entries().stream().map(RegionTable.Entry::id).toList());
		RegionTable johto = table.with(new RegionTable.Entry("johto", "gold", 2));
		assertEquals(3, johto.size());
		assertEquals(1, table.without("hoenn").size());
		assertEquals(2, table.without("johto").size(), "removing an absent region changes nothing");
	}

	@Test
	void canonicalJsonRoundTrip() throws Exception {
		RegionTable table = kantoAndHoenn();
		assertEquals("{\"pitch\":8192,\"regions\":[{\"game\":\"blue\",\"id\":\"kanto\",\"slot\":0},{\"game\":\"emerald\",\"id\":\"hoenn\",\"slot\":1}],"
				+ "\"version\":1}", table.toJson());
		RegionTable read = RegionTable.fromJson(table.toJson());
		assertEquals(table.toJson(), read.toJson());
		assertEquals(table.hash(), read.hash());
		assertEquals(table.entries(), read.entries());
		assertEquals("{\"pitch\":8192,\"regions\":[],\"version\":1}", RegionTable.empty().toJson());
		assertEquals(4096, RegionTable.fromJson("{\"pitch\":4096,\"regions\":[],\"version\":1}").pitch());
	}

	@Test
	void readingIsStrict() {
		String ok = "{\"pitch\":8192,\"regions\":[{\"game\":\"blue\",\"id\":\"kanto\",\"slot\":0}],\"version\":1}";
		assertThrows(DataException.class, () -> RegionTable.fromJson(ok.replace("\"version\":1", "\"version\":2")), "version");
		assertThrows(DataException.class, () -> RegionTable.fromJson(ok.replace("\"pitch\":8192", "\"pitch\":100")), "pitch");
		assertThrows(DataException.class, () -> RegionTable.fromJson(ok.replace("\"game\":\"blue\"", "\"game\":\"nope\"")), "unknown game");
		assertThrows(DataException.class, () -> RegionTable.fromJson(ok.replace("\"id\":\"kanto\"", "\"id\":\"Kanto\"")), "bad id");
		assertThrows(DataException.class, () -> RegionTable.fromJson(ok.replace("\"slot\":0", "\"slot\":64")), "slot range");
		assertThrows(DataException.class, () -> RegionTable.fromJson(ok.replace("\"slot\":0", "\"slot\":0,\"origin\":1")), "extra field");
		assertThrows(DataException.class, () -> RegionTable.fromJson(ok.replace("\"version\":1", "\"version\":1,\"extra\":1")), "extra root field");
		assertThrows(DataException.class, () -> RegionTable.fromJson(
				"{\"pitch\":8192,\"regions\":[{\"game\":\"blue\",\"id\":\"kanto\",\"slot\":0},{\"game\":\"red\",\"id\":\"kanto\",\"slot\":1}],\"version\":1}"),
				"duplicate id");
		assertThrows(DataException.class, () -> RegionTable.fromJson(
				"{\"pitch\":8192,\"regions\":[{\"game\":\"blue\",\"id\":\"kanto\",\"slot\":0},{\"game\":\"red\",\"id\":\"test2\",\"slot\":0}],\"version\":1}"),
				"two regions in one slot");
		assertThrows(DataException.class, () -> RegionTable.fromJson("not json"));
	}

	@Test
	void constructorRefusesWhatTheReaderRefuses() {
		assertThrows(IllegalArgumentException.class, () -> new RegionTable(List.of(new RegionTable.Entry("kanto", "blue", 0),
				new RegionTable.Entry("kanto", "red", 1))), "duplicate id");
		assertThrows(IllegalArgumentException.class, () -> new RegionTable(List.of(new RegionTable.Entry("kanto", "blue", 0),
				new RegionTable.Entry("test2", "red", 0))), "one slot");
		assertThrows(IllegalArgumentException.class, () -> new RegionTable(100, List.of()), "pitch");
		assertThrows(IllegalArgumentException.class, () -> new RegionTable.Entry("kanto", "nope", 0), "unknown game");
		assertThrows(IllegalArgumentException.class, () -> new RegionTable.Entry("Kanto", "blue", 0), "bad id");
		assertThrows(IllegalArgumentException.class, () -> new RegionTable.Entry("kanto", "blue", 64), "slot");
	}
}
