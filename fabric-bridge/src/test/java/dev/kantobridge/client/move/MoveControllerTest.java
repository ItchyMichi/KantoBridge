package dev.kantobridge.client.move;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.builder.TestData;
import dev.kantobridge.client.bridge.ConnectionStatus;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.WorldIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The controller between the bridge's messages, the models and the links: parsing, the warp round trip, the lock. */
class MoveControllerTest {
	private static WorldIndex world;

	@BeforeAll
	static void readIndex() throws Exception {
		world = WorldIndex.fromJson(Files.readString(TestData.goldenIndex(), StandardCharsets.UTF_8));
	}

	static final class FakePort implements MoveController.Port {
		final List<String> bridge = new ArrayList<>();
		final List<String> server = new ArrayList<>();
		final List<String> warnings = new ArrayList<>();
		long nextSeq = 1;
		boolean ready;
		@Nullable Vec3 readyTarget;

		@Override
		public boolean toBridge(String type, JsonObject data) {
			this.bridge.add(type + " " + data);
			return true;
		}

		@Override
		public long toServer(String type, JsonObject data) {
			this.server.add(type + " " + data);
			return this.nextSeq++;
		}

		@Override
		public long toBridgeTracked(String type, JsonObject data) {
			this.bridge.add(type + " " + data);
			return this.nextSeq++;
		}

		@Override
		public boolean destinationReady(@Nullable Vec3 target) {
			this.readyTarget = target;
			return this.ready;
		}

		@Override
		public void warn(String area, String code, long count) {
			this.warnings.add(area + "." + code + " " + count);
		}
	}

	static final class Rig {
		final FakePort port = new FakePort();
		final MoveController controller = new MoveController(this.port);
		long now = 5_000;
		double x;
		double y;
		double z;
		boolean connected = true;
		boolean ingame = true;
		boolean free;
		boolean battle;
		boolean roam;
		String mode = "idle";
		@Nullable WorldIndex index = world;

		void at(String map, int cx, int cy) {
			Vec3 centre = world.toBlock(map, cx, cy);
			this.x = centre.x;
			this.y = centre.y;
			this.z = centre.z;
		}

		ForcedMover.@Nullable Velocity tick(@Nullable Dir intent, float yaw) {
			this.now += 50;
			ForcedMover.Velocity velocity = this.controller.beforeMove(this.now,
					new MoveController.Frame(this.index, this.free, this.connected, this.ingame, this.mode, false, this.battle, this.roam, this.x,
							this.z));
			if (velocity != null) {
				this.x += velocity.x();
				this.z += velocity.z();
			}
			this.controller.afterMove(this.now, this.x, this.y, this.z, intent, yaw);
			return velocity;
		}

		void bridge(String type, String json) throws Exception {
			this.bridge(type, null, json);
		}

		void bridge(String type, @Nullable Long re, String json) throws Exception {
			this.controller.onBridgeMessage(new Envelope(type, 1, re, ProtoJson.parseObject(json)), this.now);
		}

		void server(String type, @Nullable Long re, String json) throws Exception {
			this.controller.onServerMessage(new Envelope(type, 1, re, ProtoJson.parseObject(json)), this.now);
		}
	}

	private static Rig linkedAt(String map, int cx, int cy, String facing) throws Exception {
		Rig rig = new Rig();
		rig.at(map, cx, cy);
		rig.bridge("snapshot", "{\"ingame\":true,\"player\":{\"map\":\"" + map + "\",\"x\":" + cx + ",\"y\":" + cy + ",\"facing\":\"" + facing
				+ "\"},\"state\":{\"mode\":\"idle\",\"bike\":false,\"surf\":false,\"slope\":false,\"hidden\":false,\"dark\":false,\"lit\":false}}");
		rig.tick(null, Dir.byId(facing).yaw());
		return rig;
	}

	@Test
	void stepsAndFacesGoToGen1BridgeInTheProtocolsShape() throws Exception {
		Rig rig = linkedAt("TEST_TOWN", 5, 8, "up");
		assertEquals(InputLock.Reason.NONE, rig.controller.reason());
		assertTrue(rig.controller.following());
		rig.at("TEST_TOWN", 5, 7);
		rig.tick(null, 180.0F);
		assertEquals(List.of("move.step {\"dir\":\"up\",\"n\":1}"), rig.port.bridge);
		rig.bridge("player.pos", "{\"map\":\"TEST_TOWN\",\"x\":5,\"y\":7,\"facing\":\"up\",\"kind\":\"walk\",\"frames\":16,\"n\":1}");
		rig.port.bridge.clear();
		for (int i = 0; i < MovementSync.PUSH_TICKS; i++) {
			rig.tick(Dir.UP, 180.0F);
		}
		assertEquals(List.of("move.step {\"dir\":\"up\",\"n\":2,\"push\":true}"), rig.port.bridge);
		rig.bridge("player.blocked", "{\"dir\":\"up\",\"reason\":\"tile\",\"map\":\"TEST_TOWN\",\"x\":5,\"y\":7,\"n\":2}");
		rig.port.bridge.clear();
		for (int i = 0; i < MovementSync.FACE_STABLE_TICKS; i++) {
			rig.tick(null, 90.0F);
		}
		assertEquals(List.of("move.face {\"dir\":\"left\"}"), rig.port.bridge);
		assertEquals(List.of(), rig.port.warnings);
	}

	@Test
	void aWarpFadesRequestsAndOpensAgain() throws Exception {
		Rig rig = linkedAt("TEST_TOWN", 5, 5, "up");
		rig.bridge("warp", "{\"phase\":\"start\",\"kind\":\"door\"}");
		rig.bridge("warp", "{\"phase\":\"arrive\",\"kind\":\"door\",\"to\":{\"map\":\"TEST_HOUSE\",\"x\":4,\"y\":8,\"facing\":\"up\"}}");
		assertEquals(InputLock.Reason.NONE, rig.controller.reason(), "decided on the next tick");
		rig.tick(Dir.UP, 180.0F);
		assertEquals(InputLock.Reason.WARP, rig.controller.reason());
		for (int i = 0; i < 5; i++) {
			rig.tick(null, 180.0F);
		}
		assertEquals(List.of("warp.request {\"to\":{\"map\":\"TEST_HOUSE\",\"x\":4,\"y\":8,\"facing\":\"up\"},\"kind\":\"door\"}"),
				rig.port.server);
		rig.server("warp.done", 99L, "{\"ok\":true}");
		rig.port.ready = true;
		rig.tick(null, 180.0F);
		assertEquals(WarpFade.Phase.DARK, rig.controller.fade().phase(), "an answer to another request changes nothing");

		rig.server("warp.done", 1L, "{\"ok\":true,\"to\":{\"map\":\"TEST_HOUSE\",\"x\":4,\"y\":8,\"facing\":\"up\"}}");
		rig.at("TEST_HOUSE", 4, 8);
		rig.tick(null, 180.0F);
		assertEquals(WarpFade.Phase.IN, rig.controller.fade().phase());
		assertEquals(world.toBlock("TEST_HOUSE", 4, 8), rig.port.readyTarget, "the readiness check looks at the requested cell");
		rig.tick(null, 180.0F);
		assertEquals(InputLock.Reason.NONE, rig.controller.reason(), "free again while the screen opens");
		assertEquals(0, rig.controller.sync().stats().teleports, "landed where the hidden player is: nothing to fix");
		assertEquals(List.of(), rig.port.warnings);
	}

	@Test
	void aCaveShaftsWarpIsTakenWhereThePlayerStandsWithoutAFade() throws Exception {
		// CAVES_3D_PLAN §3.6: down the ladder at TEST_CAVE_1F (3, 5) onto B1F's (3, 3)
		Rig rig = linkedAt("TEST_CAVE_1F", 2, 5, "right");
		rig.at("TEST_CAVE_1F", 3, 5);
		rig.tick(null, Dir.RIGHT.yaw());
		rig.y -= 6;
		rig.tick(null, Dir.RIGHT.yaw());
		rig.at("TEST_CAVE_B1F", 3, 3);
		rig.tick(null, Dir.RIGHT.yaw());
		assertEquals(List.of("warp.take {\"from\":{\"map\":\"TEST_CAVE_1F\",\"x\":3,\"y\":5},\"to\":{\"map\":\"TEST_CAVE_B1F\",\"x\":3,"
				+ "\"y\":3,\"facing\":\"right\"},\"kind\":\"ladder\"}"), rig.port.bridge, "no step onto the shaft, the take at the bottom");
		rig.tick(null, Dir.RIGHT.yaw());
		assertEquals(InputLock.Reason.WARP, rig.controller.reason(), "held while the hidden game takes it");
		rig.bridge("warp.taken", 1L, "{\"ok\":true}");
		rig.bridge("warp", 1L, "{\"phase\":\"start\",\"kind\":\"stairs\"}");
		rig.bridge("warp", 1L, "{\"phase\":\"arrive\",\"kind\":\"stairs\",\"to\":{\"map\":\"TEST_CAVE_B1F\",\"x\":3,\"y\":3,\"facing\":\"right\"}}");
		rig.tick(null, Dir.RIGHT.yaw());
		assertEquals(InputLock.Reason.NONE, rig.controller.reason());
		assertEquals(List.of(), rig.port.server, "no fade, no teleport: the player already stands there");
		assertEquals(WarpFade.Phase.IDLE, rig.controller.fade().phase());
		assertEquals(1, rig.controller.sync().stats().takes);
	}

	@Test
	void aRefusedWarpOpensTheScreenWithAWarning() throws Exception {
		Rig rig = linkedAt("TEST_TOWN", 5, 5, "up");
		rig.bridge("warp", "{\"phase\":\"arrive\",\"kind\":\"door\",\"to\":{\"map\":\"ROUTE_99\",\"x\":4,\"y\":8,\"facing\":\"up\"}}");
		for (int i = 0; i < 6; i++) {
			rig.tick(null, 180.0F);
		}
		assertEquals(1, rig.port.server.size());
		rig.server("warp.done", 1L, "{\"ok\":false}");
		rig.tick(null, 180.0F);
		assertEquals(List.of("warp.refused 1"), rig.port.warnings);
		assertEquals(InputLock.Reason.UNBUILT_MAP, rig.controller.reason(), "the hidden player is somewhere this world doesn't have");
		ConnectionStatus.Line hint = rig.controller.hint();
		assertNotNull(hint);
		assertEquals("kantobridge.hud.move.unbuilt", hint.key());
		assertEquals(List.of("ROUTE_99"), hint.args());
	}

	@Test
	void theLockFollowsTheLinkTheGameAndTheWorld() throws Exception {
		Rig rig = linkedAt("TEST_TOWN", 5, 8, "down");
		rig.connected = false;
		rig.tick(null, 0.0F);
		assertEquals(InputLock.Reason.NOT_CONNECTED, rig.controller.reason());
		assertNull(rig.controller.hint(), "the connection line already says so");
		rig.connected = true;
		rig.ingame = false;
		rig.tick(null, 0.0F);
		assertEquals(InputLock.Reason.NOT_IN_GAME, rig.controller.reason());
		assertEquals("kantobridge.hud.move.not_in_game", rig.controller.hint().key());
		rig.ingame = true;
		rig.mode = "cutscene";
		rig.tick(null, 0.0F);
		assertEquals(InputLock.Reason.BUSY, rig.controller.reason());
		rig.free = true;
		rig.tick(null, 0.0F);
		assertEquals(InputLock.Reason.NONE, rig.controller.reason(), "creative and spectator look around freely");
		assertFalse(rig.controller.following());
		rig.free = false;
		rig.index = null;
		rig.tick(null, 0.0F);
		assertEquals(InputLock.Reason.NONE, rig.controller.reason(), "not a Kanto world");
		assertFalse(rig.controller.following());
	}

	@Test
	void aBattleHoldsThePlayerAndTellsTheWaitingGameNothing() throws Exception {
		Rig rig = linkedAt("TEST_TOWN", 5, 8, "up");
		rig.battle = true;
		rig.tick(null, 180.0F);
		assertEquals(InputLock.Reason.BATTLE, rig.controller.reason());
		// A sent-out Pokémon shoves the player a cell up: no step, push or face reaches the hidden game.
		rig.at("TEST_TOWN", 5, 7);
		for (int i = 0; i < 2 * MovementSync.PUSH_TICKS; i++) {
			rig.tick(Dir.UP, 90.0F);
		}
		assertEquals(List.of(), rig.port.bridge);
		assertEquals(List.of(), rig.port.server);
		// After the battle the player is put back on the hidden cell (a sync teleport, without a kind).
		rig.battle = false;
		for (int i = 0; i < 20 && rig.port.server.isEmpty(); i++) {
			rig.tick(null, 180.0F);
		}
		assertEquals(List.of(), rig.port.bridge);
		assertEquals(List.of("warp.request {\"to\":{\"map\":\"TEST_TOWN\",\"x\":5,\"y\":8,\"facing\":\"up\"}}"), rig.port.server);
	}

	@Test
	void aMinimisedBattleLetsThePlayerRoamAndSnapsBackAfterwards() throws Exception {
		Rig rig = linkedAt("TEST_TOWN", 5, 8, "up");
		double startX = rig.x;
		double startZ = rig.z;
		rig.battle = true;
		rig.tick(null, 180.0F);
		assertEquals(InputLock.Reason.BATTLE, rig.controller.reason(), "the battle's screen up: held");
		assertEquals(new BattleRoam.Anchor(startX, startZ), rig.controller.roamAnchor());
		// R: the screen minimised, the player walks two cells off; the anchor stays where the battle started
		rig.roam = true;
		rig.tick(null, 180.0F);
		assertEquals(InputLock.Reason.ROAM, rig.controller.reason());
		rig.at("TEST_TOWN", 5, 6);
		for (int i = 0; i < 2 * MovementSync.PUSH_TICKS; i++) {
			rig.tick(Dir.UP, 180.0F);
		}
		assertEquals(InputLock.Reason.ROAM, rig.controller.reason());
		assertEquals(new BattleRoam.Anchor(startX, startZ), rig.controller.roamAnchor());
		assertEquals(List.of(), rig.port.bridge, "the waiting hidden game is told nothing");
		assertEquals(List.of(), rig.port.server);
		// R again, then the battle ends: back on the hidden cell
		rig.roam = false;
		rig.tick(null, 180.0F);
		assertEquals(InputLock.Reason.BATTLE, rig.controller.reason());
		rig.battle = false;
		for (int i = 0; i < 20 && rig.port.server.isEmpty(); i++) {
			rig.tick(null, 180.0F);
		}
		assertNull(rig.controller.roamAnchor());
		assertEquals(List.of(), rig.port.bridge);
		assertEquals(List.of("warp.request {\"to\":{\"map\":\"TEST_TOWN\",\"x\":5,\"y\":8,\"facing\":\"up\"}}"), rig.port.server);
	}

	@Test
	void roamingNeedsABattle() throws Exception {
		Rig rig = linkedAt("TEST_TOWN", 5, 8, "up");
		rig.roam = true;
		rig.tick(null, 180.0F);
		assertEquals(InputLock.Reason.NONE, rig.controller.reason(), "a stale minimised flag without a battle changes nothing");
		assertNull(rig.controller.roamAnchor());
	}

	@Test
	void forcedMovesDriveThePlayerAndHoldTheKeys() throws Exception {
		Rig rig = linkedAt("TEST_TOWN", 5, 8, "down");
		rig.bridge("player.state", "{\"mode\":\"forced\",\"bike\":false,\"surf\":false,\"slope\":false,\"hidden\":false,\"dark\":false,\"lit\":false}");
		rig.mode = "forced";
		rig.bridge("player.pos", "{\"map\":\"TEST_TOWN\",\"x\":5,\"y\":10,\"facing\":\"down\",\"kind\":\"ledge\",\"frames\":32}");
		double startZ = rig.z;
		List<InputLock.Reason> reasons = new ArrayList<>();
		while (rig.tick(null, 0.0F) != null && reasons.size() < 30) {
			reasons.add(rig.controller.reason());
		}
		assertEquals(startZ + 4.0, rig.z, 1.0E-9, "two cells south");
		assertEquals(12, reasons.size(), "32 frames in 11 ticks, then the brake");
		assertTrue(reasons.subList(0, 11).stream().allMatch(reason -> reason == InputLock.Reason.FORCED), "keys held while moving");
		assertEquals(InputLock.Reason.BUSY, reasons.get(11), "then the hidden game's own mode holds them");
	}

	@Test
	void malformedMessagesAreIgnored() throws Exception {
		Rig rig = linkedAt("TEST_TOWN", 5, 8, "down");
		rig.bridge("player.pos", "{\"map\":7,\"x\":\"nine\",\"kind\":\"walk\"}");
		rig.bridge("player.pos", "{\"map\":\"TEST_TOWN\",\"x\":5,\"y\":9,\"facing\":\"sideways\",\"kind\":\"walk\",\"frames\":16}");
		rig.bridge("player.blocked", "{\"dir\":\"up\"}");
		rig.bridge("warp", "{\"phase\":\"arrive\",\"kind\":\"door\",\"to\":{\"map\":\"TEST_HOUSE\"}}");
		rig.bridge("warp", "{\"phase\":\"sideways\",\"kind\":\"door\"}");
		rig.server("warp.done", null, "{\"ok\":true}");
		rig.tick(null, 0.0F);
		assertEquals(InputLock.Reason.NONE, rig.controller.reason());
		assertEquals(0, rig.controller.sync().stats().positions);
		assertEquals(List.of(), rig.port.bridge);
		assertEquals(List.of(), rig.port.server);
	}
}
