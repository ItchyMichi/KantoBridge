package dev.kantobridge.gametest;

import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.pokemon.DaycareService;
import dev.kantobridge.pokemon.Gen1Mon;
import dev.kantobridge.pokemon.Gen1Mons;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Gen 1's daycare done with Cobblemon (docs/PLAN.md P15): {@code daycare.deposit} takes a party Pokémon to the player's
 * daycare on the server (a file in the world that survives a restart), {@code daycare.withdraw} brings it back with one
 * experience point per step, capped at level 100, into the PC when the party is full; repeats change nothing, mismatches
 * are counted, and {@code party.sync} follows without the boarded Pokémon. Made-up Pokémon only.
 */
public class DaycareGameTests implements FabricGameTest {
	private static final String BATCH = "kantobridge_daycare";

	private static Pokemon gift(int dex, int level) {
		try {
			return Gen1Mons.gift(new Gen1Mon(dex, level, List.of(33), null));
		} catch (Gen1Mons.Missing e) {
			throw new GameTestAssertException("Cobblemon lacks " + e.code());
		}
	}

	private static void deposit(ServerPlayer player, long seq, UUID uid) {
		RelayService.get().receive(player, PartyGameTests.envelope(seq, MsgTypes.DAYCARE_DEPOSIT, "{\"uid\":\"" + uid + "\"}"));
	}

	private static void withdraw(ServerPlayer player, long seq, UUID uid, long steps) {
		RelayService.get().receive(player, PartyGameTests.envelope(seq, MsgTypes.DAYCARE_WITHDRAW, "{\"uid\":\"" + uid + "\",\"steps\":" + steps + "}"));
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void aBoardedPokemonWaitsOnTheServerAndComesBackWithItsSteps(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
			PlayerPartyStore party = PlayerExtensionsKt.party(player);
			Pokemon boarded = gift(54, 20);
			Pokemon partner = gift(74, 12);
			party.add(boarded);
			party.add(partner);
			UUID uid = boarded.getUuid();
			int experience = boarded.getExperience();

			long deposits = DaycareService.deposits();
			deposit(player, 1, uid);
			helper.assertTrue(DaycareService.deposits() == deposits + 1, "the deposit wasn't counted");
			helper.assertTrue(party.get(uid) == null && party.occupied() == 1, "the boarded Pokémon is still in the party");
			helper.assertTrue(PlayerExtensionsKt.pc(player).get(uid) == null, "it went to the PC, not the daycare");
			helper.assertTrue(DaycareService.held(player).equals(Set.of(uid)), "the daycare doesn't hold it");
			helper.assertTrue(Files.isRegularFile(DaycareService.file(server, player.getUUID())), "no daycare file in the world");
			// gen1-bridge repeats what it hasn't seen applied: nothing changes.
			long repeats = DaycareService.repeats();
			deposit(player, 2, uid);
			helper.assertTrue(DaycareService.repeats() == repeats + 1 && DaycareService.held(player).size() == 1, "a repeat changed something");

			// A restart: the daycare is read back from its file.
			DaycareService.forget(server);
			helper.assertTrue(DaycareService.held(player).equals(Set.of(uid)), "the daycare was lost across a restart");

			// 420 steps later: back in the party with 420 experience points.
			long withdrawals = DaycareService.withdrawals();
			withdraw(player, 3, uid, 420);
			Pokemon back = party.get(uid);
			helper.assertTrue(back != null && DaycareService.withdrawals() == withdrawals + 1, "not back in the party");
			helper.assertTrue(back.getExperience() == experience + 420, "experience " + back.getExperience() + ", want " + (experience + 420));
			helper.assertTrue(back.getLevel() == back.getExperienceGroup().getLevel(experience + 420), "the level doesn't follow the experience");
			helper.assertTrue(back.getSpecies().getNationalPokedexNumber() == 54 && back.getOriginalTrainerType() == boarded.getOriginalTrainerType(),
					"not the same Pokémon");
			helper.assertTrue(DaycareService.held(player).isEmpty(), "still in the daycare");
			repeats = DaycareService.repeats();
			withdraw(player, 4, uid, 420);
			helper.assertTrue(DaycareService.repeats() == repeats + 1 && back.getExperience() == experience + 420, "a repeat gave experience twice");

			// Mismatches: a uid nobody has, and one that was never boarded.
			long unknown = DaycareService.warnings("deposit_unknown_uid");
			deposit(player, 5, UUID.randomUUID());
			helper.assertTrue(DaycareService.warnings("deposit_unknown_uid") == unknown + 1, "an unknown deposit wasn't counted");
			long notBoarded = DaycareService.warnings("withdraw_not_deposited");
			withdraw(player, 6, partner.getUuid(), 10);
			helper.assertTrue(DaycareService.warnings("withdraw_not_deposited") == notBoarded + 1, "a withdrawal of a party member wasn't counted");
			long gone = DaycareService.warnings("withdraw_unknown_uid");
			withdraw(player, 7, UUID.randomUUID(), 10);
			helper.assertTrue(DaycareService.warnings("withdraw_unknown_uid") == gone + 1, "an unknown withdrawal wasn't counted");

			// party.take{reason:"daycare"} is a deposit too.
			RelayService.get().receive(player, PartyGameTests.envelope(8, MsgTypes.PARTY_TAKE,
					"{\"uid\":\"" + partner.getUuid() + "\",\"reason\":\"daycare\"}"));
			helper.assertTrue(DaycareService.held(player).equals(Set.of(partner.getUuid())) && party.get(partner.getUuid()) == null,
					"party.take for the daycare didn't board it");
			withdraw(player, 9, partner.getUuid(), 0);
			helper.assertTrue(party.get(partner.getUuid()) != null && DaycareService.held(player).isEmpty(), "back with no steps walked");
			helper.assertTrue(sent.stream().noneMatch(message -> message.type().equals(MsgTypes.ERROR)), "a valid request was refused");
			helper.assertTrue(PlayerExtensionsKt.party(player).occupied() == 2, "the party lost or gained someone");
		}));
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void levelOneHundredAndAFullPartyAreRespected(GameTestHelper helper) {
		MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
			PlayerPartyStore party = PlayerExtensionsKt.party(player);
			Pokemon veteran = gift(54, 99);
			party.add(veteran);
			for (int i = 0; i < 5; i++) {
				party.add(gift(16 + i, 5));
			}
			UUID uid = veteran.getUuid();
			deposit(player, 1, uid);
			helper.assertTrue(party.occupied() == 5, "not boarded");
			// The party fills up again meanwhile: it comes back into the PC.
			party.add(gift(21, 5));
			withdraw(player, 2, uid, Integer.MAX_VALUE);
			Pokemon back = PlayerExtensionsKt.pc(player).get(uid);
			helper.assertTrue(back != null && party.get(uid) == null, "with a full party it belongs in the PC");
			helper.assertTrue(back.getLevel() == 100, "level " + back.getLevel() + " after the steps; Gen 1 stops at 100");
			helper.assertTrue(back.getExperience() == back.getExperienceGroup().getExperience(100), "experience past level 100's");
			// Malformed requests are refused with an error.
			RelayService.get().receive(player, PartyGameTests.envelope(3, MsgTypes.DAYCARE_WITHDRAW, "{\"uid\":\"" + uid + "\",\"steps\":-1}"));
			RelayService.get().receive(player, PartyGameTests.envelope(4, MsgTypes.DAYCARE_DEPOSIT, "{\"uid\":\"" + uid + "\",\"extra\":1}"));
			helper.assertTrue(sent.stream().filter(message -> message.type().equals(MsgTypes.ERROR)).count() == 2, "malformed requests weren't refused");
		}));
		helper.succeed();
	}

	/**
	 * A crash between a deposit's two writes leaves the Pokémon both in the daycare file and in the saved party; the hidden
	 * game may then board it again or take it back. Neither may make a second Pokémon with the same uid.
	 */
	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void aStaleEntryNeverMakesASecondPokemon(GameTestHelper helper) {
		MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
			PlayerPartyStore party = PlayerExtensionsKt.party(player);
			Pokemon boarded = gift(54, 20);
			party.add(boarded);
			party.add(gift(74, 12));
			UUID uid = boarded.getUuid();
			// What the saved party would still hold: the same Pokémon, the same uid.
			JsonObject saved = boarded.saveToJSON(player.registryAccess(), new JsonObject());
			deposit(player, 1, uid);
			Pokemon survivor = Pokemon.Companion.loadFromJSON(player.registryAccess(), saved);
			party.add(survivor);
			int experience = survivor.getExperience();

			// Taken back: the party's copy gets the steps, the daycare's entry goes, nobody is doubled.
			long back = DaycareService.warnings("withdraw_already_back");
			withdraw(player, 2, uid, 30);
			helper.assertTrue(DaycareService.warnings("withdraw_already_back") == back + 1, "the stale entry wasn't noticed");
			helper.assertTrue(party.occupied() == 2 && party.get(uid) == survivor, "a second Pokémon with the same uid");
			helper.assertTrue(survivor.getExperience() == experience + 30 && DaycareService.held(player).isEmpty(), "steps or entry");

			// Boarded again while a stale entry is left: the party's copy is the one that boards.
			deposit(player, 3, uid);
			party.add(Pokemon.Companion.loadFromJSON(player.registryAccess(), saved));
			long replaced = DaycareService.warnings("deposit_replaced_stale");
			deposit(player, 4, uid);
			helper.assertTrue(DaycareService.warnings("deposit_replaced_stale") == replaced + 1, "the stale entry wasn't replaced");
			helper.assertTrue(party.get(uid) == null && DaycareService.held(player).equals(Set.of(uid)), "the party's copy didn't board");
			helper.assertTrue(sent.stream().noneMatch(message -> message.type().equals(MsgTypes.ERROR)), "a valid request was refused");
		}));
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 100)
	public void partySyncFollowsADeposit(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.join(helper);
		RelayTap.Recorder relay = RelayTap.open(player);
		UUID[] boarded = new UUID[1];
		try {
			PlayerPartyStore party = PlayerExtensionsKt.party(player);
			Pokemon first = gift(54, 20);
			party.add(first);
			party.add(gift(74, 12));
			boarded[0] = first.getUuid();
			deposit(player, 1, first.getUuid());
		} catch (RuntimeException e) {
			relay.close();
			MockPlayers.leave(helper, player);
			throw e;
		}
		helper.succeedWhen(() -> {
			List<Envelope> syncs = relay.of(MsgTypes.PARTY_SYNC);
			helper.assertTrue(!syncs.isEmpty(), "no party.sync yet");
			JsonArray list = syncs.get(syncs.size() - 1).data().getAsJsonArray("party");
			helper.assertTrue(list.size() == 1, "the sync lists " + list.size() + " Pokémon");
			helper.assertTrue(!list.get(0).getAsJsonObject().get("uid").getAsString().equals(boarded[0].toString()), "the boarded one is listed");
			relay.close();
			MockPlayers.leave(helper, player);
		});
	}
}
