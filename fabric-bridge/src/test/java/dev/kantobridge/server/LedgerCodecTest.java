package dev.kantobridge.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The trainer ledger's record and codec (MG6): clamps, canonical order, persistence round trip. */
class LedgerCodecTest {
	@Test
	void theRecordClampsAndKeepsOnlyPositiveCounts() {
		Ledger ledger = new Ledger(2_000_000, Map.of("potion", 0, "nugget", 20000, "antidote", -3, "poke_ball", 4), Map.of("kanto", "aa"), Set.of("p"));
		assertEquals(Ledger.MAX_MONEY, ledger.money());
		assertEquals(Map.of("nugget", Ledger.MAX_ITEM, "poke_ball", 4), ledger.items());
		assertEquals("[nugget, poke_ball]", ledger.items().keySet().toString(), "sorted");
		assertEquals(0, ledger.count("potion"));
		assertEquals("aa", ledger.stamp("kanto"));
		assertTrue(ledger.isAdopted("p") && !ledger.isAdopted("q"));
		assertEquals(0, ledger.remove("poke_ball", 9).count("poke_ball"));
		assertEquals(0, Ledger.NONE.addMoney(-5).money());
		assertTrue(Ledger.NONE.isEmpty());
		assertFalse(ledger.isEmpty());
	}

	@Test
	void theCodecRoundTrips() {
		Ledger ledger = Ledger.NONE.add("potion", 3).add("tm_toxic", 1).addMoney(5000).withStamp("hoenn", "0123456789abcdef").withStamp("kanto", "fedcba9876543210")
				.adopt("3f2a9c1d8b7e6f5a").adopt("hoenn:fresh");
		var encoded = Ledger.CODEC.encodeStart(JsonOps.INSTANCE, ledger).getOrThrow();
		Ledger decoded = Ledger.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
		assertEquals(ledger, decoded);
		assertEquals(5000, decoded.money());
		assertEquals(Set.of("3f2a9c1d8b7e6f5a", "hoenn:fresh"), decoded.adopted());
		Ledger empty = Ledger.CODEC.parse(JsonOps.INSTANCE, new JsonObject()).getOrThrow();
		assertEquals(Ledger.NONE, empty, "every field is optional: an old player has no ledger yet");
	}
}
