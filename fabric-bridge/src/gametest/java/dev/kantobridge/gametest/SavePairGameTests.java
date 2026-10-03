package dev.kantobridge.gametest;

import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import com.google.gson.JsonObject;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.pokemon.DaycareService;
import dev.kantobridge.pokemon.Gen1Mon;
import dev.kantobridge.pokemon.Gen1Mons;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.server.SavePair;
import dev.kantobridge.server.SavePairService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Save pairs on a real server (M10 crash recovery; docs/PROTOCOL.md {@code save.begin.pair}, {@code save.loaded},
 * {@code save.mismatch}): {@code save.begin} puts its pair into the player's data before the save writes it; the hidden
 * game's Pokémon operations count once each; every {@code save.loaded} case gets its {@code save.mismatch}, or nothing;
 * a deposit the hidden save never saw comes back from the daycare, and a withdrawal of a Pokémon already back changes
 * nothing. Made-up Pokémon and made-up save ids only. A batch of its own: saves write the whole test world.
 */
public class SavePairGameTests implements FabricGameTest {
	private static final String BATCH = "kantobridge_save_pairs";
	private static final String PAIR = "0a1b2c3d4e5f6071";
	private static final String OTHER = "f0e1d2c3b4a59687";

	private static String envelope(long seq, String type, String data) {
		return BattleGameTests.envelope(seq, type, data);
	}

	private static void saveBegin(ServerPlayer player, long seq, String save, String pair, long at) {
		RelayService.get().receive(player, envelope(seq, MsgTypes.SAVE_BEGIN,
				"{\"save\":\"" + save + "\",\"reason\":\"menu\",\"pair\":\"" + pair + "\"" + (at < 0 ? "" : ",\"at\":" + at) + "}"));
	}

	private static void loaded(ServerPlayer player, long seq, String data) {
		RelayService.get().receive(player, envelope(seq, MsgTypes.SAVE_LOADED, data));
	}

	private static String loadedData(String pair, long at, int ops) {
		return "{\"fresh\":false,\"pair\":\"" + pair + "\"" + (at < 0 ? "" : ",\"at\":" + at) + ",\"ops\":" + ops + "}";
	}

	private static Pokemon gift(int dex, int level) {
		try {
			return Gen1Mons.gift(new Gen1Mon(dex, level, List.of(33), null));
		} catch (Gen1Mons.Missing e) {
			throw new GameTestAssertException("Cobblemon lacks " + e.code());
		}
	}

	/** The mismatches sent since {@code from} (in the captured list), as kinds. */
	private static List<String> kinds(List<Envelope> sent, int from) {
		return sent.subList(from, sent.size()).stream().filter(message -> message.type().equals(MsgTypes.SAVE_MISMATCH))
				.map(message -> message.data().get("kind").getAsString()).toList();
	}

	private static JsonObject lastMismatch(List<Envelope> sent) {
		Envelope last = null;
		for (Envelope message : sent) {
			if (message.type().equals(MsgTypes.SAVE_MISMATCH)) {
				last = message;
			}
		}
		if (last == null) {
			throw new GameTestAssertException("no save.mismatch");
		}
		return last.data();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 200)
	public void theSavePairIsInThePlayersDataBeforeTheSaveWritesIt(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
			BattleGameTests.partner(player);
			// Operations counted under an older pair: the save must reset them in what it writes, not after.
			saveBegin(player, 1, "sv-old", OTHER, 1_000);
			RelayService.get().receive(player, envelope(2, MsgTypes.PARTY_GIVE, "{\"give\":\"pg-sp-1\",\"dex\":16,\"level\":5,\"source\":\"gift\"}"));
			helper.assertTrue(SavePairService.of(player).ops() == 1, "the gift wasn't counted");

			saveBegin(player, 3, "sv-new", PAIR, 1_790_000_000L);
			SavePair stored = SavePairService.of(player);
			helper.assertTrue(PAIR.equals(stored.pair()) && stored.at() == 1_790_000_000L && stored.ops() == 0, "the pair after the save");
			CompoundTag written = savedPair(server, player.getUUID());
			helper.assertTrue(PAIR.equals(written.getString("pair")), "the player data the save wrote has another pair");
			helper.assertTrue(written.getLong("at") == 1_790_000_000L && written.getInt("ops") == 0, "the written pair's time or count");
			helper.assertTrue(sent.stream().filter(message -> message.type().equals(MsgTypes.SAVE_DONE)).allMatch(
					message -> message.data().get("ok").getAsBoolean()), "a save failed");

			// The same pair again (a repeated save.begin) keeps the count; the attachment survives a respawn's copy.
			RelayService.get().receive(player, envelope(4, MsgTypes.PARTY_GIVE, "{\"give\":\"pg-sp-2\",\"dex\":19,\"level\":5,\"source\":\"gift\"}"));
			saveBegin(player, 5, "sv-new", PAIR, 1_790_000_000L);
			helper.assertTrue(SavePairService.of(player).ops() == 1, "a repeated save.begin reset the count");
			helper.assertTrue(SavePair.TYPE.isPersistent(), "the pair is written with the player");
		}));
		helper.succeed();
	}

	/** The save pair in the player's data file, as the last save wrote it. */
	private static CompoundTag savedPair(MinecraftServer server, UUID player) {
		Path file = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(player + ".dat");
		CompoundTag root;
		try {
			root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
		} catch (IOException e) {
			throw new GameTestAssertException("the player's data wasn't written: " + e.getClass().getSimpleName());
		}
		CompoundTag attachments = root.getCompound(AttachmentTarget.NBT_ATTACHMENT_KEY);
		String key = SavePair.TYPE.identifier().toString();
		if (!attachments.contains(key)) {
			throw new GameTestAssertException("no save pair in the player's data");
		}
		return attachments.getCompound(key);
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 200)
	public void everySaveLoadedCaseGetsItsMismatchOrNothing(GameTestHelper helper) {
		MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
			long seq = 1;
			// No pair stored yet: nothing to compare, whatever the hidden game runs.
			loaded(player, seq++, loadedData(PAIR, 900, 3));
			loaded(player, seq++, "{\"fresh\":true,\"ops\":0}");
			helper.assertTrue(kinds(sent, 0).isEmpty(), "a world that never saved warned " + kinds(sent, 0));

			saveBegin(player, seq++, "sv-1", PAIR, 1_000);
			int mark = sent.size();
			loaded(player, seq++, loadedData(PAIR, 1_000, 0));
			helper.assertTrue(kinds(sent, mark).isEmpty(), "in step, yet warned");

			// Two gifts (one sent twice: a repeat counts once): the world is two operations on.
			RelayService.get().receive(player, envelope(seq++, MsgTypes.PARTY_GIVE, "{\"give\":\"pg-m-1\",\"dex\":16,\"level\":5,\"source\":\"gift\"}"));
			RelayService.get().receive(player, envelope(seq++, MsgTypes.PARTY_GIVE, "{\"give\":\"pg-m-1\",\"dex\":16,\"level\":5,\"source\":\"gift\"}"));
			RelayService.get().receive(player, envelope(seq++, MsgTypes.PARTY_GIVE, "{\"give\":\"pg-m-2\",\"dex\":19,\"level\":5,\"source\":\"gift\"}"));
			helper.assertTrue(SavePairService.of(player).ops() == 2, "ops " + SavePairService.of(player).ops() + ", want 2");
			mark = sent.size();
			loaded(player, seq++, loadedData(PAIR, 1_000, 2));
			helper.assertTrue(kinds(sent, mark).isEmpty(), "the hidden game is just as far: in step");

			mark = sent.size();
			loaded(player, seq++, loadedData(PAIR, 1_000, 0));
			helper.assertTrue(kinds(sent, mark).equals(List.of("hidden_behind")), "went back: " + kinds(sent, mark));
			helper.assertTrue(lastMismatch(sent).get("ops").getAsInt() == 2, "the two gifts still here");

			mark = sent.size();
			loaded(player, seq++, loadedData(PAIR, 1_000, 5));
			helper.assertTrue(kinds(sent, mark).equals(List.of("world_behind")), "Minecraft behind: " + kinds(sent, mark));
			helper.assertTrue(lastMismatch(sent).get("ops").getAsInt() == 3, "three operations missing here");

			mark = sent.size();
			loaded(player, seq++, loadedData(OTHER, 500, 0));
			JsonObject older = lastMismatch(sent);
			helper.assertTrue(kinds(sent, mark).equals(List.of("hidden_older")) && older.get("hiddenAt").getAsLong() == 500
					&& older.get("worldAt").getAsLong() == 1_000 && !older.has("ops"), "an older save");
			mark = sent.size();
			loaded(player, seq++, loadedData(OTHER, 2_000, 0));
			helper.assertTrue(kinds(sent, mark).equals(List.of("hidden_newer")), "a newer save");
			mark = sent.size();
			loaded(player, seq++, loadedData(OTHER, -1, 0));
			helper.assertTrue(kinds(sent, mark).equals(List.of("other")), "a save of unknown age");
			mark = sent.size();
			loaded(player, seq++, "{\"fresh\":true,\"ops\":1}");
			helper.assertTrue(kinds(sent, mark).equals(List.of("new_game")), "a New Game");
			helper.assertTrue(SavePairService.of(player).ops() == 2 && PAIR.equals(SavePairService.of(player).pair()),
					"a save.loaded changed the world's pair");

			// Malformed: an error each, nothing compared.
			mark = sent.size();
			loaded(player, seq++, "{\"fresh\":false,\"ops\":0}");
			loaded(player, seq++, "{\"fresh\":false,\"pair\":\"" + PAIR + "\",\"ops\":-1}");
			loaded(player, seq++, "{\"fresh\":false,\"pair\":\"" + PAIR + "\",\"ops\":0,\"extra\":1}");
			loaded(player, seq++, "{\"fresh\":false,\"pair\":\"" + PAIR + "\",\"ops\":0,\"daycare\":\"not-a-uid\"}");
			List<Envelope> answers = sent.subList(mark, sent.size());
			helper.assertTrue(answers.size() == 4 && answers.stream().allMatch(message -> message.type().equals(MsgTypes.ERROR)),
					"malformed save.loaded: " + answers.stream().map(Envelope::type).toList());
		}));
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 200)
	public void operationsCountOnceAndASaveStartsOver(GameTestHelper helper) {
		MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
			PlayerPartyStore party = PlayerExtensionsKt.party(player);
			Pokemon boarded = gift(54, 20);
			party.add(boarded);
			UUID uid = boarded.getUuid();
			saveBegin(player, 1, "sv-ops", PAIR, 1_000);
			RelayService.get().receive(player, envelope(2, MsgTypes.PARTY_GIVE, "{\"give\":\"pg-o-1\",\"dex\":16,\"level\":5,\"source\":\"gift\"}"));
			RelayService.get().receive(player, envelope(3, MsgTypes.PARTY_GIVE, "{\"give\":\"pg-o-1\",\"dex\":16,\"level\":5,\"source\":\"gift\"}"));
			helper.assertTrue(SavePairService.of(player).ops() == 1, "a repeated gift counted");
			Pokemon given = null;
			for (Pokemon pokemon : party) {
				if (pokemon.getSpecies().getNationalPokedexNumber() == 16) {
					given = pokemon;
				}
			}
			helper.assertTrue(given != null, "the gift isn't in the party");
			String take = "{\"uid\":\"" + given.getUuid() + "\",\"reason\":\"trade\"}";
			RelayService.get().receive(player, envelope(4, MsgTypes.PARTY_TAKE, take));
			RelayService.get().receive(player, envelope(5, MsgTypes.PARTY_TAKE, take));
			helper.assertTrue(SavePairService.of(player).ops() == 2, "a repeated take counted");
			RelayService.get().receive(player, envelope(6, MsgTypes.DAYCARE_DEPOSIT, "{\"uid\":\"" + uid + "\"}"));
			RelayService.get().receive(player, envelope(7, MsgTypes.DAYCARE_DEPOSIT, "{\"uid\":\"" + uid + "\"}"));
			helper.assertTrue(SavePairService.of(player).ops() == 3, "a repeated deposit counted");
			RelayService.get().receive(player, envelope(8, MsgTypes.DAYCARE_WITHDRAW, "{\"uid\":\"" + uid + "\",\"steps\":5}"));
			RelayService.get().receive(player, envelope(9, MsgTypes.DAYCARE_WITHDRAW, "{\"uid\":\"" + uid + "\",\"steps\":5}"));
			helper.assertTrue(SavePairService.of(player).ops() == 4, "a repeated withdrawal counted");
			// Boarding it again is a new operation (deposit, withdraw and deposit alternate).
			RelayService.get().receive(player, envelope(10, MsgTypes.PARTY_TAKE, "{\"uid\":\"" + uid + "\",\"reason\":\"daycare\"}"));
			helper.assertTrue(SavePairService.of(player).ops() == 5, "a second boarding wasn't counted");

			// A save starts the count over; repeated saves of the same pair don't.
			saveBegin(player, 11, "sv-ops-2", OTHER, 2_000);
			helper.assertTrue(OTHER.equals(SavePairService.of(player).pair()) && SavePairService.of(player).ops() == 0, "the new pair");
			RelayService.get().receive(player, envelope(12, MsgTypes.DAYCARE_WITHDRAW, "{\"uid\":\"" + uid + "\",\"steps\":0}"));
			saveBegin(player, 13, "sv-ops-2b", OTHER, 2_000);
			helper.assertTrue(SavePairService.of(player).ops() == 1, "the same pair saved again reset the count");
		}));
		helper.succeed();
	}

	/**
	 * PROGRESS's daycare stranding (from M8): a Pokémon deposited after the hidden game's last save, and Gen1Recomp closed
	 * without saving. The hidden save still has it in its party, the server in its daycare; {@code save.loaded} (no
	 * {@code daycare}) brings it back. A later withdrawal of it changes nothing, and a Pokémon the hidden daycare does board
	 * stays boarded.
	 */
	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 200)
	public void aDepositTheHiddenSaveNeverSawComesBack(GameTestHelper helper) {
		MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
			PlayerPartyStore party = PlayerExtensionsKt.party(player);
			Pokemon stranded = gift(54, 20);
			Pokemon boarded = gift(74, 12);
			party.add(stranded);
			party.add(boarded);
			UUID strandedUid = stranded.getUuid();
			UUID boardedUid = boarded.getUuid();
			saveBegin(player, 1, "sv-dc", PAIR, 1_000);
			RelayService.get().receive(player, envelope(2, MsgTypes.DAYCARE_DEPOSIT, "{\"uid\":\"" + boardedUid + "\"}"));
			RelayService.get().receive(player, envelope(3, MsgTypes.DAYCARE_DEPOSIT, "{\"uid\":\"" + strandedUid + "\"}"));
			helper.assertTrue(DaycareService.held(player).equals(Set.of(strandedUid, boardedUid)), "both boarded");
			helper.assertTrue(party.occupied() == 0, "the party should be empty");

			// Gen1Recomp comes back from a save whose daycare holds only the first one.
			long returned = DaycareService.returned();
			loaded(player, 4, "{\"fresh\":false,\"pair\":\"" + PAIR + "\",\"ops\":1,\"daycare\":\"" + boardedUid + "\"}");
			helper.assertTrue(DaycareService.returned() == returned + 1, "nothing came back");
			helper.assertTrue(DaycareService.held(player).equals(Set.of(boardedUid)), "the daycare should hold only the hidden one");
			Pokemon back = party.get(strandedUid);
			helper.assertTrue(back != null && party.occupied() == 1, "the stranded Pokémon isn't back in the party");
			helper.assertTrue(back.getSpecies().getNationalPokedexNumber() == 54 && back.getLevel() == 20, "not the same Pokémon");
			helper.assertTrue(kinds(sent, 0).equals(List.of("hidden_behind")), "the one operation since the save: " + kinds(sent, 0));

			// The hidden game (on its older save) withdraws it again: nothing changes, never a second one.
			int experience = back.getExperience();
			RelayService.get().receive(player, envelope(5, MsgTypes.DAYCARE_WITHDRAW, "{\"uid\":\"" + strandedUid + "\",\"steps\":300}"));
			helper.assertTrue(party.occupied() == 1 && party.get(strandedUid) == back, "a withdrawal of a Pokémon already back made another");
			helper.assertTrue(back.getExperience() == experience, "it gained experience it never earned");
			helper.assertTrue(PlayerExtensionsKt.pc(player).get(strandedUid) == null, "a copy went to the PC");

			// A stale entry for a Pokémon that is in the party too only loses the entry.
			JsonObject copy = boarded.saveToJSON(player.registryAccess(), new JsonObject());
			party.add(Pokemon.Companion.loadFromJSON(player.registryAccess(), copy));
			loaded(player, 6, "{\"fresh\":false,\"pair\":\"" + PAIR + "\",\"ops\":2}");
			helper.assertTrue(DaycareService.held(player).isEmpty(), "the stale entry stayed");
			int copies = 0;
			for (Pokemon pokemon : party) {
				if (pokemon.getUuid().equals(boardedUid)) {
					copies++;
				}
			}
			helper.assertTrue(copies == 1 && PlayerExtensionsKt.pc(player).get(boardedUid) == null, copies + " copies of one Pokémon");
			helper.assertTrue(sent.stream().noneMatch(message -> message.type().equals(MsgTypes.ERROR)), "a valid message was refused");
			helper.assertTrue(Objects.equals(party.occupied(), 2), "the party should hold both now");
		}));
		helper.succeed();
	}
}
