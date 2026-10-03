package dev.kantobridge.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.client.act.InteractTarget;
import dev.kantobridge.client.bridge.BridgeClient;
import dev.kantobridge.client.bridge.BridgeClock;
import dev.kantobridge.client.bridge.BridgeConfig;
import dev.kantobridge.client.bridge.ClientBridgeState;
import dev.kantobridge.client.bridge.ConnectionState;
import dev.kantobridge.client.bridge.Dispatcher;
import dev.kantobridge.client.bridge.FakeGen1Process;
import dev.kantobridge.client.bridge.HelloInfo;
import dev.kantobridge.client.bridge.JsonFields;
import dev.kantobridge.client.bridge.Transport;
import dev.kantobridge.client.puppet.Puppet;
import dev.kantobridge.client.puppet.PuppetManager;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.Dir;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Talking end to end without Minecraft (docs/PLAN.md §2.4 flows 2 and 5): a real {@link BridgeClient} against
 * {@code tools/fake_gen1}'s made-up town. The snapshot's NPCs become puppets; the player stands next to the town's
 * person, A aims at the cell in front ({@code act.interact}), fake_gen1 confirms and opens a text box, the router shows
 * it, types it out, and A at its end asks for {@code ui.advance}, which closes it. A box opened from fake_gen1's console
 * is closed by a dropped link and stays closed after the reconnect's snapshot. Skipped without the project venv.
 */
class UiFakeGen1IntegrationTest {
	@Test
	void talkToAnNpcReadTheTextAndCloseIt() throws Exception {
		FakeGen1Process fake = FakeGen1Process.start();
		Assumptions.assumeTrue(fake != null,
				"tools/.venv is missing: run tools/setup.ps1 (or tools/setup.sh) to enable the fake_gen1 integration tests");
		BridgeClient client = null;
		try {
			Catalog catalog = Catalog.bundled();
			Dispatcher dispatcher = new Dispatcher(catalog);
			ClientBridgeState state = new ClientBridgeState();
			state.attach(dispatcher);
			BridgeClient[] link = new BridgeClient[1];
			List<String> sent = new ArrayList<>();
			UiRouter router = new UiRouter(new UiRouter.Port() {
				@Override
				public boolean send(String type, JsonObject data) {
					sent.add(type);
					return link[0].send(type, data);
				}
			});
			PuppetManager puppets = new PuppetManager(new PuppetManager.Listener() {
				@Override
				public void added(Puppet puppet) {
				}

				@Override
				public void removed(Puppet puppet) {
				}
			});
			List<JsonObject> results = new ArrayList<>();
			long[] now = {0};
			dispatcher.on(MsgTypes.UI_OPEN, m -> router.onOpen(m.data(), now[0]));
			dispatcher.on(MsgTypes.UI_UPDATE, m -> router.onUpdate(m.data(), now[0]));
			dispatcher.on(MsgTypes.UI_CLOSE, m -> router.onClose(m.data()));
			dispatcher.on(MsgTypes.SNAPSHOT, m -> {
				router.onSnapshot(m.data(), now[0]);
				puppets.onSet(JsonFields.obj(m.data(), "npcs"));
			});
			dispatcher.on(MsgTypes.PLAYER_STATE, m -> router.onMode(JsonFields.str(m.data(), "mode"), now[0]));
			dispatcher.on(MsgTypes.NPC_SET, m -> puppets.onSet(m.data()));
			dispatcher.on(MsgTypes.ACT_RESULT, m -> results.add(m.data()));
			dispatcher.onState((connection, detail) -> {
				if (connection != ConnectionState.CONNECTED) {
					router.onDisconnected();
					puppets.clear();
				}
			});
			BridgeConfig config = BridgeConfig.fromCatalog(catalog, fake.port()).withHeartbeat(200, 5000).withReconnect(100, 500);
			client = new BridgeClient(config, catalog,
					() -> new HelloInfo("0.1.0", "1.21.1", UUID.fromString("0f8b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b"), "Tester"),
					Transport.socket(), BridgeClock.system(), dispatcher);
			link[0] = client;
			client.start();
			BridgeClient bridge = client;
			Runnable pump = () -> {
				dispatcher.drain(1000);
				now[0] += 50;
				router.tick(now[0]);
			};

			waitFor("the link and snapshot", pump, () -> bridge.state() == ConnectionState.CONNECTED && state.ingame());
			// The town's three NPCs are puppets with negative ids.
			assertEquals(3, puppets.size());
			assertEquals(PuppetManager.FIRST_ID, puppets.get("TEST_TOWN_obj_1").entityId());
			assertTrue(puppets.all().stream().allMatch(p -> p.entityId() <= PuppetManager.FIRST_ID));

			// Stand left of the person (10,8) looking east, and press A.
			fake.command("pos 9 8 right");
			waitFor("the hidden player to move", pump, () -> state.x() == 9 && state.y() == 8);
			CellPos target = InteractTarget.faced(state.map(), state.x(), state.y(), Dir.fromYaw(Dir.RIGHT.yaw()));
			assertEquals(new CellPos("TEST_TOWN", 10, 8), target);
			assertTrue(InteractTarget.reachable(state.map(), state.x(), state.y(), target));
			JsonObject interact = new JsonObject();
			interact.addProperty("map", target.map());
			interact.addProperty("x", target.x());
			interact.addProperty("y", target.y());
			assertTrue(client.send(MsgTypes.ACT_INTERACT, interact));
			waitFor("the text box", pump, () -> router.open());
			assertEquals(1, results.size());
			assertTrue(JsonFields.bool(results.get(0), "ok", false));
			UiRouter.View view = router.view();
			assertEquals(UiKind.TEXT, view.main().kind());
			TextBoxModel text = view.main().text();
			assertEquals(1, text.pageCount());

			// It types out to its end with the ▼, then A asks for the advance that closes it.
			waitFor("the typing", pump, () -> text.stop() == TextBoxModel.Stop.END);
			assertTrue(text.arrow());
			assertEquals(2, text.visible().size(), "both lines in view");
			router.press(view.main(), now[0]);
			waitFor("the close", pump, () -> !router.open());
			assertEquals(List.of(MsgTypes.UI_ADVANCE), sent);
			assertEquals(1, router.stats().closed);

			// A box from fake_gen1's console closes when the link drops, and the reconnect's snapshot has none.
			fake.command("text Made up | for tests");
			waitFor("the console's box", pump, () -> router.open());
			assertEquals(1, router.top().text().pageCount());
			fake.command("drop");
			waitFor("the drop", pump, () -> !router.open());
			waitFor("the reconnect", pump, () -> bridge.state() == ConnectionState.CONNECTED && state.ingame() && puppets.size() == 3);
			for (int i = 0; i < 10; i++) {
				pump.run();
				Thread.sleep(10);
			}
			assertTrue(!router.open(), "nothing reopened");
			assertEquals(0, router.stats().refused);
		} finally {
			if (client != null) {
				client.close();
			}
			fake.close();
		}
	}

	private static void waitFor(String what, Runnable pump, BooleanSupplier done) throws InterruptedException {
		long deadline = System.nanoTime() + 15_000_000_000L;
		while (System.nanoTime() < deadline) {
			pump.run();
			if (done.getAsBoolean()) {
				return;
			}
			Thread.sleep(10);
		}
		throw new AssertionError("timed out waiting for " + what);
	}
}
