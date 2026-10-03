package dev.kantobridge.proto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Redaction must match protocol/redaction-vectors.json exactly, so Java, Python and Lua logs agree. */
class RedactTest {
	@Test
	void placeholdersMatchVectors() throws Exception {
		JsonArray placeholders = vectors().getAsJsonArray("placeholders");
		assertFalse(placeholders.isEmpty());
		for (JsonElement vector : placeholders) {
			JsonObject v = vector.getAsJsonObject();
			assertEquals(v.get("placeholder").getAsString(), Redact.placeholder(v.get("value")), ProtoJson.write(v.get("value")));
		}
	}

	@Test
	void everyExampleRedactsExactlyAsTheVectorsSay() throws Exception {
		Map<String, JsonObject> expected = new HashMap<>();
		for (JsonElement vector : vectors().getAsJsonArray("messages")) {
			JsonObject v = vector.getAsJsonObject();
			expected.put(v.get("type").getAsString() + "#" + v.get("seq").getAsLong(), v.getAsJsonObject("redacted"));
		}
		Catalog catalog = Catalog.bundled();
		int checked = 0;
		for (ProtocolFiles.Example example : ProtocolFiles.examples()) {
			String key = example.type() + "#" + example.seq();
			JsonObject want = expected.get(key);
			assertNotNull(want, "no redaction vector for " + key);
			Envelope envelope = Envelope.fromJson(example.json());
			JsonObject redacted = Redact.envelope(catalog, envelope);
			assertEquals(want, redacted.get("data"), key);
			// The envelope itself is untouched by redaction.
			assertEquals(envelope.type(), redacted.get("type").getAsString());
			assertEquals(envelope.seq(), redacted.get("seq").getAsLong());
			checked++;
		}
		assertEquals(expected.size(), checked, "vectors and examples differ in number");
	}

	@Test
	void redactionNeverChangesItsInput() throws Exception {
		JsonObject data = ProtoJson.parseObject("{\"pages\":[[\"line\"]],\"ui\":3}");
		JsonObject copy = data.deepCopy();
		JsonObject redacted = Redact.data(List.of("/pages"), data);
		assertEquals(copy, data);
		assertTrue(redacted.get("pages").getAsString().startsWith("<rom:arr:1:"));
	}

	@Test
	void wildcardsCoverArraysAndObjects() throws Exception {
		JsonObject data = ProtoJson.parseObject(
				"{\"party\":[{\"nick\":\"A\",\"dex\":1},{\"dex\":2},{\"nick\":\"é\",\"dex\":3}],\"m\":{\"x\":{\"n\":\"q\"},\"y\":{}}}");
		JsonObject redacted = Redact.data(List.of("/m/*/n", "/missing/path", "/party/*/nick", "/party/9/nick"), data);
		JsonArray party = redacted.getAsJsonArray("party");
		assertEquals(Redact.placeholder(data.getAsJsonArray("party").get(0).getAsJsonObject().get("nick")),
				party.get(0).getAsJsonObject().get("nick").getAsString());
		assertFalse(party.get(1).getAsJsonObject().has("nick"));
		assertEquals(3, party.get(2).getAsJsonObject().get("dex").getAsInt());
		assertTrue(party.get(2).getAsJsonObject().get("nick").getAsString().startsWith("<rom:str:1:"));
		assertTrue(redacted.getAsJsonObject("m").getAsJsonObject("x").get("n").getAsString().startsWith("<rom:str:1:"));
		assertEquals(0, redacted.getAsJsonObject("m").getAsJsonObject("y").size());
	}

	@Test
	void pointerTokensUnescape() {
		assertEquals(List.of("a/b", "~c", "*"), Redact.pointerTokens("/a~1b/~0c/*"));
		assertEquals(List.of(), Redact.pointerTokens(""));
	}

	@Test
	void unknownTypesKeepNoData() throws Exception {
		Envelope envelope = Envelope.parse("{\"type\":\"made.up\",\"seq\":1,\"data\":{\"text\":\"secret\"}}");
		JsonObject redacted = Redact.envelope(Catalog.bundled(), envelope);
		assertTrue(redacted.get("data").getAsString().startsWith("<rom:obj:1:"));
	}

	private static JsonObject vectors() throws Exception {
		return ProtocolFiles.readJson("redaction-vectors.json").getAsJsonObject();
	}
}
