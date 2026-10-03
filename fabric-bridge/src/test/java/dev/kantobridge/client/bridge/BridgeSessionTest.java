package dev.kantobridge.client.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.Envelope;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The connection logic alone, driven by plain calls and a hand-set clock. */
class BridgeSessionTest {
	static final Catalog CATALOG = Catalog.bundled();
	static final BridgeConfig CONFIG = BridgeConfig.fromCatalog(CATALOG, 27151);

	private Recorder out;
	private BridgeSession session;
	private BridgeStats stats;

	/** Collects what the session asks for. */
	static final class Recorder implements BridgeSession.Output {
		final List<Envelope> sent = new ArrayList<>();
		final List<Envelope> delivered = new ArrayList<>();
		@Nullable HelloOk connected;
		@Nullable String rejected;
		int rejectedProtocol = -2;
		@Nullable String closed;
		int closeCalls;
		private long seq;

		@Override
		public long send(String type, @Nullable Long re, JsonObject data) {
			this.sent.add(new Envelope(type, ++this.seq, re, data));
			return this.seq;
		}

		@Override
		public void deliver(Envelope message) {
			this.delivered.add(message);
		}

		@Override
		public void connected(HelloOk ok) {
			this.connected = ok;
		}

		@Override
		public void rejected(String reason, int protocol) {
			this.rejected = reason;
			this.rejectedProtocol = protocol;
		}

		@Override
		public void closed(String reason) {
			this.closed = reason;
			this.closeCalls++;
		}

		Envelope last() {
			return this.sent.get(this.sent.size() - 1);
		}
	}

	@BeforeEach
	void setUp() {
		this.out = new Recorder();
		this.stats = new BridgeStats();
		HelloInfo info = new HelloInfo("0.1.0", "1.21.1", UUID.fromString("0f8b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b"), "Tester");
		this.session = new BridgeSession(CATALOG, CONFIG, info.toData(CATALOG.protocol(), BridgeClient.ROLE), this.stats, this.out);
	}

	static String line(String type, long seq, @Nullable Long re, JsonObject data) {
		return new Envelope(type, seq, re, data).toJson();
	}

	static JsonObject data(Object... keyValues) {
		JsonObject data = new JsonObject();
		for (int i = 0; i < keyValues.length; i += 2) {
			Object value = keyValues[i + 1];
			if (value instanceof Number number) {
				data.addProperty((String) keyValues[i], number);
			} else if (value instanceof Boolean bool) {
				data.addProperty((String) keyValues[i], bool);
			} else {
				data.addProperty((String) keyValues[i], (String) value);
			}
		}
		return data;
	}

	static String helloOk(long re) {
		return line("hello.ok", 1, re, data("protocol", 1, "bridge", "0.1.0", "engine", "0.3.49", "game", "blue", "session", "s-1"));
	}

	private void receive(String line, long now) {
		this.session.receive(line, line.length(), now);
	}

	private void connect() {
		this.session.open(0);
		this.receive(helloOk(1), 10);
	}

	@Test
	void helloComesFirstWithTheClientFacts() {
		this.session.open(0);
		Envelope hello = this.out.sent.get(0);
		assertEquals("hello", hello.type());
		assertEquals(1, hello.seq());
		assertNull(hello.re());
		JsonObject data = hello.data();
		assertEquals(1, data.get("protocol").getAsInt());
		assertEquals("client", data.get("role").getAsString());
		assertEquals("0.1.0", data.get("mod").getAsString());
		assertEquals("1.21.1", data.get("minecraft").getAsString());
		assertEquals("Tester", data.getAsJsonObject("player").get("name").getAsString());
		assertEquals("0f8b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b", data.getAsJsonObject("player").get("uuid").getAsString());
	}

	@Test
	void helloLeavesOutAnInvalidPlayerName() {
		JsonObject data = new HelloInfo("0.1.0", null, UUID.randomUUID(), "bad name!").toData(1, "client");
		assertFalse(data.has("player"));
		assertFalse(data.has("minecraft"));
	}

	@Test
	void helloOkConnectsAndLaterMessagesAreDelivered() {
		this.connect();
		assertTrue(this.session.isConnected());
		assertEquals(new HelloOk("s-1", "blue", "0.3.49", "0.1.0"), this.out.connected);
		this.receive(line("snapshot", 2, null, new JsonObject()), 20);
		assertEquals(List.of("hello.ok", "snapshot"), this.out.delivered.stream().map(Envelope::type).toList());
		assertEquals(2, this.stats.messagesIn.get());
	}

	@Test
	void helloOkMustAnswerOurHello() {
		this.session.open(0);
		this.receive(helloOk(7), 10);
		assertEquals("handshake.re", this.out.closed);
		assertNull(this.out.connected);
	}

	@Test
	void aDifferentProtocolNumberIsARejection() {
		this.session.open(0);
		this.receive(line("hello.ok", 1, 1L, data("protocol", 2, "bridge", "9", "engine", "9", "game", "red", "session", "s")), 10);
		assertEquals("protocol", this.out.rejected);
		assertEquals(2, this.out.rejectedProtocol, "gen1-bridge's own number, for the overlay");
		assertEquals("rejected", this.out.closed);
	}

	@Test
	void helloRejectGivesTheReasonAndCloses() {
		this.session.open(0);
		this.receive(line("hello.reject", 1, 1L, data("reason", "busy", "protocol", 1)), 10);
		assertEquals("busy", this.out.rejected);
		assertEquals(1, this.out.rejectedProtocol);
		assertEquals("rejected", this.out.closed);
		assertTrue(this.session.isClosed());
	}

	@Test
	void helloRejectForTheProtocolNamesGenOneBridgesNumber() {
		this.session.open(0);
		this.receive(line("hello.reject", 1, 1L, data("reason", "protocol", "protocol", 3)), 10);
		assertEquals("protocol", this.out.rejected);
		assertEquals(3, this.out.rejectedProtocol);
		this.setUp();
		this.session.open(0);
		this.receive(line("hello.reject", 1, 1L, data("reason", "protocol", "protocol", 99_999)), 10);
		assertEquals(-1, this.out.rejectedProtocol, "a number out of range isn't shown");
	}

	@Test
	void helloOkCarriesTheTestedEngine() {
		this.session.open(0);
		this.receive(line("hello.ok", 1, 1L, data("protocol", 1, "bridge", "0.1.0", "engine", "0.3.50", "tested", "0.3.49", "game", "blue",
				"session", "s-1")), 10);
		HelloOk ok = this.out.connected;
		assertEquals(new HelloOk("s-1", "blue", "0.3.50", "0.1.0", "0.3.49"), ok);
		assertTrue(ok.untestedEngine());
		assertFalse(new HelloOk("s-1", "blue", "0.3.49", "0.1.0", "0.3.49").untestedEngine());
		assertFalse(new HelloOk("s-1", "blue", "0.3.49", "0.1.0").untestedEngine(), "no tested version: nothing to warn about");
		this.setUp();
		this.session.open(0);
		this.receive(line("hello.ok", 1, 1L, data("protocol", 1, "bridge", "0.1.0", "engine", "0.3.50", "tested", "not a version!", "game",
				"blue", "session", "s-1")), 10);
		assertNull(this.out.connected.tested(), "a malformed tested version is left out");
	}

	@Test
	void messagesBeforeHelloOkAreDroppedQuietly() {
		this.session.open(0);
		this.receive(line("snapshot", 1, null, new JsonObject()), 5);
		assertTrue(this.out.delivered.isEmpty());
		assertEquals(1, this.out.sent.size(), "nothing but hello is sent");
	}

	@Test
	void pingsEveryIntervalOnceConnectedAndMeasuresTheRoundTrip() {
		this.connect();
		this.session.tick(10 + CONFIG.pingMs() - 1);
		assertEquals(1, this.out.sent.size());
		long at = 10 + CONFIG.pingMs();
		this.session.tick(at);
		Envelope ping = this.out.last();
		assertEquals("ping", ping.type());
		assertEquals(at, ping.data().get("t").getAsLong());
		this.receive(line("pong", 2, ping.seq(), data("t", at)), at + 7);
		assertEquals(1, this.stats.pongs.get());
		assertEquals(7, this.stats.lastRttMs);
	}

	@Test
	void answersGen1PingsWithPong() {
		this.connect();
		this.receive(line("ping", 5, null, data("t", 77)), 20);
		Envelope pong = this.out.last();
		assertEquals("pong", pong.type());
		assertEquals(5L, pong.re());
		assertEquals(77, pong.data().get("t").getAsLong());
	}

	@Test
	void silenceDropsTheConnectionEvenDuringTheHandshake() {
		this.session.open(0);
		this.session.tick(CONFIG.timeoutMs() - 1);
		assertFalse(this.session.isClosed());
		this.session.tick(CONFIG.timeoutMs());
		assertEquals("timeout", this.out.closed);

		this.setUp();
		this.connect();
		this.session.tick(10 + CONFIG.timeoutMs() - 1);
		assertFalse(this.session.isClosed());
		this.session.tick(10 + CONFIG.timeoutMs());
		assertEquals("timeout", this.out.closed);
		assertEquals(1, this.stats.drops.get());
	}

	@Test
	void unknownTypesWrongLegsAndReRulesGetErrors() {
		this.connect();
		this.receive(line("no.such", 2, null, new JsonObject()), 20);
		assertError("unknown_type", 2L);
		this.receive(line("move.step", 3, null, data("dir", "up", "n", 1)), 20);
		assertError("catalog.leg", 3L);
		this.receive(line("pong", 4, null, data("t", 1)), 20);
		assertError("catalog.re", 4L);
		this.receive(line("player.pos", 5, 1L, new JsonObject()), 20);
		assertError("catalog.re", 5L);
		assertEquals(List.of("hello.ok"), this.out.delivered.stream().map(Envelope::type).toList());
		assertEquals(4, this.stats.protocolErrors.get());
		assertTrue(this.session.isConnected(), "protocol errors don't drop the link");
	}

	@Test
	void malformedEnvelopesGetBadEnvelope() {
		this.connect();
		this.receive("{not json", 20);
		assertError("bad_envelope", null);
		this.receive("{\"type\":\"ping\",\"seq\":0,\"data\":{}}", 20);
		assertError("bad_envelope", null);
		this.receive("{\"type\":\"ping\",\"seq\":1,\"data\":{\"t\":1}} trailing", 20);
		assertError("bad_envelope", null);
	}

	@Test
	void errorRepliesNeverQuoteTheOffendingMessage() {
		this.connect();
		String secret = "Some game text";
		this.receive(line("no.such", 2, null, data("text", secret)), 20);
		this.receive("{\"type\":\"no.such\",\"seq\":3,\"data\":{\"text\":\"" + secret + "\"} oops", 20);
		for (Envelope sent : this.out.sent) {
			assertFalse(sent.toJson().contains(secret));
		}
	}

	@Test
	void peerErrorsAreDeliveredButNeverAnswered() {
		this.connect();
		int before = this.out.sent.size();
		this.receive(line("error", 2, 1L, data("code", "bad_message", "msg", "failed schema check")), 20);
		assertEquals(before, this.out.sent.size());
		assertEquals("error", this.out.delivered.get(this.out.delivered.size() - 1).type());
	}

	@Test
	void closingIsFinal() {
		this.connect();
		this.session.close("eof");
		this.session.close("again");
		this.receive(line("snapshot", 2, null, new JsonObject()), 20);
		this.session.tick(100_000);
		assertEquals("eof", this.out.closed);
		assertEquals(1, this.out.closeCalls);
		assertEquals(List.of("hello.ok"), this.out.delivered.stream().map(Envelope::type).toList());
	}

	private void assertError(String code, @Nullable Long re) {
		Envelope error = this.out.last();
		assertEquals("error", error.type());
		assertEquals(code, error.data().get("code").getAsString());
		assertEquals(re, error.re());
	}
}
