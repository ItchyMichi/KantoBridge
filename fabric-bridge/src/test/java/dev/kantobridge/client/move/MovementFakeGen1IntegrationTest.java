package dev.kantobridge.client.move;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.builder.TestData;
import dev.kantobridge.client.bridge.BridgeClient;
import dev.kantobridge.client.bridge.BridgeClock;
import dev.kantobridge.client.bridge.BridgeConfig;
import dev.kantobridge.client.bridge.ClientBridgeState;
import dev.kantobridge.client.bridge.ConnectionState;
import dev.kantobridge.client.bridge.Dispatcher;
import dev.kantobridge.client.bridge.FakeGen1Process;
import dev.kantobridge.client.bridge.HelloInfo;
import dev.kantobridge.client.bridge.Transport;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.Place;
import dev.kantobridge.world.WorldIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Walking and warps end to end without Minecraft: a real {@link BridgeClient} talks to {@code tools/fake_gen1} (its
 * made-up town and house), a {@link MoveController} drives a simulated player in the synthetic world's WorldIndex, and
 * the test plays the server's part ({@code warp.request} teleports and answers). The player is put on the hidden
 * player's cell on joining, walks up into the house, and leaves by pushing against the exit mat's edge, ending one cell
 * below the town door after the walk-out. Skipped without the project venv.
 */
class MovementFakeGen1IntegrationTest {
	private static final double WALK_PER_TICK = 0.375;

	/** The simulated player, and the server's half of warps. */
	static final class Sim implements MoveController.Port {
		final WorldIndex world;
		final Dispatcher dispatcher;
		final ClientBridgeState state;
		@Nullable BridgeClient client;
		@Nullable MoveController controller;
		final List<String> toGen1 = new ArrayList<>();
		final List<String> requests = new ArrayList<>();
		final List<String> warnings = new ArrayList<>();
		private final ArrayDeque<Envelope> serverInbox = new ArrayDeque<>();
		private long nextSeq = 1;
		long now;
		double x;
		double y;
		double z;
		float yaw;

		Sim(WorldIndex world, Dispatcher dispatcher, ClientBridgeState state) {
			this.world = world;
			this.dispatcher = dispatcher;
			this.state = state;
		}

		@Override
		public boolean toBridge(String type, JsonObject data) {
			this.toGen1.add(type + " " + data);
			return this.client.send(type, data);
		}

		@Override
		public long toServer(String type, JsonObject data) {
			// WarpService: teleport to the cell's centre at feet level, facing its way, then answer with the same re.
			long seq = this.nextSeq++;
			this.requests.add(type + " " + data);
			Place to = Place.fromJson(data.get("to"));
			Vec3 centre = this.world.toBlock(to.map(), to.x(), to.y());
			this.x = centre.x;
			this.y = centre.y;
			this.z = centre.z;
			this.yaw = to.facing().yaw();
			JsonObject reply = new JsonObject();
			reply.addProperty("ok", true);
			reply.add("to", to.toJson());
			this.serverInbox.add(new Envelope(MsgTypes.WARP_DONE, seq, seq, reply));
			return seq;
		}

		@Override
		public boolean destinationReady(@Nullable Vec3 target) {
			return target == null || (Math.abs(this.x - target.x) <= 1.0 && Math.abs(this.z - target.z) <= 1.0);
		}

		@Override
		public void warn(String area, String code, long count) {
			this.warnings.add(area + "." + code);
		}

		void moveTo(String map, int cx, int cy) {
			Vec3 centre = this.world.toBlock(map, cx, cy);
			this.x = centre.x;
			this.y = centre.y;
			this.z = centre.z;
		}

		@Nullable CellPos cell() {
			return this.world.toCell((int) Math.floor(this.x), (int) Math.floor(this.y), (int) Math.floor(this.z)).orElse(null);
		}

		/**
		 * One world tick: what arrived meanwhile first (as Minecraft's client tick drains the bridge), then the controller
		 * around the player's move. The player walks {@code walking} at Gen 1 speed unless held or against a wall.
		 */
		void tick(@Nullable Dir walking, boolean wall) throws InterruptedException {
			// Real time for fake_gen1 to answer; the controller runs on simulated time.
			Thread.sleep(15);
			this.dispatcher.drain(1000);
			Envelope reply;
			while ((reply = this.serverInbox.poll()) != null) {
				this.controller.onServerMessage(reply, this.now);
			}
			this.now += 50;
			MoveController.Frame frame = new MoveController.Frame(this.world, false, this.client.state() == ConnectionState.CONNECTED,
					this.state.ingame(), this.state.mode(), false, this.x, this.z);
			ForcedMover.Velocity velocity = this.controller.beforeMove(this.now, frame);
			boolean held = this.controller.reason() != InputLock.Reason.NONE;
			if (velocity != null) {
				this.x += velocity.x();
				this.z += velocity.z();
			} else if (!held && walking != null && !wall) {
				this.x += walking.dx() * WALK_PER_TICK;
				this.z += walking.dy() * WALK_PER_TICK;
			}
			this.controller.afterMove(this.now, this.x, this.y, this.z, held ? null : walking, this.yaw);
		}

		void tickUntil(String what, int maxTicks, @Nullable Dir walking, boolean wall, BooleanSupplier done) throws InterruptedException {
			for (int i = 0; i < maxTicks; i++) {
				if (done.getAsBoolean()) {
					return;
				}
				this.tick(walking, wall);
			}
			throw new AssertionError("timed out waiting for " + what + " (player at " + this.cell() + ", lock "
					+ this.controller.reason() + ", fade " + this.controller.fade().phase() + ")");
		}

		boolean settledAt(String map, int cx, int cy) {
			Vec3 centre = this.world.toBlock(map, cx, cy);
			return this.controller.fade().phase() == WarpFade.Phase.IDLE && !this.controller.mover().active()
					&& Math.abs(this.x - centre.x) < 0.2 && Math.abs(this.z - centre.z) < 0.2 && Math.abs(this.y - centre.y) < 0.01;
		}
	}

	@Test
	void joinsWalksIntoTheHouseAndLeavesByTheExitMat() throws Exception {
		WorldIndex world = WorldIndex.fromJson(Files.readString(TestData.goldenIndex(), StandardCharsets.UTF_8));
		FakeGen1Process fake = FakeGen1Process.start();
		Assumptions.assumeTrue(fake != null,
				"tools/.venv is missing: run tools/setup.ps1 (or tools/setup.sh) to enable the fake_gen1 integration tests");
		BridgeClient client = null;
		try {
			Catalog catalog = Catalog.bundled();
			Dispatcher dispatcher = new Dispatcher(catalog);
			ClientBridgeState state = new ClientBridgeState();
			state.attach(dispatcher);
			Sim sim = new Sim(world, dispatcher, state);
			MoveController controller = new MoveController(sim);
			sim.controller = controller;
			for (String type : List.of(MsgTypes.SNAPSHOT, MsgTypes.PLAYER_POS, MsgTypes.PLAYER_BLOCKED, MsgTypes.PLAYER_STATE, MsgTypes.WARP)) {
				dispatcher.on(type, message -> controller.onBridgeMessage(message, sim.now));
			}
			BridgeConfig config = BridgeConfig.fromCatalog(catalog, fake.port()).withHeartbeat(200, 5000).withReconnect(100, 500);
			client = new BridgeClient(config, catalog,
					() -> new HelloInfo("0.1.0", "1.21.1", UUID.fromString("0f8b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b"), "Tester"),
					Transport.socket(), BridgeClock.system(), dispatcher);
			sim.client = client;
			client.start();

			// Joining somewhere else: once linked, the player is teleported to the hidden player (fake_gen1 starts on
			// TEST_TOWN 5,8 facing down), behind the fade, with a warp.request without a kind.
			sim.moveTo("TEST_TOWN", 2, 2);
			BridgeClient link = client;
			sim.tickUntil("the link", 300, null, false, () -> link.state() == ConnectionState.CONNECTED && state.ingame());
			sim.tickUntil("the join teleport", 100, null, false, () -> sim.settledAt("TEST_TOWN", 5, 8));
			assertEquals(List.of("warp.request {\"to\":{\"map\":\"TEST_TOWN\",\"x\":5,\"y\":8,\"facing\":\"down\"}}"), sim.requests);

			// Walk up to the town door (5,5): three steps, each answered with its n, then the door warp into the house.
			sim.yaw = Dir.UP.yaw();
			double door = world.toBlock("TEST_TOWN", 5, 5).z;
			sim.tickUntil("the door", 40, Dir.UP, false, () -> sim.z <= door || controller.fade().active());
			sim.tickUntil("the house", 100, null, false, () -> sim.settledAt("TEST_HOUSE", 4, 8));
			assertEquals("warp.request {\"to\":{\"map\":\"TEST_HOUSE\",\"x\":4,\"y\":8,\"facing\":\"up\"},\"kind\":\"door\"}", sim.requests.get(1));

			// Push down against the wall below the exit mat: after 8 ticks a push step, answered by the edge warp (a sync
			// with its n), then the door walk-out, walked by the forced mover once the screen opens.
			sim.yaw = Dir.DOWN.yaw();
			sim.tickUntil("the exit warp", 40, Dir.DOWN, true, () -> controller.fade().active());
			sim.tickUntil("the walk-out", 120, null, false, () -> sim.settledAt("TEST_TOWN", 5, 6));
			assertEquals("warp.request {\"to\":{\"map\":\"TEST_TOWN\",\"x\":5,\"y\":5,\"facing\":\"down\"},\"kind\":\"edge\"}", sim.requests.get(2));

			assertEquals(List.of("move.step {\"dir\":\"up\",\"n\":1}", "move.step {\"dir\":\"up\",\"n\":2}", "move.step {\"dir\":\"up\",\"n\":3}",
					"move.step {\"dir\":\"down\",\"n\":4,\"push\":true}"), sim.toGen1, "steps and the push, and nothing else");
			assertEquals(3, sim.requests.size());
			MovementSync.Stats stats = controller.sync().stats();
			assertEquals(0, stats.lost, "every step was answered");
			assertEquals(0, controller.sync().pendingSteps());
			assertEquals(0, stats.blocked);
			assertEquals(0, controller.fade().warnings());
			assertEquals(List.of(), sim.warnings);
			assertTrue("TEST_TOWN".equals(state.map()) && state.x() == 5 && state.y() == 6, "fake_gen1's player is where Minecraft's is");
		} finally {
			if (client != null) {
				client.close();
			}
			fake.close();
		}
	}
}
