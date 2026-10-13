package dev.kantobridge.gametest;

import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.block.StoryCellBlockEntity;
import dev.kantobridge.builder.BuildTask;
import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportRoutes;
import dev.kantobridge.builder.place.BlockPlacer;
import dev.kantobridge.builder.plan.PlanException;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.builder.plan.WorldPlanner;
import dev.kantobridge.builder.zone.VanillaTerrain;
import dev.kantobridge.builder.zone.ZoneContent;
import dev.kantobridge.builder.zone.ZonePlan;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.pokemon.Gen1Mon;
import dev.kantobridge.pokemon.Gen1Mons;
import dev.kantobridge.pokemon.StashService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.server.HabitatService;
import dev.kantobridge.server.PassCrossings;
import dev.kantobridge.server.PassService;
import dev.kantobridge.server.RegionPresenceService;
import dev.kantobridge.server.WorldIndexSync;
import dev.kantobridge.story.StoryStateMessages;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.Games;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.RegionRecord;
import dev.kantobridge.world.RegionTable;
import dev.kantobridge.world.Scale;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import dev.kantobridge.world.WorldIndex;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

/**
 * Crossings between regions (docs/specs/MULTI_GAME_PLAN.md §4.7, §4.8, §6 check 4; MG5), on the server half: two
 * hand-made regions, {@code kanto} (slot 0) and {@code test2} (slot 1, 8,192 blocks east), each holding one half of the
 * connecting route {@code pass_test} as a zone with a pass and a gate house, published far from every other test. A mock
 * player on kanto's pass asks to cross: the server fades it, refuses a second crossing meanwhile, and when the client's
 * {@code region.state} follows the far region puts the player one cell inside the far gate house's door; the way back
 * works alike. Every refusal has its code; an abort or 15 s without the client's answer ends a crossing where the player
 * stands; a far game at its title is started by the client, so the server moves nobody. {@code party.stash} empties the
 * party into the PC before such a start. Last, a connecting half built from the synthetic bundle has its gate house of
 * planks, panes and a dark-oak roof, and its pass is a story cell closed per player.
 */
public class PassGameTests implements FabricGameTest {
	// one batch per test: a batch's tests run at the same time and these share the server's published world and the
	// pass service (withdraw/reset), so they run one after another instead
	private static final String BATCH = "kantobridge_passes";
	private static final String HASH = "0".repeat(64);
	/** Far from the synthetic (3.2M), shape and zone (3.6M), region (4.4M), encounter (5.2M) and dungeon (6M) tests. */
	private static final int AX = 7_000_000;
	private static final int AZ = 7_000_000;
	/** The built half's own origin, clear of the hand-made regions (kanto at 7.0M, test2 8,192 east of it). */
	private static final int BUILD_X = 7_200_000;
	private static final int BUILD_Z = 7_000_000;
	private static final long SEED = 4321L;
	private static final String GAME = "red";
	private static final String KANTO = RegionRecord.LEGACY_ID;
	private static final String TEST2 = "test2";
	private static final String ROUTE = "pass_test";
	private static final String ROUTE_MAP = "KB_ROUTE_PASS_TEST";
	/** The pass cell in the route map's grid: the gate house's far (east) wall holds it. */
	private static final int[] PASS = {10, 5};
	/** The house's footprint, 7×7 cells, its far door on its east wall at the pass: it faces right (+X). */
	private static final WorldIndex.GateHouse HOUSE = new WorldIndex.GateHouse(4, 2, 7, 7, Dir.RIGHT);
	/** Where a crossing arrives: one cell from the pass into the house, looking into it. */
	private static final Dir INWARD = HOUSE.facing().opposite();

	// ---- the two hand-made regions ----

	private static int originX(String region) {
		return KANTO.equals(region) ? AX : AX + RegionTable.PITCH;
	}

	/** A region's index: the route map at the region's origin and one zone around it, with its half of the route or not. */
	private static WorldIndex index(String region, boolean withHalf) {
		int x = originX(region);
		String far = KANTO.equals(region) ? TEST2 : KANTO;
		String end = KANTO.equals(region) ? WorldIndex.Pass.END_FROM : WorldIndex.Pass.END_TO;
		WorldIndex.Pass pass = withHalf ? new WorldIndex.Pass(List.of(PASS), ROUTE, far, end) : null;
		WorldIndex.Zone zone = new WorldIndex.Zone(ROUTE, ROUTE_MAP, "forest", x - 20, Scale.SURFACE_Y, AZ - 40, 200, 200,
				"kantobridge:wild_forest", List.of(), List.of(), null, null, pass, withHalf ? HOUSE : null);
		MapPlacement routeMap = new MapPlacement(ROUTE_MAP, x, Scale.SURFACE_Y, AZ, 2, 2, 0, MapPlacement.Kind.ROUTE, "PAL",
				"kantobridge:wild_forest", false, null);
		return new WorldIndex(GAME, HASH, List.of(routeMap), HASH, StoryCellTable.empty(), List.of(), List.of(zone));
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

	/**
	 * Publishes kanto (always with its half) and, when asked, test2 (with its half of the route, or a zone without one),
	 * then the table of what is published; nothing is broadcast.
	 */
	private static void publish(MinecraftServer server, boolean withTest2, boolean test2Half) {
		WorldIndexSync.withdraw(server);
		List<RegionTable.Entry> entries = new ArrayList<>();
		WorldIndexSync.publishRegion(server, record(KANTO, 0), index(KANTO, true), null, null, null, false);
		entries.add(new RegionTable.Entry(KANTO, GAME, 0));
		if (withTest2) {
			WorldIndexSync.publishRegion(server, record(TEST2, 1), index(TEST2, test2Half), null, null, null, false);
			entries.add(new RegionTable.Entry(TEST2, GAME, 1));
		}
		WorldIndexSync.publishTable(server, new RegionTable(entries));
	}

	private static void restore(MinecraftServer server, @Nullable WorldIndex before) {
		WorldIndexSync.withdraw(server);
		if (before != null) {
			WorldIndexSync.publish(server, before, false);
		}
	}

	/** The middle of a region's pass cell, at the floor. */
	private static Vec3 passCentre(String region) {
		return new Vec3(originX(region) + Scale.cellToBlock(PASS[0]) + 1.0, Scale.SURFACE_Y, AZ + Scale.cellToBlock(PASS[1]) + 1.0);
	}

	/** Where a crossing into a region arrives: the middle of the cell just inside its house's far door, at the floor. */
	private static Vec3 arrival(String region) {
		return new Vec3(originX(region) + Scale.cellToBlock(PASS[0] + INWARD.dx()) + 1.0, Scale.SURFACE_Y,
				AZ + Scale.cellToBlock(PASS[1] + INWARD.dy()) + 1.0);
	}

	private static void standOn(ServerPlayer player, Vec3 at) {
		player.teleportTo(at.x, at.y, at.z);
	}

	// ---- messages ----

	private static String envelope(String type, long seq, String data) {
		return "{\"type\":\"" + type + "\",\"seq\":" + seq + ",\"data\":" + data + "}";
	}

	private static void receive(ServerPlayer player, String type, long seq, String data) {
		RelayService.get().receive(player, envelope(type, seq, data));
	}

	/** The client's links: the followed region and the parked ones. */
	private static void state(ServerPlayer player, long seq, String followed, String... parked) {
		StringBuilder list = new StringBuilder();
		for (String region : parked) {
			list.append(list.length() == 0 ? "" : ",").append('"').append(region).append('"');
		}
		receive(player, MsgTypes.REGION_STATE, seq, "{\"followed\":\"" + followed + "\",\"parked\":[" + list + "]}");
	}

	private static void cross(ServerPlayer player, long seq, String dir) {
		receive(player, MsgTypes.PASS_CROSS, seq, "{\"route\":\"" + ROUTE + "\",\"dir\":\"" + dir + "\"}");
	}

	private static void stash(ServerPlayer player, long seq, String region) {
		receive(player, MsgTypes.PARTY_STASH, seq, "{\"region\":\"" + region + "\"}");
	}

	private static void storyState(ServerPlayer player, long seq, JsonObject data) {
		RelayService.get().receive(player, envelope(MsgTypes.STORY_STATE, seq, ProtoJson.write(data)));
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

	private static int fades(List<Envelope> sent) {
		int n = 0;
		for (Envelope message : sent) {
			if (message.type().equals(MsgTypes.ROUTE_FADE)) {
				n++;
			}
		}
		return n;
	}

	private static @Nullable String regionOf(Envelope message) {
		return message.data().has("region") ? message.data().get("region").getAsString() : null;
	}

	/** The newest error sent carries {@code code} and answers {@code re} (null: unsolicited). */
	private static void assertError(GameTestHelper helper, List<Envelope> sent, String code, @Nullable Long re, String what) {
		Envelope error = last(sent, MsgTypes.ERROR);
		helper.assertTrue(error != null, what + ": no error was sent");
		String got = error.data().has("code") ? error.data().get("code").getAsString() : "-";
		helper.assertTrue(code.equals(got), what + ": error code " + got + ", expected " + code);
		helper.assertTrue(re == null ? error.re() == null : re.equals(error.re()), what + ": the error answers " + error.re() + ", expected " + re);
	}

	/** One more fade than {@code before}, of the service's ticks, tagged with the near region. */
	private static void assertFade(GameTestHelper helper, List<Envelope> sent, int before, String region) {
		helper.assertTrue(fades(sent) == before + 1, "fades sent: " + fades(sent) + ", expected " + (before + 1));
		Envelope fade = last(sent, MsgTypes.ROUTE_FADE);
		helper.assertTrue(fade != null && fade.data().get("ticks").getAsInt() == PassCrossings.FADE_TICKS, "the fade's ticks");
		helper.assertTrue(region.equals(regionOf(fade)), "the fade isn't tagged with the near region: " + regionOf(fade));
	}

	private static void assertAt(GameTestHelper helper, ServerPlayer player, Vec3 want, float yaw, String what) {
		helper.assertTrue(player.position().distanceTo(want) < 1.0E-6, what + ": at " + player.position() + ", expected " + want);
		helper.assertTrue(Math.abs(Mth.wrapDegrees(player.getYRot() - yaw)) < 1.0E-3, what + ": facing " + player.getYRot() + ", expected " + yaw);
	}

	// ---- a crossing each way ----

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH + "_1")
	public void aCrossingArrivesInsideTheFarGateHouse(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		WorldIndex before = WorldIndexSync.current(server);
		PassService.reset(server);
		try {
			publish(server, true, true);
			helper.assertTrue(new HashSet<>(PassService.halvesOf(server, ROUTE)).equals(Set.of(KANTO, TEST2)),
					"the route's halves: " + PassService.halvesOf(server, ROUTE));
			MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
				// the client's links: kanto followed, test2 parked
				state(player, 1, KANTO, TEST2);
				helper.assertTrue(RegionPresenceService.hasLink(player, TEST2), "the far link isn't known");
				standOn(player, passCentre(KANTO));
				long accepted = PassService.accepted();
				long teleports = PassService.teleports();
				cross(player, 2, "to");
				assertFade(helper, sent, 0, KANTO);
				helper.assertTrue(PassService.inProgress(player) && PassService.accepted() == accepted + 1, "the crossing didn't begin");
				// one crossing at a time
				long busy = PassService.refused(PassCrossings.BUSY);
				cross(player, 3, "to");
				assertError(helper, sent, PassCrossings.BUSY, 3L, "a second cross");
				helper.assertTrue(PassService.refused(PassCrossings.BUSY) == busy + 1, "pass.busy wasn't counted");
				helper.assertTrue(fades(sent) == 1, "a refused cross faded the player");
				// the client follows the far region: the server puts the player just inside the far house's door
				state(player, 4, TEST2, KANTO);
				assertAt(helper, player, arrival(TEST2), INWARD.yaw(), "the arrival in test2");
				helper.assertTrue(!PassService.inProgress(player) && PassService.teleports() == teleports + 1, "the crossing didn't end with a teleport");
				// and back: from test2's pass, heading for the route's from end
				standOn(player, passCentre(TEST2));
				cross(player, 5, "from");
				assertFade(helper, sent, 1, TEST2);
				state(player, 6, KANTO, TEST2);
				assertAt(helper, player, arrival(KANTO), INWARD.yaw(), "the arrival back in kanto");
				helper.assertTrue(PassService.teleports() == teleports + 2 && !PassService.inProgress(player), "the way back didn't teleport");
			}));
		} finally {
			PassService.reset(server);
			restore(server, before);
		}
		helper.succeed();
	}

	// ---- refusals ----

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH + "_2")
	public void aCrossingIsRefusedWhereItCannotGo(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		WorldIndex before = WorldIndexSync.current(server);
		PassService.reset(server);
		try {
			MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
				// not on a pass cell
				publish(server, true, true);
				state(player, 1, KANTO, TEST2);
				Vec3 pass = passCentre(KANTO);
				standOn(player, new Vec3(pass.x + 20.0, pass.y, pass.z));
				long bad = PassService.refused(PassCrossings.BAD_MESSAGE);
				cross(player, 2, "to");
				assertError(helper, sent, PassCrossings.BAD_MESSAGE, 2L, "off the pass");
				helper.assertTrue(PassService.refused(PassCrossings.BAD_MESSAGE) == bad + 1 && !PassService.inProgress(player), "an off-pass cross began");
				// the wrong way
				standOn(player, pass);
				cross(player, 3, "from");
				assertError(helper, sent, PassCrossings.BAD_MESSAGE, 3L, "the wrong end");
				// the far region isn't in the world
				publish(server, false, false);
				state(player, 4, KANTO);
				standOn(player, pass);
				cross(player, 5, "to");
				assertError(helper, sent, PassCrossings.NO_REGION, 5L, "no far region");
				helper.assertTrue(PassService.refused(PassCrossings.NO_REGION) >= 1, "pass.no_region wasn't counted");
				// the far region exists but holds no half of the route
				publish(server, true, false);
				state(player, 6, KANTO, TEST2);
				standOn(player, pass);
				cross(player, 7, "to");
				assertError(helper, sent, PassCrossings.NOT_BUILT, 7L, "an unbuilt far half");
				// no link for the far region's game
				publish(server, true, true);
				state(player, 8, KANTO);
				standOn(player, pass);
				cross(player, 9, "to");
				assertError(helper, sent, PassCrossings.NO_LINK, 9L, "no link");
				helper.assertTrue(fades(sent) == 0 && !PassService.inProgress(player), "a refused cross faded the player or began a crossing");
				// an accepted crossing the client gives up on
				state(player, 10, KANTO, TEST2);
				long aborted = PassService.aborted();
				long teleports = PassService.teleports();
				cross(player, 11, "to");
				assertFade(helper, sent, 0, KANTO);
				receive(player, MsgTypes.PASS_ABORT, 12, "{\"route\":\"" + ROUTE + "\",\"reason\":\"not_idle\"}");
				assertError(helper, sent, PassCrossings.TIMEOUT, null, "an abort");
				helper.assertTrue(PassService.aborted() == aborted + 1 && !PassService.inProgress(player), "the abort didn't end the crossing");
				Vec3 at = player.position();
				state(player, 13, TEST2, KANTO);
				helper.assertTrue(player.position().equals(at) && PassService.teleports() == teleports, "an aborted crossing still moved the player");
			}));
		} finally {
			PassService.reset(server);
			restore(server, before);
		}
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH + "_3", timeoutTicks = 800)
	public void aCrossingWithoutAnAnswerTimesOut(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		WorldIndex before = WorldIndexSync.current(server);
		PassService.reset(server);
		publish(server, true, true);
		ServerPlayer player = MockPlayers.join(helper);
		RelayTap.Recorder tap = RelayTap.open(player);
		long timeouts = PassService.timeouts();
		long teleports = PassService.teleports();
		long[] started = {0};
		Runnable cleanup = () -> {
			tap.close();
			MockPlayers.leave(helper, player);
			PassService.reset(server);
			restore(server, before);
		};
		try {
			state(player, 1, KANTO, TEST2);
			standOn(player, passCentre(KANTO));
			cross(player, 2, "to");
			helper.assertTrue(PassService.inProgress(player) && fades(tap.sent) == 1, "the crossing didn't begin");
			started[0] = helper.getTick();
		} catch (RuntimeException | AssertionError e) {
			cleanup.run();
			throw e;
		}
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(helper.getTick() - started[0] > PassCrossings.TIMEOUT_TICKS + 5, "waiting for the timer"))
				.thenExecute(() -> {
					try {
						helper.assertTrue(!PassService.inProgress(player), "the crossing is still under way after the timer");
						helper.assertTrue(PassService.timeouts() == timeouts + 1, "the timeout wasn't counted");
						assertError(helper, tap.sent, PassCrossings.TIMEOUT, null, "the timer");
						helper.assertTrue(PassService.teleports() == teleports && player.getX() < originX(TEST2) - 1000,
								"a timed-out crossing moved the player");
					} finally {
						cleanup.run();
					}
				})
				.thenSucceed();
	}

	// ---- a far game at its title ----

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH + "_4")
	public void aFarGameAtItsTitleIsStartedByTheClient(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		WorldIndex before = WorldIndexSync.current(server);
		PassService.reset(server);
		try {
			publish(server, true, true);
			MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
				state(player, 1, KANTO, TEST2);
				// the far game's last report: still at its title
				receive(player, MsgTypes.GAME_STATUS, 2, "{\"game\":\"" + GAME + "\",\"region\":\"" + TEST2 + "\",\"playthrough\":\"title\","
						+ "\"ingame\":false,\"started\":false,\"badges\":0,\"markers\":{}}");
				helper.assertTrue(last(sent, MsgTypes.ERROR) == null, "the report was refused");
				standOn(player, passCentre(KANTO));
				long relinks = PassService.relinks();
				long teleports = PassService.teleports();
				cross(player, 3, "to");
				assertFade(helper, sent, 0, KANTO);
				PassCrossings.Crossing crossing = PassService.crossings(server).crossing(player.getUUID());
				helper.assertTrue(crossing != null && crossing.start(), "the crossing doesn't know the far game starts");
				// the client stashed, started and followed the far game: its own relink places the player
				state(player, 4, TEST2, KANTO);
				helper.assertTrue(player.position().distanceTo(passCentre(KANTO)) < 1.0E-6, "the server moved a player whose game the client starts");
				helper.assertTrue(PassService.relinks() == relinks + 1 && PassService.teleports() == teleports && !PassService.inProgress(player),
						"the start path wasn't a relink");
			}));
		} finally {
			PassService.reset(server);
			restore(server, before);
		}
		helper.succeed();
	}

	// ---- party.stash ----

	private static Pokemon gift(int dex, int level) {
		try {
			return Gen1Mons.gift(new Gen1Mon(dex, level, List.of(33), null));
		} catch (Gen1Mons.Missing e) {
			throw new GameTestAssertException("Cobblemon lacks " + e.code());
		}
	}

	private static int count(Iterable<Pokemon> store) {
		int n = 0;
		for (Pokemon ignored : store) {
			n++;
		}
		return n;
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH + "_5")
	public void partyStashEmptiesThePartyIntoThePc(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		WorldIndex before = WorldIndexSync.current(server);
		try {
			publish(server, true, true);
			MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
				PlayerPartyStore party = PlayerExtensionsKt.party(player);
				PCStore pc = PlayerExtensionsKt.pc(player);
				Pokemon first = gift(1, 5);
				Pokemon second = gift(4, 5);
				helper.assertTrue(party.add(first) && party.add(second) && count(party) == 2, "the party wasn't seeded");
				long stashed = StashService.stashed();
				long moved = StashService.moved();
				stash(player, 7, TEST2);
				Envelope result = last(sent, MsgTypes.ACT_RESULT);
				helper.assertTrue(result != null && Long.valueOf(7).equals(result.re()), "party.stash wasn't answered with act.result re 7");
				helper.assertTrue(result.data().get("ok").getAsBoolean() && "stash".equals(result.data().get("kind").getAsString())
						&& result.data().get("moved").getAsInt() == 2, "act.result " + result.data());
				helper.assertTrue(TEST2.equals(regionOf(result)), "the answer isn't tagged with the region");
				helper.assertTrue(count(party) == 0, "the party isn't empty: " + count(party));
				helper.assertTrue(pc.get(first.getUuid()) != null && pc.get(second.getUuid()) != null, "the PC doesn't hold both");
				helper.assertTrue(StashService.stashed() == stashed + 1 && StashService.moved() == moved + 2, "the stash wasn't counted");
				// nothing to move is still an answer
				stash(player, 8, TEST2);
				Envelope again = last(sent, MsgTypes.ACT_RESULT);
				helper.assertTrue(again != null && Long.valueOf(8).equals(again.re()) && again.data().get("moved").getAsInt() == 0,
						"an empty party's stash wasn't answered with moved 0");
				// a region this world doesn't have is refused
				stash(player, 9, "nowhere");
				assertError(helper, sent, "bad_message", 9L, "a stash for an unknown region");
				helper.assertTrue(StashService.stashed() == stashed + 2, "a refused stash was counted");
				// back into the party, so nothing of the test stays in the player's PC
				for (Pokemon pokemon : List.of(first, second)) {
					pc.remove(pokemon);
					party.add(pokemon);
				}
			}));
		} finally {
			restore(server, before);
		}
		helper.succeed();
	}

	// ---- a built connecting half ----

	private static ExportBundle bundle() {
		try {
			return ExportBundle.fromClasspath(PassGameTests.class.getClassLoader(), "kantobridge/synthetic_bundle/red/");
		} catch (BundleException e) {
			throw new GameTestAssertException("synthetic bundle unreadable: " + e.code());
		}
	}

	/**
	 * One half of a connecting route built beside the synthetic Kanto: the gate house stands at the far gate (oak-plank
	 * walls with glass panes under a dark-oak roof), its far door is the pass, a story cell of type {@code pass} that
	 * closes for a player whose relayed story state lists it and stays open for one whose state doesn't.
	 */
	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH + "_6", timeoutTicks = 12000)
	public void aBuiltHalfEndsInAGateHouseWhosePassIsAStoryCell(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ExportBundle bundle = bundle();
		WorldPlan base;
		try {
			base = WorldPlanner.plan(bundle, new WorldPlanner.Options(null, BUILD_X, BUILD_Z, level.getMinBuildHeight()));
		} catch (BundleException | PlanException e) {
			throw new GameTestAssertException("the synthetic plan failed: " + e.getClass().getSimpleName());
		}
		// the from half, off TEST_HILL (whose neighbour TEST_TOWN has a grass table), heading for test2; no dungeon
		ExportRoutes routes = new ExportRoutes(List.of(new ExportRoutes.Zone(ROUTE, "PASS TEST", ExportRoutes.CONNECTING, "forest", "TEST_HILL",
				ROUTE_MAP, 1, 1, 0.0, 1, 3, List.of("OPP_TEST"), 1, 3, 0.0, KANTO, TEST2, ExportRoutes.END_FROM)));
		ZoneContent content;
		try {
			content = ZoneContent.of(bundle, List.of("TEST_HILL"));
		} catch (BundleException e) {
			throw new GameTestAssertException("the synthetic content failed: " + e.code());
		}
		long t0 = System.nanoTime();
		WorldPlan plan = BuildTask.withZones(base, routes, SEED, () -> VanillaTerrain.of(level.registryAccess(), SEED), BUILD_X, BUILD_Z, content);
		long planMs = (System.nanoTime() - t0) / 1_000_000L;
		helper.assertTrue(plan.zones().size() == 1, "the half wasn't planned");
		ZonePlan zone = plan.zones().get(0);
		helper.assertTrue(zone.crossing() != null && TEST2.equals(zone.crossing().toRegion()) && ExportRoutes.END_FROM.equals(zone.crossing().end()),
				"the half doesn't know its crossing");
		WorldIndex.Zone entry = zone.zone();
		helper.assertTrue(entry.pass() != null && entry.gateHouse() != null, "the index entry has no pass or gate house");
		helper.assertTrue(entry.pass().route().equals(ROUTE) && TEST2.equals(entry.pass().toRegion()) && WorldIndex.Pass.END_FROM.equals(entry.pass().end()),
				"the pass names another route or region");
		helper.assertTrue(entry.pass().cells().size() == zone.passCells().size(), "the plan and its index disagree on the pass cells");
		for (int[] cell : entry.pass().cells()) {
			helper.assertTrue(entry.gateHouse().contains(cell[0], cell[1]), "a pass cell outside the gate house");
		}
		List<StoryCellTable.Entry> passes = zone.storyEntries().stream().filter(e -> e.type() == StoryCellType.PASS).toList();
		helper.assertTrue(passes.size() == entry.pass().cells().size() && passes.stream().allMatch(e -> ROUTE.equals(e.obj())),
				"the pass cells aren't story entries of the zone");
		helper.assertTrue(plan.index().storyCells().entries().stream().anyMatch(e -> e.type() == StoryCellType.PASS), "the world's table has no pass cell");
		BlockPlacer placer = new BlockPlacer(level, plan, List.of(), List.of(), HabitatService.ensurePools(plan.pools()));
		long[] ticks = {0};
		helper.startSequence()
				.thenWaitUntil(() -> {
					ticks[0]++;
					helper.assertTrue(placer.tick(System.nanoTime() + 25_000_000L), "still placing");
				})
				.thenExecute(() -> {
					try {
						KantoBridge.LOGGER.info("MG5: the connecting half planned in {} ms and placed in {} ticks ({} pass cells)", planMs, ticks[0],
								passes.size());
						verifyHouse(helper, zone, level);
						verifyPass(helper, plan, zone, level, passes);
					} finally {
						placer.release();
					}
				})
				.thenSucceed();
	}

	/** The house's footprint holds oak-plank walls, glass panes at eye level and a dark-oak roof. */
	private static void verifyHouse(GameTestHelper helper, ZonePlan zone, ServerLevel level) {
		WorldIndex.GateHouse house = zone.zone().gateHouse();
		MapPlacement routeMap = zone.routeMap();
		int planks = 0;
		int panes = 0;
		int roof = 0;
		int lights = 0;
		for (int cy = house.y(); cy < house.y() + house.d(); cy++) {
			for (int cx = house.x(); cx < house.x() + house.w(); cx++) {
				int bx = routeMap.x() + Scale.cellToBlock(cx);
				int bz = routeMap.z() + Scale.cellToBlock(cy);
				level.getChunk(bx >> 4, bz >> 4);
				for (int dx = 0; dx < Scale.CELL_BLOCKS; dx++) {
					for (int dz = 0; dz < Scale.CELL_BLOCKS; dz++) {
						for (int y = ZonePlan.FEET; y <= ZonePlan.FEET + ZonePlan.HOUSE_WALL; y++) {
							BlockState state = level.getBlockState(new BlockPos(bx + dx, y, bz + dz));
							if (state.is(Blocks.OAK_PLANKS)) {
								planks++;
							} else if (state.is(Blocks.GLASS_PANE)) {
								panes++;
							} else if (state.is(Blocks.DARK_OAK_PLANKS)) {
								roof++;
							} else if (state.getLightEmission() > 0) {
								lights++;
							}
						}
					}
				}
			}
		}
		KantoBridge.LOGGER.info("MG5: the built gate house ({}x{} cells) holds {} plank blocks, {} panes, {} roof blocks, {} lights", house.w(),
				house.d(), planks, panes, roof, lights);
		helper.assertTrue(planks > 0, "no oak-plank walls in the gate house");
		helper.assertTrue(panes > 0, "no glass panes in the gate house");
		helper.assertTrue(roof >= house.w() * house.d(), "the roof doesn't cover the house: " + roof + " blocks");
	}

	/** The pass cells carry story-cell blocks keyed as the zone's entries, closed per player by the relayed story state. */
	private static void verifyPass(GameTestHelper helper, WorldPlan plan, ZonePlan zone, ServerLevel level, List<StoryCellTable.Entry> passes) {
		MapPlacement routeMap = zone.routeMap();
		List<BlockPos> blocks = new ArrayList<>();
		for (int i = 0; i < passes.size(); i++) {
			int[] cell = zone.zone().pass().cells().get(i);
			BlockPos pos = new BlockPos(routeMap.x() + Scale.cellToBlock(cell[0]), ZonePlan.FEET, routeMap.z() + Scale.cellToBlock(cell[1]));
			level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
			helper.assertTrue(level.getBlockEntity(pos) instanceof StoryCellBlockEntity cellEntity && passes.get(i).key().equals(cellEntity.cellKey()),
					"pass cell " + i + " isn't a story cell with the entry's key");
			blocks.add(pos);
		}
		WorldIndex.Zone at = plan.index().zoneAt(blocks.get(0).getX(), blocks.get(0).getZ());
		helper.assertTrue(at != null && at.id().equals(zone.id()), "the pass isn't inside the zone's box");
		Set<String> keys = passes.stream().map(StoryCellTable.Entry::key).collect(Collectors.toSet());
		ServerPlayer closed = MockPlayers.join(helper);
		ServerPlayer open = MockPlayers.join(helper);
		try {
			// closed's client says the pass is shut (the far region unbuilt or unlinked); open's says nothing of it
			storyState(closed, 1, StoryStateMessages.full(keys, false, false, List.of()));
			storyState(open, 1, StoryStateMessages.full(List.of(), false, false, List.of()));
			for (BlockPos pos : blocks) {
				helper.assertTrue(closedFor(level, pos, closed), "the pass is open for a player whose state closes it");
				helper.assertTrue(!closedFor(level, pos, open), "the pass is closed for a player whose state doesn't list it");
			}
			// the far region got built and linked: the client opens the pass
			storyState(closed, 2, StoryStateMessages.delta(null, keys, null, null, null));
			for (BlockPos pos : blocks) {
				helper.assertTrue(!closedFor(level, pos, closed), "an opened pass still collides");
			}
		} finally {
			MockPlayers.leave(helper, closed);
			MockPlayers.leave(helper, open);
		}
	}

	private static boolean closedFor(ServerLevel level, BlockPos pos, ServerPlayer player) {
		return !level.getBlockState(pos).getCollisionShape(level, pos, CollisionContext.of(player)).isEmpty();
	}
}
