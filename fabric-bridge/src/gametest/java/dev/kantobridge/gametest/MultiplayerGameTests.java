package dev.kantobridge.gametest;

import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.plan.ArtCatalog;
import dev.kantobridge.builder.plan.PlanException;
import dev.kantobridge.builder.plan.Warnings;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.builder.plan.WorldPlanner;
import dev.kantobridge.builder.shape.ShapePlan;
import dev.kantobridge.battle.BattleService;
import dev.kantobridge.net.RelayHandshake;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.pokemon.DaycareService;
import dev.kantobridge.pokemon.Gen1Mon;
import dev.kantobridge.pokemon.Gen1Mons;
import dev.kantobridge.pokemon.PartyService;
import dev.kantobridge.pokemon.PcService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.server.SavePair;
import dev.kantobridge.server.SavePairService;
import dev.kantobridge.server.SessionService;
import dev.kantobridge.server.TextureStatusService;
import dev.kantobridge.server.WorldIndexSync;
import dev.kantobridge.story.Activity;
import dev.kantobridge.story.StoryState;
import dev.kantobridge.story.StoryStates;
import dev.kantobridge.story.Surfing;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.ShapeDocs;
import dev.kantobridge.world.ShapeMap;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.WorldIndex;
import dev.kantobridge.world.WorldIndexParts;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Two players in one world (docs/PLAN.md §6 M9; DESIGN §8 "Multiplayer: separate stories"), as far as mock players
 * allow: relay authority (no relay message from one player's client changes another player's state, whatever ids it
 * names), the world's slot map sent with its index so a joining player can build their own pack against it, each
 * player's activity kept for their own speech bubble and synced to everyone else, and each player's texture status.
 * Batches of their own: they publish WorldIndexes and span ticks.
 */
public class MultiplayerGameTests implements FabricGameTest {
	private static final String BATCH_AUTHORITY = "kantobridge_mp_authority";
	private static final String BATCH_SLOTS = "kantobridge_mp_slots";
	private static final String BATCH_PLAYERS = "kantobridge_mp_players";
	private static final String SYNTHETIC = "kantobridge/synthetic_bundle/red/";
	/** A story cell of the synthetic town (an object that stands in the hidden game). */
	private static final String CELL = "TEST_TOWN:15:4";
	/** Made-up save pairs (M10). */
	private static final String VICTIM_PAIR = "0a1b2c3d4e5f6071";
	private static final String OTHER_PAIR = "f0e1d2c3b4a59687";

	static String envelope(long seq, String type, String data) {
		return BattleGameTests.envelope(seq, type, data);
	}

	private static Pokemon gift(int dex, int level) {
		try {
			return Gen1Mons.gift(new Gen1Mon(dex, level, List.of(33), null));
		} catch (Gen1Mons.Missing e) {
			throw new GameTestAssertException("Cobblemon lacks " + e.code());
		}
	}

	/** Everything a relay message could change about a player, as plain values (equal when unchanged). */
	private record Fingerprint(Set<String> active, Set<String> boulders, Set<String> lit, boolean surf, boolean bike, Activity activity,
			TextureStatusService.Status texture, Integer slot, int balls, Set<UUID> daycare, String party, Vec3 position, boolean inBattle,
			boolean pcOpen, SavePair savePair, boolean surfing, boolean relayAgreed) {
		static Fingerprint of(ServerPlayer player) {
			StoryState state = StoryStates.of(player);
			return new Fingerprint(new TreeSet<>(state.active()), new TreeSet<>(state.boulders()), new TreeSet<>(state.lit()), state.surf(),
					state.bike(), Activity.of(player), TextureStatusService.of(player), SessionService.slotOf(player.server, player.getUUID()),
					BagGameTests.count(player, "poke_ball"), Set.copyOf(DaycareService.held(player)), ProtoJson.write(PartyService.snapshot(player)),
					player.position(), BattleRegistry.getBattleByParticipatingPlayer(player) != null || BattleService.battleOf(player) != null,
					PcService.isOpen(player), SavePairService.of(player), Surfing.of(player), RelayHandshake.agreed(player));
		}

		/** Counts, flags and short hashes, for a failure message. */
		String summary() {
			return "active=" + this.active.size() + " boulders=" + this.boulders.size() + " lit=" + this.lit.size() + " surf=" + this.surf
					+ " bike=" + this.bike + " activity=" + this.activity + " texture=" + (this.texture == null ? "-" : this.texture.ok()) + " slot="
					+ this.slot + " balls=" + this.balls + " daycare=" + this.daycare.size() + " party="
					+ dev.kantobridge.world.Hashes.shortHash(dev.kantobridge.world.Hashes.sha256(this.party)) + " battle=" + this.inBattle + " pc="
					+ this.pcOpen + " pairOps=" + this.savePair.ops() + " surfing=" + this.surfing + " relay=" + this.relayAgreed;
		}
	}

	// ---- relay authority ----

	/**
	 * Every client-to-server type, sent by one player's client and aimed at the other player's state where a message can
	 * name anything (their Pokémon's uids, their boarded Pokémon, their save id, their story cell): the sender's own state
	 * changes, the other player's stays exactly as it was, and nothing at all is sent to them.
	 */
	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH_AUTHORITY, timeoutTicks = 400)
	public void aPlayerCantChangeAnotherPlayersStateThroughTheRelay(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		WorldIndex index = WarpGameTests.publishSyntheticIndex(helper);
		ServerPlayer victim = MockPlayers.join(helper);
		ServerPlayer other = MockPlayers.join(helper);
		RelayTap.Recorder victimSees = RelayTap.open(victim);
		RelayTap.Recorder otherSees = RelayTap.open(other);
		RelayService relay = RelayService.get();
		Pokemon kept = gift(7, 15);
		Pokemon boarded = gift(54, 20);
		Fingerprint[] before = new Fingerprint[1];
		int[] seenBefore = new int[1];
		Runnable cleanUp = () -> {
			victimSees.close();
			otherSees.close();
			PcService.close(other);
			WorldIndexSync.withdraw(server);
			MockPlayers.leave(helper, victim);
			MockPlayers.leave(helper, other);
		};
		try {
			// The victim's own state, set by its own client's messages.
			PlayerExtensionsKt.party(victim).add(kept);
			PlayerExtensionsKt.party(victim).add(boarded);
			kept.setCurrentHealth(kept.getMaxHealth() - 3);
			relay.receive(victim, envelope(1, MsgTypes.STORY_STATE, "{\"full\":true,\"active\":[\"" + CELL + "\"],\"surf\":true,\"bike\":false,\"lit\":[\"TEST_HOUSE\"]}"));
			relay.receive(victim, envelope(2, MsgTypes.ACTIVITY, "{\"activity\":\"battle\"}"));
			relay.receive(victim, envelope(3, MsgTypes.TEXTURE_STATUS, "{\"ok\":true,\"slotMapHash\":\"" + index.slotMapHash() + "\",\"textures\":19}"));
			relay.receive(victim, envelope(4, MsgTypes.SESSION_BOUND, "{\"slot\":3}"));
			relay.receive(victim, envelope(5, MsgTypes.BAG_SYNC, "{\"items\":{\"POKE_BALL\":2}}"));
			relay.receive(victim, envelope(6, MsgTypes.DAYCARE_DEPOSIT, "{\"uid\":\"" + boarded.getUuid() + "\"}"));
			relay.receive(victim, envelope(7, MsgTypes.SAVE_BEGIN, "{\"save\":\"sv-shared\",\"reason\":\"menu\",\"pair\":\"" + VICTIM_PAIR + "\"}"));
			relay.receive(victim, envelope(8, MsgTypes.SAVE_LOADED, "{\"fresh\":false,\"pair\":\"" + VICTIM_PAIR + "\",\"ops\":0,\"daycare\":\""
					+ boarded.getUuid() + "\"}"));
			relay.receive(victim, envelope(9, MsgTypes.RELAY_HELLO, "{\"protocol\":" + RelayHandshake.protocol() + ",\"mod\":\"0.1.0\"}"));
			helper.assertTrue(DaycareService.held(victim).equals(Set.of(boarded.getUuid())), "the victim's own deposit failed");
			helper.assertTrue(Objects.equals(SessionService.slotOf(server, victim.getUUID()), 3), "the victim's own pairing failed");
			helper.assertTrue(VICTIM_PAIR.equals(SavePairService.of(victim).pair()), "the victim's own save pair");
			helper.assertTrue(Surfing.of(victim) && RelayHandshake.agreed(victim), "the victim's own surf flag and relay hello");
		} catch (RuntimeException e) {
			cleanUp.run();
			throw e;
		}
		helper.startSequence()
				// The victim's own syncs (party, Pokédex, save) go out first; then nothing more should reach them.
				.thenIdle(40)
				.thenExecute(() -> {
					before[0] = Fingerprint.of(victim);
					seenBefore[0] = victimSees.sent.size();
					String uid = kept.getUuid().toString();
					long seq = 100;
					// Story: the other player's own state, whatever cells it names.
					relay.receive(other, envelope(seq++, MsgTypes.STORY_STATE, "{\"full\":true,\"active\":[\"" + CELL + "\",\"TEST_TOWN:10:14\"],"
							+ "\"surf\":false,\"bike\":true,\"lit\":[]}"));
					relay.receive(other, envelope(seq++, MsgTypes.STORY_STATE, "{\"full\":false,\"remove\":[\"" + CELL + "\"],\"surf\":true}"));
					// Warps move the sender only.
					relay.receive(other, envelope(seq++, MsgTypes.WARP_REQUEST, "{\"to\":{\"map\":\"TEST_HOUSE\",\"x\":4,\"y\":8,\"facing\":\"up\"},\"kind\":\"door\"}"));
					// A battle for the sender (no Pokémon: aborted, to the sender).
					relay.receive(other, envelope(seq++, MsgTypes.BATTLE_START, BattleGameTests.start("bt-mp", "trainer", BattleGameTests.TRAINER,
							BattleGameTests.TEAM)));
					// Party requests naming the victim's Pokémon find nothing in the sender's party and PC.
					relay.receive(other, envelope(seq++, MsgTypes.PARTY_TAKE, "{\"uid\":\"" + uid + "\",\"reason\":\"trade\"}"));
					relay.receive(other, envelope(seq++, MsgTypes.PARTY_RENAME, "{\"uid\":\"" + uid + "\",\"nick\":\"MINE\"}"));
					relay.receive(other, envelope(seq++, MsgTypes.PARTY_ITEM, "{\"item\":\"RARE_CANDY\",\"uid\":\"" + uid + "\"}"));
					relay.receive(other, envelope(seq++, MsgTypes.PARTY_TAKE, "{\"uid\":\"" + uid + "\",\"reason\":\"daycare\"}"));
					relay.receive(other, envelope(seq++, MsgTypes.DAYCARE_DEPOSIT, "{\"uid\":\"" + uid + "\"}"));
					relay.receive(other, envelope(seq++, MsgTypes.DAYCARE_WITHDRAW, "{\"uid\":\"" + boarded.getUuid() + "\",\"steps\":500}"));
					relay.receive(other, envelope(seq++, MsgTypes.PARTY_HEAL, "{}"));
					relay.receive(other, envelope(seq++, MsgTypes.PARTY_GIVE, "{\"give\":\"pg-mp\",\"dex\":1,\"level\":5,\"source\":\"gift\"}"));
					// The bag, the PC, a save with the victim's save id, the activity, the pack, the save pairing.
					relay.receive(other, envelope(seq++, MsgTypes.BAG_SYNC, "{\"items\":{\"POKE_BALL\":9,\"POTION\":1}}"));
					relay.receive(other, envelope(seq++, MsgTypes.PC_OPEN, "{\"pc\":\"pc-mp\"}"));
					relay.receive(other, envelope(seq++, MsgTypes.SAVE_BEGIN, "{\"save\":\"sv-shared\",\"reason\":\"menu\",\"pair\":\"" + OTHER_PAIR
							+ "\"}"));
					// M10: which save the sender's hidden game runs (its daycare holds nothing: the victim's boarded Pokémon is
					// none of its business), and the relay's hello.
					relay.receive(other, envelope(seq++, MsgTypes.SAVE_LOADED, "{\"fresh\":false,\"pair\":\"" + VICTIM_PAIR + "\",\"ops\":7}"));
					relay.receive(other, envelope(seq++, MsgTypes.RELAY_HELLO, "{\"protocol\":" + RelayHandshake.protocol() + ",\"mod\":\"0.1.0\"}"));
					relay.receive(other, envelope(seq++, MsgTypes.ACTIVITY, "{\"activity\":\"dialogue\"}"));
					relay.receive(other, envelope(seq++, MsgTypes.TEXTURE_STATUS, "{\"ok\":false,\"reason\":\"no_bridge\"}"));
					relay.receive(other, envelope(seq++, MsgTypes.SESSION_BOUND, "{\"slot\":9}"));
					relay.receive(other, envelope(seq, MsgTypes.ERROR, "{\"code\":\"x\",\"msg\":\"y\"}"));
				})
				.thenIdle(30)
				.thenExecute(() -> {
					// Each message did its job, for its sender.
					StoryState mine = StoryStates.of(other);
					helper.assertTrue(mine.isActive("TEST_TOWN:10:14") && !mine.isActive(CELL) && mine.surf() && mine.bike(), "the sender's story state");
					helper.assertTrue(other.position().distanceTo(index.toBlock("TEST_HOUSE", 4, 8)) < 1.0E-6, "the sender didn't warp");
					helper.assertTrue(otherSees.of(MsgTypes.BATTLE_RESULT).size() == 1 && otherSees.of(MsgTypes.WARP_DONE).size() == 1
							&& otherSees.of(MsgTypes.SAVE_DONE).size() == 1, "the sender's answers");
					helper.assertTrue(PlayerExtensionsKt.party(other).occupied() == 1, "the sender's gift");
					helper.assertTrue(BagGameTests.count(other, "poke_ball") == 9, "the sender's bag");
					helper.assertTrue(PcService.isOpen(other), "the sender's PC");
					helper.assertTrue(Activity.of(other) == Activity.DIALOGUE, "the sender's activity");
					TextureStatusService.Status texture = TextureStatusService.of(other);
					helper.assertTrue(texture != null && !texture.ok() && "no_bridge".equals(texture.reason()), "the sender's texture status");
					helper.assertTrue(Objects.equals(SessionService.slotOf(server, other.getUUID()), 9), "the sender's pairing");
					helper.assertTrue(OTHER_PAIR.equals(SavePairService.of(other).pair()), "the sender's save pair");
					helper.assertTrue(otherSees.of(MsgTypes.SAVE_MISMATCH).size() == 1, "the sender's save.mismatch");
					helper.assertTrue(Surfing.of(other) && RelayHandshake.agreed(other), "the sender's surf flag and relay hello");

					// And the victim: exactly as before, and told nothing.
					Fingerprint after = Fingerprint.of(victim);
					helper.assertTrue(after.equals(before[0]), "the victim's state changed: before " + before[0].summary() + ", after " + after.summary());
					helper.assertTrue(kept.getCurrentHealth() == kept.getMaxHealth() - 3 && kept.getNickname() == null, "the victim's Pokémon changed");
					helper.assertTrue(PlayerExtensionsKt.party(victim).get(kept.getUuid()) == kept, "the victim's Pokémon left their party");
					List<String> told = new ArrayList<>();
					victimSees.sent.subList(seenBefore[0], victimSees.sent.size()).forEach(message -> told.add(message.type()));
					helper.assertTrue(told.isEmpty(), "the victim was sent " + told + " because of another player's messages");
				})
				.thenExecute(cleanUp)
				.thenSucceed();
	}

	// ---- world.slots ----

	private static ExportBundle syntheticBundle() {
		try {
			return ExportBundle.fromClasspath(MultiplayerGameTests.class.getClassLoader(), SYNTHETIC);
		} catch (BundleException e) {
			throw new GameTestAssertException("synthetic bundle unreadable: " + e.code());
		}
	}

	/** The synthetic world's slot map: the one its golden WorldIndex names. */
	static SlotMap syntheticSlots() {
		try {
			return ArtCatalog.of(syntheticBundle(), new Warnings()).slotMap();
		} catch (BundleException | PlanException e) {
			throw new GameTestAssertException("synthetic slot map: " + e.getClass().getSimpleName());
		}
	}

	private static SlotMap reassemble(GameTestHelper helper, List<Envelope> parts) {
		try {
			return SlotMap.fromJson(reassembleText(helper, parts, "world.slots"));
		} catch (DataException e) {
			throw new GameTestAssertException("world.slots parts rejected: " + e.problem());
		}
	}

	/** A document's text from its parts: deflated ones ({@code enc:"deflate"}, G2G) inflated. */
	static String reassembleText(GameTestHelper helper, List<Envelope> parts, String what) {
		WorldIndexParts.Assembler assembler = new WorldIndexParts.Assembler();
		String text = null;
		try {
			for (Envelope part : parts) {
				text = assembler.accept(part.data().get("index").getAsInt(), part.data().get("count").getAsInt(), part.data().get("hash").getAsString(),
						part.data().get("part").getAsString());
			}
			helper.assertTrue(text != null, "the " + what + " parts don't make a whole document");
			boolean deflated = parts.stream().allMatch(part -> part.data().has("enc") && part.data().get("enc").getAsString().equals(ShapeDocs.ENC_DEFLATE));
			return deflated ? ShapeDocs.unwireText(text, 1 << 26) : text;
		} catch (DataException e) {
			throw new GameTestAssertException(what + " parts rejected: " + e.problem());
		}
	}

	private static long chars(List<WorldIndexParts.Part> parts) {
		return parts.stream().mapToLong(part -> part.text().length()).sum();
	}

	/**
	 * A joining player gets the WorldIndex and then the world's slot map ({@code world.slots}), the one the index names, so
	 * their client can draw its own art into the host's slots; a slot map the index doesn't name is never sent. Since MG9
	 * G2G both are offered by hash ({@code world.index.offer}, {@code world.slots.offer}) and their parts (deflated) go
	 * out when the client asks ({@code world.index.get}, {@code world.slots.get}); asked again at once nothing goes out, a
	 * hash the server doesn't publish gets nothing, a request without one an error.
	 */
	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH_SLOTS)
	public void joiningPlayersGetTheWorldsSlotMapWithItsIndex(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		WorldIndex golden = WarpGameTests.publishSyntheticIndex(helper);
		SlotMap slots = syntheticSlots();
		helper.assertTrue(slots.hash().equals(golden.slotMapHash()), "the synthetic world's index names another slot map");
		try {
			WorldIndexSync.publish(server, golden, slots, false);
			helper.assertTrue(golden.slotMapHash().equals(WorldIndexSync.currentSlotsHash(server)), "the slot map wasn't published");
			MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
				// The join path asks first whether the client has our mod; a mock player's connection (like a vanilla client)
				// registered no channels, so it gets nothing that way.
				helper.assertTrue(WorldIndexSync.send(server, player) == 0 && sent.isEmpty(), "a client without the mod was sent parts");
				int count = WorldIndexSync.sendTo(server, player);
				List<String> types = sent.stream().map(Envelope::type).toList();
				helper.assertTrue(count == 2 && types.equals(List.of(MsgTypes.WORLD_INDEX_OFFER, MsgTypes.WORLD_SLOTS_OFFER)),
						"the index's offer, then the slots': " + types);
				JsonObject indexOffer = sent.get(0).data();
				JsonObject slotsOffer = sent.get(1).data();
				String indexHash = dev.kantobridge.world.Hashes.sha256(golden.toJson());
				helper.assertTrue(indexOffer.get("hash").getAsString().equals(indexHash) && indexHash.equals(WorldIndexSync.currentHash(server))
						&& !indexOffer.has("region"), "the index's offer: its text's hash, untagged");
				helper.assertTrue(indexOffer.get("chars").getAsLong() == chars(WorldIndexSync.currentParts(server)), "the index's wire size");
				helper.assertTrue(slotsOffer.get("hash").getAsString().equals(golden.slotMapHash())
						&& slotsOffer.get("chars").getAsLong() == chars(WorldIndexSync.currentSlotParts(server)), "the slots' offer names the index's map");

				// The client asks for both: deflated parts that make the documents offered.
				int before = sent.size();
				RelayService.get().receive(player, BattleGameTests.envelope(1, MsgTypes.WORLD_INDEX_GET, "{\"hash\":\"" + indexHash + "\"}"));
				List<Envelope> indexMessages = List.copyOf(sent.subList(before, sent.size()));
				helper.assertTrue(indexMessages.size() == WorldIndexSync.currentParts(server).size()
						&& indexMessages.stream().allMatch(m -> m.type().equals(MsgTypes.WORLD_INDEX) && !m.data().has("region")),
						"the index's parts, untagged: " + indexMessages.size());
				helper.assertTrue(reassembleText(helper, indexMessages, "world.index").equals(golden.toJson()), "another index arrived");
				before = sent.size();
				RelayService.get().receive(player, BattleGameTests.envelope(2, MsgTypes.WORLD_SLOTS_GET, "{\"hash\":\"" + golden.slotMapHash() + "\"}"));
				List<Envelope> slotMessages = List.copyOf(sent.subList(before, sent.size()));
				int slotParts = WorldIndexSync.currentSlotParts(server).size();
				helper.assertTrue(slotParts >= 1 && slotMessages.size() == slotParts && slotMessages.stream().allMatch(m -> m.type().equals(MsgTypes.WORLD_SLOTS)),
						"the slots' parts: " + slotMessages.size());
				SlotMap received = reassemble(helper, slotMessages);
				helper.assertTrue(received.hash().equals(golden.slotMapHash()) && received.slots().equals(slots.slots()), "a different slot map arrived");

				// Asked again at once: not sent twice; a document the server doesn't publish: nothing; naming none: an error.
				before = sent.size();
				RelayService.get().receive(player, BattleGameTests.envelope(3, MsgTypes.WORLD_INDEX_GET, "{\"hash\":\"" + indexHash + "\"}"));
				RelayService.get().receive(player, BattleGameTests.envelope(4, MsgTypes.WORLD_SLOTS_GET, "{\"hash\":\"" + golden.slotMapHash() + "\"}"));
				RelayService.get().receive(player, BattleGameTests.envelope(5, MsgTypes.WORLD_INDEX_GET, "{\"hash\":\"" + "0".repeat(64) + "\"}"));
				RelayService.get().receive(player, BattleGameTests.envelope(6, MsgTypes.WORLD_SLOTS_GET, "{\"hash\":\"" + "0".repeat(64) + "\"}"));
				helper.assertTrue(sent.size() == before, "a repeat or an unknown document went out: " + (sent.size() - before));
				RelayService.get().receive(player, BattleGameTests.envelope(7, MsgTypes.WORLD_SLOTS_GET, "{}"));
				RelayService.get().receive(player, BattleGameTests.envelope(8, MsgTypes.WORLD_INDEX_GET, "{\"region\":\"kanto\"}"));
				helper.assertTrue(sent.size() == before + 2 && sent.get(before).type().equals(MsgTypes.ERROR) && sent.get(before + 1).type().equals(MsgTypes.ERROR),
						"requests naming no document");
			}));

			// A slot map the index doesn't name (a stray file, another build's) is left out: clients keep their own packs.
			SlotMap foreign;
			try {
				foreign = SlotMap.enumerate(List.of(new SlotMap.Key("tilesets.other", "TEST_PALETTE", 1, SlotMap.Shape.FULL)));
			} catch (SlotMap.FullException e) {
				throw new GameTestAssertException("a one-key slot map is full");
			}
			WorldIndexSync.publish(server, golden, foreign, false);
			helper.assertTrue(WorldIndexSync.currentSlotsHash(server) == null && WorldIndexSync.currentSlotParts(server).isEmpty(),
					"a foreign slot map was published");
			MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
				WorldIndexSync.sendTo(server, player);
				helper.assertTrue(!sent.isEmpty() && sent.stream().noneMatch(message -> message.type().equals(MsgTypes.WORLD_SLOTS)
						|| message.type().equals(MsgTypes.WORLD_SLOTS_OFFER)), "world.slots went out for a slot map the index doesn't name");
			}));
		} finally {
			WorldIndexSync.withdraw(server);
		}
		helper.succeed();
	}

	// ---- world.shapes ----

	/** The synthetic world's plan at the golden index's origin, so its shape map names the golden WorldIndex. */
	private static ShapePlan syntheticShapes(GameTestHelper helper, WorldIndex golden) {
		try {
			// The JUnit tests' TestData.MIN_BUILD_HEIGHT, which the golden index was planned with.
			WorldPlan plan = WorldPlanner.plan(syntheticBundle(), new WorldPlanner.Options(null, SyntheticBuildGameTests.ORIGIN_X,
					SyntheticBuildGameTests.ORIGIN_Z, -352));
			helper.assertTrue(plan.index().toJson().equals(golden.toJson()), "the plan's index isn't the golden one");
			helper.assertTrue(plan.shapes() != null, "no shapes planned");
			return plan.shapes();
		} catch (BundleException | PlanException e) {
			throw new GameTestAssertException("synthetic plan: " + e.getClass().getSimpleName());
		}
	}

	private static String reassembleDoc(GameTestHelper helper, List<Envelope> parts, String doc) {
		WorldIndexParts.Assembler assembler = new WorldIndexParts.Assembler();
		String text = null;
		try {
			for (Envelope part : parts) {
				if (doc.equals(part.data().get("doc").getAsString())) {
					text = assembler.accept(part.data().get("index").getAsInt(), part.data().get("count").getAsInt(),
							part.data().get("hash").getAsString(), part.data().get("part").getAsString());
				}
			}
		} catch (DataException e) {
			throw new GameTestAssertException("world.shapes " + doc + " parts rejected: " + e.problem());
		}
		helper.assertTrue(text != null, "the world.shapes " + doc + " parts don't make a whole document");
		return text;
	}

	/**
	 * The voxel plan's V2 (docs/specs/VOXEL_BLOCKS_PLAN.md §4.4), on demand since the follow-up's item 4: a joining player
	 * gets the world's shape offer after its slot map (each document by the SHA-256 of what it holds, with its wire size),
	 * and the documents themselves only when its client asks ({@code world.shapes.get}): the shape index (deflated,
	 * {@code enc:"deflate"}) first, then the shape map; the same request again within the resend time sends nothing, a
	 * hash the server doesn't publish nothing, a request naming no document an error. Only a map naming the published
	 * index and that shape index is offered; otherwise clients draw cubes.
	 */
	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH_SLOTS)
	public void joiningPlayersAreOfferedTheWorldsShapesAndGetWhatTheyAskFor(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		WorldIndex golden = WarpGameTests.publishSyntheticIndex(helper);
		SlotMap slots = syntheticSlots();
		ShapePlan shapes = syntheticShapes(helper, golden);
		String shapeIndex = shapes.index().toJson();
		try {
			WorldIndexSync.publish(server, golden, slots, shapeIndex, shapes.map().encode(), false);
			helper.assertTrue(shapes.index().hash().equals(WorldIndexSync.currentShapesHash(server)), "the shapes weren't published");
			MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
				int count = WorldIndexSync.sendTo(server, player);
				helper.assertTrue(count == 3 && sent.size() == count, "messages sent: " + count);
				List<String> types = sent.stream().map(Envelope::type).toList();
				helper.assertTrue(types.equals(List.of(MsgTypes.WORLD_INDEX_OFFER, MsgTypes.WORLD_SLOTS_OFFER, MsgTypes.WORLD_SHAPES_OFFER)),
						"three offers on join, no parts: " + types);
				JsonObject offer = sent.get(count - 1).data();
				List<WorldIndexParts.Part> parts = WorldIndexSync.currentShapeParts(server);
				long chars = parts.stream().mapToLong(part -> part.text().length()).sum();
				helper.assertTrue(offer.getAsJsonObject("index").get("hash").getAsString().equals(shapes.index().hash())
						&& offer.getAsJsonObject("map").get("hash").getAsString().equals(shapes.map().hash()), "the offer names other documents");
				helper.assertTrue(offer.getAsJsonObject("index").get("chars").getAsLong() + offer.getAsJsonObject("map").get("chars").getAsLong()
						== chars, "the offer's sizes aren't the parts'");
				helper.assertTrue(!offer.has("region"), "a legacy world's offer is untagged");

				// The client asks for both: the index's parts (deflated), then the map's.
				int before = sent.size();
				RelayService.get().receive(player, BattleGameTests.envelope(1, MsgTypes.WORLD_SHAPES_GET,
						"{\"index\":\"" + shapes.index().hash() + "\",\"map\":\"" + shapes.map().hash() + "\"}"));
				List<Envelope> shapeMessages = List.copyOf(sent.subList(before, sent.size()));
				helper.assertTrue(shapeMessages.size() == parts.size() && shapeMessages.stream().allMatch(m -> m.type().equals(MsgTypes.WORLD_SHAPES)),
						"the documents' parts: " + shapeMessages.size() + " of " + parts.size());
				List<String> docs = shapeMessages.stream().map(message -> message.data().get("doc").getAsString()).toList();
				helper.assertTrue(docs.indexOf("map") == docs.lastIndexOf("index") + 1, "the shape index comes first, then the map");
				helper.assertTrue(shapeMessages.stream().allMatch(m -> m.data().has("enc") == m.data().get("doc").getAsString().equals("index")),
						"only the index parts carry enc");
				try {
					helper.assertTrue(ShapeDocs.unwireText(reassembleDoc(helper, shapeMessages, "index"), 1 << 26).equals(shapeIndex),
							"another shape index arrived");
					ShapeMap map = ShapeMap.fromWire(reassembleDoc(helper, shapeMessages, "map"), 1 << 24);
					helper.assertTrue(map.hash().equals(shapes.map().hash()), "another shape map arrived");
				} catch (DataException e) {
					throw new GameTestAssertException("a shape document doesn't decode: " + e.problem());
				}
				// Asked again at once: not sent twice; a document the server doesn't publish: nothing; naming none: an error.
				before = sent.size();
				RelayService.get().receive(player, BattleGameTests.envelope(2, MsgTypes.WORLD_SHAPES_GET,
						"{\"map\":\"" + shapes.map().hash() + "\"}"));
				RelayService.get().receive(player, BattleGameTests.envelope(3, MsgTypes.WORLD_SHAPES_GET, "{\"map\":\"" + "0".repeat(64) + "\"}"));
				helper.assertTrue(sent.size() == before, "a repeat or an unknown document went out: " + (sent.size() - before));
				RelayService.get().receive(player, BattleGameTests.envelope(4, MsgTypes.WORLD_SHAPES_GET, "{}"));
				helper.assertTrue(sent.size() == before + 1 && sent.get(before).type().equals(MsgTypes.ERROR), "a request naming no document");
			}));

			// A map bound to another index (or another shape index) stays home.
			WorldIndexSync.publish(server, golden, slots, shapeIndex, shapes.map().withHeader("0".repeat(64), shapes.index().hash()).encode(), false);
			helper.assertTrue(WorldIndexSync.currentShapesHash(server) == null && WorldIndexSync.currentShapeParts(server).isEmpty(),
					"a shape map naming another index was published");
			WorldIndexSync.publish(server, golden, slots, shapeIndex, shapes.map().withHeader(shapes.map().indexHash(), "0".repeat(64)).encode(),
					false);
			helper.assertTrue(WorldIndexSync.currentShapesHash(server) == null, "a shape map naming another shape index was published");
			MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
				WorldIndexSync.sendTo(server, player);
				helper.assertTrue(!sent.isEmpty() && sent.stream().noneMatch(message -> message.type().equals(MsgTypes.WORLD_SHAPES)
						|| message.type().equals(MsgTypes.WORLD_SHAPES_OFFER)), "shapes were offered for a map that doesn't match");
			}));
		} finally {
			WorldIndexSync.withdraw(server);
		}
		helper.succeed();
	}

	// ---- activity and texture status, per player ----

	/**
	 * Each player's activity is their own, and Fabric syncs it to every client but their own (the bubble is for the
	 * others; mock players' connections never take attachment syncs, so the rule itself is checked).
	 */
	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH_PLAYERS)
	public void eachPlayersActivityIsTheirsAndSyncsToTheOthers(GameTestHelper helper) {
		RelayService relay = RelayService.get();
		MockPlayers.with(helper, fighter -> MockPlayers.with(helper, reader -> RelayTap.capture(fighter, sent -> {
			relay.receive(fighter, envelope(1, MsgTypes.ACTIVITY, "{\"activity\":\"battle\"}"));
			relay.receive(reader, envelope(1, MsgTypes.ACTIVITY, "{\"activity\":\"dialogue\"}"));
			helper.assertTrue(Activity.of(fighter) == Activity.BATTLE && Activity.of(reader) == Activity.DIALOGUE, "activities mixed up");
			helper.assertTrue(Activity.TYPE.isSynced(), "the activity isn't synced to clients");
			helper.assertTrue(Activity.SYNC.test(fighter, reader) && Activity.SYNC.test(reader, fighter), "other players' clients must get it");
			helper.assertFalse(Activity.SYNC.test(fighter, fighter), "a player's own client must not get its own bubble");
			relay.receive(fighter, envelope(2, MsgTypes.ACTIVITY, "{\"activity\":\"none\"}"));
			helper.assertTrue(Activity.of(fighter) == Activity.NONE && Activity.of(reader) == Activity.DIALOGUE, "the battle's end changed the other");
			relay.receive(fighter, envelope(3, MsgTypes.ACTIVITY, "{\"activity\":\"dancing\"}"));
			helper.assertTrue(Activity.of(fighter) == Activity.NONE, "a malformed activity was used");
			helper.assertTrue(sent.size() == 1 && sent.get(0).type().equals(MsgTypes.ERROR) && Objects.equals(sent.get(0).re(), 3L),
					"the malformed activity wasn't answered with an error");
		})));
		helper.succeed();
	}

	/**
	 * M10: each player's surf flag (from their own {@code story.state}) is kept for the other players' clients, which draw
	 * the surf sprite under them; Fabric syncs it to every client but the surfer's own (checked as a rule: mock players'
	 * connections never take attachment syncs), and a paused player's activity is theirs alone like any other.
	 */
	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH_PLAYERS)
	public void aSurfersFlagIsKeptForTheOtherPlayers(GameTestHelper helper) {
		RelayService relay = RelayService.get();
		MockPlayers.with(helper, surfer -> MockPlayers.with(helper, watcher -> {
			helper.assertFalse(Surfing.of(surfer), "nobody surfs before their client says so");
			relay.receive(surfer, envelope(1, MsgTypes.STORY_STATE, "{\"full\":true,\"active\":[],\"surf\":true,\"bike\":false,\"lit\":[]}"));
			relay.receive(watcher, envelope(1, MsgTypes.STORY_STATE, "{\"full\":true,\"active\":[],\"surf\":false,\"bike\":false,\"lit\":[]}"));
			helper.assertTrue(Surfing.of(surfer) && !Surfing.of(watcher), "surf flags mixed up");
			helper.assertTrue(Surfing.TYPE.isSynced(), "the surf flag isn't synced to clients");
			helper.assertTrue(Activity.SYNC.test(surfer, watcher), "the watcher's client must get it");
			relay.receive(surfer, envelope(2, MsgTypes.STORY_STATE, "{\"full\":false,\"surf\":false}"));
			helper.assertFalse(Surfing.of(surfer), "stepping ashore wasn't kept");
			relay.receive(surfer, envelope(3, MsgTypes.STORY_STATE, "{\"full\":false,\"add\":[]}"));
			helper.assertFalse(Surfing.of(surfer), "a delta without surf changed it");
			relay.receive(watcher, envelope(2, MsgTypes.ACTIVITY, "{\"activity\":\"paused\"}"));
			helper.assertTrue(Activity.of(watcher) == Activity.PAUSED && Activity.of(surfer) == Activity.NONE, "the paused activity is the watcher's");
		}));
		helper.succeed();
	}

	/** {@code texture.status}: a hash and a count per player, judged against the world's slot map; never art. */
	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH_PLAYERS)
	public void eachPlayersTextureStatusIsKept(GameTestHelper helper) {
		RelayService relay = RelayService.get();
		MinecraftServer server = helper.getLevel().getServer();
		WorldIndex index = WorldIndexSync.current(server);
		String worldHash = index == null ? "0".repeat(64) : index.slotMapHash();
		MockPlayers.with(helper, ready -> MockPlayers.with(helper, waiting -> RelayTap.capture(ready, sent -> {
			relay.receive(ready, envelope(1, MsgTypes.TEXTURE_STATUS, "{\"ok\":true,\"slotMapHash\":\"" + worldHash + "\",\"textures\":4273}"));
			relay.receive(waiting, envelope(1, MsgTypes.TEXTURE_STATUS, "{\"ok\":false,\"reason\":\"no_bridge\"}"));
			TextureStatusService.Status mine = TextureStatusService.of(ready);
			TextureStatusService.Status theirs = TextureStatusService.of(waiting);
			helper.assertTrue(mine != null && mine.ok() && mine.textures() == 4273 && worldHash.equals(mine.slotMapHash()), "the ready player's status");
			helper.assertTrue(mine.matchesWorld() == (index != null), "judged against the world's slot map");
			helper.assertTrue(theirs != null && !theirs.ok() && "no_bridge".equals(theirs.reason()) && theirs.slotMapHash() == null,
					"the waiting player's status");
			// Malformed: an error, and the last good status stands.
			relay.receive(ready, envelope(2, MsgTypes.TEXTURE_STATUS, "{\"ok\":true,\"art\":\"AAAA\"}"));
			relay.receive(ready, envelope(3, MsgTypes.TEXTURE_STATUS, "{\"ok\":false,\"reason\":\"Not A Code\"}"));
			helper.assertTrue(TextureStatusService.of(ready) == mine, "a malformed status replaced a good one");
			helper.assertTrue(sent.size() == 2 && sent.stream().allMatch(message -> message.type().equals(MsgTypes.ERROR)), "malformed statuses not refused");
		})));
		helper.succeed();
	}
}
