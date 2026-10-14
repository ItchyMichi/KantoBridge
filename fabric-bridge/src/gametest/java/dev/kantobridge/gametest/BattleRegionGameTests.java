package dev.kantobridge.gametest;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.google.gson.JsonObject;
import dev.kantobridge.battle.EncounterService;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.server.WorldIndexSync;
import dev.kantobridge.world.WorldIndex;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * MG8 (found filming the Hoenn story): in a world of several regions, a hidden battle's {@code battle.result} carries the
 * region its {@code battle.start} came with, so the client hands it to the link that holds the battle, not to the legacy
 * region's (an untagged message goes there); likewise {@code pc.closed} for a {@code pc.open}. Two hand-made regions
 * ({@link LedgerGameTests#publish}); a refusal (no Pokémon yet), an encounter that is stopped, and Bill's PC asked for
 * mid-battle, all from the second region's game.
 */
public class BattleRegionGameTests implements FabricGameTest {
	// its own batch: it publishes and withdraws the server's world, which the other batches' tests read
	private static final String BATCH = "kantobridge_battle_region";
	private static final String TEST2 = "test2";

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 200)
	public void aBattlesResultGoesBackToTheRegionItCameFrom(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		WorldIndex before = WorldIndexSync.current(server);
		LedgerGameTests.publish(server);
		ServerPlayer player = MockPlayers.join(helper);
		RelayTap.Recorder relay = RelayTap.open(player);
		try {
			// no Pokémon yet: refused at once, tagged as it came
			RelayService.get().receive(player, BattleGameTests.envelope(1, MsgTypes.BATTLE_START, tagged("br-1")));
			List<Envelope> results = relay.of(MsgTypes.BATTLE_RESULT);
			helper.assertTrue(results.size() == 1 && "aborted".equals(results.get(0).data().get("outcome").getAsString()), "the refusal");
			helper.assertTrue(TEST2.equals(regionOf(results.get(0))), "the refusal went back untagged: " + regionOf(results.get(0)));
			BattleGameTests.partner(player);
			RelayService.get().receive(player, BattleGameTests.envelope(2, MsgTypes.BATTLE_START, tagged("br-2")));
			helper.assertTrue(EncounterService.battleOf(player) != null, "no encounter battle (warnings: " + EncounterService.warnings() + ")");
			// Bill's PC asked for mid-battle: refused at once, its pc.closed tagged as the pc.open came
			RelayService.get().receive(player, BattleGameTests.envelope(3, MsgTypes.PC_OPEN, "{\"pc\":\"pc-r1\",\"region\":\"" + TEST2 + "\"}"));
			List<Envelope> closed = relay.of(MsgTypes.PC_CLOSED);
			helper.assertTrue(closed.size() == 1 && TEST2.equals(regionOf(closed.get(0))), "pc.closed: " + closed.size() + " "
					+ (closed.isEmpty() ? "-" : regionOf(closed.get(0))));
		} catch (RuntimeException e) {
			EncounterGameTests.cleanUp(helper, player, relay);
			LedgerGameTests.restore(server, before);
			throw e;
		}
		var end = helper.startSequence()
				.thenExecuteAfter(2, () -> {
					PokemonBattle battle = EncounterService.battleOf(player);
					helper.assertTrue(battle != null, "the encounter ended early");
					battle.stop();
					List<Envelope> results = relay.of(MsgTypes.BATTLE_RESULT);
					helper.assertTrue(results.size() == 2, "results: " + results.size());
					JsonObject data = results.get(1).data();
					helper.assertTrue("br-2".equals(data.get("battle").getAsString()), "the result answers another battle");
					helper.assertTrue(TEST2.equals(regionOf(results.get(1))), "the result went back untagged: " + regionOf(results.get(1)));
				})
				.thenExecute(() -> LedgerGameTests.restore(server, before));
		MockPlayers.leaveAfterBattle(helper, end, relay::close, player);
	}

	/** A static encounter's start as the second region's game sends it (the client tags it with the link's region). */
	private static String tagged(String battle) {
		String start = EncounterGameTests.wildStart(battle, "static", 263, 2, false, false);
		return start.substring(0, start.length() - 1) + ",\"region\":\"" + TEST2 + "\"}";
	}

	private static String regionOf(Envelope message) {
		return message.data().has("region") ? message.data().get("region").getAsString() : null;
	}
}
