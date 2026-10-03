package dev.kantobridge.client.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.Envelope;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The socket thread over an in-memory transport: backoff, handshake, heartbeat, drops, close. */
class BridgeClientTest {
	static final Catalog CATALOG = Catalog.bundled();

	private final PipeTransport transport = new PipeTransport();
	private final RecordingClock clock = new RecordingClock();
	private final Recorder recorder = new Recorder();
	private @Nullable BridgeClient client;

	/** Real time for timers; backoff sleeps are recorded and shortened to 1 ms. */
	static final class RecordingClock implements BridgeClock {
		private final BridgeClock real = BridgeClock.system();
		final List<Long> sleeps = Collections.synchronizedList(new ArrayList<>());

		@Override
		public long millis() {
			return this.real.millis();
		}

		@Override
		public void sleep(long ms) throws InterruptedException {
			this.sleeps.add(ms);
			Thread.sleep(1);
		}
	}

	static final class Recorder implements BridgeClient.Listener {
		final List<String> states = new CopyOnWriteArrayList<>();
		final List<Envelope> messages = new CopyOnWriteArrayList<>();

		@Override
		public void stateChanged(ConnectionState state, @Nullable String detail) {
			this.states.add(state + (detail == null ? "" : ":" + detail));
		}

		@Override
		public void message(Envelope message) {
			this.messages.add(message);
		}
	}

	private BridgeClient start(BridgeConfig config) {
		BridgeClient started = new BridgeClient(config, CATALOG,
				() -> new HelloInfo("0.1.0", "1.21.1", UUID.fromString("0f8b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b"), "Tester"),
				this.transport, this.clock, this.recorder);
		this.client = started;
		started.start();
		return started;
	}

	/** Long heartbeat so pings don't interleave with what a test reads, unless it wants them. */
	private static BridgeConfig quiet() {
		return BridgeConfig.fromCatalog(CATALOG, 27151).withHeartbeat(60_000, 120_000).withTick(10);
	}

	@AfterEach
	void tearDown() {
		if (this.client != null) {
			this.client.close();
		}
	}

	static void await(String what, BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + 5_000_000_000L;
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("timed out waiting for " + what);
			}
			Thread.sleep(5);
		}
	}

	private static String helloOk(long re, String session) {
		JsonObject data = new JsonObject();
		data.addProperty("protocol", 1);
		data.addProperty("bridge", "0.1.0");
		data.addProperty("engine", "0.3.49");
		data.addProperty("game", "blue");
		data.addProperty("session", session);
		return new Envelope("hello.ok", 1, re, data).toJson();
	}

	/** Accepts the next connection and completes its handshake. */
	private PipeTransport.Pipe handshake(String session) throws Exception {
		PipeTransport.Pipe pipe = this.transport.nextPipe();
		Envelope hello = Envelope.parse(pipe.serverReadLine());
		assertEquals("hello", hello.type());
		assertEquals(1, hello.seq());
		pipe.serverSend(helloOk(hello.seq(), session));
		await("connected", () -> this.client.state() == ConnectionState.CONNECTED);
		return pipe;
	}

	@Test
	void backoffDoublesWhileGen1RecompIsUnreachable() throws Exception {
		this.transport.refuse.set(6);
		this.start(quiet());
		this.handshake("s-1");
		assertEquals(List.of(500L, 1000L, 2000L, 4000L, 5000L, 5000L), this.clock.sleeps);
		assertEquals(7, this.transport.attempts.get());
		assertTrue(this.recorder.states.contains("DISCONNECTED:unreachable"));
		assertEquals("CONNECTED:blue", this.recorder.states.get(this.recorder.states.size() - 1));
	}

	@Test
	void handshakeFirstThenMessagesFlowBothWays() throws Exception {
		BridgeClient started = this.start(quiet());
		JsonObject step = new JsonObject();
		step.addProperty("dir", "up");
		step.addProperty("n", 1);
		assertFalse(started.send("move.step", step), "nothing is sent before the handshake");
		PipeTransport.Pipe pipe = this.handshake("s-1");
		assertEquals(new HelloOk("s-1", "blue", "0.3.49", "0.1.0"), started.handshake());
		pipe.serverSend("{\"data\":{},\"seq\":2,\"type\":\"resync\"}"); // wrong leg: answered, not delivered
		Envelope error = Envelope.parse(pipe.serverReadLine());
		assertEquals("error", error.type());
		assertEquals(2L, error.re());
		pipe.serverSend(new Envelope("player.pos", 3, null, ClientSideStateTest.message("player.pos", 3,
				"{\"map\":\"TEST_TOWN\",\"x\":5,\"y\":7,\"facing\":\"up\",\"kind\":\"walk\",\"frames\":16}").data()).toJson());
		await("player.pos delivered", () -> this.recorder.messages.stream().anyMatch(m -> m.type().equals("player.pos")));
		assertEquals(List.of("hello.ok", "player.pos"), this.recorder.messages.stream().map(Envelope::type).toList());

		assertTrue(started.send("move.step", step));
		Envelope sent = Envelope.parse(pipe.serverReadLine());
		assertEquals("move.step", sent.type());
		assertEquals(3, sent.seq(), "hello was 1 and the error reply 2: one counter per connection");
		assertThrows(IllegalArgumentException.class, () -> started.send("player.pos", new JsonObject()));
		assertThrows(IllegalArgumentException.class, () -> started.send("pong", new JsonObject()));
	}

	@Test
	void rejectionWaitsTheLongestBackoff() throws Exception {
		BridgeClient started = this.start(quiet());
		PipeTransport.Pipe pipe = this.transport.nextPipe();
		Envelope hello = Envelope.parse(pipe.serverReadLine());
		pipe.serverSend("{\"data\":{\"protocol\":1,\"reason\":\"protocol\"},\"re\":" + hello.seq() + ",\"seq\":1,\"type\":\"hello.reject\"}");
		await("retry after rejection", () -> this.transport.attempts.get() >= 2);
		assertTrue(this.recorder.states.contains("REJECTED:protocol"));
		assertEquals(5000L, this.clock.sleeps.get(0));
		assertEquals(0, started.stats().snapshot().handshakes());
	}

	@Test
	void reconnectsAfterADropAndTellsRestartsFromReconnects() throws Exception {
		BridgeClient started = this.start(quiet());
		PipeTransport.Pipe first = this.handshake("s-1");
		first.serverHangUp();
		// handshake() waits for the client's next connection, so this is the reconnect.
		PipeTransport.Pipe second = this.handshake("s-1");
		assertTrue(this.recorder.states.contains("DISCONNECTED:eof"));
		assertEquals(500L, this.clock.sleeps.get(0), "a handshake resets the backoff");
		BridgeStats.Snapshot stats = started.stats().snapshot();
		assertEquals(1, stats.reconnects(), "same session id: only the connection was lost");
		assertEquals(0, stats.restarts());

		second.serverHangUp();
		this.handshake("s-2");
		stats = started.stats().snapshot();
		assertEquals(1, stats.restarts(), "a new session id means gen1-bridge restarted");
		assertEquals(3, stats.handshakes());
		assertEquals(3, stats.connects());
	}

	@Test
	void silenceDropsTheLinkAndPingsKeepGoingMeanwhile() throws Exception {
		BridgeClient started = this.start(BridgeConfig.fromCatalog(CATALOG, 27151).withHeartbeat(40, 400).withTick(10));
		PipeTransport.Pipe pipe = this.handshake("s-1");
		Envelope ping = Envelope.parse(pipe.serverReadLine());
		assertEquals("ping", ping.type());
		assertTrue(ping.data().get("t").getAsLong() >= 0);
		await("timeout drop", () -> this.recorder.states.contains("DISCONNECTED:timeout"));
		assertTrue(started.stats().snapshot().drops() >= 1);
	}

	@Test
	void anOversizeLineDropsTheLinkAndReconnects() throws Exception {
		BridgeConfig small = new BridgeConfig("127.0.0.1", 27151, 60_000, 120_000, 500, 5000, 256, 10, 2000);
		this.start(small);
		PipeTransport.Pipe pipe = this.handshake("s-1");
		pipe.serverSendRaw(("{\"pad\":\"" + "x".repeat(400) + "\"}\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		await("drop for the long line", () -> this.recorder.states.contains("DISCONNECTED:frame.too_long"));
		await("reconnect", () -> this.transport.attempts.get() >= 2);
	}

	@Test
	void sendNeverBlocksEvenWhenTheSocketDoes() throws Exception {
		BridgeClient started = this.start(quiet());
		PipeTransport.Pipe pipe = this.handshake("s-1");
		pipe.blockWrites = true;
		JsonObject face = new JsonObject();
		face.addProperty("dir", "left");
		long start = System.nanoTime();
		for (int i = 0; i < 2000; i++) {
			assertTrue(started.send("move.face", face));
		}
		assertTrue(System.nanoTime() - start < 1_000_000_000L, "send() must only queue");
	}

	@Test
	void closeStopsTheThreadAndTheLink() throws Exception {
		BridgeClient started = this.start(quiet());
		PipeTransport.Pipe pipe = this.handshake("s-1");
		long start = System.nanoTime();
		started.close();
		assertTrue(System.nanoTime() - start < 2_000_000_000L);
		assertEquals(ConnectionState.STOPPED, started.state());
		assertTrue(pipe.closed);
		int attempts = this.transport.attempts.get();
		Thread.sleep(50);
		assertEquals(attempts, this.transport.attempts.get(), "no reconnect after close");
		started.close(); // idempotent
	}

	@Test
	void closeBeforeStartIsFine() {
		BridgeClient never = new BridgeClient(quiet(), CATALOG, () -> null, this.transport, this.clock, this.recorder);
		never.close();
		never.start();
		assertEquals(ConnectionState.STOPPED, never.state());
		assertEquals(0, this.transport.attempts.get());
	}
}
