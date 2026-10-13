package dev.kantobridge.gametest;

import com.google.gson.JsonObject;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.server.Ledger;
import dev.kantobridge.server.LedgerRules;
import dev.kantobridge.server.LedgerService;
import dev.kantobridge.server.WorldIndexSync;
import dev.kantobridge.world.Games;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.RegionRecord;
import dev.kantobridge.world.RegionTable;
import dev.kantobridge.world.Scale;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.WorldIndex;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * One trainer economy (docs/specs/MULTI_GAME_PLAN.md §4.9, §6 check 4; MG6), on the server half: in a world of two
 * hand-made regions, {@code kanto} (slot 0) and {@code test2} (slot 1), a mock player's hidden games report their bags.
 * The first report of a save never projected into is adopted into the player's ledger and projected straight back as
 * {@code bag.set}; a stamped report is read back as the difference since that projection (a Potion drunk, prize money);
 * the ledger projected into the other region's game carries the same Potions, balls and money with a stamp of its own;
 * what that game could not hold ({@code act.result{kind:"bag.set", left}}) shrinks the remembered projection and stays
 * in the ledger; a report with a stamp this server never gave (an older save restored) is answered with a fresh
 * projection and changes nothing; and a legacy world (one implicit region) keeps today's per-game bag path untouched.
 */
public class LedgerGameTests implements FabricGameTest {
	// one batch per test: a batch's tests run at the same time and these share the server's published world and the
	// ledger service's per-player slots, which each test publishes, withdraws and forgets.
	private static final String BATCH = "kantobridge_ledger";
	private static final String HASH = "0".repeat(64);
	/** Far from every other test's area (synthetic 3.2M, shapes 3.6M, regions 4.4M, encounters 5.2M, dungeons 6M, passes 7M–7.2M). */
	private static final int AX = 7_600_000;
	private static final int AZ = 7_600_000;
	private static final String GAME = "red";
	private static final String KANTO = RegionRecord.LEGACY_ID;
	private static final String TEST2 = "test2";
	private static final String TOWN = "TEST_TOWN";
	/** A stamp this server never handed out: an older save restored (save.mismatch kinds). */
	private static final String STALE_STAMP = "0000000000000000";

	// ---- the two hand-made regions ----

	private static int originX(String region) {
		return KANTO.equals(region) ? AX : AX + RegionTable.PITCH;
	}

	/** A region's index: one surface map at the region's origin (the ledger needs the regions, not their blocks). */
	private static WorldIndex index(String region) {
		MapPlacement town = new MapPlacement(TOWN, originX(region), Scale.SURFACE_Y, AZ, 20, 18, 0, MapPlacement.Kind.SURFACE, "PAL",
				"kantobridge:kanto_outdoor", false, null);
		return new WorldIndex(GAME, HASH, List.of(town), HASH, StoryCellTable.empty(), List.of());
	}

	private static RegionRecord record(String region, int slot) {
		int x = originX(region);
		RegionRecord.Box bounds = new RegionRecord.Box(x - 100, AZ - 100, x + 600, AZ + 600, -64, 320);
		RegionRecord.Compat compat = RegionRecord.Compat.of(Objects.requireNonNull(Games.compat(GAME), "compat"));
		return new RegionRecord(region, GAME, "gen1", HASH, slot, new RegionRecord.Origin(x, Scale.SURFACE_Y, AZ), bounds,
				RegionRecord.Areas.NONE, new SlotMap.Range(slot * 16, slot * 16 + 15), RegionRecord.Documents.of(region),
				new RegionRecord.DocumentHashes(HASH, HASH, HASH), RegionRecord.NO_GENERATOR, new RegionRecord.Rules("gen1", null, compat),
				RegionRecord.Namespaces.of(GAME, region));
	}

	/** Publishes both regions and their table; nothing is broadcast. */
	private static void publish(MinecraftServer server) {
		WorldIndexSync.withdraw(server);
		WorldIndexSync.publishRegion(server, record(KANTO, 0), index(KANTO), null, null, null, false);
		WorldIndexSync.publishRegion(server, record(TEST2, 1), index(TEST2), null, null, null, false);
		WorldIndexSync.publishTable(server, new RegionTable(List.of(new RegionTable.Entry(KANTO, GAME, 0), new RegionTable.Entry(TEST2, GAME, 1))));
	}

	private static void restore(MinecraftServer server, @Nullable WorldIndex before) {
		WorldIndexSync.withdraw(server);
		if (before != null) {
			WorldIndexSync.publish(server, before, false);
		}
	}

	// ---- messages ----

	private static void receive(ServerPlayer player, String type, long seq, String data) {
		RelayService.get().receive(player, BattleGameTests.envelope(seq, type, data));
	}

	/** The hidden game's answer to a projection, forwarded by the client: {@code act.result} answering the {@code bag.set}'s seq. */
	private static void result(ServerPlayer player, long seq, long re, String data) {
		RelayService.get().receive(player, "{\"type\":\"" + MsgTypes.ACT_RESULT + "\",\"seq\":" + seq + ",\"re\":" + re + ",\"data\":" + data + "}");
	}

	/** A hidden game's bag report: {@code bag.sync} with the region, the items, the money and (when given) the stamp. */
	private static void sync(ServerPlayer player, long seq, String region, String items, long money, @Nullable String stamp) {
		String data = "{\"region\":\"" + region + "\",\"items\":" + items + ",\"money\":" + money
				+ (stamp == null ? "" : ",\"stamp\":\"" + stamp + "\"") + "}";
		receive(player, MsgTypes.BAG_SYNC, seq, data);
	}

	private static @Nullable Envelope last(List<Envelope> sent, String type) {
		Envelope found = null;
		for (Envelope message : sent) {
			if (message.type().equals(type)) {
				found = message;
			}
		}
		return found;
	}

	private static int count(List<Envelope> sent, String type) {
		int n = 0;
		for (Envelope message : sent) {
			if (message.type().equals(type)) {
				n++;
			}
		}
		return n;
	}

	private static @Nullable String regionOf(Envelope message) {
		return message.data().has("region") ? message.data().get("region").getAsString() : null;
	}

	private static int itemOf(Envelope set, String symbol) {
		JsonObject items = set.data().getAsJsonObject("items");
		return items.has(symbol) ? items.get(symbol).getAsInt() : 0;
	}

	/** The newest {@code bag.set}: {@code count} of them so far, tagged with {@code region}, with these items and money and a 16-hex stamp. */
	private static Envelope assertSet(GameTestHelper helper, List<Envelope> sent, int count, String region, int potions, int balls, long money,
			String what) {
		helper.assertTrue(count(sent, MsgTypes.BAG_SET) == count, what + ": bag.set sent " + count(sent, MsgTypes.BAG_SET) + " times, expected " + count);
		Envelope set = last(sent, MsgTypes.BAG_SET);
		helper.assertTrue(set != null, what + ": no bag.set");
		helper.assertTrue(region.equals(regionOf(set)), what + ": bag.set tagged " + regionOf(set) + ", expected " + region);
		helper.assertTrue(itemOf(set, "POTION") == potions && itemOf(set, "POKE_BALL") == balls,
				what + ": bag.set items " + set.data().get("items") + ", expected POTION " + potions + " and POKE_BALL " + balls);
		helper.assertTrue(set.data().get("money").getAsLong() == money, what + ": bag.set money " + set.data().get("money") + ", expected " + money);
		String stamp = set.data().get("stamp").getAsString();
		helper.assertTrue(stamp.matches("[0-9a-f]{16}"), what + ": the stamp isn't 16 hex digits");
		return set;
	}

	private static void assertLedger(GameTestHelper helper, ServerPlayer player, int potions, int balls, long money, String what) {
		Ledger ledger = Ledger.of(player);
		helper.assertTrue(ledger.count("potion") == potions && ledger.count("poke_ball") == balls && ledger.money() == money,
				what + ": the ledger holds potion " + ledger.count("potion") + ", poke_ball " + ledger.count("poke_ball") + ", money " + ledger.money()
						+ "; expected " + potions + ", " + balls + ", " + money);
	}

	// ---- the economy across two regions ----

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH + "_1")
	public void theLedgerFollowsTheHiddenGamesAcrossRegions(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		WorldIndex before = WorldIndexSync.current(server);
		try {
			publish(server);
			helper.assertTrue(LedgerService.enabled(server), "two regions: the ledger owns bag and money");
			MockPlayers.with(helper, player -> {
				try {
					RelayTap.capture(player, sent -> {
						helper.assertTrue(Ledger.of(player).isEmpty(), "a fresh player's ledger isn't empty");
						long adoptions = LedgerService.adoptions();
						long projections = LedgerService.projections();
						long syncs = LedgerService.syncs();
						long results = LedgerService.results();
						long reprojected = LedgerService.reprojected();

						// (a) Kanto's game reports a save never projected into: adopted into the ledger and projected back.
						sync(player, 1, KANTO, "{\"POTION\":3,\"POKE_BALL\":10}", 3000, null);
						helper.assertTrue(LedgerService.adoptions() == adoptions + 1, "the first report wasn't adopted");
						helper.assertTrue(LedgerService.projections() == projections + 1, "the adopted save wasn't projected back");
						assertLedger(helper, player, 3, 10, 3000, "after the adoption");
						Envelope kantoSet = assertSet(helper, sent, 1, KANTO, 3, 10, 3000, "the adoption's projection");
						String kantoStamp = kantoSet.data().get("stamp").getAsString();
						helper.assertTrue(kantoStamp.equals(Ledger.of(player).stamp(KANTO)), "the ledger doesn't remember kanto's stamp");
						helper.assertTrue(Ledger.of(player).isAdopted(KANTO + ":fresh"), "a never-saved game is adopted under its fresh key");

						// (b) The stamped report is read back as the difference: a Potion drunk, prize money won.
						sync(player, 2, KANTO, "{\"POTION\":2,\"POKE_BALL\":10}", 3500, kantoStamp);
						helper.assertTrue(LedgerService.syncs() == syncs + 2, "the read-back wasn't counted as a sync");
						assertLedger(helper, player, 2, 10, 3500, "after the read-back");
						helper.assertTrue(count(sent, MsgTypes.BAG_SET) == 1, "a read-back projected again");
						LedgerRules.Projection kantoProjected = LedgerService.projected(player, KANTO);
						helper.assertTrue(kantoProjected != null && kantoProjected.items().getOrDefault("POTION", 0) == 2
								&& kantoProjected.money() == 3500, "kanto's remembered projection didn't follow the report");

						// (c) The player follows test2: the ledger goes into that game with a stamp of its own.
						receive(player, MsgTypes.REGION_STATE, 3, "{\"followed\":\"" + TEST2 + "\",\"parked\":[\"" + KANTO + "\"]}");
						helper.assertTrue(LedgerService.project(player, TEST2, GAME), "the projection into test2 wasn't sent");
						Envelope test2Set = assertSet(helper, sent, 2, TEST2, 2, 10, 3500, "the projection into test2");
						String test2Stamp = test2Set.data().get("stamp").getAsString();
						helper.assertTrue(!test2Stamp.equals(kantoStamp), "test2's stamp is kanto's");
						helper.assertTrue(test2Stamp.equals(Ledger.of(player).stamp(TEST2)) && kantoStamp.equals(Ledger.of(player).stamp(KANTO)),
								"the ledger doesn't remember a stamp per region");
						// test2's game confirms what it holds: nothing changes.
						sync(player, 4, TEST2, "{\"POTION\":2,\"POKE_BALL\":10}", 3500, test2Stamp);
						helper.assertTrue(LedgerService.syncs() == syncs + 3, "the confirmation wasn't counted as a sync");
						assertLedger(helper, player, 2, 10, 3500, "after test2's confirmation");
						helper.assertTrue(count(sent, MsgTypes.BAG_SET) == 2, "the confirmation projected again");

						// (d) The trunk: test2's game could hold one Potion less; the rest stays in the ledger.
						result(player, 5, test2Set.seq(), "{\"ok\":false,\"kind\":\"bag.set\",\"reason\":\"too_many\",\"left\":{\"POTION\":1},"
								+ "\"region\":\"" + TEST2 + "\"}");
						helper.assertTrue(LedgerService.results() == results + 1, "the game's answer wasn't counted");
						LedgerRules.Projection test2Projected = LedgerService.projected(player, TEST2);
						helper.assertTrue(test2Projected != null && test2Projected.items().getOrDefault("POTION", 0) == 1,
								"the remembered projection didn't shrink by the Potion left");
						assertLedger(helper, player, 2, 10, 3500, "after the trunk answer: the ledger keeps both Potions");
						helper.assertTrue(count(sent, MsgTypes.BAG_SET) == 2, "too_many projected again");

						// (e) Kanto's game comes back with a stamp this server never gave (an older save restored): a fresh projection, no diff.
						sync(player, 6, KANTO, "{\"POTION\":9,\"POKE_BALL\":1}", 100, STALE_STAMP);
						helper.assertTrue(LedgerService.reprojected() == reprojected + 1, "the stale stamp wasn't noticed");
						assertLedger(helper, player, 2, 10, 3500, "after the stale report: the ledger's view stands");
						assertSet(helper, sent, 3, KANTO, 2, 10, 3500, "the re-projection into kanto");
						helper.assertTrue(!Ledger.of(player).stamp(KANTO).equals(kantoStamp), "the re-projection kept the old stamp");
						helper.assertTrue(LedgerService.projections() == projections + 3, "projections counted: " + (LedgerService.projections() - projections));
					});
				} finally {
					LedgerService.forget(player);
				}
			});
		} finally {
			restore(server, before);
		}
		helper.succeed();
	}

	// ---- a legacy world ----

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH + "_2")
	public void aLegacyWorldKeepsThePerGameBag(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		WorldIndex before = WorldIndexSync.current(server);
		try {
			WorldIndexSync.withdraw(server);
			WarpGameTests.publishSyntheticIndex(helper);
			helper.assertTrue(!LedgerService.enabled(server), "a legacy world: the hidden game owns its bag");
			MockPlayers.with(helper, player -> {
				try {
					long adoptions = LedgerService.adoptions();
					long syncs = LedgerService.syncs();
					// today's path: the report mirrors the battle items, the ledger hears nothing
					BagGameTests.sync(player, 1, "{\"POKE_BALL\":5}");
					helper.assertTrue(BagGameTests.count(player, "poke_ball") == 5, "the legacy mirror didn't follow the bag");
					helper.assertTrue(Ledger.of(player).isEmpty(), "a legacy world's report reached the ledger");
					helper.assertTrue(LedgerService.adoptions() == adoptions && LedgerService.syncs() == syncs, "the ledger service counted a legacy report");
					helper.assertTrue(LedgerService.projected(player, KANTO) == null, "a legacy world remembered a projection");
				} finally {
					LedgerService.forget(player);
				}
			});
		} finally {
			restore(server, before);
		}
		helper.succeed();
	}
}
