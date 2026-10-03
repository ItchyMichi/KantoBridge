package dev.kantobridge.proto;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class CatalogTest {
	@Test
	void bundledCatalogIsTheRepositoryCatalog() throws Exception {
		Catalog bundled = Catalog.bundled();
		Catalog repository = Catalog.parse(ProtocolFiles.read("catalog.json"));
		assertEquals(repository.messages(), bundled.messages());
		assertEquals(MsgTypes.PROTOCOL, bundled.protocol());
		assertEquals(4_194_304, bundled.maxLineBytes());
		assertEquals(262_144, bundled.relayMaxBytes());
		assertEquals(2_000, bundled.pingMs());
		assertEquals(10_000, bundled.timeoutMs());
		assertEquals(500, bundled.reconnectInitialMs());
		assertEquals(5_000, bundled.reconnectMaxMs());
	}

	@Test
	void generatedConstantsCoverEveryType() {
		Set<String> catalogTypes = new TreeSet<>(Catalog.bundled().messages().keySet());
		assertEquals(new ArrayList<>(catalogTypes), MsgTypes.ALL);
		assertEquals("hello.ok", MsgTypes.HELLO_OK);
		assertEquals("player.pos", MsgTypes.PLAYER_POS);
	}

	@Test
	void relayPayloadLimitMatchesCatalog() {
		assertEquals(Catalog.bundled().relayMaxBytes(), dev.kantobridge.net.KbJsonPayload.MAX_CHARS);
	}

	@Test
	void checksLegsAndReplyRules() {
		Catalog catalog = Catalog.bundled();
		JsonObject data = new JsonObject();
		// pong is a reply on both socket legs: re is required.
		assertDoesNotThrow(() -> catalog.check(Envelope.reply(MsgTypes.PONG, 2, 1, data), Catalog.Leg.G2M));
		assertCode("catalog.re", () -> catalog.check(Envelope.of(MsgTypes.PONG, 2, data), Catalog.Leg.M2G));
		// ping never carries re.
		assertCode("catalog.re", () -> catalog.check(Envelope.reply(MsgTypes.PING, 2, 1, data), Catalog.Leg.M2G));
		// player.pos only travels gen1-bridge -> Minecraft; the relay must refuse it.
		assertDoesNotThrow(() -> catalog.check(Envelope.of(MsgTypes.PLAYER_POS, 1, data), Catalog.Leg.G2M));
		assertCode("catalog.leg", () -> catalog.check(Envelope.of(MsgTypes.PLAYER_POS, 1, data), Catalog.Leg.C2S));
		assertCode("catalog.type", () -> catalog.check(Envelope.of("no.such.type", 1, data), Catalog.Leg.G2M));
	}

	@Test
	void everyTypeHasLegsAndRomFlagsMatchRedactPaths() {
		for (Catalog.Entry entry : Catalog.bundled().messages().values()) {
			assertFalse(entry.legs().isEmpty(), entry.type());
			assertEquals(!entry.redact().isEmpty(), entry.rom(), entry.type() + ": rom flag disagrees with its redact paths");
			List<String> sorted = new ArrayList<>(entry.redact());
			sorted.sort(null);
			assertEquals(sorted, entry.redact(), entry.type() + ": redact paths are not sorted");
			assertTrue(entry.schema().equals("messages/" + entry.type() + ".schema.json"), entry.type());
		}
	}

	@Test
	void rejectsMalformedCatalogs() {
		assertCode("catalog.format", () -> Catalog.parse("{\"protocol\":1}"));
		String base = "{\"protocol\":1,\"limits\":{\"maxLineBytes\":1,\"exportPageBytes\":1,\"relayMaxBytes\":1},"
				+ "\"heartbeat\":{\"pingMs\":1,\"timeoutMs\":1},\"reconnect\":{\"initialMs\":1,\"maxMs\":1},\"messages\":";
		assertNotNull(assertDoesNotThrow(() -> Catalog.parse(base + "{}}")));
		assertCode("catalog.format", () -> Catalog.parse(base
				+ "{\"x\":{\"legs\":[\"sideways\"],\"re\":\"never\",\"rom\":false,\"redact\":[],\"schema\":\"s\"}}}"));
		assertCode("catalog.format", () -> Catalog.parse(base
				+ "{\"x\":{\"legs\":[\"g2m\"],\"re\":\"sometimes\",\"rom\":false,\"redact\":[],\"schema\":\"s\"}}}"));
		assertCode("catalog.format", () -> Catalog.parse(base
				+ "{\"x\":{\"legs\":[\"g2m\"],\"re\":\"never\",\"rom\":false,\"redact\":[\"nope\"],\"schema\":\"s\"}}}"));
	}

	private interface Call {
		void run() throws Exception;
	}

	private static void assertCode(String code, Call call) {
		ProtocolException e = assertThrows(ProtocolException.class, call::run);
		assertEquals(code, e.code());
	}
}
