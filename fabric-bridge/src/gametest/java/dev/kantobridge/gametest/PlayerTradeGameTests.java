package dev.kantobridge.gametest;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.pokemon.evolution.Evolution;
import com.cobblemon.mod.common.battles.BattleBuilder;
import com.cobblemon.mod.common.battles.BattleStartResult;
import com.cobblemon.mod.common.battles.SuccessfulBattleStart;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.evolution.variants.TradeEvolution;
import com.cobblemon.mod.common.trade.ActiveTrade;
import com.cobblemon.mod.common.trade.PlayerTradeParticipant;
import com.cobblemon.mod.common.trade.TradeManager;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.pokemon.Gen1Mon;
import dev.kantobridge.pokemon.Gen1Mons;
import dev.kantobridge.pokemon.PartyService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerPlayer;

/**
 * Player trades and player battles on Cobblemon's own systems (DESIGN §8: "Trades and battles use Cobblemon's systems,
 * and both players' bridges then update their shadow parties and Pokédexes, including trade evolutions"; docs/PLAN.md
 * §6 M9), with two mock players: both players' {@code party.sync} and {@code dex.sync} follow (debounced), a traded
 * Pokémon forgets the hidden game's gift id, and a trade evolution is Cobblemon's own accept prompt. Made-up Pokémon only.
 * Batches of their own: they span ticks, and the battle runs real Showdown.
 */
public class PlayerTradeGameTests implements FabricGameTest {
	private static final String BATCH_TRADE = "kantobridge_mp_trade";
	private static final String BATCH_BATTLE = "kantobridge_mp_battle";

	private static Pokemon gift(int dex, int level) {
		try {
			return Gen1Mons.gift(new Gen1Mon(dex, level, List.of(33), null));
		} catch (Gen1Mons.Missing e) {
			throw new GameTestAssertException("Cobblemon lacks " + e.code());
		}
	}

	/** The last {@code party.sync}'s entry for {@code uid}, or null when the last sync doesn't list it (or there is none). */
	private static JsonObject lastEntry(RelayTap.Recorder relay, UUID uid) {
		List<Envelope> syncs = relay.of(MsgTypes.PARTY_SYNC);
		if (syncs.isEmpty()) {
			return null;
		}
		for (JsonElement element : syncs.get(syncs.size() - 1).data().getAsJsonArray("party")) {
			if (element.getAsJsonObject().get("uid").getAsString().equals(uid.toString())) {
				return element.getAsJsonObject();
			}
		}
		return null;
	}

	private static Set<Integer> lastDex(RelayTap.Recorder relay, String field) {
		List<Envelope> syncs = relay.of(MsgTypes.DEX_SYNC);
		return syncs.isEmpty() ? Set.of() : DexGameTests.numbers(syncs.get(syncs.size() - 1).data().getAsJsonArray(field));
	}

	/**
	 * A gift the giver's hidden game made (it carries that game's {@code give} id) is traded for the receiver's Pokémon:
	 * each party's sync follows, the receiver's lists it without the id (their gen1-bridge must never match it against a
	 * gift of its own), both Pokédexes own what arrived, and the trade evolution waits on Cobblemon's accept prompt; once
	 * accepted, the evolved species reaches the receiver's party and Pokédex.
	 */
	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH_TRADE, timeoutTicks = 300)
	public void aTradeSyncsBothPlayersAndTheTradedGiftForgetsItsId(GameTestHelper helper) {
		ServerPlayer giver = MockPlayers.join(helper);
		ServerPlayer receiver = MockPlayers.join(helper);
		RelayTap.Recorder giverSees = RelayTap.open(giver);
		RelayTap.Recorder receiverSees = RelayTap.open(receiver);
		Runnable cleanUp = () -> {
			giverSees.close();
			receiverSees.close();
			MockPlayers.leave(helper, giver);
			MockPlayers.leave(helper, receiver);
		};
		Pokemon[] kadabra = new Pokemon[1];
		Pokemon[] pidgey = new Pokemon[1];
		int[] syncsBefore = new int[2];
		long trades = PartyService.trades();
		try {
			// The giver's hidden game gave a Kadabra (Kadabra evolves when traded); the receiver caught a Pidgey.
			RelayService.get().receive(giver, PartyGameTests.envelope(1, MsgTypes.PARTY_GIVE,
					"{\"give\":\"pg-trade\",\"dex\":64,\"level\":30,\"moves\":[93],\"source\":\"gift\"}"));
			kadabra[0] = PlayerExtensionsKt.party(giver).get(0);
			helper.assertTrue(kadabra[0] != null && kadabra[0].getSpecies().getNationalPokedexNumber() == 64, "no Kadabra for the giver");
			helper.assertTrue("pg-trade".equals(kadabra[0].getPersistentData().getString(PartyService.GIVE_KEY)), "the gift has no give id");
			pidgey[0] = gift(16, 9);
			PlayerExtensionsKt.party(receiver).add(pidgey[0]);
		} catch (RuntimeException e) {
			cleanUp.run();
			throw e;
		}
		GameTestSequence sequence = helper.startSequence()
				// Both players' first syncs go out.
				.thenWaitUntil(() -> helper.assertTrue(lastEntry(giverSees, kadabra[0].getUuid()) != null
						&& lastEntry(receiverSees, pidgey[0].getUuid()) != null, "the parties weren't synced yet"))
				.thenExecute(() -> {
					helper.assertTrue("pg-trade".equals(lastEntry(giverSees, kadabra[0].getUuid()).get("give").getAsString()),
							"the giver's sync names the gift");
					syncsBefore[0] = giverSees.of(MsgTypes.PARTY_SYNC).size();
					syncsBefore[1] = receiverSees.of(MsgTypes.PARTY_SYNC).size();
					// Cobblemon's own trade: what both players accepting their offers runs.
					ActiveTrade trade = new ActiveTrade(new PlayerTradeParticipant(giver), new PlayerTradeParticipant(receiver));
					trade.getPlayer1Offer().updateOffer(kadabra[0]);
					trade.getPlayer2Offer().updateOffer(pidgey[0]);
					TradeManager.INSTANCE.performTrade(trade, trade.getPlayer1(), kadabra[0], trade.getPlayer2(), pidgey[0]);
					helper.assertTrue(PlayerExtensionsKt.party(receiver).get(kadabra[0].getUuid()) == kadabra[0]
							&& PlayerExtensionsKt.party(giver).get(pidgey[0].getUuid()) == pidgey[0], "Cobblemon didn't swap the Pokémon");
					helper.assertTrue(PartyService.trades() == trades + 1, "the trade wasn't seen");
					helper.assertFalse(kadabra[0].getPersistentData().contains(PartyService.GIVE_KEY), "the traded gift kept the giver's give id");
				})
				// Both parties sync again: the giver without the Kadabra, the receiver with it and without the id.
				.thenWaitUntil(() -> {
					helper.assertTrue(giverSees.of(MsgTypes.PARTY_SYNC).size() > syncsBefore[0]
							&& receiverSees.of(MsgTypes.PARTY_SYNC).size() > syncsBefore[1], "no sync after the trade yet");
					helper.assertTrue(lastEntry(giverSees, kadabra[0].getUuid()) == null && lastEntry(giverSees, pidgey[0].getUuid()) != null,
							"the giver's sync doesn't show the trade");
					JsonObject arrived = lastEntry(receiverSees, kadabra[0].getUuid());
					helper.assertTrue(arrived != null && lastEntry(receiverSees, pidgey[0].getUuid()) == null, "the receiver's sync doesn't show the trade");
					helper.assertFalse(arrived.has("give"), "the receiver's sync carries the giver's give id");
					helper.assertTrue(lastDex(receiverSees, "owned").contains(64) && lastDex(giverSees, "owned").contains(16),
							"the Pokédexes don't own what arrived");
				})
				.thenExecute(() -> {
					// Every sync of the receiver since the trade lacked the id, not just the last one.
					for (Envelope sync : receiverSees.of(MsgTypes.PARTY_SYNC)) {
						for (JsonElement entry : sync.data().getAsJsonArray("party")) {
							helper.assertFalse(entry.getAsJsonObject().has("give"), "a sync of the receiver named a give id");
						}
					}
					// The trade evolution is Cobblemon's own prompt for the receiver; they accept it.
					Evolution offered = null;
					for (Evolution evolution : kadabra[0].getEvolutionProxy().server()) {
						if (evolution instanceof TradeEvolution) {
							offered = evolution;
						}
					}
					helper.assertTrue(offered != null, "the traded Kadabra has no evolution waiting for the receiver");
					helper.assertTrue(kadabra[0].getSpecies().getNationalPokedexNumber() == 64, "it evolved without the player's say");
					kadabra[0].getEvolutionProxy().server().start(offered);
				})
				.thenWaitUntil(() -> {
					JsonObject evolved = lastEntry(receiverSees, kadabra[0].getUuid());
					helper.assertTrue(evolved != null && evolved.get("dex").getAsInt() == 65, "the evolved Alakazam isn't in the receiver's sync");
					helper.assertTrue(lastDex(receiverSees, "owned").contains(65), "the receiver's Pokédex doesn't own Alakazam");
				})
				.thenExecute(cleanUp);
		sequence.thenSucceed();
	}

	/**
	 * A battle between two players (Cobblemon's own, real Showdown): no {@code party.sync} while it runs, one for each
	 * player once it is over, and each player's Pokédex sees the other's Pokémon ({@code dex.sync}). Showdown runs in real
	 * time, so a second sequence fails the test after {@value BattleGameTests#SHOWDOWN_SECONDS} s of wall-clock time.
	 */
	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH_BATTLE, timeoutTicks = 200_000)
	public void aPlayerBattleSyncsBothPlayersAfterwards(GameTestHelper helper) {
		ServerPlayer first = MockPlayers.join(helper);
		ServerPlayer second = MockPlayers.join(helper);
		RelayTap.Recorder firstSees = RelayTap.open(first);
		RelayTap.Recorder secondSees = RelayTap.open(second);
		Pokemon bulbasaur = gift(1, 10);
		Pokemon charmander = gift(4, 10);
		PokemonBattle[] battle = new PokemonBattle[1];
		int[] syncsAtEnd = new int[2];
		List<String> problems = new ArrayList<>();
		Runnable cleanUp = () -> {
			firstSees.close();
			secondSees.close();
			MockPlayers.leaveNow(helper, first);
			MockPlayers.leaveNow(helper, second);
		};
		try {
			PlayerExtensionsKt.party(first).add(bulbasaur);
			PlayerExtensionsKt.party(second).add(charmander);
		} catch (RuntimeException e) {
			cleanUp.run();
			throw e;
		}
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BattleGameTests.SHOWDOWN_SECONDS);
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(System.nanoTime() > deadline, "still within the time Showdown has"))
				.thenExecute(cleanUp)
				.thenFail(() -> new GameTestAssertException("the player battle didn't run its course within " + BattleGameTests.SHOWDOWN_SECONDS
						+ " s: " + problems));
		GameTestSequence end = helper.startSequence()
				// Their first syncs, before the battle.
				.thenWaitUntil(() -> helper.assertTrue(!firstSees.of(MsgTypes.PARTY_SYNC).isEmpty() && !secondSees.of(MsgTypes.PARTY_SYNC).isEmpty(),
						"no first sync yet"))
				.thenExecute(() -> {
					BattleStartResult result = BattleBuilder.INSTANCE.pvp1v1(first, second);
					helper.assertTrue(result instanceof SuccessfulBattleStart, "Cobblemon didn't start the player battle");
					battle[0] = ((SuccessfulBattleStart) result).getBattle();
					helper.assertTrue(battle[0].isPvP(), "not a player battle");
					syncsAtEnd[0] = firstSees.of(MsgTypes.PARTY_SYNC).size();
					syncsAtEnd[1] = secondSees.of(MsgTypes.PARTY_SYNC).size();
				})
				// Showdown sends both Pokémon out: each player's Pokédex sees the other's.
				.thenWaitUntil(() -> {
					problems.clear();
					if (!lastDex(firstSees, "seen").contains(4)) {
						problems.add("the first player's Pokédex hasn't seen Charmander");
					}
					if (!lastDex(secondSees, "seen").contains(1)) {
						problems.add("the second player's Pokédex hasn't seen Bulbasaur");
					}
					helper.assertTrue(problems.isEmpty(), String.join("; ", problems));
				})
				.thenExecute(() -> {
					helper.assertTrue(firstSees.of(MsgTypes.PARTY_SYNC).size() == syncsAtEnd[0]
							&& secondSees.of(MsgTypes.PARTY_SYNC).size() == syncsAtEnd[1], "a party.sync went out during the battle");
					// Cobblemon ends it (what a forfeit or a win leads to, without waiting for Showdown's turns).
					battle[0].stop();
				})
				// Once it is over, both players' parties sync.
				.thenWaitUntil(() -> helper.assertTrue(firstSees.of(MsgTypes.PARTY_SYNC).size() > syncsAtEnd[0]
						&& secondSees.of(MsgTypes.PARTY_SYNC).size() > syncsAtEnd[1], "no sync after the battle yet"))
				.thenExecute(() -> {
					helper.assertTrue(lastEntry(firstSees, bulbasaur.getUuid()) != null && lastEntry(secondSees, charmander.getUuid()) != null,
							"each sync lists the player's own Pokémon");
				});
		MockPlayers.leaveAfterBattle(helper, end, () -> {
			firstSees.close();
			secondSees.close();
		}, first, second);
	}
}
