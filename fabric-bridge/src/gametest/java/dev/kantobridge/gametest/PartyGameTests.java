package dev.kantobridge.gametest;

import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokemon.PokemonNicknamedEvent;
import com.cobblemon.mod.common.api.events.pokemon.evolution.EvolutionTestedEvent;
import com.cobblemon.mod.common.api.pokemon.evolution.Evolution;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.pokemon.OriginalTrainerType;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.pokemon.Dvs;
import dev.kantobridge.pokemon.Gen1Mon;
import dev.kantobridge.pokemon.Gen1Mons;
import dev.kantobridge.pokemon.PartyService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * The party between Cobblemon and the hidden game on a real server (docs/PLAN.md §2.4 flow 11, §4.4 {@code PartyService},
 * P14, P16): gifts into the party and then the PC with exact moves and IVs from DVs, {@code party.sync}'s contents, heal,
 * the Name Rater, trades, and the evolution filter. Made-up Pokémon only.
 */
public class PartyGameTests implements FabricGameTest {
	private static final String BATCH = "kantobridge_party";

	static String envelope(long seq, String type, String data) {
		return "{\"type\":\"" + type + "\",\"seq\":" + seq + ",\"data\":" + data + "}";
	}

	static int pcCount(ServerPlayer player) {
		int count = 0;
		for (Pokemon ignored : PlayerExtensionsKt.pc(player)) {
			count++;
		}
		return count;
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void giftsFillThePartyThenThePc(GameTestHelper helper) {
		RelayService relay = RelayService.get();
		MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
			PlayerPartyStore party = PlayerExtensionsKt.party(player);
			String starter = "{\"give\":\"pg-1\",\"dex\":7,\"level\":5,\"moves\":[33,39],\"dvs\":{\"atk\":1,\"def\":2,\"spd\":3,\"spc\":4},"
					+ "\"nick\":\"SHELLY\",\"source\":\"starter\"}";
			relay.receive(player, envelope(1, MsgTypes.PARTY_GIVE, starter));
			helper.assertTrue(party.occupied() == 1, "the starter isn't in the party");
			Pokemon given = party.get(0);
			helper.assertTrue(given.getSpecies().getNationalPokedexNumber() == 7 && given.getLevel() == 5, "species or level");
			helper.assertTrue(Gen1Mons.moveNumbers(given).equals(List.of(33, 39)), "the moves must be exactly the Gen 1 ones");
			Dvs dvs = new Dvs(1, 2, 3, 4);
			for (Dvs.Stat stat : Dvs.Stat.values()) {
				helper.assertTrue(given.getIvs().getOrDefault(Gen1Mons.cobblemonStat(stat)) == dvs.ivFor(stat), "IV for " + stat);
			}
			helper.assertTrue(given.getNickname() != null && given.getNickname().getString().equals("SHELLY"), "nickname");
			helper.assertTrue(given.getOriginalTrainerType() == OriginalTrainerType.PLAYER
					&& player.getUUID().toString().equals(given.getOriginalTrainer()), "the starter belongs to the player");
			helper.assertFalse(given.getShiny(), "no Gen 1 shinies");

			// gen1-bridge repeats a gift until a party.sync shows it: the same give id is applied once (kept on the Pokémon).
			helper.assertTrue("pg-1".equals(given.getPersistentData().getString(PartyService.GIVE_KEY)), "the give id isn't kept");
			long repeated = PartyService.repeatedGives();
			relay.receive(player, envelope(2, MsgTypes.PARTY_GIVE, starter));
			helper.assertTrue(party.occupied() == 1, "a repeated give id added a second Pokémon");
			helper.assertTrue(PartyService.repeatedGives() == repeated + 1, "the repeat wasn't counted");

			// Five more fill the party (Cobblemon's default moves without a move list); the next goes to the PC.
			for (int i = 2; i <= 7; i++) {
				relay.receive(player, envelope(i + 1, MsgTypes.PARTY_GIVE,
						"{\"give\":\"pg-" + i + "\",\"dex\":" + (14 + i) + ",\"level\":" + (3 + i) + ",\"source\":\"gift\"}"));
			}
			helper.assertTrue(party.occupied() == 6, "the party should be full, has " + party.occupied());
			helper.assertTrue(pcCount(player) == 1, "the seventh should be in the PC");
			relay.receive(player, envelope(9, MsgTypes.PARTY_GIVE, "{\"give\":\"pg-7\",\"dex\":21,\"level\":10,\"source\":\"gift\"}"));
			helper.assertTrue(pcCount(player) == 1, "a repeated give id added a second Pokémon to the PC");
			for (Pokemon pokemon : party) {
				helper.assertFalse(Gen1Mons.moveNumbers(pokemon).isEmpty(), "a gift without moves got none");
			}

			// A trade keeps its NPC trainer.
			relay.receive(player, envelope(10, MsgTypes.PARTY_GIVE,
					"{\"give\":\"pg-8\",\"dex\":1,\"level\":10,\"ot\":\"TESTOT\",\"source\":\"trade\"}"));
			Pokemon traded = null;
			for (Pokemon pokemon : PlayerExtensionsKt.pc(player)) {
				if (pokemon.getSpecies().getNationalPokedexNumber() == 1) {
					traded = pokemon;
				}
			}
			helper.assertTrue(traded != null, "the traded Pokémon isn't in the PC");
			helper.assertTrue(traded.getOriginalTrainerType() == OriginalTrainerType.NPC, "a traded Pokémon keeps its NPC trainer");
			helper.assertTrue(sent.stream().noneMatch(message -> message.type().equals(MsgTypes.ERROR)), "a valid give was refused");
		}));
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 100)
	public void thePartyIsSyncedToTheClient(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.join(helper);
		RelayTap.Recorder relay = RelayTap.open(player);
		List<Pokemon> given = new ArrayList<>();
		try {
			// Cobblemon synchronised the mock player's data on joining: the party is followed (and the starter locked).
			helper.assertTrue(PartyService.watching(player), "the party isn't followed after Cobblemon's data sync");
			PlayerPartyStore party = PlayerExtensionsKt.party(player);
			given.add(Gen1Mons.gift(new Gen1Mon(25, 12, List.of(84, 45, 86), new Dvs(15, 14, 13, 12))));
			given.add(Gen1Mons.gift(new Gen1Mon(1, 7, List.of(33), null)));
			for (Pokemon pokemon : given) {
				party.add(pokemon);
			}
			given.get(0).setCurrentHealth(given.get(0).getMaxHealth() - 3);
			given.get(1).setNickname(Component.literal("BUD"));
			PCStore pc = PlayerExtensionsKt.pc(player);
			pc.add(Gen1Mons.gift(new Gen1Mon(10, 3, List.of(33), null)));
		} catch (Gen1Mons.Missing | RuntimeException e) {
			relay.close();
			MockPlayers.leave(helper, player);
			throw e instanceof RuntimeException runtime ? runtime : new GameTestAssertException("Cobblemon lacks a species or move");
		}
		helper.succeedWhen(() -> {
			List<Envelope> syncs = relay.of(MsgTypes.PARTY_SYNC);
			helper.assertTrue(!syncs.isEmpty(), "no party.sync yet");
			JsonObject data = syncs.get(syncs.size() - 1).data();
			JsonArray list = data.getAsJsonArray("party");
			helper.assertTrue(list.size() == 2, "the sync lists " + list.size() + " Pokémon");
			for (int i = 0; i < 2; i++) {
				JsonObject entry = list.get(i).getAsJsonObject();
				Pokemon pokemon = given.get(i);
				helper.assertTrue(entry.get("uid").getAsString().equals(pokemon.getUuid().toString()), "uid of slot " + i);
				helper.assertTrue(entry.get("dex").getAsInt() == pokemon.getSpecies().getNationalPokedexNumber(), "dex of slot " + i);
				helper.assertTrue(entry.get("level").getAsInt() == pokemon.getLevel(), "level of slot " + i);
				List<Integer> moves = new ArrayList<>();
				entry.getAsJsonArray("moves").forEach(move -> moves.add(move.getAsInt()));
				helper.assertTrue(moves.equals(Gen1Mons.moveNumbers(pokemon)), "moves of slot " + i);
				helper.assertTrue(entry.get("hp").getAsInt() == pokemon.getCurrentHealth(), "hp of slot " + i);
				helper.assertTrue(entry.get("maxhp").getAsInt() == pokemon.getMaxHealth(), "maxhp of slot " + i);
			}
			JsonObject first = list.get(0).getAsJsonObject();
			helper.assertTrue(first.get("hp").getAsInt() == first.get("maxhp").getAsInt() - 3, "the damage isn't in the sync");
			helper.assertFalse(first.has("nick") || first.has("status"), "no nickname or status on the first");
			helper.assertTrue(list.get(1).getAsJsonObject().get("nick").getAsString().equals("BUD"), "the nickname isn't in the sync");
			helper.assertTrue(data.getAsJsonObject("box").get("count").getAsInt() == 1, "the PC holds one Pokémon");
			helper.assertTrue(syncs.size() <= 2, "syncs aren't debounced: " + syncs.size());
			relay.close();
			MockPlayers.leave(helper, player);
		});
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void healNameRaterAndTrades(GameTestHelper helper) {
		RelayService relay = RelayService.get();
		MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
			PlayerPartyStore party = PlayerExtensionsKt.party(player);
			relay.receive(player, envelope(1, MsgTypes.PARTY_GIVE, "{\"give\":\"ph-1\",\"dex\":4,\"level\":8,\"source\":\"gift\"}"));
			relay.receive(player, envelope(2, MsgTypes.PARTY_GIVE, "{\"give\":\"ph-2\",\"dex\":19,\"level\":4,\"source\":\"gift\"}"));
			Pokemon first = party.get(0);
			Pokemon second = party.get(1);
			helper.assertTrue(first != null && second != null, "the gifts are missing");

			first.setCurrentHealth(1);
			relay.receive(player, envelope(3, MsgTypes.PARTY_HEAL, "{}"));
			helper.assertTrue(first.getCurrentHealth() == first.getMaxHealth(), "party.heal didn't heal");

			// The Name Rater renames; an empty name clears the nickname.
			relay.receive(player, envelope(4, MsgTypes.PARTY_RENAME, "{\"uid\":\"" + first.getUuid() + "\",\"nick\":\"BLAZE\"}"));
			helper.assertTrue(first.getNickname() != null && first.getNickname().getString().equals("BLAZE"), "party.rename");
			relay.receive(player, envelope(5, MsgTypes.PARTY_RENAME, "{\"uid\":\"" + first.getUuid() + "\",\"nick\":\"\"}"));
			helper.assertTrue(first.getNickname() == null, "an empty name must clear the nickname");
			// Cobblemon's own rename screen is cancelled (P16).
			PokemonNicknamedEvent rename = new PokemonNicknamedEvent(player, first, Component.literal("NOPE"));
			CobblemonEvents.POKEMON_NICKNAMED.post(rename);
			helper.assertTrue(rename.isCanceled(), "a player rename wasn't cancelled");

			// A trade takes a Pokémon away.
			relay.receive(player, envelope(6, MsgTypes.PARTY_TAKE, "{\"uid\":\"" + second.getUuid() + "\",\"reason\":\"trade\"}"));
			helper.assertTrue(party.occupied() == 1 && party.get(second.getUuid()) == null, "party.take didn't remove the Pokémon");
			long unknown = PartyService.warnings("take_unknown_uid");
			relay.receive(player, envelope(7, MsgTypes.PARTY_TAKE, "{\"uid\":\"" + UUID.randomUUID() + "\",\"reason\":\"trade\"}"));
			helper.assertTrue(PartyService.warnings("take_unknown_uid") == unknown + 1, "an unknown uid wasn't counted");
			helper.assertTrue(party.occupied() == 1, "an unknown uid took something");

			// Malformed requests are refused with an error.
			relay.receive(player, envelope(8, MsgTypes.PARTY_HEAL, "{\"all\":true}"));
			relay.receive(player, envelope(9, MsgTypes.PARTY_RENAME, "{\"uid\":\"not-a-uid\",\"nick\":\"X\"}"));
			relay.receive(player, envelope(10, MsgTypes.PARTY_GIVE, "{\"give\":\"ph-3\",\"dex\":200,\"level\":5,\"source\":\"gift\"}"));
			long errors = sent.stream().filter(message -> message.type().equals(MsgTypes.ERROR)).count();
			helper.assertTrue(errors == 3, "expected 3 errors, got " + errors);
		}));
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void evolutionsBeyond151AreBlocked(GameTestHelper helper) {
		Pokemon eevee;
		try {
			eevee = Gen1Mons.gift(new Gen1Mon(133, 30, List.of(33), null));
		} catch (Gen1Mons.Missing e) {
			throw new GameTestAssertException("Cobblemon lacks Eevee");
		}
		Evolution toEspeon = null;
		Evolution toVaporeon = null;
		for (Evolution evolution : eevee.getSpecies().getEvolutions()) {
			String target = evolution.getResult().getSpecies();
			if (target != null && target.endsWith("espeon")) {
				toEspeon = evolution;
			} else if (target != null && target.endsWith("vaporeon")) {
				toVaporeon = evolution;
			}
		}
		helper.assertTrue(toEspeon != null && toVaporeon != null, "Eevee's evolutions aren't loaded");
		EvolutionTestedEvent espeon = new EvolutionTestedEvent(eevee, toEspeon, true, true);
		EvolutionTestedEvent vaporeon = new EvolutionTestedEvent(eevee, toVaporeon, true, true);
		long blocked = PartyService.evolutionsBlocked();
		CobblemonEvents.EVOLUTION_TESTED.post(espeon);
		CobblemonEvents.EVOLUTION_TESTED.post(vaporeon);
		helper.assertFalse(espeon.getResult(), "Espeon (196) must be blocked");
		helper.assertTrue(vaporeon.getResult(), "Vaporeon (134) must stay possible");
		helper.assertTrue(PartyService.evolutionsBlocked() == blocked + 1, "the block wasn't counted");
		helper.succeed();
	}
}
