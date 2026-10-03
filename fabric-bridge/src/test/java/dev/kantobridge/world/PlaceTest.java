package dev.kantobridge.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.kantobridge.proto.ProtoJson;
import org.junit.jupiter.api.Test;

/** The protocol's place: strict reading (Java doesn't check per-type schemas), writing, and its range. */
class PlaceTest {
	private static Place read(String json) throws Exception {
		return Place.fromJson(ProtoJson.parse(json));
	}

	@Test
	void readsAndWritesThePlaceShape() throws Exception {
		Place place = read("{\"map\":\"TEST_HOUSE\",\"x\":4,\"y\":8,\"facing\":\"up\"}");
		assertEquals(new Place("TEST_HOUSE", 4, 8, Dir.UP), place);
		assertEquals("{\"map\":\"TEST_HOUSE\",\"x\":4,\"y\":8,\"facing\":\"up\"}", place.toJson().toString());
		assertEquals(new CellPos("TEST_HOUSE", 4, 8), place.cell());
		assertEquals(new Place("A_MAP", -1, 0, Dir.LEFT), read("{\"map\":\"A_MAP\",\"x\":-1,\"y\":0,\"facing\":\"left\"}"),
				"one cell past an edge is allowed");
	}

	@Test
	void rejectsAnythingOffSchema() throws Exception {
		assertNull(read("{\"map\":\"TEST_HOUSE\",\"x\":4,\"y\":8}"), "no facing");
		assertNull(read("{\"map\":\"TEST_HOUSE\",\"x\":4,\"y\":8,\"facing\":\"north\"}"));
		assertNull(read("{\"map\":\"test_house\",\"x\":4,\"y\":8,\"facing\":\"up\"}"), "map ids are upper case");
		assertNull(read("{\"map\":\"TEST_HOUSE\",\"x\":4.5,\"y\":8,\"facing\":\"up\"}"));
		assertNull(read("{\"map\":\"TEST_HOUSE\",\"x\":\"4\",\"y\":8,\"facing\":\"up\"}"));
		assertNull(read("{\"map\":\"TEST_HOUSE\",\"x\":4097,\"y\":8,\"facing\":\"up\"}"));
		assertNull(read("{\"map\":\"TEST_HOUSE\",\"x\":4,\"y\":8,\"facing\":\"up\",\"kind\":\"walk\"}"), "extra fields");
		assertNull(read("[1,2]"));
		assertNull(Place.fromJson(null));
		assertThrows(IllegalArgumentException.class, () -> new Place("bad id", 0, 0, Dir.UP));
	}
}
