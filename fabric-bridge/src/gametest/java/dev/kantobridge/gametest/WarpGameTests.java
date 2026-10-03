package dev.kantobridge.gametest;

import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.server.WarpService;
import dev.kantobridge.server.WorldIndexSync;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.Place;
import dev.kantobridge.world.WorldIndex;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * {@code warp.request} on a real server (docs/PLAN.md §2.4 flow 7, §4.4 {@code WarpService}) against the synthetic
 * world's WorldIndex (the golden file the synthetic build writes; publishing it changes nothing a build would not).
 * A batch of its own, so the index published here never races another test's.
 */
public class WarpGameTests implements FabricGameTest {
	private static final String BATCH = "kantobridge_warps";
	private static final String GOLDEN = "kantobridge/golden/synthetic_world_index.json";

	/** Makes the synthetic world's index the server's (without sending it to anyone) and returns it. */
	static WorldIndex publishSyntheticIndex(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		try (InputStream in = WarpGameTests.class.getClassLoader().getResourceAsStream(GOLDEN)) {
			helper.assertTrue(in != null, "golden WorldIndex missing");
			WorldIndexSync.publish(server, new String(in.readAllBytes(), StandardCharsets.UTF_8), false);
		} catch (IOException e) {
			throw new GameTestAssertException("golden WorldIndex unreadable");
		}
		WorldIndex index = WorldIndexSync.current(server);
		helper.assertTrue(index != null && index.map("TEST_HOUSE") != null, "the published index has no TEST_HOUSE");
		return index;
	}

	private static String request(long seq, String data) {
		return "{\"type\":\"warp.request\",\"seq\":" + seq + ",\"data\":" + data + "}";
	}

	private static void assertAt(GameTestHelper helper, ServerPlayer player, Vec3 want, float yaw, String what) {
		helper.assertTrue(player.position().distanceTo(want) < 1.0E-6, what + ": not at the cell's centre");
		helper.assertTrue(Math.abs(Mth.wrapDegrees(player.getYRot() - yaw)) < 1.0E-3, what + ": facing the wrong way");
	}

	private static void assertDone(GameTestHelper helper, Envelope reply, long re, boolean ok) {
		helper.assertTrue(reply.type().equals("warp.done"), "expected warp.done, got " + reply.type());
		helper.assertTrue(Objects.equals(reply.re(), re), "warp.done must answer seq " + re);
		helper.assertTrue(reply.data().get("ok").getAsBoolean() == ok, "warp.done ok must be " + ok + " for seq " + re);
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void aWarpRequestTeleportsToTheCellCentre(GameTestHelper helper) {
		WorldIndex index = publishSyntheticIndex(helper);
		RelayService relay = RelayService.get();
		MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
			long teleports = WarpService.teleports();
			relay.receive(player, request(7, "{\"to\":{\"map\":\"TEST_HOUSE\",\"x\":4,\"y\":8,\"facing\":\"up\"},\"kind\":\"door\"}"));
			assertAt(helper, player, index.toBlock("TEST_HOUSE", 4, 8), Dir.UP.yaw(), "door warp");
			helper.assertTrue(sent.size() == 1, "expected one reply, got " + sent.size());
			assertDone(helper, sent.get(0), 7, true);
			helper.assertTrue(new Place("TEST_HOUSE", 4, 8, Dir.UP).equals(Place.fromJson(sent.get(0).data().get("to"))),
					"warp.done must name the cell");

			// Without a kind: the client's own sync (join, drift), back up to the town, one layer up.
			relay.receive(player, request(8, "{\"to\":{\"map\":\"TEST_TOWN\",\"x\":5,\"y\":6,\"facing\":\"down\"}}"));
			assertAt(helper, player, index.toBlock("TEST_TOWN", 5, 6), Dir.DOWN.yaw(), "sync teleport");
			assertDone(helper, sent.get(1), 8, true);
			helper.assertTrue(WarpService.teleports() == teleports + 2, "two teleports counted");
		}));
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void badWarpRequestsAreRefused(GameTestHelper helper) {
		publishSyntheticIndex(helper);
		RelayService relay = RelayService.get();
		List<String> bad = List.of(
				"{\"to\":{\"map\":\"NOWHERE\",\"x\":1,\"y\":1,\"facing\":\"up\"}}",
				"{\"to\":{\"map\":\"TEST_HOUSE\",\"x\":10,\"y\":0,\"facing\":\"up\"}}",
				"{\"to\":{\"map\":\"TEST_HOUSE\",\"x\":0,\"y\":-1,\"facing\":\"up\"}}",
				"{\"to\":{\"map\":\"TEST_HOUSE\",\"x\":4,\"y\":8}}",
				"{\"to\":{\"map\":\"TEST_HOUSE\",\"x\":4,\"y\":8,\"facing\":\"up\"},\"kind\":\"portal\"}",
				"{\"to\":{\"map\":\"TEST_HOUSE\",\"x\":4,\"y\":8,\"facing\":\"up\"},\"player\":\"someone else\"}");
		MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
			Vec3 start = player.position();
			long unknownMap = WarpService.refusals(WarpService.Refusal.UNKNOWN_MAP);
			long badCell = WarpService.refusals(WarpService.Refusal.BAD_CELL);
			long badRequest = WarpService.refusals(WarpService.Refusal.BAD_REQUEST);
			for (int i = 0; i < bad.size(); i++) {
				relay.receive(player, request(20 + i, bad.get(i)));
			}
			helper.assertTrue(player.position().equals(start), "a refused request moved the player");
			helper.assertTrue(sent.size() == bad.size(), "every request is answered, got " + sent.size());
			for (int i = 0; i < bad.size(); i++) {
				assertDone(helper, sent.get(i), 20 + i, false);
				helper.assertFalse(sent.get(i).data().has("to"), "a refusal names no cell");
			}
			helper.assertTrue(WarpService.refusals(WarpService.Refusal.UNKNOWN_MAP) == unknownMap + 1, "unknown_map count");
			helper.assertTrue(WarpService.refusals(WarpService.Refusal.BAD_CELL) == badCell + 2, "bad_cell count");
			helper.assertTrue(WarpService.refusals(WarpService.Refusal.BAD_REQUEST) == badRequest + 3, "bad_request count");
		}));
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void aRequestMovesOnlyItsSender(GameTestHelper helper) {
		WorldIndex index = publishSyntheticIndex(helper);
		MockPlayers.with(helper, sender -> MockPlayers.with(helper, other -> {
			Vec3 otherStart = other.position();
			RelayService.get().receive(sender, request(1, "{\"to\":{\"map\":\"TEST_HOUSE_2F\",\"x\":5,\"y\":2,\"facing\":\"down\"},\"kind\":\"stairs\"}"));
			assertAt(helper, sender, index.toBlock("TEST_HOUSE_2F", 5, 2), Dir.DOWN.yaw(), "stairs warp");
			helper.assertTrue(other.position().equals(otherStart), "another player was moved");
		}));
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void withoutAnIndexNothingMoves(GameTestHelper helper) {
		MockPlayers.with(helper, player -> {
			Vec3 start = player.position();
			WarpService.Result result = WarpService.teleport(player, null, new Place("TEST_TOWN", 1, 1, Dir.UP));
			helper.assertTrue(result.refusal() == WarpService.Refusal.NO_INDEX, "a world without an index must refuse warps");
			helper.assertTrue(player.position().equals(start), "the player moved");
		});
		helper.succeed();
	}
}
