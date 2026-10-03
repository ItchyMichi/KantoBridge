package dev.kantobridge.gametest;

import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.server.SaveService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Saving on a real server (docs/PLAN.md §2.4 flow 12, §4.4 {@code SaveService}): {@code save.begin} writes the player's
 * Cobblemon party and PC (which Cobblemon itself would write only on its timer) and the player's data, and is answered
 * {@code save.done{ok:true}} once per id; repeats are answered without saving again; malformed requests are refused.
 * A batch of its own: a save writes the whole test world.
 */
public class SaveGameTests implements FabricGameTest {
	private static final String BATCH = "kantobridge_saves";

	static String saveBegin(long seq, String data) {
		return BattleGameTests.envelope(seq, MsgTypes.SAVE_BEGIN, data);
	}

	/** Where Cobblemon's file storage keeps one of the player's stores (NBT by default, JSON when configured). */
	private static List<Path> storeFiles(MinecraftServer server, String storeFolder, UUID player) {
		Path folder = server.getWorldPath(LevelResource.ROOT).resolve("pokemon").resolve(storeFolder).resolve(player.toString().substring(0, 2));
		return List.of(folder.resolve(player + ".dat"), folder.resolve(player + ".json"));
	}

	private static boolean written(List<Path> candidates) {
		for (Path file : candidates) {
			try {
				if (Files.isRegularFile(file) && Files.size(file) > 0) {
					return true;
				}
			} catch (IOException e) {
				// Being replaced by Cobblemon's writer right now: not yet.
			}
		}
		return false;
	}

	private static void delete(List<Path> files) {
		for (Path file : files) {
			try {
				Files.deleteIfExists(file);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
	}

	private static void assertDone(GameTestHelper helper, Envelope message, String save, boolean ok, long re) {
		helper.assertTrue(message.type().equals(MsgTypes.SAVE_DONE), "expected save.done, got " + message.type());
		helper.assertTrue(message.data().get("save").getAsString().equals(save), "save.done answers another save");
		helper.assertTrue(message.data().get("ok").getAsBoolean() == ok, "save " + save + " should be ok=" + ok);
		helper.assertTrue(message.data().size() == 2, "save.done carries save and ok only");
		helper.assertTrue(Objects.equals(message.re(), re), "save.done must answer seq " + re);
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 200)
	public void aSaveWritesThePlayersPokemonAndDataAndIsAnsweredOncePerId(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerPlayer player = MockPlayers.join(helper);
		RelayTap.Recorder relay = RelayTap.open(player);
		UUID id = player.getUUID();
		List<Path> party = storeFiles(server, "playerpartystore", id);
		List<Path> pc = storeFiles(server, "pcstore", id);
		Path data = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat");
		try {
			BattleGameTests.partner(player);
			// Nothing of this player's on disk yet, so whatever appears was written by the save.
			delete(party);
			delete(pc);
			delete(List.of(data));
			long saves = SaveService.saves();
			RelayService.get().receive(player, saveBegin(1, "{\"save\":\"sv-1\",\"reason\":\"center\"}"));
			List<Envelope> done = relay.of(MsgTypes.SAVE_DONE);
			helper.assertTrue(done.size() == 1, "expected one save.done, got " + done.size() + " (warnings: " + SaveService.warnings() + ")");
			assertDone(helper, done.get(0), "sv-1", true, 1);
			helper.assertTrue(SaveService.saves() == saves + 1, "the save wasn't counted");
			helper.assertTrue(Files.isRegularFile(data), "the player's data wasn't saved");

			// The same id again (gen1-bridge after a reconnect): the same answer, no second save.
			RelayService.get().receive(player, saveBegin(2, "{\"save\":\"sv-1\",\"reason\":\"center\"}"));
			done = relay.of(MsgTypes.SAVE_DONE);
			helper.assertTrue(done.size() == 2, "a repeated save wasn't answered");
			assertDone(helper, done.get(1), "sv-1", true, 2);
			helper.assertTrue(SaveService.saves() == saves + 1, "a repeated id saved again");

			// Another save is saved.
			RelayService.get().receive(player, saveBegin(3, "{\"save\":\"sv-2\",\"reason\":\"menu\"}"));
			done = relay.of(MsgTypes.SAVE_DONE);
			helper.assertTrue(done.size() == 3, "the second save wasn't answered");
			assertDone(helper, done.get(2), "sv-2", true, 3);
			helper.assertTrue(SaveService.saves() == saves + 2, "the second save wasn't saved");
		} catch (RuntimeException e) {
			relay.close();
			MockPlayers.leave(helper, player);
			throw e;
		}
		// Cobblemon's own writer thread puts the stores on disk.
		helper.succeedWhen(() -> {
			helper.assertTrue(written(party), "the party wasn't written");
			helper.assertTrue(written(pc), "the PC wasn't written (Cobblemon itself never writes an unchanged PC)");
			helper.assertTrue(relay.of(MsgTypes.SAVE_DONE).size() == 3, "answered more than once");
			relay.close();
			MockPlayers.leave(helper, player);
		});
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void malformedSavesAreRefused(GameTestHelper helper) {
		MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
			long saves = SaveService.saves();
			// No reason: an error, and ok:false for its id. A bad reason: the same. No usable id: an error only.
			RelayService.get().receive(player, saveBegin(1, "{\"save\":\"sv-x\"}"));
			RelayService.get().receive(player, saveBegin(2, "{\"save\":\"sv-y\",\"reason\":\"quit\"}"));
			RelayService.get().receive(player, saveBegin(3, "{\"save\":5,\"reason\":\"menu\"}"));
			helper.assertTrue(sent.size() == 5, "expected 3 errors and 2 answers, got " + sent.size());
			helper.assertTrue(sent.get(0).type().equals(MsgTypes.ERROR) && Objects.equals(sent.get(0).re(), 1L), "no error for seq 1");
			assertDone(helper, sent.get(1), "sv-x", false, 1);
			helper.assertTrue(sent.get(2).type().equals(MsgTypes.ERROR) && Objects.equals(sent.get(2).re(), 2L), "no error for seq 2");
			assertDone(helper, sent.get(3), "sv-y", false, 2);
			helper.assertTrue(sent.get(4).type().equals(MsgTypes.ERROR) && Objects.equals(sent.get(4).re(), 3L), "no error for seq 3");
			helper.assertTrue(SaveService.saves() == saves, "a malformed request saved");
		}));
		helper.succeed();
	}
}
