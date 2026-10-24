package dev.kantobridge.gametest;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.CobblemonEntities;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.ActorType;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.battles.BattleFleeAttemptEvent;
import com.cobblemon.mod.common.api.events.pokeball.ThrownPokeballHitEvent;
import com.cobblemon.mod.common.api.events.pokemon.PokemonCapturedEvent;
import com.cobblemon.mod.common.api.pokeball.PokeBalls;
import com.cobblemon.mod.common.api.spawning.SpawnCause;
import com.cobblemon.mod.common.api.spawning.SpawningZone;
import com.cobblemon.mod.common.api.spawning.position.AreaSpawnablePosition;
import com.cobblemon.mod.common.api.spawning.position.calculators.SpawnablePositionCalculator;
import com.cobblemon.mod.common.api.spawning.spawner.PlayerSpawner;
import com.cobblemon.mod.common.api.spawning.spawner.SpawningZoneInput;
import com.cobblemon.mod.common.battles.BattleFormat;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.entity.pokeball.EmptyPokeBallEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.properties.UncatchableProperty;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import com.google.gson.JsonObject;
import dev.kantobridge.battle.EncounterService;
import dev.kantobridge.block.KbBlocks;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Static, surfing and fishing encounters on a real server with Cobblemon (docs/PLAN.md §2.4 flow 9, §4.4
 * {@code EncounterService}): a wild {@code battle.start} spawns a still, persistent, unbattleable Pokémon outside the
 * spawn cap and starts Cobblemon's wild battle; balls from outside the battle are refused, {@code rules.catch} false
 * makes it uncatchable; the end is answered once as aborted, win, lose, fled (a flee attempt, since the client holds the
 * player) or caught (with the Pokémon), and the Pokémon goes. Endings are driven the way Cobblemon drives them
 * ({@code stop()}, winners then {@code end()}, its events); made-up encounters only.
 */
public class EncounterGameTests implements FabricGameTest {
	private static final String BATCH = "kantobridge_encounters";

	/**
	 * A tuft keeps its grass spawnable (the voxel plan's R3, V4): a decor carrier at feet level has no collision, so
	 * Cobblemon's own resolver of spawn positions (as {@code /checkspawn} runs it) offers the floor block under a tuft
	 * exactly as it offers bare ground.
	 */
	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 100)
	public void aTuftKeepsItsGrassSpawnable(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos corner = helper.absolutePos(new BlockPos(1, 1, 1));
		BlockState floor = KbBlocks.tileFull().get(0).defaultBlockState();
		for (int dz = 0; dz < 6; dz++) {
			for (int dx = 0; dx < 6; dx++) {
				level.setBlock(corner.offset(dx, 0, dz), floor, 3);
				// Tufts on the western half, bare ground on the eastern half.
				if (dx < 3) {
					level.setBlock(corner.offset(dx, 1, dz), KbBlocks.DECOR.defaultBlockState(), 3);
				}
			}
		}
		ServerPlayer player = MockPlayers.join(helper);
		try {
			PlayerSpawner spawner = PlayerExtensionsKt.getSpawner(player);
			helper.assertTrue(spawner != null, "the mock player has no Cobblemon spawner");
			SpawningZoneInput input = new SpawningZoneInput(new SpawnCause(spawner, player), level, corner.getX() - 1, corner.getY() - 1,
					corner.getZ() - 1, 8, 6, 8);
			SpawningZone zone = Cobblemon.INSTANCE.getSpawningZoneGenerator().generate(spawner, input);
			List<AreaSpawnablePosition> positions = Cobblemon.INSTANCE.getAreaSpawnablePositionResolver().resolve(spawner,
					SpawnablePositionCalculator.Companion.getPrioritizedAreaCalculators(), zone);
			int underTufts = 0;
			int bare = 0;
			for (AreaSpawnablePosition position : positions) {
				BlockPos at = position.getPosition();
				if (at.getY() != corner.getY() || at.getX() < corner.getX() || at.getX() >= corner.getX() + 6 || at.getZ() < corner.getZ()
						|| at.getZ() >= corner.getZ() + 6) {
					continue;
				}
				if (at.getX() < corner.getX() + 3) {
					underTufts++;
				} else {
					bare++;
				}
			}
			helper.assertTrue(bare > 0, "bare ground offers no spawn position (" + positions.size() + " positions)");
			helper.assertTrue(underTufts == bare, "the floor under tufts offers " + underTufts + " positions, bare ground " + bare);
		} finally {
			MockPlayers.leave(helper, player);
		}
		helper.succeed();
	}

	static String wildStart(String battle, String kind, int dex, int level, boolean canCatch, boolean canFlee) {
		return "{\"battle\":\"" + battle + "\",\"kind\":\"" + kind + "\",\"wild\":{\"dex\":" + dex + ",\"level\":" + level + "},\"rules\":{\"catch\":"
				+ canCatch + ",\"flee\":" + canFlee + "}}";
	}

	/** Starts an encounter through the relay; fails (cleaning up) if it didn't start. */
	static PokemonEntity start(GameTestHelper helper, ServerPlayer player, String battle, String kind, int dex, int level, boolean canCatch,
			boolean canFlee) {
		BattleGameTests.partner(player);
		RelayService.get().receive(player, BattleGameTests.envelope(1, MsgTypes.BATTLE_START, wildStart(battle, kind, dex, level, canCatch, canFlee)));
		PokemonEntity entity = EncounterService.entityOf(player);
		helper.assertTrue(entity != null, "no encounter Pokémon (warnings: " + EncounterService.warnings() + ")");
		helper.assertTrue(EncounterService.battleOf(player) != null, "no encounter battle");
		return entity;
	}

	static void assertOneResult(GameTestHelper helper, RelayTap.Recorder relay, String battle, String outcome) {
		List<Envelope> results = relay.of(MsgTypes.BATTLE_RESULT);
		helper.assertTrue(results.size() == 1, "expected one battle.result, got " + results.size());
		JsonObject data = results.get(0).data();
		helper.assertTrue(data.get("battle").getAsString().equals(battle), "the result answers another battle");
		helper.assertTrue(data.get("outcome").getAsString().equals(outcome), "should end " + outcome + ", got " + data.get("outcome").getAsString());
	}

	/** A failing test's clean-up, at once: the battle stops, the player's Pokémon are taken back, the player leaves. */
	static void cleanUp(GameTestHelper helper, ServerPlayer player, RelayTap.Recorder relay) {
		relay.close();
		if (!player.isRemoved()) {
			MockPlayers.leaveNow(helper, player);
		}
	}

	static @Nullable BattleActor wildActor(PokemonBattle battle) {
		for (BattleActor actor : battle.getActors()) {
			if (actor.getType() == ActorType.WILD) {
				return actor;
			}
		}
		return null;
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 200)
	public void aStaticEncounterIsAStillPokemonInAWildBattle(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.join(helper);
		RelayTap.Recorder relay = RelayTap.open(player);
		PokemonEntity entity;
		try {
			long started = EncounterService.started();
			entity = start(helper, player, "en-1", "static", 143, 30, true, false);
			helper.assertTrue(EncounterService.started() == started + 1, "the start wasn't counted");
			// The Pokémon: the hidden game's species and level, wild, standing still next to the player, for this battle only.
			Pokemon pokemon = entity.getPokemon();
			helper.assertTrue(pokemon.getSpecies().getNationalPokedexNumber() == 143 && pokemon.getLevel() == 30, "species or level");
			helper.assertTrue(pokemon.isWild() && !pokemon.getShiny(), "a wild, never shiny Pokémon");
			helper.assertTrue(entity.isNoAi(), "the encounter Pokémon must not move (no_ai)");
			helper.assertTrue(entity.isPersistenceRequired(), "the encounter Pokémon must not despawn");
			helper.assertTrue(entity.getEntityData().get(PokemonEntity.getUNBATTLEABLE()), "others could battle it");
			helper.assertFalse(entity.getCountsTowardsSpawnCap() || entity.getEntityData().get(PokemonEntity.getCOUNTS_TOWARDS_SPAWN_CAP()),
					"it counts towards the spawn cap");
			helper.assertTrue(entity.getTags().contains(EncounterService.TAG) && EncounterService.isEncounter(entity), "not tagged");
			helper.assertTrue(entity.isAlive() && entity.level() == player.level() && entity.distanceTo(player) <= 6.0F, "not next to the player");
			helper.assertTrue(UncatchableProperty.INSTANCE.isCatchable(entity), "catching is allowed by the rules");
			// Cobblemon's wild battle: player versus the Pokémon, modern singles, the entity bound to it.
			PokemonBattle battle = EncounterService.battleOf(player);
			helper.assertTrue(battle.isPvW() && BattleRegistry.getBattleByParticipatingPlayer(player) == battle, "not a wild battle Cobblemon knows");
			helper.assertTrue(battle.getFormat().equals(BattleFormat.Companion.getGEN_9_SINGLES()), "not GEN_9_SINGLES");
			helper.assertTrue(battle.getBattleId().equals(entity.getBattleId()), "the Pokémon isn't bound to its battle");
			helper.assertTrue(relay.of(MsgTypes.BATTLE_RESULT).isEmpty(), "answered before the battle ended");

			// The rules say no running: a flee attempt is ignored (Cobblemon asks again).
			CobblemonEvents.BATTLE_FLEE_ATTEMPT.post(new BattleFleeAttemptEvent(battle, (PlayerBattleActor) battle.getActor(player)));
			// A ball from the battling player is Cobblemon's business; one at the Pokémon outside its battle is refused.
			EmptyPokeBallEntity ball = new EmptyPokeBallEntity(PokeBalls.INSTANCE.getPOKE_BALL(), player.level(), player,
					CobblemonEntities.EMPTY_POKEBALL);
			ThrownPokeballHitEvent inBattle = new ThrownPokeballHitEvent(ball, entity);
			CobblemonEvents.THROWN_POKEBALL_HIT.post(inBattle);
			helper.assertFalse(inBattle.isCanceled(), "a ball thrown in the encounter's battle was refused");
			UUID battleId = entity.getBattleId();
			entity.setBattleId(null);
			ThrownPokeballHitEvent outside = new ThrownPokeballHitEvent(ball, entity);
			CobblemonEvents.THROWN_POKEBALL_HIT.post(outside);
			entity.setBattleId(battleId);
			helper.assertTrue(outside.isCanceled(), "a ball thrown at the encounter outside its battle wasn't refused");
		} catch (RuntimeException e) {
			cleanUp(helper, player, relay);
			throw e;
		}
		PokemonEntity discarded = entity;
		GameTestSequence end = helper.startSequence()
				.thenExecuteAfter(2, () -> {
					PokemonBattle battle = EncounterService.battleOf(player);
					helper.assertTrue(battle != null && !battle.getEnded(), "a flee attempt ended a battle the rules don't let you run from");
					// Stopped (a disconnect, a crash): nobody won, the hidden game hears aborted.
					battle.stop();
					assertOneResult(helper, relay, "en-1", "aborted");
					helper.assertTrue(EncounterService.battleOf(player) == null, "the encounter still counts as running");
				})
				.thenWaitUntil(() -> helper.assertTrue(discarded.isRemoved(), "the encounter Pokémon wasn't discarded"))
				.thenExecute(() -> helper.assertTrue(relay.of(MsgTypes.BATTLE_RESULT).size() == 1, "answered more than once"));
		MockPlayers.leaveAfterBattle(helper, end, relay::close, player);
	}

	/**
	 * FP5 (FOREIGN_POKEMON_PLAN §4.12): a Bug-Catching Contest battle ({@code kind:"contest"}, {@code use}: the lead) is a
	 * wild battle in which Cobblemon fights with only the {@code use} Pokémon, the real one (never a copy), while the rest of
	 * the party stays where it is.
	 */
	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 200)
	public void aContestBattleUsesOnlyTheLead(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.join(helper);
		RelayTap.Recorder relay = RelayTap.open(player);
		try {
			Pokemon lead = BattleGameTests.partner(player);
			Pokemon second = BattleGameTests.partner(player);
			String start = "{\"battle\":\"en-c1\",\"kind\":\"contest\",\"wild\":{\"dex\":10,\"level\":7},\"use\":[\"" + lead.getUuid()
					+ "\"],\"rules\":{\"catch\":true,\"flee\":true}}";
			RelayService.get().receive(player, BattleGameTests.envelope(1, MsgTypes.BATTLE_START, start));
			PokemonBattle battle = EncounterService.battleOf(player);
			helper.assertTrue(battle != null, "no contest battle (warnings: " + EncounterService.warnings() + ")");
			PlayerBattleActor actor = (PlayerBattleActor) battle.getActor(player);
			helper.assertTrue(actor.getPokemonList().size() == 1, "the contest battle has " + actor.getPokemonList().size() + " Pokémon");
			helper.assertTrue(actor.getPokemonList().get(0).getOriginalPokemon() == lead, "not the lead itself");
			helper.assertTrue(PlayerExtensionsKt.party(player).get(second.getUuid()) == second, "the rest of the party moved");
			battle.stop();
		} catch (RuntimeException e) {
			cleanUp(helper, player, relay);
			throw e;
		}
		GameTestSequence end = helper.startSequence().thenExecuteAfter(2, () -> assertOneResult(helper, relay, "en-c1", "aborted"));
		MockPlayers.leaveAfterBattle(helper, end, relay::close, player);
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 200)
	public void aFleeAttemptEndsTheEncounterAsFled(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.join(helper);
		RelayTap.Recorder relay = RelayTap.open(player);
		PokemonEntity entity;
		long flees = EncounterService.flees();
		try {
			entity = start(helper, player, "en-2", "surf", 72, 15, false, true);
			helper.assertFalse(UncatchableProperty.INSTANCE.isCatchable(entity), "rules.catch false must make it uncatchable");
			PokemonBattle battle = EncounterService.battleOf(player);
			// Run: Cobblemon would wait for the held player to walk away; the encounter ends as fled instead.
			CobblemonEvents.BATTLE_FLEE_ATTEMPT.post(new BattleFleeAttemptEvent(battle, (PlayerBattleActor) battle.getActor(player)));
		} catch (RuntimeException e) {
			cleanUp(helper, player, relay);
			throw e;
		}
		PokemonEntity discarded = entity;
		GameTestSequence end = helper.startSequence().thenWaitUntil(() -> {
			helper.assertTrue(!relay.of(MsgTypes.BATTLE_RESULT).isEmpty(), "no result yet");
			assertOneResult(helper, relay, "en-2", "fled");
			helper.assertTrue(BattleRegistry.getBattleByParticipatingPlayer(player) == null, "the battle didn't end");
			helper.assertTrue(discarded.isRemoved(), "the encounter Pokémon wasn't discarded");
			helper.assertTrue(EncounterService.flees() > flees, "the flight wasn't counted");
		});
		MockPlayers.leaveAfterBattle(helper, end, relay::close, player);
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 200)
	public void defeatsAndLossesAreReported(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.join(helper);
		RelayTap.Recorder relay = RelayTap.open(player);
		try {
			start(helper, player, "en-3", "fish", 118, 10, true, true);
			PokemonBattle battle = EncounterService.battleOf(player);
			BattleActor me = battle.getActor(player);
			BattleActor wild = wildActor(battle);
			helper.assertTrue(me != null && wild != null, "the battle's actors are missing");
			// What Showdown's |win| makes Cobblemon do: name the winners, then end. The player won: defeated.
			battle.setWinners(List.of(me));
			battle.setLosers(List.of(wild));
			battle.end();
			assertOneResult(helper, relay, "en-3", "win");
		} catch (RuntimeException e) {
			cleanUp(helper, player, relay);
			throw e;
		}
		ServerPlayer second = MockPlayers.join(helper);
		RelayTap.Recorder secondRelay = RelayTap.open(second);
		try {
			start(helper, second, "en-4", "static", 74, 12, true, false);
			PokemonBattle battle = EncounterService.battleOf(second);
			BattleActor me = battle.getActor(second);
			BattleActor wild = wildActor(battle);
			battle.setWinners(List.of(wild));
			battle.setLosers(List.of(me));
			battle.end();
			assertOneResult(helper, secondRelay, "en-4", "lose");
		} catch (RuntimeException e) {
			cleanUp(helper, second, secondRelay);
			cleanUp(helper, player, relay);
			throw e;
		}
		GameTestSequence end = helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
				EncounterService.entityOf(player) == null && EncounterService.entityOf(second) == null, "still running"));
		MockPlayers.leaveAfterBattle(helper, end, () -> {
			secondRelay.close();
			relay.close();
		}, second, player);
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 200)
	public void aCatchIsReportedWithThePokemon(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.join(helper);
		RelayTap.Recorder relay = RelayTap.open(player);
		Pokemon caught;
		try {
			PokemonEntity entity = start(helper, player, "en-5", "static", 19, 7, true, false);
			PokemonBattle battle = EncounterService.battleOf(player);
			caught = entity.getPokemon();
			// What a successful ball does inside the battle: the Pokémon leaves the world for the party, then the event.
			EmptyPokeBallEntity ball = new EmptyPokeBallEntity(PokeBalls.INSTANCE.getPOKE_BALL(), player.level(), player,
					CobblemonEntities.EMPTY_POKEBALL);
			entity.discard();
			PlayerExtensionsKt.party(player).add(caught);
			CobblemonEvents.POKEMON_CAPTURED.post(new PokemonCapturedEvent(caught, player, ball));
			// Showdown then names the player the winner of a capture.
			battle.setWinners(List.of(battle.getActor(player)));
			battle.setLosers(List.of(wildActor(battle)));
			battle.end();
			assertOneResult(helper, relay, "en-5", "caught");
			JsonObject data = relay.of(MsgTypes.BATTLE_RESULT).get(0).data();
			JsonObject catchData = data.getAsJsonObject("caught");
			helper.assertTrue(catchData != null && catchData.get("uid").getAsString().equals(caught.getUuid().toString()),
					"the catch must name the Pokémon's uid");
			helper.assertTrue(catchData.get("dex").getAsInt() == 19 && catchData.get("level").getAsInt() == 7, "dex or level");
			helper.assertTrue(PlayerExtensionsKt.party(player).get(caught.getUuid()) != null, "the caught Pokémon isn't in the party");
		} catch (RuntimeException e) {
			cleanUp(helper, player, relay);
			throw e;
		}
		String uid = caught.getUuid().toString();
		GameTestSequence end = helper.startSequence().thenWaitUntil(() -> {
			helper.assertTrue(EncounterService.entityOf(player) == null, "still running");
			// A party.sync follows the battle, with the catch in it, and the Pokédex owns it (gen1-bridge feeds Gen 1's
			// Pokédex from dex.sync only).
			List<Envelope> syncs = relay.of(MsgTypes.PARTY_SYNC);
			helper.assertTrue(!syncs.isEmpty() && syncs.get(syncs.size() - 1).data().toString().contains(uid), "no party.sync with the catch yet");
			List<Envelope> dex = relay.of(MsgTypes.DEX_SYNC);
			helper.assertTrue(!dex.isEmpty() && DexGameTests.numbers(dex.get(dex.size() - 1).data().getAsJsonArray("owned")).contains(19),
					"no dex.sync owning the catch yet");
		});
		MockPlayers.leaveAfterBattle(helper, end, relay::close, player);
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 100)
	public void aRepeatedOrSecondEncounterIsNotStarted(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.join(helper);
		RelayTap.Recorder relay = RelayTap.open(player);
		try {
			PokemonEntity entity = start(helper, player, "en-6", "static", 143, 30, true, false);
			RelayService.get().receive(player, BattleGameTests.envelope(2, MsgTypes.BATTLE_START, wildStart("en-6", "static", 143, 30, true, false)));
			helper.assertTrue(EncounterService.entityOf(player) == entity && relay.of(MsgTypes.BATTLE_RESULT).isEmpty(),
					"a repeated start must be ignored");
			RelayService.get().receive(player, BattleGameTests.envelope(3, MsgTypes.BATTLE_START, wildStart("en-7", "surf", 72, 5, true, true)));
			assertOneResult(helper, relay, "en-7", "aborted");
			helper.assertTrue(EncounterService.entityOf(player) == entity, "a second encounter replaced the first");
		} catch (RuntimeException e) {
			cleanUp(helper, player, relay);
			throw e;
		}
		MockPlayers.leaveAfterBattle(helper, helper.startSequence(), relay::close, player);
	}
}
