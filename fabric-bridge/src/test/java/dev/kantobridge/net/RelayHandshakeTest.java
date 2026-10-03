package dev.kantobridge.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.McBootstrap;
import dev.kantobridge.proto.Catalog;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The relay's version handshake (M10; docs/PROTOCOL.md {@code relay.hello}): who may stay, what the hello carries, and
 * that a refused player reads both versions even without our lang file.
 */
class RelayHandshakeTest {
	@BeforeAll
	static void setUp() {
		McBootstrap.ensure();
	}

	@Test
	void theDecision() {
		assertEquals(RelayHandshake.Verdict.NO_MOD, RelayHandshake.decide(false, 1, null), "a client that can't receive our payload");
		assertEquals(RelayHandshake.Verdict.NO_MOD, RelayHandshake.decide(false, 1, 1), "whatever it would have said");
		assertEquals(RelayHandshake.Verdict.OK, RelayHandshake.decide(true, 1, null), "its answer may still be on its way");
		assertEquals(RelayHandshake.Verdict.OK, RelayHandshake.decide(true, 1, 1));
		assertEquals(RelayHandshake.Verdict.PROTOCOL, RelayHandshake.decide(true, 1, 2));
		assertEquals(RelayHandshake.Verdict.PROTOCOL, RelayHandshake.decide(true, 2, 1));
	}

	@Test
	void theHelloCarriesOurProtocolAndVersion() {
		JsonObject hello = RelayHandshake.helloData();
		assertEquals(Catalog.bundled().protocol(), hello.get("protocol").getAsInt());
		assertTrue(RelayHandshake.VERSION.matcher(hello.get("mod").getAsString()).matches());
		assertEquals(2, hello.size(), "protocol and mod only");
	}

	@Test
	void theReasonsNameBothVersionsWithoutOurLangFile() {
		Component protocol = RelayHandshake.protocolReason(7, "9.9.9");
		String text = protocol.getString();
		assertTrue(text.contains("9.9.9") && text.contains("7"), "the client's version");
		assertTrue(text.contains(RelayHandshake.modVersion()) && text.contains(Integer.toString(RelayHandshake.protocol())), "the server's");
		String noMod = RelayHandshake.noModReason().getString();
		assertTrue(noMod.contains(RelayHandshake.modVersion()) && noMod.contains("kantobridge"), "what to install");
	}
}
