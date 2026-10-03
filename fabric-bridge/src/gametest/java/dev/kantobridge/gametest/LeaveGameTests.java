package dev.kantobridge.gametest;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.entity.npc.NPCEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.kantobridge.battle.BattleService;
import dev.kantobridge.entity.DeferredRemovals;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * Regression for a server crash: Cobblemon discards a player's own sent-out Pokémon in {@code stopSeenByPlayer}, and
 * Minecraft calls that from inside {@code ChunkMap.removeEntity}'s loop over every tracked entity while a leaving player
 * is untracked, so the discard changed the map under the loop ({@code NullPointerException} in fastutil's iterator,
 * depending on where the entities sit in the map). Here the player leaves exactly as a disconnect does
 * ({@code PlayerList.remove}) with their Pokémon out, in the middle of a battle and in the tick their battle ends; no
 * entity but the player may be unloaded meanwhile (deterministic, whatever the map's layout), and the Pokémon is gone at
 * the end of the tick. Real Showdown battles (it sends the Pokémon out), so the same wall-clock limit as
 * {@code BattleGameTests.aForfeitIsALoss}. A batch of its own.
 */
public class LeaveGameTests implements FabricGameTest {
	private static final String BATCH = "kantobridge_leave";

	/** The player being removed right now, and the other entities unloaded meanwhile (server thread only). */
	private static @Nullable ServerPlayer leaving;
	private static final List<Entity> UNLOADED_WHILE_LEAVING = new ArrayList<>();

	static {
		// Fabric fires this at the start of ServerLevel's onTrackingEnd: where ChunkMap takes an entity out of its map.
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (leaving != null && entity != leaving) {
				UNLOADED_WHILE_LEAVING.add(entity);
			}
		});
	}

	/** Removes the player as a disconnect does; returns every other entity unloaded while that ran. */
	private static List<Entity> leaveWatching(GameTestHelper helper, ServerPlayer player) {
		UNLOADED_WHILE_LEAVING.clear();
		leaving = player;
		try {
			MockPlayers.leave(helper, player);
		} finally {
			leaving = null;
		}
		List<Entity> unloaded = List.copyOf(UNLOADED_WHILE_LEAVING);
		UNLOADED_WHILE_LEAVING.clear();
		return unloaded;
	}

	private static boolean sentOut(Pokemon pokemon) {
		PokemonEntity entity = pokemon.getEntity();
		return entity != null && !entity.isRemoved();
	}

	/** A real trainer battle for the player; the test fails (and cleans up) if Showdown never sends their Pokémon out. */
	private static Pokemon fight(GameTestHelper helper, ServerPlayer player, RelayTap.Recorder relay, String battle) {
		Pokemon partner;
		try {
			partner = BattleGameTests.partner(player);
			BattleGameTests.startBattle(helper, player, battle, "trainer", BattleGameTests.TRAINER);
		} catch (RuntimeException e) {
			relay.close();
			MockPlayers.leaveNow(helper, player);
			throw e;
		}
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BattleGameTests.SHOWDOWN_SECONDS);
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(System.nanoTime() > deadline, "still within the time Showdown has"))
				.thenExecute(() -> {
					relay.close();
					if (!player.isRemoved()) {
						MockPlayers.leaveNow(helper, player);
					}
				})
				.thenFail(() -> new GameTestAssertException("Showdown didn't send the player's Pokémon out within "
						+ BattleGameTests.SHOWDOWN_SECONDS + " s"));
		return partner;
	}

	/** After the leave: nothing else was unloaded during it, and Cobblemon's discard of the Pokémon waits for the tick's end. */
	private static void assertLeftCleanly(GameTestHelper helper, List<Entity> unloaded, PokemonEntity out) {
		helper.assertTrue(unloaded.isEmpty(), unloaded.size() + " entities were removed while the leaving player was untracked");
		helper.assertTrue(DeferredRemovals.isPending(out),
				"the player's Pokémon wasn't seen by them as they left, so this test proves nothing");
		helper.assertFalse(out.isRemoved(), "the Pokémon was removed inside the server's entity tracking");
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 200_000)
	public void leavingMidBattleWithAPokemonOutRemovesNothingWhileThePlayerIsUntracked(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.join(helper);
		RelayTap.Recorder relay = RelayTap.open(player);
		Pokemon partner = fight(helper, player, relay, "lv-1");
		NPCEntity proxy = BattleService.proxyOf(player);
		PokemonEntity[] out = new PokemonEntity[1];
		helper.startSequence()
				// Showdown starts the battle and Cobblemon sends the player's Pokémon out: an entity the player owns and sees.
				.thenWaitUntil(() -> helper.assertTrue(sentOut(partner), "the player's Pokémon isn't out yet"))
				.thenExecute(() -> {
					out[0] = partner.getEntity();
					helper.assertTrue(BattleService.battleOf(player) != null, "the battle ended before the player left");
					List<Entity> unloaded = leaveWatching(helper, player);
					relay.close();
					assertLeftCleanly(helper, unloaded, out[0]);
					// Leaving stopped the battle (Cobblemon and BattleService both do), nothing answered a gone player.
					helper.assertTrue(BattleRegistry.getBattleByParticipatingPlayer(player) == null, "the battle outlived the player");
					helper.assertTrue(BattleService.battleOf(player) == null, "the battle still counts as running");
				})
				.thenWaitUntil(() -> {
					helper.assertTrue(out[0].isRemoved(), "the player's Pokémon wasn't discarded after they left");
					helper.assertTrue(proxy.isRemoved(), "the proxy wasn't discarded after the battle");
				})
				.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 200_000)
	public void leavingInTheTickTheBattleEndsRemovesNothingWhileThePlayerIsUntracked(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.join(helper);
		RelayTap.Recorder relay = RelayTap.open(player);
		Pokemon partner = fight(helper, player, relay, "lv-2");
		NPCEntity proxy = BattleService.proxyOf(player);
		PokemonEntity[] out = new PokemonEntity[1];
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(sentOut(partner), "the player's Pokémon isn't out yet"))
				.thenExecute(() -> {
					out[0] = partner.getEntity();
					PokemonBattle battle = BattleService.battleOf(player);
					helper.assertTrue(battle != null, "the battle ended before its time");
					BattleActor me = battle.getActor(player);
					BattleActor trainer = battle.getActor(proxy);
					// The trainer wins (what Showdown's |win| makes Cobblemon do): the result goes out and Cobblemon starts
					// recalling the player's Pokémon, which stays out for its 1.5 s animation.
					battle.setWinners(List.of(trainer));
					battle.setLosers(List.of(me));
					battle.end();
					List<Envelope> results = relay.of(MsgTypes.BATTLE_RESULT);
					helper.assertTrue(results.size() == 1, "expected one battle.result, got " + results.size());
					BattleGameTests.assertResult(helper, results.get(0), "lv-2", "lose");
					helper.assertTrue(sentOut(partner), "the Pokémon must still be out, recalling, when the player leaves");
					// The same tick: the player disconnects.
					List<Entity> unloaded = leaveWatching(helper, player);
					relay.close();
					assertLeftCleanly(helper, unloaded, out[0]);
				})
				.thenWaitUntil(() -> {
					helper.assertTrue(out[0].isRemoved(), "the player's Pokémon wasn't discarded after they left");
					helper.assertTrue(proxy.isRemoved(), "the proxy wasn't discarded after the battle");
				})
				.thenSucceed();
	}
}
