package dev.kantobridge.client.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.client.bridge.BridgeClient;
import dev.kantobridge.client.bridge.BridgeClock;
import dev.kantobridge.client.bridge.BridgeConfig;
import dev.kantobridge.client.bridge.ConnectionState;
import dev.kantobridge.client.bridge.Dispatcher;
import dev.kantobridge.client.bridge.FakeGen1Process;
import dev.kantobridge.client.bridge.HelloInfo;
import dev.kantobridge.client.bridge.HelloOk;
import dev.kantobridge.client.bridge.JsonFields;
import dev.kantobridge.client.bridge.Transport;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * M10 crash recovery over a real link, against {@code tools/fake_gen1}: the pairing glue of {@code SessionRuntime} (the
 * {@link SessionGate}, the {@link SessionBinder}, the save and PC relays) on a real {@link BridgeClient}, with this test as
 * the Minecraft server. The bind is answered {@code session.bound{switched}} and followed by {@code save.loaded}, which
 * goes up to the server; after a drop the client reconnects, binds again and relays the new {@code save.loaded}; a bind
 * that waits shows its {@code session.waiting} reason, and its switch drops what waited and asks for a fresh snapshot;
 * {@code save.begin} carries its pair; Bill's PC's id goes up and comes back on {@code pc.closed}. Skipped without the
 * project venv.
 */
class ReconnectFakeGen1IntegrationTest {
	private static final String WORLD = "0f8e9a1c-2b3d-4e5f-8a9b-0c1d2e3f4a5b";

	/** What reached "the server", in order. */
	private final List<Envelope> server = new CopyOnWriteArrayList<>();
	private final List<Boolean> openings = new CopyOnWriteArrayList<>();
	private final List<String> warnings = new CopyOnWriteArrayList<>();
	private long serverSeq;
	private long resyncs;

	@Test
	void dropReconnectBindAndSaveLoadedRelayed() throws Exception {
		FakeGen1Process fake = FakeGen1Process.start();
		Assumptions.assumeTrue(fake != null,
				"tools/.venv is missing: run tools/setup.ps1 (or tools/setup.sh) to enable the fake_gen1 integration test");
		Catalog catalog = Catalog.bundled();
		Dispatcher dispatcher = new Dispatcher(catalog);
		BridgeClient client = new BridgeClient(BridgeConfig.fromCatalog(catalog, fake.port()).withHeartbeat(200, 5000).withReconnect(100, 500),
				catalog, () -> new HelloInfo("0.1.0", "1.21.1", UUID.fromString("0f8b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b"), "Tester"),
				Transport.socket(), BridgeClock.system(), dispatcher);
		BridgeClock clock = BridgeClock.system();
		SessionGate[] gateRef = new SessionGate[1];
		SaveRelay saves = new SaveRelay(new SaveRelay.Port() {
			@Override
			public boolean toBridge(String type, @Nullable Long re, JsonObject data) {
				return gateRef[0].bridgeOpen() && client.send(type, re, data);
			}

			@Override
			public boolean toServer(String type, JsonObject data) {
				return gateRef[0].serverOpen() && ReconnectFakeGen1IntegrationTest.this.toServer(type, data);
			}

			@Override
			public void warn(String code, long count) {
				ReconnectFakeGen1IntegrationTest.this.warnings.add(code);
			}
		});
		PcRelay pc = new PcRelay(new PcRelay.Port() {
			@Override
			public boolean toBridge(String type, JsonObject data) {
				return gateRef[0].bridgeOpen() && client.send(type, data);
			}

			@Override
			public boolean toServer(String type, JsonObject data) {
				return gateRef[0].serverOpen() && ReconnectFakeGen1IntegrationTest.this.toServer(type, data);
			}

			@Override
			public void unlink() {
			}

			@Override
			public void warn(String code, long count) {
				ReconnectFakeGen1IntegrationTest.this.warnings.add(code);
			}
		});
		SessionGate gate = new SessionGate(new SessionGate.Port() {
			@Override
			public void replay(Envelope message) {
				if (message.type().equals(MsgTypes.SAVE_LOADED)) {
					saves.loaded(message);
				} else if (message.type().equals(MsgTypes.SAVE_BEGIN)) {
					saves.fromBridge(message, clock.millis());
				}
			}

			@Override
			public void opened(boolean stale) {
				ReconnectFakeGen1IntegrationTest.this.openings.add(stale);
				if (stale && client.send(MsgTypes.RESYNC, new JsonObject())) {
					ReconnectFakeGen1IntegrationTest.this.resyncs++;
				}
			}

			@Override
			public void worldChanged() {
			}

			@Override
			public void rebind() {
			}

			@Override
			public void warn(String code, long count) {
				ReconnectFakeGen1IntegrationTest.this.warnings.add(code);
			}
		});
		gateRef[0] = gate;
		SessionBinder binder = new SessionBinder(new SessionBinder.Port() {
			@Override
			public long toBridge(String type, JsonObject data) {
				long seq = client.sendTracked(type, data);
				if (seq > 0) {
					gate.bindSent(seq, clock.millis());
				}
				return seq;
			}

			@Override
			public boolean toServer(String type, JsonObject data) {
				return false;
			}

			@Override
			public void warn(String code, long count) {
				ReconnectFakeGen1IntegrationTest.this.warnings.add(code);
			}
		}, false);
		// SessionRuntime's wiring, with this thread as the client thread.
		dispatcher.onState((state, detail) -> {
			if (state == ConnectionState.CONNECTED) {
				saves.linkChanged();
				binder.linkUp();
				HelloOk hello = client.handshake();
				gate.linkUp(hello == null ? null : hello.session(), clock.millis());
			} else {
				binder.linkDown();
				gate.linkDown(clock.millis());
			}
		});
		dispatcher.on(MsgTypes.SESSION_BOUND, message -> {
			if (binder.onBound(message.data(), message.re())) {
				gate.bound(message.re(), binder.switched(), clock.millis());
			}
		});
		dispatcher.on(MsgTypes.SESSION_WAITING, message -> gate.waiting(JsonFields.str(message.data(), "reason"), message.re(), clock.millis()));
		dispatcher.on(MsgTypes.SAVE_LOADED, message -> {
			if (gate.admit(message) == SessionGate.Admit.PASS) {
				saves.loaded(message);
			}
		});
		dispatcher.on(MsgTypes.SAVE_BEGIN, message -> {
			if (gate.admit(message) == SessionGate.Admit.PASS) {
				saves.fromBridge(message, clock.millis());
			}
		});
		dispatcher.on(MsgTypes.PC_OPEN, message -> {
			String id = JsonFields.str(message.data(), "pc");
			if (id != null && gate.admit(message) == SessionGate.Admit.PASS) {
				pc.fromBridge(id, clock.millis());
			}
		});
		dispatcher.on(MsgTypes.SNAPSHOT, message -> {
			gate.snapshot();
			pc.bridgeSnapshot(clock.millis());
		});
		Runnable tick = () -> {
			gate.world(true, WORLD, clock.millis());
			binder.world(WORLD, true);
			binder.tick();
			gate.tick(clock.millis());
			saves.tick(clock.millis());
		};
		try {
			client.start();
			// The first pairing: session.bound{switched:none}, then save.loaded goes up to the server.
			this.run(dispatcher, tick, "the first save.loaded", () -> this.relayed(MsgTypes.SAVE_LOADED) == 1);
			assertEquals(List.of(false), this.openings, "switched:none: the hidden game stayed on its save, nothing to drop");
			assertEquals(SessionGate.State.OPEN, gate.state());
			JsonObject first = this.last(MsgTypes.SAVE_LOADED).data();
			assertFalse(first.get("fresh").getAsBoolean());
			assertEquals(0, first.get("ops").getAsInt());
			String firstPair = first.get("pair").getAsString();

			// A hidden-game save: save.begin goes up with its pair (a new one), and its answer comes back.
			fake.command("save");
			this.run(dispatcher, tick, "save.begin relayed", () -> this.relayed(MsgTypes.SAVE_BEGIN) == 1);
			JsonObject begin = this.last(MsgTypes.SAVE_BEGIN).data();
			assertTrue(begin.has("pair") && !begin.get("pair").getAsString().equals(firstPair), "the new save's pair");
			assertTrue(begin.has("at"));
			JsonObject done = new JsonObject();
			done.addProperty("save", begin.get("save").getAsString());
			done.addProperty("ok", true);
			saves.fromServer(new Envelope(MsgTypes.SAVE_DONE, ++this.serverSeq, null, done), clock.millis());

			// Gen1Recomp drops the link: the client reconnects, binds again, and the new save.loaded goes up.
			fake.command("drop");
			this.run(dispatcher, tick, "a save.loaded after the reconnect", () -> this.relayed(MsgTypes.SAVE_LOADED) == 2);
			assertEquals(begin.get("pair").getAsString(), this.last(MsgTypes.SAVE_LOADED).data().get("pair").getAsString(),
					"the hidden game runs from the save it just made");
			assertEquals(2, binder.stats().binds);
			assertEquals(List.of(false, false), this.openings);

			// The hidden game went back to its last save (a crash and CONTINUE): save.loaded says so at once.
			fake.command("reload");
			this.run(dispatcher, tick, "save.loaded after a reload", () -> this.relayed(MsgTypes.SAVE_LOADED) == 3);

			// A bind that waits: its reason shows while held; the switch then drops what waited and asks for a snapshot.
			fake.command("waitbind busy 1.5");
			fake.command("drop");
			this.run(dispatcher, tick, "the waiting reason", () -> "busy".equals(gate.waiting()));
			assertTrue(gate.held());
			this.run(dispatcher, tick, "the switch's save.loaded", () -> this.relayed(MsgTypes.SAVE_LOADED) == 4);
			assertEquals(List.of(false, false, true), this.openings, "switched:title: a fresh start");
			assertEquals(1, this.resyncs, "a fresh snapshot asked for");
			this.run(dispatcher, tick, "the fresh snapshot", () -> !gate.holdsPlayer());

			// Bill's PC: its id goes up, and the server's pc.closed echoes it back to the hidden game.
			fake.command("pc");
			this.run(dispatcher, tick, "pc.open relayed", () -> this.relayed(MsgTypes.PC_OPEN) == 1);
			String pcId = this.last(MsgTypes.PC_OPEN).data().get("pc").getAsString();
			assertEquals(pcId, pc.id());
			pc.fromServer(pcId, clock.millis());
			assertFalse(pc.pending());
			String counts = awaitCounts(fake, "pc.closed=1");
			assertTrue(counts.contains("pc.closed=1"), "the hidden game got its pc.closed");
			assertTrue(counts.contains("save.done=1"), "and the save's answer");
			assertEquals(0, client.stats().snapshot().protocolErrors(), "fake_gen1's messages pass the client's checks");
			assertTrue(this.warnings.isEmpty(), "no warnings: " + this.warnings);
		} finally {
			client.close();
			fake.close();
		}
	}

	/** fake_gen1's message counts once they contain {@code wanted} (the client's last sends may still be on the way). */
	private static String awaitCounts(FakeGen1Process fake, String wanted) throws Exception {
		String counts = null;
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			fake.command("counts");
			counts = fake.awaitLine("counts ", 5_000);
			assertNotNull(counts, "fake_gen1 printed its counts");
			if (counts.contains(wanted)) {
				return counts;
			}
			Thread.sleep(100);
		}
		return counts;
	}

	private boolean toServer(String type, JsonObject data) {
		this.server.add(new Envelope(type, ++this.serverSeq, null, data.deepCopy()));
		return true;
	}

	private long relayed(String type) {
		return this.server.stream().filter(message -> message.type().equals(type)).count();
	}

	private Envelope last(String type) {
		Envelope found = null;
		for (Envelope message : this.server) {
			if (message.type().equals(type)) {
				found = message;
			}
		}
		assertNotNull(found, "nothing of type " + type);
		return found;
	}

	/** Drains the dispatcher and ticks, as the client thread does, until {@code done} or 15 s. */
	private void run(Dispatcher dispatcher, Runnable tick, String what, BooleanSupplier done) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
		while (System.nanoTime() < deadline) {
			dispatcher.drain(512);
			tick.run();
			if (done.getAsBoolean()) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("timed out waiting for " + what);
	}
}
