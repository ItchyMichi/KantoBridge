package dev.kantobridge.gametest;

import com.google.gson.JsonObject;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.Ids;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.proto.ProtocolException;
import dev.kantobridge.server.SessionRecord;
import dev.kantobridge.server.SessionService;
import dev.kantobridge.world.DataException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;

/**
 * The world's id for save pairing on a real server (docs/PLAN.md §4.4 {@code SessionService}): created when the server
 * starts, kept in {@code <world>/kantobridge/session.json}, the same after a restart (as far as a GameTest can restart:
 * the record is read again from disk), and each player's paired slot recorded with it. A batch of its own: it reloads
 * the server's record.
 */
public class SessionGameTests implements FabricGameTest {
	private static final String BATCH = "kantobridge_session";

	private static SessionRecord readFile(Path file) {
		try {
			return SessionRecord.parse(Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException | DataException | ProtocolException e) {
			throw new GameTestAssertException("the session record is unreadable: " + e.getClass().getSimpleName());
		}
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void theWorldIdIsStableAcrossRestarts(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		String world = SessionService.worldId(server);
		helper.assertTrue(world != null, "the server started without a world id (warnings: " + SessionService.warnings() + ")");
		helper.assertTrue(Ids.ID.matcher(world).matches() && Ids.UID.matcher(world).matches(), "the world id isn't a UUID protocol id");
		Path file = SessionService.file(server);
		helper.assertTrue(Files.isRegularFile(file), "no session record in the world's kantobridge folder");
		helper.assertTrue(readFile(file).world().equals(world), "the file holds another id");
		// What the next start does: read the record from the world folder again.
		for (int i = 0; i < 2; i++) {
			SessionRecord again = SessionService.load(server);
			helper.assertTrue(again != null && again.world().equals(world), "the world id changed on a restart");
		}
		helper.assertTrue(world.equals(SessionService.worldId(server)), "the world id changed on a restart");
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void aPairingIsRecordedPerPlayer(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayers.with(helper, player -> {
			// What a client hands its gen1-bridge: the world, and multiplayer for anyone but an integrated server's owner.
			JsonObject bind = SessionService.bindData(player);
			helper.assertTrue(bind != null && bind.size() == 2, "no session.bind body");
			helper.assertTrue(bind.get("world").getAsString().equals(SessionService.worldId(server)), "session.bind names another world");
			helper.assertTrue(bind.get("multiplayer").getAsBoolean(), "a player of a server it doesn't own plays online");

			helper.assertTrue(SessionService.record(player, 7), "the pairing wasn't recorded");
			helper.assertTrue(Integer.valueOf(7).equals(SessionService.slotOf(server, player.getUUID())), "the slot isn't kept");
			helper.assertTrue(Integer.valueOf(7).equals(readFile(SessionService.file(server)).slot(player.getUUID())), "the slot isn't on disk");
			SessionService.load(server);
			helper.assertTrue(Integer.valueOf(7).equals(SessionService.slotOf(server, player.getUUID())), "the slot was lost on a restart");

			// Through the relay once the protocol gives session.bound its client-to-server leg (M9).
			if (SessionService.hasLeg(MsgTypes.SESSION_BOUND, Catalog.Leg.C2S)) {
				RelayService.get().receive(player, BattleGameTests.envelope(1, MsgTypes.SESSION_BOUND, "{\"slot\":9}"));
				helper.assertTrue(Integer.valueOf(9).equals(SessionService.slotOf(server, player.getUUID())), "a relayed session.bound wasn't recorded");
			}
		});
		helper.succeed();
	}
}
