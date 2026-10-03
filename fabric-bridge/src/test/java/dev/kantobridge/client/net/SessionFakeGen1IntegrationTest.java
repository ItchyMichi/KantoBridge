package dev.kantobridge.client.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.client.bridge.BridgeClient;
import dev.kantobridge.client.bridge.BridgeClock;
import dev.kantobridge.client.bridge.BridgeConfig;
import dev.kantobridge.client.bridge.ConnectionState;
import dev.kantobridge.client.bridge.Dispatcher;
import dev.kantobridge.client.bridge.FakeGen1Process;
import dev.kantobridge.client.bridge.HelloInfo;
import dev.kantobridge.client.bridge.Transport;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.MsgTypes;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The save pairing over a real link: a {@link SessionBinder} on a real {@link BridgeClient} against {@code tools/fake_gen1}
 * (which answers {@code session.bind} with slot 1). The bind goes out once the link is up and again after a reconnect.
 * Skipped without the project venv.
 */
class SessionFakeGen1IntegrationTest {
	private static final String WORLD = "0f8e9a1c-2b3d-4e5f-8a9b-0c1d2e3f4a5b";

	@Test
	void theWorldIsBoundOnConnectAndAgainAfterAReconnect() throws Exception {
		FakeGen1Process fake = FakeGen1Process.start();
		Assumptions.assumeTrue(fake != null,
				"tools/.venv is missing: run tools/setup.ps1 (or tools/setup.sh) to enable the fake_gen1 integration test");
		Catalog catalog = Catalog.bundled();
		Dispatcher dispatcher = new Dispatcher(catalog);
		BridgeClient client = new BridgeClient(BridgeConfig.fromCatalog(catalog, fake.port()).withHeartbeat(200, 5000).withReconnect(100, 500),
				catalog, () -> new HelloInfo("0.1.0", "1.21.1", UUID.fromString("0f8b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b"), "Tester"),
				Transport.socket(), BridgeClock.system(), dispatcher);
		SessionBinder binder = new SessionBinder(new SessionBinder.Port() {
			@Override
			public long toBridge(String type, JsonObject data) {
				return client.sendTracked(type, data);
			}

			@Override
			public boolean toServer(String type, JsonObject data) {
				return false;
			}

			@Override
			public void warn(String code, long count) {
				throw new AssertionError("unexpected warning " + code);
			}
		}, false);
		// The glue's wiring, with this thread as the client thread.
		dispatcher.onState((state, detail) -> {
			if (state == ConnectionState.CONNECTED) {
				binder.linkUp();
			} else {
				binder.linkDown();
			}
		});
		dispatcher.on(MsgTypes.SESSION_BOUND, message -> binder.onBound(message.data(), message.re()));
		binder.world(WORLD, true);
		try {
			client.start();
			run(dispatcher, binder, "the first bind's answer", () -> binder.slot() != null);
			assertEquals(1, binder.slot());
			assertEquals(1, binder.stats().binds);
			assertTrue(binder.bound());

			fake.command("drop");
			run(dispatcher, binder, "a bind on the new link", () -> binder.stats().binds == 2 && binder.slot() != null);
			assertEquals(1, binder.slot());
			assertEquals(2, binder.stats().bounds);
			assertEquals(0, client.stats().snapshot().protocolErrors(), "fake_gen1 takes session.bind as it is");
		} finally {
			client.close();
			fake.close();
		}
	}

	/** Drains the dispatcher and ticks the binder, as the client thread does, until {@code done} or 10 s. */
	private static void run(Dispatcher dispatcher, SessionBinder binder, String what, BooleanSupplier done) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			dispatcher.drain(512);
			binder.tick();
			if (done.getAsBoolean()) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("timed out waiting for " + what);
	}
}
