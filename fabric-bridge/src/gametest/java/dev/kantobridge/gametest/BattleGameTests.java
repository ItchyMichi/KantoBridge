package dev.kantobridge.gametest;

import com.cobblemon.mod.common.api.abilities.PotentialAbility;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.pokemon.Natures;
import com.cobblemon.mod.common.api.storage.party.NPCPartyStore;
import com.cobblemon.mod.common.battles.BattleFormat;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.entity.npc.NPCEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.battle.BattleProxies;
import dev.kantobridge.battle.BattleService;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.pokemon.Dvs;
import dev.kantobridge.pokemon.Gen1Mon;
import dev.kantobridge.pokemon.Gen1Mons;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Trainer battles on a real server with Cobblemon and Showdown (docs/PLAN.md §2.4 flow 8, §4.4 {@code BattleService},
 * P11): {@code battle.start} through the relay spawns a hidden proxy whose party has the exact Gen 1 moves and IVs from
 * DVs, Cobblemon's battle starts with a mock player, and the end (stop, forfeit, a win) is answered once with the right
 * outcome. Made-up teams only. A batch of its own: battles run over many ticks.
 */
public class BattleGameTests implements FabricGameTest {
	private static final String BATCH = "kantobridge_battles";
	/** Wall-clock time a Showdown round trip may take. */
	static final int SHOWDOWN_SECONDS = 60;
	static final String TEAM = "[{\"dex\":16,\"level\":9,\"moves\":[33,28],\"dvs\":{\"atk\":9,\"def\":8,\"spd\":8,\"spc\":8}},"
			+ "{\"dex\":19,\"level\":8,\"moves\":[33,39,98],\"dvs\":{\"atk\":1,\"def\":2,\"spd\":3,\"spc\":4}}]";
	/** No usable trainer cell (an unknown map), so the proxy stands in front of the player. */
	static final String TRAINER = "{\"cls\":\"OPP_BUG_CATCHER\",\"party\":2,\"name\":\"TESTER\",\"npc\":\"TEST_TOWN_obj_3\",\"map\":\"NOWHERE\","
			+ "\"x\":1,\"y\":1}";

	static String start(String battle, String kind, String trainer, String team) {
		return "{\"battle\":\"" + battle + "\",\"kind\":\"" + kind + "\",\"trainer\":" + trainer + ",\"team\":" + team
				+ ",\"rules\":{\"catch\":false,\"flee\":false}}";
	}

	static String envelope(long seq, String type, String data) {
		return "{\"type\":\"" + type + "\",\"seq\":" + seq + ",\"data\":" + data + "}";
	}

	/** A Pokémon for the player, so that a battle can start. */
	static Pokemon partner(ServerPlayer player) {
		try {
			Pokemon pokemon = Gen1Mons.gift(new Gen1Mon(7, 12, List.of(33, 55), null));
			PlayerExtensionsKt.party(player).add(pokemon);
			return pokemon;
		} catch (Gen1Mons.Missing e) {
			throw new GameTestAssertException("Cobblemon lacks " + e.code());
		}
	}

	static void assertResult(GameTestHelper helper, Envelope message, String battle, String outcome) {
		helper.assertTrue(message.type().equals(MsgTypes.BATTLE_RESULT), "expected battle.result, got " + message.type());
		helper.assertTrue(message.data().get("battle").getAsString().equals(battle), "the result answers another battle");
		helper.assertTrue(message.data().get("outcome").getAsString().equals(outcome),
				"battle " + battle + " should end " + outcome + ", got " + message.data().get("outcome").getAsString());
		helper.assertTrue(message.data().size() == 2, "a trainer battle's result carries battle and outcome only");
	}

	/** One trainer Pokémon as PLAN P11 wants it. */
	static void assertTrainerMon(GameTestHelper helper, Pokemon pokemon, int dex, int level, List<Integer> moves, Dvs dvs) {
		String what = "trainer Pokémon " + dex;
		helper.assertTrue(pokemon.getSpecies().getNationalPokedexNumber() == dex, what + ": species");
		helper.assertTrue(pokemon.getLevel() == level, what + ": level");
		helper.assertTrue(Gen1Mons.moveNumbers(pokemon).equals(moves), what + ": moves must be exactly the Gen 1 ones");
		for (Dvs.Stat stat : Dvs.Stat.values()) {
			int iv = pokemon.getIvs().getOrDefault(Gen1Mons.cobblemonStat(stat));
			helper.assertTrue(iv == dvs.ivFor(stat), what + ": IV for " + stat + " is " + iv + ", want " + dvs.ivFor(stat));
		}
		helper.assertTrue(pokemon.getNature() == Natures.HARDY, what + ": nature must be Hardy");
		PotentialAbility first = Gen1Mons.firstRegularAbility(pokemon);
		helper.assertTrue(first != null && pokemon.getAbility().getTemplate() == first.getTemplate(), what + ": not the first ability");
		helper.assertTrue(pokemon.heldItem().isEmpty(), what + ": holds an item");
		helper.assertFalse(pokemon.getShiny(), what + ": shiny");
		helper.assertTrue(pokemon.getCurrentHealth() == pokemon.getMaxHealth(), what + ": not at full HP");
	}

	/** Starts a battle through the relay and returns its proxy (cleaning up and failing if anything is off). */
	static NPCEntity startBattle(GameTestHelper helper, ServerPlayer player, String battle, String kind, String trainer) {
		RelayService.get().receive(player, envelope(1, MsgTypes.BATTLE_START, start(battle, kind, trainer, TEAM)));
		NPCEntity proxy = BattleService.proxyOf(player);
		helper.assertTrue(proxy != null, "no battle proxy (warnings: " + BattleService.warnings() + ")");
		helper.assertTrue(BattleService.battleOf(player) != null, "no battle registered for the player");
		return proxy;
	}

	private static void cleanUp(GameTestHelper helper, ServerPlayer player, RelayTap.Recorder relay) {
		PokemonBattle battle = BattleRegistry.getBattleByParticipatingPlayer(player);
		if (battle != null) {
			battle.stop();
		}
		relay.close();
		MockPlayers.leave(helper, player);
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 400)
	public void aTrainerBattleRunsAgainstAHiddenProxy(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.join(helper);
		RelayTap.Recorder relay = RelayTap.open(player);
		NPCEntity proxy;
		try {
			partner(player);
			long started = BattleService.started();
			proxy = startBattle(helper, player, "bt-1", "trainer", TRAINER);
			helper.assertTrue(BattleService.started() == started + 1, "the start wasn't counted");
			// The proxy: tagged, alive next to the player, still, never sent to any client, a regular trainer's class.
			helper.assertTrue(BattleProxies.isProxy(proxy) && proxy.isAlive() && proxy.level() == player.level(), "the proxy isn't in the level");
			helper.assertFalse(proxy.broadcastToPlayer(player), "the proxy would be sent to the fighting player's client");
			helper.assertTrue(proxy.isNoAi(), "the proxy must not move");
			helper.assertTrue(proxy.distanceTo(player) <= 6.0F, "without a trainer cell the proxy stands next to the player");
			helper.assertTrue(proxy.getNpc().getId().equals(ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "battle_proxy")),
					"a bug catcher is a regular trainer");
			helper.assertTrue(proxy.getNpc().getSkill() == 1, "regular trainers play at skill 1");
			// Its party: the hidden game's team, exactly.
			NPCPartyStore party = proxy.getParty();
			helper.assertTrue(party != null && party.occupied() == 2, "the proxy's party isn't the team");
			assertTrainerMon(helper, party.get(0), 16, 9, List.of(33, 28), new Dvs(9, 8, 8, 8));
			assertTrainerMon(helper, party.get(1), 19, 8, List.of(33, 39, 98), new Dvs(1, 2, 3, 4));
			// Cobblemon's battle: player versus NPC, modern singles.
			PokemonBattle battle = BattleService.battleOf(player);
			helper.assertTrue(battle.isPvN(), "not a player-versus-NPC battle");
			helper.assertTrue(BattleRegistry.getBattleByParticipatingPlayer(player) == battle, "Cobblemon doesn't know the battle");
			helper.assertTrue(battle.getFormat().equals(BattleFormat.Companion.getGEN_9_SINGLES()), "not GEN_9_SINGLES");
			helper.assertTrue(relay.of(MsgTypes.BATTLE_RESULT).isEmpty(), "answered before the battle ended");

			// The same battle again is ignored; another one while it runs is aborted.
			long duplicates = BattleService.duplicates();
			RelayService.get().receive(player, envelope(2, MsgTypes.BATTLE_START, start("bt-1", "trainer", TRAINER, TEAM)));
			helper.assertTrue(BattleService.duplicates() == duplicates + 1, "a repeated start wasn't recognised");
			helper.assertTrue(relay.of(MsgTypes.BATTLE_RESULT).isEmpty(), "a repeated start was answered");
			RelayService.get().receive(player, envelope(3, MsgTypes.BATTLE_START, start("bt-2", "trainer", TRAINER, TEAM)));
			helper.assertTrue(BattleService.proxyOf(player) == proxy, "a second battle replaced the first");
			List<Envelope> results = relay.of(MsgTypes.BATTLE_RESULT);
			helper.assertTrue(results.size() == 1, "the second start wasn't answered");
			assertResult(helper, results.get(0), "bt-2", "aborted");

			// Stopped (a disconnect, /stopbattle): nobody won, so the hidden game hears aborted, once.
			battle.stop();
			results = relay.of(MsgTypes.BATTLE_RESULT);
			helper.assertTrue(results.size() == 2, "expected one answer for the stopped battle, got " + (results.size() - 1));
			assertResult(helper, results.get(1), "bt-1", "aborted");
			helper.assertTrue(BattleService.proxyOf(player) == null, "the battle still counts as running");
		} catch (RuntimeException e) {
			cleanUp(helper, player, relay);
			throw e;
		}
		NPCEntity discarded = proxy;
		helper.succeedWhen(() -> {
			helper.assertTrue(discarded.isRemoved(), "the proxy wasn't discarded after the battle");
			helper.assertTrue(relay.of(MsgTypes.BATTLE_RESULT).size() == 2, "answered more than once");
			cleanUp(helper, player, relay);
		});
	}

	/**
	 * Real Showdown, real Cobblemon: the forfeit takes a few seconds of wall-clock time (Cobblemon's battle waits are in
	 * milliseconds, while the GameTest server ticks as fast as it can), so the tick budget is huge and a second sequence
	 * fails the test after {@value #SHOWDOWN_SECONDS} s of real time instead.
	 */
	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 200_000)
	public void aForfeitIsALoss(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.join(helper);
		RelayTap.Recorder relay = RelayTap.open(player);
		try {
			partner(player);
			NPCEntity proxy = startBattle(helper, player, "bt-3", "rival", "{\"cls\":\"OPP_RIVAL1\",\"party\":1}");
			helper.assertTrue(proxy.getNpc().getId().getPath().equals("battle_proxy_rival") && proxy.getNpc().getSkill() == 3,
					"the rival plays at skill 3");
			PokemonBattle battle = BattleService.battleOf(player);
			BattleActor me = battle.getActor(player);
			helper.assertTrue(me != null, "the player isn't in the battle");
			// What Cobblemon's Forfeit button does: Showdown makes the other side win.
			battle.writeShowdownAction(">forcelose " + me.getShowdownId());
		} catch (RuntimeException e) {
			cleanUp(helper, player, relay);
			throw e;
		}
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(SHOWDOWN_SECONDS);
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(System.nanoTime() > deadline, "still within the time Showdown has"))
				.thenExecute(() -> cleanUp(helper, player, relay))
				.thenFail(() -> new GameTestAssertException("Showdown didn't end the forfeited battle within " + SHOWDOWN_SECONDS + " s"));
		helper.succeedWhen(() -> {
			List<Envelope> results = relay.of(MsgTypes.BATTLE_RESULT);
			helper.assertTrue(!results.isEmpty(), "Showdown hasn't ended the battle yet");
			helper.assertTrue(results.size() == 1, "answered more than once");
			assertResult(helper, results.get(0), "bt-3", "lose");
			cleanUp(helper, player, relay);
		});
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 400)
	public void aWinIsReportedAsAWin(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.join(helper);
		RelayTap.Recorder relay = RelayTap.open(player);
		NPCEntity proxy;
		try {
			partner(player);
			proxy = startBattle(helper, player, "bt-4", "trainer", "{\"cls\":\"OPP_BROCK\",\"party\":1}");
			helper.assertTrue(proxy.getNpc().getId().getPath().equals("battle_proxy_gym") && proxy.getNpc().getSkill() == 3,
					"gym leaders play at skill 3");
			PokemonBattle battle = BattleService.battleOf(player);
			BattleActor me = battle.getActor(player);
			BattleActor trainer = battle.getActor(proxy);
			helper.assertTrue(me != null && trainer != null, "the battle's actors are missing");
			// What Showdown's |win| makes Cobblemon do (WinInstruction): name the winners, then end.
			battle.setWinners(List.of(me));
			battle.setLosers(List.of(trainer));
			battle.end();
			List<Envelope> results = relay.of(MsgTypes.BATTLE_RESULT);
			helper.assertTrue(results.size() == 1, "expected one answer, got " + results.size());
			assertResult(helper, results.get(0), "bt-4", "win");
		} catch (RuntimeException e) {
			cleanUp(helper, player, relay);
			throw e;
		}
		NPCEntity discarded = proxy;
		helper.succeedWhen(() -> {
			helper.assertTrue(discarded.isRemoved(), "the proxy wasn't discarded after the battle");
			cleanUp(helper, player, relay);
		});
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void battlesThatCannotRunAreAborted(GameTestHelper helper) {
		RelayService relay = RelayService.get();
		MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
			// No Pokémon to fight with.
			long noParty = BattleService.warnings("no_party");
			relay.receive(player, envelope(1, MsgTypes.BATTLE_START, start("bx-1", "trainer", TRAINER, TEAM)));
			helper.assertTrue(BattleService.warnings("no_party") == noParty + 1, "no_party wasn't counted");
			partner(player);
			// A wild kind (static, surf, fish) is M6's.
			long unsupported = BattleService.warnings("unsupported_kind");
			relay.receive(player, envelope(2, MsgTypes.BATTLE_START,
					"{\"battle\":\"bx-2\",\"kind\":\"static\",\"wild\":{\"dex\":143,\"level\":30},\"rules\":{\"catch\":true,\"flee\":false}}"));
			helper.assertTrue(BattleService.warnings("unsupported_kind") == unsupported + 1, "unsupported_kind wasn't counted");
			// Malformed but with an id: an error and an answer. Without a usable id: an error only.
			relay.receive(player, envelope(3, MsgTypes.BATTLE_START, "{\"battle\":\"bx-3\",\"kind\":\"trainer\",\"rules\":{\"catch\":false,\"flee\":false}}"));
			relay.receive(player, envelope(4, MsgTypes.BATTLE_START, "{\"battle\":7,\"kind\":\"trainer\"}"));
			helper.assertTrue(BattleService.proxyOf(player) == null, "a battle that can't run spawned a proxy");
			helper.assertTrue(sent.size() == 5, "expected 3 results and 2 errors, got " + sent.size());
			assertResult(helper, sent.get(0), "bx-1", "aborted");
			assertResult(helper, sent.get(1), "bx-2", "aborted");
			helper.assertTrue(sent.get(2).type().equals(MsgTypes.ERROR) && Objects.equals(sent.get(2).re(), 3L), "malformed: no error");
			assertResult(helper, sent.get(3), "bx-3", "aborted");
			helper.assertTrue(sent.get(4).type().equals(MsgTypes.ERROR) && Objects.equals(sent.get(4).re(), 4L), "no id: no error");
		}));
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 100)
	public void leftoverProxiesAreDiscardedOnLoad(GameTestHelper helper) {
		// A proxy saved by a crash comes back with its chunk; nothing manages it, so it goes.
		NPCEntity leftover = new NPCEntity(helper.getLevel());
		leftover.addTag(BattleProxies.TAG);
		leftover.moveTo(helper.absoluteVec(new net.minecraft.world.phys.Vec3(1.5, 2.0, 1.5)));
		helper.assertTrue(helper.getLevel().addFreshEntity(leftover), "the leftover couldn't be added");
		helper.succeedWhen(() -> helper.assertTrue(leftover.isRemoved(), "the leftover proxy is still there"));
	}
}
