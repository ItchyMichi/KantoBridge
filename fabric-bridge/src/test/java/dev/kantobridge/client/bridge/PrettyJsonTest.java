package dev.kantobridge.client.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import dev.kantobridge.builder.TestData;
import dev.kantobridge.proto.ProtoJson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/** {@link PrettyJson} against Python's {@code tools/kb_protocol/jsonfmt.pretty}, byte for byte. */
class PrettyJsonTest {
	@Test
	void reproducesTheSyntheticBundlesIndexExactly() throws Exception {
		// fake_gen1 --write-bundle wrote this file with jsonfmt.pretty; parsing keeps the key order.
		byte[] file = Files.readAllBytes(TestData.syntheticBundle().resolve("index.json"));
		JsonObject index = ProtoJson.parseObject(new String(file, StandardCharsets.UTF_8));
		assertEquals(new String(file, StandardCharsets.UTF_8), PrettyJson.write(index));
	}

	@Test
	void reordersAWireIndexIntoTheFileOrder() throws Exception {
		byte[] file = Files.readAllBytes(TestData.syntheticBundle().resolve("index.json"));
		JsonObject index = ProtoJson.parseObject(new String(file, StandardCharsets.UTF_8));
		// On the wire the index is canonical (sorted keys); the file keeps the protocol's order.
		JsonObject wire = ProtoJson.parseObject(ProtoJson.write(index));
		assertEquals("bridge", wire.keySet().iterator().next());
		assertEquals(new String(file, StandardCharsets.UTF_8), PrettyJson.write(BundleWriter.ordered(wire)));
	}

	@Test
	void fitsWhatFitsAndBreaksTheRest() throws Exception {
		assertEquals("{}\n", PrettyJson.write(new JsonObject()));
		assertEquals("{\"a\": [1, 2], \"b\": {}, \"c\": \"x\"}\n", PrettyJson.write(ProtoJson.parse("{\"a\":[1,2],\"b\":{},\"c\":\"x\"}")));
		String longValue = "y".repeat(120);
		assertEquals("{\n  \"k\": \"" + longValue + "\",\n  \"n\": [true, null]\n}\n",
				PrettyJson.write(ProtoJson.parse("{\"k\":\"" + longValue + "\",\"n\":[true,null]}")));
		// A one-line form of exactly 100 columns stays flat; 101 breaks.
		String hundred = "[" + "1".repeat(98) + "]";
		assertEquals(100, hundred.length());
		assertEquals(hundred + "\n", PrettyJson.write(ProtoJson.parse(hundred)));
		String digits = "1".repeat(99);
		assertEquals("[\n  " + digits + "\n]\n", PrettyJson.write(ProtoJson.parse("[" + digits + "]")));
	}

	@Test
	void countsCodePointsAndEscapesLikePython() throws Exception {
		// Python's len() counts code points: 60 emoji plus quotes and brackets are 64 columns (124 UTF-16 units).
		String emoji = "😀".repeat(60);
		assertEquals("[\"" + emoji + "\"]\n", PrettyJson.write(ProtoJson.parse("[\"" + emoji + "\"]")));
		assertEquals("{\"q\": \"a\\\"b\\\\c\\n\\u0001é\"}\n", PrettyJson.write(ProtoJson.parse("{\"q\":\"a\\\"b\\\\c\\n\\u0001é\"}")));
	}
}
