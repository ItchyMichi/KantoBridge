package dev.kantobridge.gametest;

import dev.kantobridge.net.RelayService;
import dev.kantobridge.server.BuildHold;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * Players who join while a "Create Kanto world" build runs wait as spectators and are let in at the spawn when it is
 * done; nobody is ever saved as a spectator. The hold applies to every player on the server, so these tests run in a
 * batch of their own.
 */
public class BuildHoldGameTests implements FabricGameTest {
	private static final String BATCH = "kantobridge_build_hold";

	/** The game mode itself: GameTest mock players override isSpectator() to always say no. */
	private static GameType mode(ServerPlayer player) {
		return player.gameMode.getGameModeForPlayer();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void joiningPlayersWaitUntilTheBuildIsDone(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		BuildHold.begin(server);
		try {
			MockPlayers.with(helper, player -> {
				helper.assertTrue(mode(player) == GameType.SPECTATOR, "a player who joins during the build waits as a spectator");
				helper.assertTrue(BuildHold.isHeld(player), "the player is held");
				BuildHold.release(server);
				helper.assertFalse(BuildHold.isHolding(server), "release ends the hold");
				helper.assertTrue(mode(player) != GameType.SPECTATOR, "released players get their own game mode back");
				BlockPos spawn = server.overworld().getSharedSpawnPos();
				helper.assertTrue(player.blockPosition().equals(spawn), "released players stand on the world spawn");
			});
		} finally {
			BuildHold.release(server);
		}
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void theRulesLeaveHeldPlayersWaiting(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		BuildHold.begin(server);
		try {
			MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
				helper.assertTrue(mode(player) == GameType.SPECTATOR, "the rules put a held player into Adventure");
				helper.assertTrue(player.getAttributeBaseValue(Attributes.JUMP_STRENGTH) == 0.0, "movement rules apply to everyone");
				Vec3 waiting = player.position();
				RelayService.get().receive(player, "{\"type\":\"warp.request\",\"seq\":1,\"data\":"
						+ "{\"to\":{\"map\":\"TEST_TOWN\",\"x\":5,\"y\":6,\"facing\":\"down\"}}}");
				helper.assertTrue(player.position().equals(waiting), "a held player was warped out of the hold");
				helper.assertTrue(sent.size() == 1 && !sent.get(0).data().get("ok").getAsBoolean(), "the warp must be refused");
				BuildHold.release(server);
				helper.assertTrue(mode(player) == GameType.ADVENTURE, "a player let in must be in Adventure");
			}));
		} finally {
			BuildHold.release(server);
		}
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void aHeldPlayerWhoLeavesIsSavedWithTheirOwnMode(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		BuildHold.begin(server);
		ServerPlayer[] left = new ServerPlayer[1];
		try {
			MockPlayers.with(helper, player -> {
				left[0] = player;
				helper.assertTrue(mode(player) == GameType.SPECTATOR, "held as a spectator");
			});
			// MockPlayers removed the player: PlayerList.remove saves it, after the leave hook restored the mode.
			helper.assertTrue(mode(left[0]) != GameType.SPECTATOR, "no save keeps the spectator mode");
			helper.assertFalse(BuildHold.isHeld(left[0]), "a player who left is no longer held");
		} finally {
			BuildHold.release(server);
		}
		helper.succeed();
	}
}
