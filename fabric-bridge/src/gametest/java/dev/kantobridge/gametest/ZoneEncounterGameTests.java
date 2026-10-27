package dev.kantobridge.gametest;

import com.google.gson.JsonObject;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.server.ZoneService;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.WorldIndex;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Wild Routes trainers on the server (WR5, docs/specs/WILD_ROUTES_PLAN.md §4.6) with a mock player and a made-up zoned
 * WorldIndex handed straight to {@link ZoneService#tickPlayer} (nothing is built or published): the sighting's
 * {@code route.sight}, then {@code route.battle} with the party and a line, the client's {@code route.result} through
 * the relay's own handler, no second challenge on the same visit, and a rematch after leaving the zone.
 */
public class ZoneEncounterGameTests implements FabricGameTest {
	private static final String HASH = "0".repeat(64);
	/** Far from every other test's blocks: the route map's A at block (5,200,000, 5,200,000). */
	private static final int AX = 5_200_000;
	private static final int AZ = 5_200_000;

	private static WorldIndex index() {
		WorldIndex.ZoneTrainer trainer = new WorldIndex.ZoneTrainer("a1.t1", 5, 0, Dir.RIGHT, 3, "OPP_LASS", "SPRITE_TEST_GIRL",
				List.of(new WorldIndex.PartyMember("TESTMON_A", 6), new WorldIndex.PartyMember("TESTMON_B", 7)));
		return new WorldIndex("red", HASH,
				List.of(new MapPlacement("KB_ROUTE_GT", AX, 64, AZ, 2, 2, 0, MapPlacement.Kind.ROUTE, "PAL", "kantobridge:wild_forest", false, null)),
				HASH, StoryCellTable.empty(), List.of(), List.of(new WorldIndex.Zone("gt_zone", "KB_ROUTE_GT", "forest", AX - 20, 64, AZ - 40,
						160, 120, "kantobridge:wild_forest", List.of(trainer), List.of(), null)));
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void aTrainerSeesBattlesAndRematchesOnTheNextVisit(GameTestHelper helper) {
		WorldIndex index = index();
		MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
			// cell (8, 0): three cells in front of the trainer
			player.teleportTo(AX + 17.0, 64, AZ + 1.0);
			long t = 1_000;
			ZoneService.tickPlayer(player, index, t);
			List<Envelope> sights = sent.stream().filter(m -> m.type().equals(MsgTypes.ROUTE_SIGHT)).toList();
			helper.assertTrue(sights.size() == 1, "sightings " + sights.size());
			JsonObject sight = sights.get(0).data();
			helper.assertTrue("{\"route\":\"gt_zone\",\"id\":\"a1.t1\",\"x\":7,\"y\":0,\"frames\":32}".equals(sight.toString()), "sight " + sight);
			Envelope battle = null;
			for (long k = t + 1; k < t + 100 && battle == null; k++) {
				ZoneService.tickPlayer(player, index, k);
				battle = sent.stream().filter(m -> m.type().equals(MsgTypes.ROUTE_BATTLE)).findFirst().orElse(null);
			}
			helper.assertTrue(battle != null, "no route.battle");
			JsonObject data = battle.data();
			helper.assertTrue("OPP_LASS".equals(data.get("class").getAsString()) && data.getAsJsonArray("party").size() == 2
					&& data.getAsJsonObject("lines").get("before").getAsString().length() <= 18, "battle " + data.keySet());
			// the client relays the hidden game's win
			JsonObject result = new JsonObject();
			result.addProperty("op", "battle");
			result.addProperty("ok", true);
			result.addProperty("route", "gt_zone");
			result.addProperty("id", "a1.t1");
			result.addProperty("outcome", "win");
			RelayService.get().handler(MsgTypes.ROUTE_RESULT).handle(player, new Envelope(MsgTypes.ROUTE_RESULT, 9, battle.seq(), result));
			helper.assertTrue(ZoneService.encounters().beaten(player.getUUID()).contains("a1.t1"), "the win wasn't kept");
			int before = sent.size();
			for (long k = t + 200; k < t + 260; k++) {
				ZoneService.tickPlayer(player, index, k);
			}
			helper.assertTrue(sent.size() == before, "challenged twice on one visit");
			// out of the zone and back: a rematch
			player.teleportTo(AX - 500.0, 64, AZ - 500.0);
			ZoneService.tickPlayer(player, index, t + 300);
			player.teleportTo(AX + 17.0, 64, AZ + 1.0);
			ZoneService.tickPlayer(player, index, t + 301);
			helper.assertTrue(sent.stream().filter(m -> m.type().equals(MsgTypes.ROUTE_SIGHT)).count() == 2, "no rematch on the next visit");
			ZoneService.encounters().forget(player.getUUID());
		}));
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void aPlayerWhoLeavesInTheZoneReturnsToTheSameVisit(GameTestHelper helper) {
		// WORLD_SESSIONS_PLAN D10 (WS1): the visit is kept on the player (kantobridge:zone_visit) across a leave
		WorldIndex index = index();
		MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
			player.teleportTo(AX + 17.0, 64, AZ + 1.0);
			long t = 5_000;
			ZoneService.tickPlayer(player, index, t);
			JsonObject result = new JsonObject();
			result.addProperty("op", "battle");
			result.addProperty("ok", true);
			result.addProperty("route", "gt_zone");
			result.addProperty("id", "a1.t1");
			result.addProperty("outcome", "win");
			RelayService.get().handler(MsgTypes.ROUTE_RESULT).handle(player, new Envelope(MsgTypes.ROUTE_RESULT, 9, null, result));
			helper.assertTrue(ZoneService.encounters().beaten(player.getUUID()).contains("a1.t1"), "the win wasn't kept");
			ZoneService.leave(player);
			helper.assertTrue(ZoneService.encounters().zone(player.getUUID()) == null, "the server forgot the visit");
			helper.assertTrue(dev.kantobridge.server.ZoneVisitRecord.stored(player) != null, "the visit wasn't kept on the player");
			ZoneService.join(player);
			helper.assertTrue(dev.kantobridge.server.ZoneVisitRecord.stored(player) == null, "the kept visit is read once");
			int before = (int) sent.stream().filter(m -> m.type().equals(MsgTypes.ROUTE_SIGHT)).count();
			for (long k = t + 10; k < t + 70; k++) {
				ZoneService.tickPlayer(player, index, k);
			}
			helper.assertTrue(sent.stream().filter(m -> m.type().equals(MsgTypes.ROUTE_SIGHT)).count() == before,
					"challenged again on the continued visit");
			helper.assertTrue(ZoneService.encounters().beaten(player.getUUID()).contains("a1.t1"), "the continued visit lost its win");
			ZoneService.encounters().forget(player.getUUID());
		}));
		helper.succeed();
	}
}
