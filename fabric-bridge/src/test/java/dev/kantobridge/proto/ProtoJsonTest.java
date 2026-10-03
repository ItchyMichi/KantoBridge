package dev.kantobridge.proto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class ProtoJsonTest {
	// Expected strings come from Python: json.dumps(v, sort_keys=True, separators=(",", ":"), ensure_ascii=False).

	@Test
	void sortsKeysByCodePointLikePython() throws ProtocolException {
		assertCanonical("{\"\u00e9\":\"x\",\"b\":1,\"a\":[true,null]}", "{\"a\":[true,null],\"b\":1,\"\u00e9\":\"x\"}");
		// U+FFFF sorts before U+1F600 by code point, although its UTF-16 unit is larger than the surrogate's.
		assertCanonical("{\"\ud83d\ude00\":2,\"\uffff\":1,\"z\":3,\"A\":4}", "{\"A\":4,\"z\":3,\"\uffff\":1,\"\ud83d\ude00\":2}");
		assertCanonical("{\"nested\":{\"y\":[{\"b\":2,\"a\":1}],\"x\":null}}", "{\"nested\":{\"x\":null,\"y\":[{\"a\":1,\"b\":2}]}}");
	}

	@Test
	void escapesExactlyLikePython() throws ProtocolException {
		String input = "\"ctl:\\u0000\\u0001\\u001F\u007f\\b\\f\\n\\r\\t\\\"\\\\\\/ <>&'= \u2028\u2029 \u00e9\\ud83d\\ude00\"";
		String expected = "\"ctl:\\u0000\\u0001\\u001f\u007f\\b\\f\\n\\r\\t\\\"\\\\/ <>&'= \u2028\u2029 \u00e9\ud83d\ude00\"";
		assertCanonical(input, expected);
	}

	@Test
	void writesIntegersPlainly() throws ProtocolException {
		assertCanonical("[0, -1, 9007199254740991, -9007199254740991, 18446744073709551616]",
				"[0,-1,9007199254740991,-9007199254740991,18446744073709551616]");
		assertCanonical("-0", "0");
		assertCanonical(" { } ", "{}");
		assertCanonical("[ ]", "[]");
	}

	@Test
	void rejectsAnythingNotStrictJson() {
		String[] bad = {
			"NaN", "[Infinity]", "-Infinity", "[1,]", "{\"a\":1,}", "{'a':1}", "{a:1}", "// c\n{}", "01", "1.", ".5", "+1", "1e",
			"\"tab\there\"", "\"\\x\"", "\"\\u12G4\"", "\"\\ud800\"", "\"\\udc00\"", "\"\\ud800\\u0041\"", "{\"a\":1,\"a\":2}",
			"{} {}", "{}x", "tru", "nul", "\"open", "",
		};
		for (String text : bad) {
			assertThrows(ProtocolException.class, () -> ProtoJson.parse(text), text);
		}
	}

	@Test
	void allowsWhitespaceAroundTheValue() {
		assertTrue(parses(" \t[1] \r\n"));
	}

	@Test
	void rejectsDeepNesting() {
		String deep = "[".repeat(ProtoJson.MAX_DEPTH + 1) + "]".repeat(ProtoJson.MAX_DEPTH + 1);
		ProtocolException e = assertThrows(ProtocolException.class, () -> ProtoJson.parse(deep));
		assertEquals("json.depth", e.code());
		String ok = "[".repeat(ProtoJson.MAX_DEPTH) + "]".repeat(ProtoJson.MAX_DEPTH);
		assertTrue(parses(ok));
	}

	@Test
	void reportsTrailingDataByCode() {
		ProtocolException e = assertThrows(ProtocolException.class, () -> ProtoJson.parse("{\"a\":1}{\"b\":2}"));
		assertEquals("json.trailing", e.code());
	}

	@Test
	void keepsEmptyObjectsAndArraysApart() throws ProtocolException {
		JsonObject value = ProtoJson.parseObject("{\"o\":{},\"a\":[]}");
		assertTrue(value.get("o").isJsonObject());
		assertTrue(value.get("a").isJsonArray());
		assertEquals("{\"a\":[],\"o\":{}}", ProtoJson.write(value));
	}

	@Test
	void parseObjectRejectsOtherTopLevelValues() {
		ProtocolException e = assertThrows(ProtocolException.class, () -> ProtoJson.parseObject("[1]"));
		assertEquals("json.not_object", e.code());
	}

	private static boolean parses(String text) {
		try {
			ProtoJson.parse(text);
			return true;
		} catch (ProtocolException e) {
			return false;
		}
	}

	private static void assertCanonical(String input, String expected) throws ProtocolException {
		JsonElement value = ProtoJson.parse(input);
		assertEquals(expected, ProtoJson.write(value));
		// Canonical output is a fixed point.
		assertEquals(expected, ProtoJson.write(ProtoJson.parse(expected)));
	}
}
