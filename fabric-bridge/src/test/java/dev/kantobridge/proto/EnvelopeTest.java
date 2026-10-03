package dev.kantobridge.proto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class EnvelopeTest {
	@Test
	void parsesAndWritesCanonically() throws ProtocolException {
		Envelope envelope = Envelope.parse("{\"data\":{\"t\":5},\"type\":\"pong\",\"re\":3,\"seq\":7}");
		assertEquals("pong", envelope.type());
		assertEquals(7, envelope.seq());
		assertEquals(3L, envelope.re());
		assertEquals(5, envelope.data().get("t").getAsInt());
		assertEquals("{\"data\":{\"t\":5},\"re\":3,\"seq\":7,\"type\":\"pong\"}", envelope.toJson());
	}

	@Test
	void replyFieldIsOptional() throws ProtocolException {
		Envelope envelope = Envelope.parse("{\"type\":\"resync\",\"seq\":1,\"data\":{}}");
		assertNull(envelope.re());
		assertEquals("{\"data\":{},\"seq\":1,\"type\":\"resync\"}", envelope.toJson());
	}

	@Test
	void rejectsBadEnvelopes() {
		String[][] cases = {
			{"{\"type\":\"ping\",\"data\":{}}", "envelope.seq"},
			{"{\"type\":\"ping\",\"seq\":0,\"data\":{}}", "envelope.seq"},
			{"{\"type\":\"ping\",\"seq\":1.0,\"data\":{}}", "envelope.seq"},
			{"{\"type\":\"ping\",\"seq\":9007199254740992,\"data\":{}}", "envelope.seq"},
			{"{\"type\":\"ping\",\"seq\":99999999999999999999,\"data\":{}}", "envelope.seq"},
			{"{\"type\":\"ping\",\"seq\":\"1\",\"data\":{}}", "envelope.seq"},
			{"{\"type\":\"pong\",\"seq\":1,\"re\":0,\"data\":{}}", "envelope.re"},
			{"{\"type\":\"Ping\",\"seq\":1,\"data\":{}}", "envelope.type"},
			{"{\"type\":\"ui-open\",\"seq\":1,\"data\":{}}", "envelope.type"},
			{"{\"type\":\"ui..open\",\"seq\":1,\"data\":{}}", "envelope.type"},
			{"{\"type\":7,\"seq\":1,\"data\":{}}", "envelope.type"},
			{"{\"type\":\"ping\",\"seq\":1,\"data\":[]}", "envelope.data"},
			{"{\"type\":\"ping\",\"seq\":1}", "envelope.data"},
			{"{\"type\":\"ping\",\"seq\":1,\"data\":{},\"id\":2}", "envelope.field"},
			{"[1]", "json.not_object"},
		};
		for (String[] c : cases) {
			ProtocolException e = assertThrows(ProtocolException.class, () -> Envelope.parse(c[0]), c[0]);
			assertEquals(c[1], e.code(), c[0]);
		}
	}

	@Test
	void constructorGuardsRanges() {
		JsonObject data = new JsonObject();
		assertThrows(IllegalArgumentException.class, () -> Envelope.of("ping", 0, data));
		assertThrows(IllegalArgumentException.class, () -> Envelope.of("ping", Envelope.MAX_SEQ + 1, data));
		assertThrows(IllegalArgumentException.class, () -> Envelope.of("Bad.Type", 1, data));
		assertThrows(IllegalArgumentException.class, () -> Envelope.reply("pong", 1, 0, data));
	}
}
