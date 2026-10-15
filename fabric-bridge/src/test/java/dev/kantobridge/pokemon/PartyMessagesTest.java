package dev.kantobridge.pokemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.McBootstrap;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.DataException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

/** {@code party.give} as the server reads it, and the pure parts of {@code party.sync}. */
class PartyMessagesTest {
	@Test
	void theProtocolExamplesRead() throws Exception {
		Path file = Path.of(System.getProperty("kb.protocolDir"), "examples", "party.give.json");
		JsonArray examples = ProtoJson.parse(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray();
		JsonObject starter = examples.get(0).getAsJsonObject().getAsJsonObject("data");
		PartyGive give = PartyGive.read(starter);
		assertEquals(PartyGive.Source.STARTER, give.source());
		assertEquals(new Gen1Mon(7, 5, List.of(33, 39), null), give.mon());
		assertNull(give.nick());
		assertNull(give.ot());
		PartyGive trade = PartyGive.read(examples.get(1).getAsJsonObject().getAsJsonObject("data"));
		assertEquals(PartyGive.Source.TRADE, trade.source());
		assertEquals(new Dvs(1, 2, 3, 4), trade.mon().dvs());
		assertEquals(List.of(), trade.mon().moves(), "no moves: Cobblemon's default moveset");
		for (JsonElement example : examples) {
			PartyGive.read(example.getAsJsonObject().getAsJsonObject("data"));
		}
	}

	@Test
	void aGen3GiftReads() throws Exception {
		// MG7: Emerald's starter: a national number past 151, a move past 165, IVs
		PartyGive give = PartyGive.read(json("{\"give\":\"g-3\",\"dex\":252,\"level\":5,\"moves\":[1,43,71],"
				+ "\"ivs\":{\"hp\":1,\"atk\":2,\"def\":3,\"spa\":4,\"spd\":5,\"spe\":6},\"source\":\"starter\"}"));
		assertEquals(new Gen1Mon(252, 5, List.of(1, 43, 71), null, new Ivs(1, 2, 3, 4, 5, 6)), give.mon());
		assertEquals(PartyGive.Source.STARTER, give.source());
	}

	@Test
	void aGen2GiftCarriesItsDetails() throws Exception {
		// MG9 (G2F): DVs, the held item by its canonical id, gender, shininess, Unown's letter, friendship
		PartyGive give = PartyGive.read(json("{\"give\":\"g-4\",\"dex\":201,\"level\":10,\"moves\":[237],"
				+ "\"dvs\":{\"atk\":4,\"def\":4,\"spd\":4,\"spc\":4},\"item\":\"leftovers\",\"shiny\":false,"
				+ "\"gender\":\"genderless\",\"form\":\"q\",\"friendship\":120,\"source\":\"egg\"}"));
		assertEquals(new MonDetails("leftovers", false, "genderless", "q", 120), give.mon().details());
		assertEquals(4, give.mon().dvs().atk());
		JsonObject out = new JsonObject();
		give.mon().details().write(out);
		assertEquals("{\"form\":\"q\",\"friendship\":120,\"gender\":\"genderless\",\"item\":\"leftovers\",\"shiny\":false}",
				ProtoJson.write(out));
		assertThrows(DataException.class, () -> PartyGive.read(json("{\"give\":\"g-4\",\"dex\":7,\"level\":5,"
				+ "\"friendship\":256,\"source\":\"gift\"}")));
		assertThrows(IllegalArgumentException.class, () -> new MonDetails("Leftovers", null, null, null, null));
		assertTrue(MonDetails.NONE.isEmpty());
	}

	@Test
	void malformedGiftsAreRefused() throws Exception {
		String base = "\"give\":\"g-1\",\"dex\":7,\"level\":5,\"source\":\"gift\"";
		PartyGive.read(json("{" + base + "}"));
		PartyGive.read(json("{" + base + ",\"nick\":\"\"}"));
		for (String bad : new String[] {"{\"give\":\"g-1\",\"dex\":7,\"level\":5}", "{" + base.replace("\"gift\"", "\"stolen\"") + "}",
				"{" + base.replace("\"dex\":7", "\"dex\":0") + "}", "{" + base + ",\"moves\":[]}", "{" + base + ",\"moves\":[2001]}",
				"{" + base.replace("\"dex\":7", "\"dex\":2001") + "}", "{" + base + ",\"ivs\":{\"hp\":1}}",
				"{" + base + ",\"nick\":\"" + "N".repeat(33) + "\"}", "{" + base + ",\"ot\":5}", "{" + base + ",\"uid\":\"x\"}",
				"{" + base.replace("\"g-1\"", "\"g 1\"") + "}"}) {
			assertThrows(DataException.class, () -> PartyGive.read(json(bad)), bad);
		}
	}

	@Test
	void statusesAsGen1KnowsThem() {
		assertEquals("psn", PartySync.gen1Status("psn"));
		assertEquals("psn", PartySync.gen1Status("tox"));
		assertEquals("brn", PartySync.gen1Status("brn"));
		assertEquals("frz", PartySync.gen1Status("frz"));
		assertEquals("par", PartySync.gen1Status("par"));
		assertEquals("slp", PartySync.gen1Status("slp"));
		assertNull(PartySync.gen1Status(null));
		assertNull(PartySync.gen1Status("confusion"));
	}

	@Test
	void nicknamesArePlainAndBounded() {
		McBootstrap.ensure();
		assertNull(PartySync.nickname(null));
		assertNull(PartySync.nickname(Component.literal("")));
		assertEquals("SHELLY", PartySync.nickname(Component.literal("SHELLY")));
		assertEquals("N".repeat(32), PartySync.nickname(Component.literal("N".repeat(40))));
	}

	private static JsonObject json(String text) throws Exception {
		return ProtoJson.parseObject(text);
	}
}
