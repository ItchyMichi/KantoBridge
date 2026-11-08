package dev.kantobridge.bag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The bag trace's line format: time, side, event, then key=value with maps in key order; ids and counts only. */
class BagTraceTest {
	@Test
	void aLineIsTimeSideEventAndFields() {
		Map<String, Integer> used = new LinkedHashMap<>();
		used.put("POTION", 1);
		used.put("POKE_BALL", 2);
		String line = BagTrace.line("S", "use.observed", "p", "1234abcd", "used", used, "region", null, "ok", true);
		assertTrue(line.matches("^\\d\\d:\\d\\d:\\d\\d\\.\\d{3} S use\\.observed .*"), line);
		assertTrue(line.endsWith(" p=1234abcd used={POKE_BALL:2,POTION:1} region=- ok=true"), line);
	}

	@Test
	void jsonValuesPrintBare() {
		JsonObject items = new JsonObject();
		items.addProperty("REPEL", 3);
		items.addProperty("NUGGET", 1);
		assertEquals("{NUGGET:1,REPEL:3}", BagTrace.value(items));
		JsonObject data = new JsonObject();
		data.addProperty("item", "POKE_BALL");
		assertEquals("POKE_BALL", BagTrace.value(data.get("item")));
		assertEquals("-", BagTrace.value(data.get("absent")));
	}

	@Test
	void stampsAndPlayersAreShort() {
		assertEquals("0123ab", BagTrace.stamp("0123abcdef012345"));
		assertEquals("-", BagTrace.stamp(null));
		assertEquals("12345678", BagTrace.who(UUID.fromString("12345678-0000-0000-0000-000000000000")));
		assertEquals(Map.of("POKE_BALL", 4), BagTrace.nonZero(Map.of("POKE_BALL", 4, "POTION", 0)));
	}
}
