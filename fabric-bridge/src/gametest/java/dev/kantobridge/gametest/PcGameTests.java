package dev.kantobridge.gametest;

import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.api.storage.pc.link.PCLink;
import com.cobblemon.mod.common.api.storage.pc.link.PCLinkManager;
import com.cobblemon.mod.common.net.messages.server.storage.pc.UnlinkPlayerFromPCPacket;
import com.cobblemon.mod.common.net.serverhandling.storage.pc.UnlinkPlayerFromPCHandler;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.pokemon.PcService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/**
 * Bill's PC as Cobblemon's PC on a real server (docs/PLAN.md §4.4 {@code PcService}): {@code pc.open} links the player's
 * Cobblemon PC and opens its screen, the screen's unlink (exactly what Cobblemon's server does when the client closes
 * it) is answered with one {@code pc.closed}, and a PC that can't open is closed at once. A batch of its own: sessions
 * span ticks.
 */
public class PcGameTests implements FabricGameTest {
	private static final String BATCH = "kantobridge_pc";

	static String pcOpen(long seq, String data) {
		return BattleGameTests.envelope(seq, MsgTypes.PC_OPEN, data);
	}

	/** What Cobblemon's server does with the client's unlink packet when its PC screen closes. */
	static void closeOnClient(GameTestHelper helper, ServerPlayer player) {
		UnlinkPlayerFromPCHandler.INSTANCE.handle(new UnlinkPlayerFromPCPacket(), helper.getLevel().getServer(), player);
	}

	/** A failing test's clean-up, at once: the battle stops, the player's Pokémon are taken back, the player leaves. */
	private static void cleanUp(GameTestHelper helper, ServerPlayer player, RelayTap.Recorder relay) {
		relay.close();
		if (!player.isRemoved()) {
			MockPlayers.leaveNow(helper, player);
		}
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 100)
	public void billsPcOpensCobblemonsPcAndIsAnsweredOnceWhenItCloses(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.join(helper);
		RelayTap.Recorder relay = RelayTap.open(player);
		UUID id = player.getUUID();
		try {
			long opened = PcService.opened();
			RelayService.get().receive(player, pcOpen(1, "{}"));
			helper.assertTrue(PcService.isOpen(player), "no PC session (warnings: " + PcService.warnings() + ")");
			helper.assertTrue(PcService.opened() == opened + 1, "the open wasn't counted");
			// Cobblemon's link: ours, to the player's own PC, permitted here (no PC block anywhere near).
			PCStore pc = PlayerExtensionsKt.pc(player);
			PCLink link = PCLinkManager.INSTANCE.getLink(id);
			helper.assertTrue(link != null && link == PcService.linkOf(player), "the player's PC link isn't the session's");
			helper.assertTrue(link.getPc() == pc && link.getPlayerID().equals(id), "the link points at another PC");
			helper.assertTrue(PCLinkManager.INSTANCE.getPC(player) == pc, "Cobblemon wouldn't let the player edit the PC");
			helper.assertTrue(relay.of(MsgTypes.PC_CLOSED).isEmpty(), "answered before the PC closed");

			// gen1-bridge asking again (after a reconnect) changes nothing: the open session answers it.
			long repeats = PcService.repeats();
			RelayService.get().receive(player, pcOpen(2, "{}"));
			helper.assertTrue(PcService.repeats() == repeats + 1, "a repeated pc.open wasn't recognised");
			helper.assertTrue(PCLinkManager.INSTANCE.getLink(id) == link, "a repeat replaced the link");
			helper.assertTrue(relay.sent.isEmpty(), "a repeat was answered");

			// The player closes Cobblemon's PC screen.
			closeOnClient(helper, player);
		} catch (RuntimeException e) {
			cleanUp(helper, player, relay);
			throw e;
		}
		helper.startSequence()
				.thenIdle(2)
				.thenExecute(() -> {
					List<Envelope> closed = relay.of(MsgTypes.PC_CLOSED);
					helper.assertTrue(closed.size() == 1, "expected one pc.closed, got " + closed.size());
					helper.assertTrue(closed.get(0).data().size() == 0 && closed.get(0).re() == null, "pc.closed is {} with no re");
					helper.assertFalse(PcService.isOpen(player), "the session outlived its screen");
				})
				.thenIdle(5)
				.thenExecute(() -> {
					helper.assertTrue(relay.of(MsgTypes.PC_CLOSED).size() == 1, "answered more than once");
					// A pc.open after the answer is a new PC.
					RelayService.get().receive(player, pcOpen(3, "{}"));
					helper.assertTrue(PcService.isOpen(player), "a second PC didn't open");
					helper.assertTrue(PcService.close(player), "the server couldn't close it");
				})
				.thenIdle(2)
				.thenExecute(() -> {
					helper.assertTrue(relay.of(MsgTypes.PC_CLOSED).size() == 2, "a PC the server closed wasn't answered");
					helper.assertTrue(PCLinkManager.INSTANCE.getLink(id) == null, "the server's close left the link");
					relay.close();
					MockPlayers.leave(helper, player);
				})
				.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 100)
	public void aPcThatCannotOpenIsClosedAtOnce(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.join(helper);
		RelayTap.Recorder relay = RelayTap.open(player);
		try {
			// Malformed: an error, and pc.closed so the hidden game doesn't wait.
			long malformed = PcService.warnings("bad_message");
			RelayService.get().receive(player, pcOpen(1, "{\"box\":2}"));
			helper.assertTrue(relay.sent.size() == 2, "expected an error and pc.closed, got " + relay.sent.size());
			helper.assertTrue(relay.sent.get(0).type().equals(MsgTypes.ERROR) && Objects.equals(relay.sent.get(0).re(), 1L), "no error");
			helper.assertTrue(relay.sent.get(1).type().equals(MsgTypes.PC_CLOSED), "no pc.closed for a malformed pc.open");
			helper.assertTrue(PcService.warnings("bad_message") == malformed + 1, "bad_message wasn't counted");

			// In a battle Cobblemon's PC stays shut, as with its /pc command.
			BattleGameTests.partner(player);
			BattleGameTests.startBattle(helper, player, "pc-bt-1", "trainer", BattleGameTests.TRAINER);
			long battles = PcService.warnings("battle");
			RelayService.get().receive(player, pcOpen(2, "{}"));
			helper.assertFalse(PcService.isOpen(player), "the PC opened during a battle");
			helper.assertTrue(PcService.warnings("battle") == battles + 1, "the refusal wasn't counted");
			helper.assertTrue(relay.of(MsgTypes.PC_CLOSED).size() == 2, "the refusal wasn't answered");
			helper.assertTrue(PCLinkManager.INSTANCE.getLink(player.getUUID()) == null, "a refused PC was linked");
		} catch (RuntimeException e) {
			cleanUp(helper, player, relay);
			throw e;
		}
		MockPlayers.leaveAfterBattle(helper, helper.startSequence(), relay::close, player);
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 100)
	public void leavingEndsThePcWithoutAnAnswer(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.join(helper);
		UUID id = player.getUUID();
		RelayTap.Recorder relay = RelayTap.open(player);
		try {
			RelayService.get().receive(player, pcOpen(1, "{}"));
			helper.assertTrue(PcService.isOpen(player), "no PC session");
		} finally {
			MockPlayers.leave(helper, player);
		}
		helper.assertFalse(PcService.isOpen(player), "the session outlived the player");
		helper.assertTrue(PCLinkManager.INSTANCE.getLink(id) == null, "the link outlived the player");
		helper.startSequence()
				.thenIdle(2)
				.thenExecute(() -> {
					relay.close();
					helper.assertTrue(relay.of(MsgTypes.PC_CLOSED).isEmpty(), "a gone player was answered");
				})
				.thenSucceed();
	}
}
