package dev.kantobridge.proto;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The synthetic examples in protocol/examples go through the Java codec unchanged. */
class ProtocolExamplesTest {
	@Test
	void everyExampleParsesAndRoundTrips() throws Exception {
		List<ProtocolFiles.Example> examples = ProtocolFiles.examples();
		assertFalse(examples.isEmpty(), "no protocol examples found");
		for (ProtocolFiles.Example example : examples) {
			String where = example.file() + " seq " + example.json().get("seq");
			assertEquals(example.file(), example.type() + ".json", where + ": example filed under the wrong type");
			Envelope envelope = assertDoesNotThrow(() -> Envelope.fromJson(example.json()), where);
			// Same tree after a canonical write and a strict re-parse.
			String canonical = envelope.toJson();
			JsonObject reparsed = ProtoJson.parseObject(canonical);
			assertEquals(example.json(), reparsed, where);
			assertEquals(canonical, Envelope.parse(canonical).toJson(), where);
		}
	}

	@Test
	void everyExampleMayTravelEveryLegOfItsType() throws Exception {
		Catalog catalog = Catalog.bundled();
		for (ProtocolFiles.Example example : ProtocolFiles.examples()) {
			Envelope envelope = Envelope.fromJson(example.json());
			Catalog.Entry entry = catalog.get(envelope.type());
			assertNotNull(entry, example.file() + ": type not in the catalog");
			for (Catalog.Leg leg : entry.legs()) {
				assertDoesNotThrow(() -> catalog.check(envelope, leg), example.file() + " on " + leg);
			}
		}
	}

	@Test
	void everyCatalogTypeHasAnExample() throws Exception {
		Set<String> seen = new HashSet<>();
		for (ProtocolFiles.Example example : ProtocolFiles.examples()) {
			seen.add(example.type());
		}
		for (String type : Catalog.bundled().messages().keySet()) {
			assertTrue(seen.contains(type), "no example for " + type);
		}
	}
}
