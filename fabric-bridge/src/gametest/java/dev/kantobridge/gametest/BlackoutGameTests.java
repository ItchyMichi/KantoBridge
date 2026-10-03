package dev.kantobridge.gametest;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.entity.npc.NPCEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.battle.BattleService;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import dev.kantobridge.server.WorldIndexSync;
import dev.kantobridge.world.Dir;
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
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A lost trainer battle and the blackout that follows, on a real server (docs/PLAN.md §2.4 flow 8, PROTOCOL.md "Battle
 * rules": a trainer {@code lose} makes gen1-bridge warp to the last Pokémon Center with kind {@code blackout}, then send
 * {@code party.heal}). The server only answers {@code lose}; this checks that nothing of Cobblemon's fights what
 * follows: the battle is over (nobody is left in or watching it), the fainted party stays fainted until the heal (no
 * revival, no passive healing), and the blackout's {@code warp.request} and {@code party.heal} both work on a party
 * that lost. A batch of its own: it publishes a copy of the synthetic WorldIndex for the warp, and withdraws it after.
 */
public class BlackoutGameTests implements FabricGameTest {
	private static final String BATCH = "kantobridge_blackout";
	private static final String GOLDEN = "kantobridge/golden/synthetic_world_index.json";
	/** Two of Cobblemon's party seconds (every 20 ticks): its revival and passive healing would have run by then. */
	private static final int SECONDS_OF_PARTY_TICKS = 45;

	/**
	 * The synthetic world's index without its habitat block, made the server's: the warp needs the maps, while a listed
	 * habitat would have the runtime habitat check count the (never built) block as missing whenever its chunk loads, which
	 * the synthetic build's test then finds.
	 */
	private static WorldIndex publishIndexWithoutHabitats(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		try (InputStream in = BlackoutGameTests.class.getClassLoader().getResourceAsStream(GOLDEN)) {
			helper.assertTrue(in != null, "golden WorldIndex missing");
			JsonObject root = ProtoJson.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			root.add("habitats", new JsonArray());
			WorldIndexSync.publish(server, ProtoJson.write(root), false);
		} catch (IOException | ProtocolException e) {
			throw new GameTestAssertException("golden WorldIndex unreadable");
		}
		WorldIndex index = WorldIndexSync.current(server);
		helper.assertTrue(index != null && index.map("TEST_TOWN") != null && index.habitats().isEmpty(), "the published index isn't usable");
		return index;
	}

	/** A failing test's clean-up, at once: the player and the tap go, and so does the index (nothing may linger). */
	private static void cleanUp(GameTestHelper helper, ServerPlayer player, RelayTap.Recorder relay) {
		relay.close();
		if (!player.isRemoved()) {
			MockPlayers.leaveNow(helper, player);
		}
		WorldIndexSync.withdraw(helper.getLevel().getServer());
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 400)
	public void aLostBattleThenTheBlackoutLeavesThePlayerHealedAtTheCenter(GameTestHelper helper) {
		WorldIndex index = publishIndexWithoutHabitats(helper);
		ServerPlayer player = MockPlayers.join(helper);
		RelayTap.Recorder relay = RelayTap.open(player);
		Pokemon partner;
		NPCEntity proxy;
		try {
			partner = BattleGameTests.partner(player);
			proxy = BattleGameTests.startBattle(helper, player, "bo-1", "trainer", BattleGameTests.TRAINER);
			PokemonBattle battle = BattleService.battleOf(player);
			BattleActor me = battle.getActor(player);
			BattleActor trainer = battle.getActor(proxy);
			helper.assertTrue(me != null && trainer != null, "the battle's actors are missing");
			// The player's last Pokémon faints and the trainer wins (what Showdown's |win| makes Cobblemon do).
			partner.setCurrentHealth(0);
			battle.setWinners(List.of(trainer));
			battle.setLosers(List.of(me));
			battle.end();
			List<Envelope> results = relay.of(MsgTypes.BATTLE_RESULT);
			helper.assertTrue(results.size() == 1, "expected one battle.result, got " + results.size());
			BattleGameTests.assertResult(helper, results.get(0), "bo-1", "lose");
			// Over at once: nothing for the player to be stuck in or watching.
			helper.assertTrue(BattleRegistry.getBattleByParticipatingPlayer(player) == null, "Cobblemon still has the player in a battle");
			helper.assertTrue(BattleService.battleOf(player) == null, "the battle still counts as running");
			helper.assertTrue(battle.getSpectators().isEmpty(), "someone is left watching the battle");
			helper.assertTrue(partner.isFainted(), "the lost battle's Pokémon isn't fainted");
		} catch (RuntimeException e) {
			cleanUp(helper, player, relay);
			throw e;
		}
		Pokemon fainted = partner;
		NPCEntity discarded = proxy;
		Vec3 centre = index.toBlock("TEST_TOWN", 5, 6);
		GameTestSequence end = helper.startSequence()
				.thenIdle(SECONDS_OF_PARTY_TICKS)
				.thenExecute(() -> {
					helper.assertTrue(fainted.isFainted(), "Cobblemon revived or healed the fainted party by itself");
					// gen1-bridge's blackout: the warp to the last Pokémon Center...
					RelayService.get().receive(player, BattleGameTests.envelope(10, MsgTypes.WARP_REQUEST,
							"{\"to\":{\"map\":\"TEST_TOWN\",\"x\":5,\"y\":6,\"facing\":\"down\"},\"kind\":\"blackout\"}"));
					helper.assertTrue(player.position().distanceTo(centre) < 1.0E-6, "the blackout didn't take the player to the Center");
					helper.assertTrue(Math.abs(Mth.wrapDegrees(player.getYRot() - Dir.DOWN.yaw())) < 1.0E-3, "facing the wrong way");
					List<Envelope> warps = relay.of(MsgTypes.WARP_DONE);
					helper.assertTrue(warps.size() == 1 && warps.get(0).data().get("ok").getAsBoolean() && Objects.equals(warps.get(0).re(), 10L),
							"the blackout warp wasn't answered ok");
					// ...then the heal.
					RelayService.get().receive(player, BattleGameTests.envelope(11, MsgTypes.PARTY_HEAL, "{}"));
					helper.assertFalse(fainted.isFainted(), "party.heal didn't revive the fainted Pokémon");
					helper.assertTrue(fainted.getCurrentHealth() == fainted.getMaxHealth(), "party.heal didn't heal fully");
					helper.assertTrue(fainted.getStatus() == null, "party.heal left a status");
					helper.assertTrue(BattleRegistry.getBattleByParticipatingPlayer(player) == null, "the player is in a battle again");
					helper.assertTrue(relay.of(MsgTypes.ERROR).isEmpty(), "the blackout's messages were refused");
					helper.assertTrue(relay.of(MsgTypes.BATTLE_RESULT).size() == 1, "the battle was answered again");
				})
				.thenWaitUntil(() -> helper.assertTrue(discarded.isRemoved(), "the proxy wasn't discarded after the battle"));
		// The index goes too (batches run one after another; nothing may linger).
		MockPlayers.leaveAfterBattle(helper, end, () -> {
			relay.close();
			WorldIndexSync.withdraw(helper.getLevel().getServer());
		}, player);
	}
}
