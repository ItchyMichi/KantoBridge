package dev.kantobridge.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import dev.kantobridge.world.DataException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A player's followed and parked regions (MG3's record for MG4's {@code region.state}). */
class RegionPresenceTest {
	private static JsonObject json(String text) {
		try {
			return ProtoJson.parseObject(text);
		} catch (ProtocolException e) {
			throw new AssertionError(e);
		}
	}

	@Test
	void theExampleStatesAreRead() throws DataException {
		RegionPresence presence = RegionPresence.read(json("{\"followed\":\"hoenn\",\"parked\":[\"kanto\"]}"));
		assertEquals("hoenn", presence.followed());
		assertEquals(List.of("kanto"), presence.parked());
		assertTrue(presence.has("hoenn") && presence.has("kanto") && !presence.has("johto"));
		assertEquals(List.of("hoenn", "kanto"), presence.regions());
		RegionPresence none = RegionPresence.read(json("{\"parked\":[]}"));
		assertNull(none.followed());
		assertTrue(none.parked().isEmpty() && none.regions().isEmpty());
	}

	@Test
	void theWireFormIsTheSchemas() throws DataException {
		RegionPresence presence = new RegionPresence("kanto", List.of("hoenn", "johto"));
		assertEquals("{\"followed\":\"kanto\",\"parked\":[\"hoenn\",\"johto\"]}", ProtoJson.write(presence.toJson()));
		assertEquals(presence, RegionPresence.read(presence.toJson()));
		assertEquals("{\"parked\":[]}", ProtoJson.write(RegionPresence.read(json("{\"parked\":[]}")).toJson()));
	}

	@Test
	void malformedStatesAreRefused() {
		assertThrows(DataException.class, () -> RegionPresence.read(json("{}")), "parked is required");
		assertThrows(DataException.class, () -> RegionPresence.read(json("{\"parked\":[\"Kanto\"]}")), "an id outside the pattern");
		assertThrows(DataException.class, () -> RegionPresence.read(json("{\"parked\":[\"kanto\",\"kanto\"]}")), "a repeat");
		assertThrows(DataException.class, () -> RegionPresence.read(json("{\"followed\":1,\"parked\":[]}")), "a number");
		assertThrows(DataException.class, () -> RegionPresence.read(json("{\"parked\":[],\"extra\":true}")), "an extra field");
	}

	@Test
	void repeatsAreDroppedAndTheDefaultFollowsOneRegion() {
		RegionPresence presence = new RegionPresence("kanto", List.of("hoenn", "hoenn", "kanto"));
		assertEquals(List.of("hoenn", "kanto"), presence.parked());
		assertEquals(List.of("kanto", "hoenn"), presence.regions(), "the followed region once, first");
		RegionPresence following = RegionPresence.following("kanto");
		assertEquals("kanto", following.followed());
		assertTrue(following.parked().isEmpty());
		assertFalse(following.has("hoenn"));
	}

	@Test
	void theCodecRoundTrips() {
		RegionPresence presence = new RegionPresence("hoenn", List.of("kanto"));
		var encoded = RegionPresence.CODEC.encodeStart(JsonOps.INSTANCE, presence).getOrThrow();
		assertEquals(presence, RegionPresence.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
		RegionPresence none = RegionPresence.CODEC.parse(JsonOps.INSTANCE, json("{}")).getOrThrow();
		assertNull(none.followed());
		assertTrue(none.parked().isEmpty());
	}
}
