package dev.kantobridge.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.BuildListener;
import dev.kantobridge.builder.BuildOptions;
import dev.kantobridge.builder.BuildReport;
import dev.kantobridge.builder.BuildTask;
import dev.kantobridge.builder.KantoWorldBuilder;
import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import dev.kantobridge.server.ProgressRecord;
import dev.kantobridge.server.ProgressService;
import dev.kantobridge.server.RegionPresence;
import dev.kantobridge.server.RegionPresenceService;
import dev.kantobridge.server.Regions;
import dev.kantobridge.server.WarpService;
import dev.kantobridge.server.WorldIndexSync;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.RegionRecord;
import dev.kantobridge.world.RegionTable;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.WorldIndex;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Regions as data (docs/specs/MULTI_GAME_PLAN.md §4.6, §4.14, §6 check 2): the synthetic bundle built twice into one world
 * folder as regions {@code kanto} (slot 0) and {@code test2} (slot 1, 8,192 blocks east), far from every other test and
 * publishing nothing by itself. The world gets a region table, a record and its own documents per region, one slot map
 * of version 2 with a family range per region and pool ids prefixed per region (none for slot 0); the {@code kanto}
 * region's index is byte for byte a legacy build's at the same origin (the slice-hash rule: its {@code slotMapHash} is
 * the hash of its own slice of the one map). Published together, the two regions reach a joining player as
 * {@code world.regions}, then each region's {@code world.index}, the one {@code world.slots} and each region's
 * {@code world.shapes}, all tagged with their region; warps resolve per region (by the message's {@code region}, else by
 * the player's position); {@code region.state} and {@code game.status} are stored per region. A legacy world keeps
 * today's wire exactly.
 */
public class RegionBuildGameTests implements FabricGameTest {
	private static final String BATCH = "kantobridge_regions";
	/** Far from the synthetic (3.2M), shape and zone (3.6M), encounter (5.2M) and dungeon (6M) tests; test2 sits 8,192 east. */
	static final int ORIGIN_X = 4_400_000;
	static final int ORIGIN_Z = 4_400_000;
	private static final String BUNDLE = "kantobridge/synthetic_bundle/red/";
	private static final String KANTO = RegionRecord.LEGACY_ID;
	private static final String TEST2 = "test2";
	private static final String DATA = "kantobridge";
	private static final String POOLS = "datapacks/kantobridge_generated/data/kantobridge/habitat_pools/";

	private static ExportBundle bundle() {
		try {
			return ExportBundle.fromClasspath(RegionBuildGameTests.class.getClassLoader(), BUNDLE);
		} catch (BundleException e) {
			throw new GameTestAssertException("synthetic bundle unreadable: " + e.code());
		}
	}

	/** A build into {@code dir}: of region {@code region} at {@code slot}, or a legacy build when {@code region} is null. */
	private static BuildTask start(GameTestHelper helper, Path dir, @Nullable String region, int slot) {
		BuildOptions options = BuildOptions.defaults().withOrigin(ORIGIN_X, ORIGIN_Z).withWorldSpawn(false).withWorldDir(dir)
				.withWritePack(false).withPublishIndex(false);
		if (region != null) {
			options = options.withRegion(region, slot);
		}
		return KantoWorldBuilder.start(helper.getLevel(), bundle(), null, options, BuildListener.NONE);
	}

	private static void waitFor(GameTestHelper helper, BuildTask[] task) {
		helper.assertTrue(task[0] != null && task[0].isDone(), "build still running");
	}

	private static Path temp(String name) {
		try {
			return Files.createTempDirectory(name);
		} catch (IOException e) {
			throw new GameTestAssertException("no temp folder");
		}
	}

	private static String text(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new GameTestAssertException(file.getFileName() + " unreadable: " + e.getClass().getSimpleName());
		}
	}

	private static byte[] bytes(Path file) {
		try {
			return Files.readAllBytes(file);
		} catch (IOException e) {
			throw new GameTestAssertException(file.getFileName() + " unreadable: " + e.getClass().getSimpleName());
		}
	}

	private static Path regionDir(Path worldDir, String region) {
		return worldDir.resolve(DATA).resolve(RegionRecord.DIR).resolve(region);
	}

	private static WorldIndex index(Path file) {
		try {
			return WorldIndex.fromJson(text(file));
		} catch (DataException e) {
			throw new GameTestAssertException(file.getFileName() + " is not a WorldIndex: " + e.problem());
		}
	}

	private static RegionRecord record(Path worldDir, String region) {
		try {
			return RegionRecord.fromJson(text(regionDir(worldDir, region).resolve(RegionRecord.FILE)));
		} catch (DataException e) {
			throw new GameTestAssertException(region + "'s record is invalid: " + e.problem());
		}
	}

	private static RegionTable table(Path worldDir) {
		try {
			return RegionTable.fromJson(text(RegionTable.file(worldDir.resolve(DATA))));
		} catch (DataException e) {
			throw new GameTestAssertException("regions.json is invalid: " + e.problem());
		}
	}

	private static SlotMap slots(Path worldDir) {
		try {
			return SlotMap.fromJson(text(worldDir.resolve(DATA).resolve(BuildTask.SLOT_MAP_FILE)));
		} catch (DataException e) {
			throw new GameTestAssertException("slot_map.json is invalid: " + e.problem());
		}
	}

	private static void assertRegionFiles(GameTestHelper helper, Path worldDir, String region) {
		Path dir = regionDir(worldDir, region);
		for (String name : List.of(BuildTask.INDEX_FILE, BuildTask.SHAPE_INDEX_FILE, BuildTask.SHAPE_MAP_FILE, RegionRecord.FILE)) {
			helper.assertTrue(Files.isRegularFile(dir.resolve(name)), region + " has no " + name);
		}
	}

	private static void delete(Path dir) {
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		} catch (IOException e) {
			// A temp folder; the OS cleans it.
		}
	}

	// ---- two regions in one world ----

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 9000)
	public void twoRegionsShareOneWorld(GameTestHelper helper) {
		Path dir = temp("kantobridge-regions");
		Path legacyDir = temp("kantobridge-regions-legacy");
		BuildTask[] kanto = {start(helper, dir, KANTO, 0)};
		BuildTask[] test2 = new BuildTask[1];
		BuildTask[] legacy = new BuildTask[1];
		String[] kantoIndexText = new String[1];
		helper.startSequence()
				.thenWaitUntil(() -> waitFor(helper, kanto))
				.thenExecute(() -> kantoIndexText[0] = verifyKanto(helper, kanto[0]))
				.thenExecute(() -> test2[0] = start(helper, dir, TEST2, 1))
				.thenWaitUntil(() -> waitFor(helper, test2))
				.thenExecute(() -> verifyTest2(helper, test2[0], kantoIndexText[0]))
				.thenExecute(() -> legacy[0] = start(helper, legacyDir, null, BuildOptions.NO_SLOT))
				.thenWaitUntil(() -> waitFor(helper, legacy))
				.thenExecute(() -> verifyLegacyBuildEqualsKanto(helper, legacy[0], dir))
				.thenExecute(() -> verifyPublishedRegions(helper, dir))
				.thenExecute(() -> {
					delete(dir);
					delete(legacyDir);
				})
				.thenSucceed();
	}

	/** The first region build of an empty world: its files under regions/kanto/, the table, the slot map, unprefixed pools. */
	private static String verifyKanto(GameTestHelper helper, BuildTask task) {
		BuildReport report = task.report();
		helper.assertTrue(task.succeeded(), "kanto build failed: " + report.error);
		helper.assertTrue(report.region != null && KANTO.equals(report.region.region()) && report.region.slot() == 0, "report region " + report.region);
		helper.assertTrue(report.region.familyLo() == 0 && report.region.familyHi() == 15, "kanto families " + report.region.familyLo() + ".."
				+ report.region.familyHi());
		Path worldDir = task.worldDir();
		Path data = worldDir.resolve(DATA);
		assertRegionFiles(helper, worldDir, KANTO);
		helper.assertFalse(Files.exists(data.resolve(BuildTask.INDEX_FILE)), "a region build wrote a root world_index.json");
		RegionTable table = table(worldDir);
		helper.assertTrue(table.size() == 1 && table.byId(KANTO) != null && table.byId(KANTO).slot() == 0, "regions.json " + table.toJson());
		SlotMap slots = slots(worldDir);
		helper.assertTrue(slots.version() == SlotMap.VERSION_2, "slot map version " + slots.version());
		SlotMap.Range kantoFamilies = slots.families(KANTO);
		helper.assertTrue(kantoFamilies != null && kantoFamilies.lo() == 0 && kantoFamilies.hi() == 15, "kanto's slot-map range " + kantoFamilies);
		String indexText = text(regionDir(worldDir, KANTO).resolve(BuildTask.INDEX_FILE));
		WorldIndex index = index(regionDir(worldDir, KANTO).resolve(BuildTask.INDEX_FILE));
		helper.assertTrue(index.slotMapHash().equals(slots.regionHash(KANTO)), "kanto's index doesn't name its own slice of the slot map");
		RegionRecord record = record(worldDir, KANTO);
		helper.assertTrue(record.slot() == 0 && record.families().lo() == 0 && record.families().hi() == 15, "kanto's record " + record.toJson());
		helper.assertTrue(record.poolPrefix().isEmpty() && record.namespaces().pools().equals("kantobridge:" + KANTO), "kanto's namespaces " + record.namespaces());
		helper.assertTrue(record.exportHash().equals(index.exportHash()), "the record names another export");
		helper.assertTrue(record.hashes().index().equals(Hashes.sha256(indexText)), "the record's index hash isn't the file's");
		helper.assertTrue(record.bounds().contains(index.map("TEST_TOWN").x(), index.map("TEST_TOWN").z()), "kanto's bounds miss its town");
		// slot 0 keeps the unprefixed pool ids (so the index's bytes are a legacy build's)
		helper.assertTrue(!index.habitats().isEmpty() && index.habitats().stream().allMatch(h -> h.pool().startsWith("kantobridge:") && !h.pool().startsWith("kantobridge:kanto_")),
				"kanto's pools are prefixed");
		helper.assertTrue(index.habitats().stream().anyMatch(h -> h.pool().equals("kantobridge:test_town")), "no kantobridge:test_town pool");
		helper.assertTrue(Files.isRegularFile(worldDir.resolve(POOLS + "test_town.json")), "the town's pool file isn't in the datapack");
		return indexText;
	}

	/** The second region: its own files and range, shifted by the pitch, prefixed pools; kanto's documents untouched. */
	private static void verifyTest2(GameTestHelper helper, BuildTask task, String kantoIndexText) {
		BuildReport report = task.report();
		helper.assertTrue(task.succeeded(), "test2 build failed: " + report.error);
		helper.assertTrue(report.region != null && TEST2.equals(report.region.region()) && report.region.slot() == 1, "report region " + report.region);
		helper.assertTrue(report.region.familyLo() == 16 && report.region.familyHi() == 31, "test2 families " + report.region.familyLo() + ".."
				+ report.region.familyHi());
		Path worldDir = task.worldDir();
		assertRegionFiles(helper, worldDir, KANTO);
		assertRegionFiles(helper, worldDir, TEST2);
		RegionTable table = table(worldDir);
		helper.assertTrue(table.size() == 2 && table.byId(KANTO).slot() == 0 && table.byId(TEST2).slot() == 1, "regions.json " + table.toJson());
		SlotMap slots = slots(worldDir);
		SlotMap.Range kantoFamilies = slots.families(KANTO);
		SlotMap.Range test2Families = slots.families(TEST2);
		helper.assertTrue(kantoFamilies != null && kantoFamilies.lo() == 0 && kantoFamilies.hi() == 15, "kanto's range moved: " + kantoFamilies);
		helper.assertTrue(test2Families != null && test2Families.lo() == 16 && test2Families.hi() == 31, "test2's range " + test2Families);
		// a rebuild of one region never touches another's documents
		helper.assertTrue(text(regionDir(worldDir, KANTO).resolve(BuildTask.INDEX_FILE)).equals(kantoIndexText), "test2's build rewrote kanto's index");
		WorldIndex kantoIndex = index(regionDir(worldDir, KANTO).resolve(BuildTask.INDEX_FILE));
		WorldIndex test2Index = index(regionDir(worldDir, TEST2).resolve(BuildTask.INDEX_FILE));
		helper.assertTrue(kantoIndex.slotMapHash().equals(slots.regionHash(KANTO)), "kanto's slice hash changed");
		helper.assertTrue(test2Index.slotMapHash().equals(slots.regionHash(TEST2)), "test2's index doesn't name its own slice");
		helper.assertTrue(test2Index.maps().keySet().equals(kantoIndex.maps().keySet()), "test2 holds other maps");
		for (Map.Entry<String, MapPlacement> entry : kantoIndex.maps().entrySet()) {
			MapPlacement a = entry.getValue();
			MapPlacement b = test2Index.map(entry.getKey());
			helper.assertTrue(b != null && b.x() == a.x() + RegionTable.PITCH && b.z() == a.z() && b.y() == a.y() && b.layer() == a.layer(),
					entry.getKey() + " isn't shifted by the pitch");
		}
		helper.assertTrue(!test2Index.habitats().isEmpty() && test2Index.habitats().stream().allMatch(h -> h.pool().startsWith("kantobridge:test2_")),
				"test2's pools aren't prefixed");
		helper.assertTrue(test2Index.habitats().stream().anyMatch(h -> h.pool().equals("kantobridge:test2_test_town")), "no kantobridge:test2_test_town pool");
		helper.assertTrue(Files.isRegularFile(worldDir.resolve(POOLS + "test_town.json")), "kanto's pool file left the datapack");
		helper.assertTrue(Files.isRegularFile(worldDir.resolve(POOLS + "test2_test_town.json")), "test2's pool file isn't in the datapack");
		RegionRecord kanto = record(worldDir, KANTO);
		RegionRecord test2 = record(worldDir, TEST2);
		helper.assertTrue(test2.slot() == 1 && test2.families().lo() == 16 && test2.families().hi() == 31, "test2's record " + test2.toJson());
		helper.assertTrue(test2.poolPrefix().equals(TEST2 + "_") && test2.namespaces().pools().equals("kantobridge:" + TEST2), "test2's namespaces " + test2.namespaces());
		helper.assertFalse(kanto.bounds().overlaps(test2.bounds()), "the regions' bounds overlap");
		helper.assertTrue(test2.bounds().minX() > kanto.bounds().maxX(), "test2 isn't east of kanto");
		helper.assertTrue(test2.origin().x() == ORIGIN_X + RegionTable.PITCH && kanto.origin().x() == ORIGIN_X, "origins " + kanto.origin() + " " + test2.origin());
	}

	/** A legacy build at the same origin writes the root files and no table; its index and shapes equal kanto's byte for byte. */
	private static void verifyLegacyBuildEqualsKanto(GameTestHelper helper, BuildTask task, Path regionWorld) {
		BuildReport report = task.report();
		helper.assertTrue(task.succeeded(), "legacy build failed: " + report.error);
		helper.assertTrue(report.region == null, "a legacy build reported a region");
		Path data = task.worldDir().resolve(DATA);
		helper.assertTrue(Files.isRegularFile(data.resolve(BuildTask.INDEX_FILE)) && Files.isRegularFile(data.resolve(BuildTask.SLOT_MAP_FILE)),
				"a legacy build wrote no root files");
		helper.assertFalse(Files.exists(RegionTable.file(data)) || Files.exists(data.resolve(RegionRecord.DIR)), "a legacy build wrote regions");
		Path kanto = regionDir(regionWorld, KANTO);
		helper.assertTrue(text(data.resolve(BuildTask.INDEX_FILE)).equals(text(kanto.resolve(BuildTask.INDEX_FILE))),
				"the kanto region's index differs from a legacy build's");
		helper.assertTrue(text(data.resolve(BuildTask.SHAPE_INDEX_FILE)).equals(text(kanto.resolve(BuildTask.SHAPE_INDEX_FILE))),
				"the kanto region's shape index differs from a legacy build's");
		helper.assertTrue(Arrays.equals(bytes(data.resolve(BuildTask.SHAPE_MAP_FILE)), bytes(kanto.resolve(BuildTask.SHAPE_MAP_FILE))),
				"the kanto region's shape map differs from a legacy build's");
		SlotMap legacy = slots(task.worldDir());
		SlotMap shared = slots(regionWorld);
		helper.assertTrue(legacy.version() == SlotMap.VERSION, "a legacy build wrote slot map version " + legacy.version());
		helper.assertTrue(legacy.hash().equals(shared.regionHash(KANTO)), "kanto's slice hash isn't the legacy map's hash");
	}

	// ---- the published world: the join sequence, warps, presence, progress ----

	private static void publishFrom(MinecraftServer server, Path worldDir) {
		WorldIndexSync.withdraw(server);
		WorldIndexSync.publishTable(server, table(worldDir));
		SlotMap slots = slots(worldDir);
		for (String region : List.of(KANTO, TEST2)) {
			Path dir = regionDir(worldDir, region);
			WorldIndexSync.publishRegion(server, record(worldDir, region), index(dir.resolve(BuildTask.INDEX_FILE)), slots,
					text(dir.resolve(BuildTask.SHAPE_INDEX_FILE)), bytes(dir.resolve(BuildTask.SHAPE_MAP_FILE)), false);
		}
	}

	private static void restore(MinecraftServer server, @Nullable WorldIndex before) {
		WorldIndexSync.withdraw(server);
		if (before != null) {
			WorldIndexSync.publish(server, before, false);
		}
	}

	private static @Nullable String regionOf(Envelope message) {
		return message.data().has("region") ? message.data().get("region").getAsString() : null;
	}

	private static void verifyPublishedRegions(GameTestHelper helper, Path worldDir) {
		MinecraftServer server = helper.getLevel().getServer();
		WorldIndex before = WorldIndexSync.current(server);
		try {
			publishFrom(server, worldDir);
			helper.assertTrue(WorldIndexSync.isRegionWorld(server), "two published regions aren't a region world");
			Regions regions = Regions.of(server);
			helper.assertTrue(regions != null && regions.ids().equals(List.of(KANTO, TEST2)) && !regions.isLegacyWorld(), "the services see " + (regions == null ? null : regions.ids()));
			WorldIndex kantoIndex = WorldIndexSync.current(server, KANTO);
			WorldIndex test2Index = WorldIndexSync.current(server, TEST2);
			helper.assertTrue(kantoIndex != null && test2Index != null, "a region's index isn't published");
			helper.assertTrue(WorldIndexSync.current(server) == kantoIndex, "the single-index view isn't the slot-0 region's");
			MapPlacement town2 = test2Index.map("TEST_TOWN");
			helper.assertTrue(regions.at(town2.x() + 5, town2.z() + 5) == regions.byId(TEST2), "test2's town isn't in test2");
			MapPlacement town1 = kantoIndex.map("TEST_TOWN");
			helper.assertTrue(regions.at(town1.x() + 5, town1.z() + 5) == regions.byId(KANTO), "kanto's town isn't in kanto");
			MockPlayers.with(helper, player -> {
				verifyJoinSequence(helper, server, player);
				verifyWarpsPerRegion(helper, player, kantoIndex, test2Index);
				verifyPresenceAndProgress(helper, player);
			});
		} finally {
			restore(server, before);
		}
	}

	private static void verifyJoinSequence(GameTestHelper helper, MinecraftServer server, ServerPlayer player) {
		RelayTap.capture(player, sent -> {
			int count = WorldIndexSync.sendTo(server, player);
			int regionParts = WorldIndexSync.currentRegionParts(server).size();
			int kantoParts = WorldIndexSync.currentParts(server, KANTO).size();
			int test2Parts = WorldIndexSync.currentParts(server, TEST2).size();
			int slotParts = WorldIndexSync.currentSlotParts(server).size();
			int kantoShapes = WorldIndexSync.currentShapeParts(server, KANTO).size();
			int test2Shapes = WorldIndexSync.currentShapeParts(server, TEST2).size();
			helper.assertTrue(regionParts >= 1 && kantoParts >= 1 && test2Parts >= 1 && slotParts >= 1 && kantoShapes >= 2 && test2Shapes >= 2,
					"parts: regions " + regionParts + ", indices " + kantoParts + "+" + test2Parts + ", slots " + slotParts + ", shapes " + kantoShapes + "+" + test2Shapes);
			int expected = regionParts + kantoParts + test2Parts + slotParts + kantoShapes + test2Shapes;
			helper.assertTrue(count == expected && sent.size() == count, "parts sent: " + count + " (expected " + expected + ", captured " + sent.size() + ")");
			int at = 0;
			List<Envelope> regionMessages = sent.subList(at, at += regionParts);
			helper.assertTrue(regionMessages.stream().allMatch(m -> m.type().equals(MsgTypes.WORLD_REGIONS) && regionOf(m) == null), "world.regions comes first, untagged");
			List<Envelope> kantoIndexMessages = sent.subList(at, at += kantoParts);
			helper.assertTrue(kantoIndexMessages.stream().allMatch(m -> m.type().equals(MsgTypes.WORLD_INDEX) && KANTO.equals(regionOf(m))), "kanto's index parts, tagged kanto");
			List<Envelope> test2IndexMessages = sent.subList(at, at += test2Parts);
			helper.assertTrue(test2IndexMessages.stream().allMatch(m -> m.type().equals(MsgTypes.WORLD_INDEX) && TEST2.equals(regionOf(m))), "test2's index parts, tagged test2");
			List<Envelope> slotMessages = sent.subList(at, at += slotParts);
			helper.assertTrue(slotMessages.stream().allMatch(m -> m.type().equals(MsgTypes.WORLD_SLOTS) && regionOf(m) == null), "one untagged world.slots after the indices");
			List<Envelope> kantoShapeMessages = sent.subList(at, at += kantoShapes);
			List<Envelope> test2ShapeMessages = sent.subList(at, at += test2Shapes);
			assertShapes(helper, kantoShapeMessages, KANTO);
			assertShapes(helper, test2ShapeMessages, TEST2);
			// the world.regions document: the table and both records
			JsonObject document = assemble(helper, regionMessages);
			helper.assertTrue(document.get("version").getAsInt() == 1 && document.has("world"), "world.regions document " + document.keySet());
			JsonArray tableEntries = document.getAsJsonObject("table").getAsJsonArray("regions");
			JsonArray records = document.getAsJsonArray("regions");
			helper.assertTrue(tableEntries.size() == 2 && records.size() == 2, "world.regions lists " + tableEntries.size() + " entries, " + records.size() + " records");
			helper.assertTrue(tableEntries.get(0).getAsJsonObject().get("id").getAsString().equals(KANTO)
					&& tableEntries.get(1).getAsJsonObject().get("id").getAsString().equals(TEST2), "the table isn't in slot order");
			helper.assertTrue(records.get(1).getAsJsonObject().get("id").getAsString().equals(TEST2)
					&& records.get(1).getAsJsonObject().getAsJsonArray("families").get(0).getAsInt() == 16, "test2's record isn't relayed whole");
		});
	}

	private static void assertShapes(GameTestHelper helper, List<Envelope> messages, String region) {
		helper.assertTrue(messages.stream().allMatch(m -> m.type().equals(MsgTypes.WORLD_SHAPES) && region.equals(regionOf(m))),
				region + "'s shapes aren't tagged " + region);
		List<String> docs = messages.stream().map(m -> m.data().get("doc").getAsString()).toList();
		helper.assertTrue(docs.contains("index") && docs.contains("map") && docs.indexOf("map") == docs.lastIndexOf("index") + 1,
				region + "'s shape index must come first, then its map: " + docs);
	}

	/** The parts' texts joined in order, parsed. */
	private static JsonObject assemble(GameTestHelper helper, List<Envelope> parts) {
		List<Envelope> ordered = new ArrayList<>(parts);
		ordered.sort(Comparator.comparingInt(m -> m.data().get("index").getAsInt()));
		StringBuilder text = new StringBuilder();
		for (Envelope part : ordered) {
			text.append(part.data().get("part").getAsString());
		}
		try {
			return ProtoJson.parseObject(text.toString());
		} catch (ProtocolException e) {
			throw new GameTestAssertException("the world.regions parts don't make a document: " + e.code());
		}
	}

	private static String request(long seq, String data) {
		return "{\"type\":\"warp.request\",\"seq\":" + seq + ",\"data\":" + data + "}";
	}

	private static void assertAt(GameTestHelper helper, ServerPlayer player, Vec3 want, float yaw, String what) {
		helper.assertTrue(player.position().distanceTo(want) < 1.0E-6, what + ": not at the cell's centre");
		helper.assertTrue(Math.abs(Mth.wrapDegrees(player.getYRot() - yaw)) < 1.0E-3, what + ": facing the wrong way");
	}

	private static void assertDone(GameTestHelper helper, Envelope reply, long re, boolean ok) {
		helper.assertTrue(reply.type().equals(MsgTypes.WARP_DONE), "expected warp.done, got " + reply.type());
		helper.assertTrue(reply.re() != null && reply.re() == re, "warp.done must answer seq " + re);
		helper.assertTrue(reply.data().get("ok").getAsBoolean() == ok, "warp.done ok must be " + ok + " for seq " + re);
	}

	/** A warp names its region; without one, the player's position says which; an unknown region is refused as no_index. */
	private static void verifyWarpsPerRegion(GameTestHelper helper, ServerPlayer player, WorldIndex kantoIndex, WorldIndex test2Index) {
		RelayService relay = RelayService.get();
		RelayTap.capture(player, sent -> {
			long noIndex = WarpService.refusals(WarpService.Refusal.NO_INDEX);
			relay.receive(player, request(7, "{\"to\":{\"map\":\"TEST_HOUSE\",\"x\":4,\"y\":8,\"facing\":\"up\"},\"kind\":\"door\",\"region\":\"" + TEST2 + "\"}"));
			assertAt(helper, player, test2Index.toBlock("TEST_HOUSE", 4, 8), Dir.UP.yaw(), "a warp into test2");
			assertDone(helper, sent.get(0), 7, true);
			// standing in test2 now: a request without a region resolves by position
			relay.receive(player, request(8, "{\"to\":{\"map\":\"TEST_TOWN\",\"x\":5,\"y\":6,\"facing\":\"down\"}}"));
			assertAt(helper, player, test2Index.toBlock("TEST_TOWN", 5, 6), Dir.DOWN.yaw(), "a warp by position");
			assertDone(helper, sent.get(1), 8, true);
			relay.receive(player, request(9, "{\"to\":{\"map\":\"TEST_TOWN\",\"x\":5,\"y\":6,\"facing\":\"down\"},\"region\":\"" + KANTO + "\"}"));
			assertAt(helper, player, kantoIndex.toBlock("TEST_TOWN", 5, 6), Dir.DOWN.yaw(), "a warp back into kanto");
			assertDone(helper, sent.get(2), 9, true);
			Vec3 before = player.position();
			relay.receive(player, request(10, "{\"to\":{\"map\":\"TEST_TOWN\",\"x\":5,\"y\":6,\"facing\":\"down\"},\"region\":\"nowhere\"}"));
			helper.assertTrue(player.position().equals(before), "a warp into an unknown region moved the player");
			assertDone(helper, sent.get(3), 10, false);
			helper.assertTrue(WarpService.refusals(WarpService.Refusal.NO_INDEX) == noIndex + 1, "an unknown region counts as no_index");
			helper.assertTrue(sent.size() == 4, "every request is answered once, got " + sent.size());
		});
	}

	private static String envelope(String type, long seq, String data) {
		return "{\"type\":\"" + type + "\",\"seq\":" + seq + ",\"data\":" + data + "}";
	}

	/** region.state is stored per player (unknown ids refused); game.status is stored per region. */
	private static void verifyPresenceAndProgress(GameTestHelper helper, ServerPlayer player) {
		RelayService relay = RelayService.get();
		RelayTap.capture(player, sent -> {
			relay.receive(player, envelope(MsgTypes.REGION_STATE, 30, "{\"followed\":\"" + TEST2 + "\",\"parked\":[\"" + KANTO + "\"]}"));
			RegionPresence presence = RegionPresenceService.of(player);
			helper.assertTrue(TEST2.equals(presence.followed()) && presence.parked().equals(List.of(KANTO)), "presence " + presence);
			helper.assertTrue(RegionPresenceService.hasLink(player, KANTO) && RegionPresenceService.hasLink(player, TEST2), "the links aren't known");
			helper.assertTrue(sent.isEmpty(), "a good region.state was answered: " + sent.stream().map(Envelope::type).toList());
			relay.receive(player, envelope(MsgTypes.REGION_STATE, 31, "{\"followed\":\"nowhere\",\"parked\":[]}"));
			helper.assertTrue(sent.size() == 1 && sent.get(0).type().equals(MsgTypes.ERROR)
					&& RegionPresenceService.UNKNOWN.equals(sent.get(0).data().get("code").getAsString()) && Long.valueOf(31).equals(sent.get(0).re()),
					"an unknown region must be refused with region.unknown");
			helper.assertTrue(TEST2.equals(RegionPresenceService.of(player).followed()), "a refused region.state changed the presence");

			relay.receive(player, envelope(MsgTypes.GAME_STATUS, 40, "{\"game\":\"red\",\"region\":\"" + TEST2 + "\",\"playthrough\":\"saved\",\"ingame\":true,"
					+ "\"started\":true,\"badges\":2,\"markers\":{\"starter\":true,\"champion\":false}}"));
			ProgressRecord progress = ProgressService.of(player, TEST2);
			helper.assertTrue(progress.badges() == 2 && progress.marker("starter") && !progress.marker("champion") && progress.isStarted(),
					"test2's progress " + progress);
			helper.assertTrue(ProgressService.of(player, KANTO).badges() == 0 && !ProgressService.of(player, KANTO).isStarted(), "kanto's progress changed");
			relay.receive(player, envelope(MsgTypes.GAME_STATUS, 41, "{\"game\":\"red\",\"region\":\"nowhere\",\"playthrough\":\"title\",\"ingame\":false,"
					+ "\"started\":false,\"badges\":0,\"markers\":{}}"));
			helper.assertTrue(sent.size() == 2 && sent.get(1).type().equals(MsgTypes.ERROR) && "bad_message".equals(sent.get(1).data().get("code").getAsString()),
					"a game.status for an unknown region must be refused");
		});
	}

	// ---- a legacy world ----

	/** A legacy world's wire is exactly today's: no world.regions, no region fields; its reports land under kanto. */
	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void aLegacyWorldKeepsTodaysWire(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		WorldIndex before = WorldIndexSync.current(server);
		try {
			WorldIndex golden = WarpGameTests.publishSyntheticIndex(helper);
			helper.assertFalse(WorldIndexSync.isRegionWorld(server), "a legacy publish made a region world");
			Regions regions = Regions.of(server);
			helper.assertTrue(regions != null && regions.isLegacyWorld() && regions.ids().equals(List.of(KANTO)) && regions.legacy() != null
					&& regions.legacy().isLegacy(), "the services don't see one implicit kanto");
			helper.assertTrue(KANTO.equals(Regions.legacyId(server)), "legacy id " + Regions.legacyId(server));
			MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
				int count = WorldIndexSync.sendTo(server, player);
				int indexParts = WorldIndexSync.currentParts(server).size();
				helper.assertTrue(WorldIndexSync.currentRegionParts(server).isEmpty(), "a legacy world has world.regions parts");
				helper.assertTrue(count >= indexParts && sent.size() == count, "parts sent: " + count);
				helper.assertTrue(sent.stream().noneMatch(m -> m.type().equals(MsgTypes.WORLD_REGIONS)), "a legacy world sent world.regions");
				helper.assertTrue(sent.stream().noneMatch(m -> m.data().has("region")), "a legacy world tagged a part with a region");
				helper.assertTrue(sent.subList(0, indexParts).stream().allMatch(m -> m.type().equals(MsgTypes.WORLD_INDEX)), "the index comes first");
				helper.assertTrue(sent.get(0).data().get("hash").getAsString().equals(golden.hash()), "another index arrived");
				// a report without a region lands under the implicit region
				RelayService.get().receive(player, envelope(MsgTypes.GAME_STATUS, 50, "{\"game\":\"red\",\"playthrough\":\"fresh\",\"ingame\":true,"
						+ "\"started\":true,\"badges\":3,\"markers\":{\"starter\":true}}"));
				helper.assertTrue(ProgressService.of(player, KANTO).badges() == 3 && ProgressService.of(player, KANTO).marker("starter"),
						"the legacy region's progress wasn't stored");
				RelayService.get().receive(player, envelope(MsgTypes.REGION_STATE, 51, "{\"followed\":\"" + KANTO + "\",\"parked\":[]}"));
				helper.assertTrue(KANTO.equals(RegionPresenceService.of(player).followed()), "following the legacy region was refused");
				helper.assertTrue(sent.size() == count, "a legacy report was answered with " + sent.subList(count, sent.size()).stream().map(Envelope::type).toList());
			}));
		} finally {
			restore(server, before);
		}
		helper.succeed();
	}
}
