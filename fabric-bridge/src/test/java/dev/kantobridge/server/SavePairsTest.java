package dev.kantobridge.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Save pairs per region (MG3): the map the non-legacy regions' pairs live in, and its codec. */
class SavePairsTest {
	private static final String PAIR = "3f2a9c1d8b7e6f5a4c3b2a19e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2918170";

	@Test
	void pairsAreKeptPerRegion() {
		SavePairs pairs = SavePairs.NONE.with("hoenn", new SavePair(PAIR, 100, 2));
		assertEquals(1, pairs.size());
		assertEquals(new SavePair(PAIR, 100, 2), pairs.get("hoenn"));
		assertSame(SavePair.NONE, pairs.get("johto"), "a region never saved in has no pair");
		SavePairs more = pairs.with("johto", new SavePair(PAIR, 200, 0)).with("hoenn", pairs.get("hoenn").counted());
		assertEquals(2, more.size());
		assertEquals(3, more.get("hoenn").ops());
		assertEquals(1, pairs.size(), "the earlier value is unchanged");
	}

	@Test
	void theCodecRoundTripsAndKeepsRegionsSorted() {
		SavePairs pairs = new SavePairs(Map.of("johto", new SavePair(PAIR, 200, 0), "hoenn", new SavePair(PAIR, 100, 5)));
		assertEquals("[hoenn, johto]", pairs.byRegion().keySet().toString());
		var encoded = SavePairs.CODEC.encodeStart(JsonOps.INSTANCE, pairs).getOrThrow();
		SavePairs decoded = SavePairs.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
		assertEquals(pairs, decoded);
		assertTrue(SavePairs.CODEC.parse(JsonOps.INSTANCE, new com.google.gson.JsonObject()).getOrThrow().byRegion().isEmpty());
	}
}
