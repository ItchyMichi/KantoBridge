package dev.kantobridge.pokemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
	void malformedGiftsAreRefused() throws Exception {
		String base = "\"give\":\"g-1\",\"dex\":7,\"level\":5,\"source\":\"gift\"";
		PartyGive.read(json("{" + base + "}"));
		PartyGive.read(json("{" + base + ",\"nick\":\"\"}"));
		for (String bad : new String[] {"{\"give\":\"g-1\",\"dex\":7,\"level\":5}", "{" + base.replace("\"gift\"", "\"stolen\"") + "}",
				"{" + base.replace("\"dex\":7", "\"dex\":0") + "}", "{" + base + ",\"moves\":[]}", "{" + base + ",\"moves\":[200]}",
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
