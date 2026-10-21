package dev.kantobridge.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.block.EdgeWallBlock;
import dev.kantobridge.block.KbBlocks;
import dev.kantobridge.block.LedgeColliderBlock;
import dev.kantobridge.block.StoryCellBlockEntity;
import dev.kantobridge.block.TileBlock;
import dev.kantobridge.builder.BuildListener;
import dev.kantobridge.builder.BuildOptions;
import dev.kantobridge.builder.BuildReport;
import dev.kantobridge.builder.BuildTask;
import dev.kantobridge.builder.KantoWorldBuilder;
import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.scene.gen3.QuarterPages;
import dev.kantobridge.server.ZoneService;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.RegionRecord;
import dev.kantobridge.world.RegionTable;
import dev.kantobridge.world.Scale;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.StoryCellType;
import dev.kantobridge.world.WorldIndex;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * MG9 (G2E, docs/specs/GEN2_BRIDGE_PLAN.md §7, §10): the synthetic Gen 2 export (tools/fake_gen1/synth2.py) built as the
 * region {@code johto} at slot 2 on a real server: alone (its 16 families from 0), then after the synthetic Kanto at slot
 * 0 and the synthetic Hoenn at slot 1 in one world (J7: three ranges, 0..15, 16..95 and 96..111, and three art sets).
 * Far from every other test; each build writes into its own temp folder, publishes nothing and writes no pack.
 */
public class Gen2BuildGameTests implements FabricGameTest {
	private static final String BATCH = "kantobridge_gen2";
	/** {@code TestData.G2_ORIGIN_X/Z}: far from the other builds. */
	static final int ORIGIN_X = 6_400_000;
	static final int ORIGIN_Z = 3_200_000;
	private static final String CRYSTAL = "kantobridge/synthetic_bundle/crystal/";
	/** G2H: the town with a Wild Routes end (synth2.resources(variant="pass")). */
	private static final String CRYSTAL_PASS = "kantobridge/synthetic_pass/crystal/";
	private static final String EMERALD = "kantobridge/synthetic_bundle/emerald/";
	private static final String RED = "kantobridge/synthetic_bundle/red/";
	private static final String JOHTO = "johto";
	private static final String HOENN = "hoenn";
	private static final int SLOT = 2;
	private static final String DATA = "kantobridge";
	private static final String POOLS = "datapacks/kantobridge_generated/data/kantobridge/habitat_pools/";

	private static ExportBundle bundle(String prefix) {
		try {
			return ExportBundle.fromClasspath(Gen2BuildGameTests.class.getClassLoader(), prefix);
		} catch (BundleException e) {
			throw new GameTestAssertException("synthetic bundle unreadable: " + e.code());
		}
	}

	/** The synthetic ground block's first tile. */
	private static int groundTile() {
		try {
			return bundle(CRYSTAL).g2tiles("TILESET_TEST_1").tile(1, 0, 0);
		} catch (BundleException e) {
			throw new GameTestAssertException("TILESET_TEST_1 unreadable: " + e.code());
		}
	}

	private static BuildTask start(GameTestHelper helper, Path dir, String prefix, String region, int slot) {
		BuildOptions options = BuildOptions.defaults().withOrigin(ORIGIN_X, ORIGIN_Z).withWorldSpawn(false).withWorldDir(dir).withWritePack(false)
				.withPublishIndex(false).withRegion(region, slot);
		return KantoWorldBuilder.start(helper.getLevel(), bundle(prefix), null, options, BuildListener.NONE);
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

	private static Path regionDir(Path worldDir, String region) {
		return worldDir.resolve(DATA).resolve(RegionRecord.DIR).resolve(region);
	}

	private static SlotMap slots(Path worldDir) {
		try {
			return SlotMap.fromJson(text(worldDir.resolve(DATA).resolve(BuildTask.SLOT_MAP_FILE)));
		} catch (DataException e) {
			throw new GameTestAssertException("slot_map.json is invalid: " + e.problem());
		}
	}

	private static RegionRecord record(Path worldDir, String region) {
		try {
			return RegionRecord.fromJson(text(regionDir(worldDir, region).resolve(RegionRecord.FILE)));
		} catch (DataException e) {
			throw new GameTestAssertException(region + "'s record is invalid: " + e.problem());
		}
	}

	private static WorldIndex index(Path worldDir, String region) {
		try {
			return WorldIndex.fromJson(text(regionDir(worldDir, region).resolve(BuildTask.INDEX_FILE)));
		} catch (DataException e) {
			throw new GameTestAssertException(region + "'s index is invalid: " + e.problem());
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

	/**
	 * G2H (docs/specs/GEN2_BRIDGE_PLAN.md §8): the synthetic Johto's town with the Johto half of a connecting route, built
	 * as region johto: the half in the region's index with its pass to Kanto and its gate house, trainers of the zone's
	 * class in its sprite with the town's neighbours in their parties, the route map placed at the trailhead, blocks in
	 * the zone's box, and a trainer's challenge carrying Crystal's class and species names as the hidden game's route end
	 * takes them (gen1-bridge's gen2 routes part).
	 */
	// (G2H: the test server's ticks run unthrottled while the builder plans the zone off its thread -- on a 2-core pod
	// 12,000 ticks passed in 5 s, before the plan was done -- so the budget is in ticks enough for a minute of planning)
	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH + "_pass", timeoutTicks = 72000)
	public void johtosHalfOfThePassIsBuilt(GameTestHelper helper) {
		Path dir = temp("kantobridge-gen2-pass");
		BuildTask[] task = {start(helper, dir, CRYSTAL_PASS, JOHTO, SLOT)};
		helper.startSequence()
				.thenWaitUntil(() -> waitFor(helper, task))
				.thenExecute(() -> {
					helper.assertTrue(task[0].succeeded(), "johto build failed: " + task[0].report().error);
					WorldIndex index = index(dir, JOHTO);
					helper.assertTrue(index.zones().size() == 1, "zones: " + index.zones().size());
					WorldIndex.Zone zone = index.zones().get(0);
					helper.assertTrue("test_pass".equals(zone.id()) && "KB_ROUTE_TEST_PASS_TO".equals(zone.routeMap()), "zone " + zone.id());
					helper.assertTrue(zone.pass() != null && "kanto".equals(zone.pass().toRegion()) && "to".equals(zone.pass().end()),
							"the pass to Kanto");
					helper.assertTrue(zone.gateHouse() != null, "the gate house");
					helper.assertTrue(!zone.trainers().isEmpty(), "trainers from the town's neighbours");
					for (WorldIndex.ZoneTrainer trainer : zone.trainers()) {
						helper.assertTrue("TEST_YOUNGSTER".equals(trainer.cls()), "class " + trainer.cls());
						helper.assertTrue("SPRITE_TEST_YOUNGSTER".equals(trainer.sprite()), "sprite " + trainer.sprite());
						for (WorldIndex.PartyMember member : trainer.party()) {
							helper.assertTrue(member.species().startsWith("TEST_"), "species " + member.species());
						}
					}
					helper.assertTrue(!zone.balls().isEmpty(), "no item balls");
					for (WorldIndex.ZoneBall ball : zone.balls()) {
						helper.assertTrue("POTION".equals(ball.item()) || "POKE_BALL".equals(ball.item()), "ball " + ball.item());
					}
					helper.assertTrue(index.map("KB_ROUTE_TEST_PASS_TO") != null, "the route map's placement");
					challenge(helper, index, zone);
					int solid = 0;
					for (int x = zone.x(); x < zone.x() + zone.w(); x += 4) {
						for (int z = zone.z(); z < zone.z() + zone.d(); z += 4) {
							for (int y = zone.y() - 2; y <= zone.y() + 2; y++) {
								if (!helper.getLevel().getBlockState(new BlockPos(x, y, z)).isAir()) {
									solid++;
								}
							}
						}
					}
					helper.assertTrue(solid > 0, "the zone's box is empty");
				})
				.thenExecute(() -> delete(dir))
				.thenSucceed();
	}

	/** A Johto half's trainer challenges a player in front of it: {@code route.battle} carries Crystal's own names. */
	private static void challenge(GameTestHelper helper, WorldIndex index, WorldIndex.Zone zone) {
		WorldIndex.ZoneTrainer trainer = zone.trainers().get(0);
		MapPlacement routeMap = index.map(zone.routeMap());
		int cx = trainer.x() + trainer.facing().dx();
		int cy = trainer.y() + trainer.facing().dy();
		MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
			player.teleportTo(routeMap.x() + cx * Scale.CELL_BLOCKS + 1.0, zone.y(), routeMap.z() + cy * Scale.CELL_BLOCKS + 1.0);
			Envelope battle = null;
			for (long tick = 1_000; tick < 1_100 && battle == null; tick++) {
				ZoneService.tickPlayer(player, JOHTO, index, tick);
				battle = sent.stream().filter(m -> m.type().equals(MsgTypes.ROUTE_BATTLE)).findFirst().orElse(null);
			}
			helper.assertTrue(battle != null, "no route.battle from " + trainer.id());
			JsonObject data = battle.data();
			helper.assertTrue("TEST_YOUNGSTER".equals(data.get("class").getAsString()), "battle class " + data.get("class"));
			helper.assertTrue(data.getAsJsonArray("party").size() == trainer.party().size(), "battle party");
			for (JsonElement member : data.getAsJsonArray("party")) {
				helper.assertTrue(member.getAsJsonObject().get("species").getAsString().startsWith("TEST_"), "battle species");
			}
			ZoneService.encounters().forget(player.getUUID());
		}));
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 9000)
	public void johtoBuildsAsARegion(GameTestHelper helper) {
		Path dir = temp("kantobridge-gen2");
		BuildTask[] task = {start(helper, dir, CRYSTAL, JOHTO, SLOT)};
		helper.startSequence()
				.thenWaitUntil(() -> waitFor(helper, task))
				.thenExecute(() -> verifyJohto(helper, task[0], 0, 15))
				.thenExecute(() -> delete(dir))
				.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH + "_3", timeoutTicks = 18000)
	public void kantoHoennAndJohtoShareOneWorld(GameTestHelper helper) {
		Path dir = temp("kantobridge-gen2-three");
		BuildTask[] kanto = {start(helper, dir, RED, RegionRecord.LEGACY_ID, 0)};
		BuildTask[] hoenn = new BuildTask[1];
		BuildTask[] johto = new BuildTask[1];
		helper.startSequence()
				.thenWaitUntil(() -> waitFor(helper, kanto))
				.thenExecute(() -> helper.assertTrue(kanto[0].succeeded(), "kanto build failed: " + kanto[0].report().error))
				.thenExecute(() -> hoenn[0] = start(helper, dir, EMERALD, HOENN, 1))
				.thenWaitUntil(() -> waitFor(helper, hoenn))
				.thenExecute(() -> helper.assertTrue(hoenn[0].succeeded(), "hoenn build failed: " + hoenn[0].report().error))
				.thenExecute(() -> johto[0] = start(helper, dir, CRYSTAL, JOHTO, SLOT))
				.thenWaitUntil(() -> waitFor(helper, johto))
				.thenExecute(() -> {
					verifyJohto(helper, johto[0], 96, 111);
					SlotMap slots = slots(dir);
					SlotMap.Range kantoFamilies = slots.families(RegionRecord.LEGACY_ID);
					SlotMap.Range hoennFamilies = slots.families(HOENN);
					helper.assertTrue(kantoFamilies != null && kantoFamilies.lo() == 0 && kantoFamilies.hi() == 15, "kanto's range moved: " + kantoFamilies);
					helper.assertTrue(hoennFamilies != null && hoennFamilies.lo() == 16 && hoennFamilies.hi() == 95, "hoenn's range moved: " + hoennFamilies);
					helper.assertTrue("red".equals(slots.artSet(RegionRecord.LEGACY_ID)) && "emerald".equals(slots.artSet(HOENN))
							&& "crystal".equals(slots.artSet(JOHTO)), "the slot map's art sets");
					helper.assertTrue(Files.isRegularFile(dir.resolve(POOLS + "test_town.json")), "kanto's pool left the datapack");
					helper.assertTrue(Files.isRegularFile(dir.resolve(POOLS + "hoenn_em_test_route.json")), "hoenn's pool left the datapack");
					RegionTable table;
					try {
						table = RegionTable.fromJson(text(RegionTable.file(dir.resolve(DATA))));
					} catch (DataException e) {
						throw new GameTestAssertException("regions.json is invalid: " + e.problem());
					}
					helper.assertTrue(table.byId(RegionRecord.LEGACY_ID) != null && table.byId(HOENN) != null && table.byId(JOHTO) != null,
							"three regions in the table");
				})
				.thenExecute(() -> delete(dir))
				.thenSucceed();
	}

	/** The johto region's files, record, slot-map range, pools and blocks. */
	private static void verifyJohto(GameTestHelper helper, BuildTask task, int familyLo, int familyHi) {
		BuildReport report = task.report();
		helper.assertTrue(task.succeeded(), "johto build failed: " + report.error);
		helper.assertTrue(report.region != null && JOHTO.equals(report.region.region()) && report.region.slot() == SLOT, "report region " + report.region);
		helper.assertTrue(report.region.familyLo() == familyLo && report.region.familyHi() == familyHi,
				"johto families " + report.region.familyLo() + ".." + report.region.familyHi());
		helper.assertTrue(report.warnings == 0, "warnings " + report.warnings);
		Path worldDir = task.worldDir();
		RegionTable.Entry entry;
		try {
			entry = RegionTable.fromJson(text(RegionTable.file(worldDir.resolve(DATA)))).byId(JOHTO);
		} catch (DataException e) {
			throw new GameTestAssertException("regions.json is invalid: " + e.problem());
		}
		helper.assertTrue(entry != null && entry.slot() == SLOT && "crystal".equals(entry.game()), "regions.json's johto entry");
		RegionRecord record = record(worldDir, JOHTO);
		helper.assertTrue("gen2".equals(record.family()), "johto's family " + record.family());
		helper.assertTrue("crystal".equals(record.namespaces().artSet()), "johto's art set " + record.namespaces().artSet());
		helper.assertTrue(record.families().lo() == familyLo && record.families().hi() == familyHi, "johto's record families " + record.families());
		helper.assertTrue(record.poolPrefix().equals(JOHTO + "_"), "johto's pool prefix " + record.poolPrefix());
		SlotMap slots = slots(worldDir);
		SlotMap.Range range = slots.families(JOHTO);
		helper.assertTrue(range != null && range.lo() == familyLo && range.hi() == familyHi, "johto's slot-map range " + range);
		WorldIndex index = index(worldDir, JOHTO);
		helper.assertTrue(index.slotMapHash().equals(slots.regionHash(JOHTO)), "johto's index doesn't name its own slice");
		helper.assertTrue(index.maps().keySet().equals(Set.of("G2_TEST_TOWN", "G2_TEST_ROUTE", "G2_TEST_HOUSE", "G2_TEST_CAVE", "G2_TEST_KANTO_TOWN")),
				"maps " + index.maps().keySet());
		MapPlacement town = index.map("G2_TEST_TOWN");
		helper.assertTrue(town.x() == ORIGIN_X + SLOT * RegionTable.PITCH && town.z() == ORIGIN_Z && town.y() == 64, "the town's origin");
		helper.assertTrue(QuarterPages.PALETTE.equals(town.palette()), "the town's palette " + town.palette());
		MapPlacement kantoTown = index.map("G2_TEST_KANTO_TOWN");
		helper.assertTrue(kantoTown.kind() == MapPlacement.Kind.SURFACE && kantoTown.x() == town.x() + 84, "Crystal's Kanto east of Johto (J3)");
		helper.assertTrue(index.map("G2_TEST_CAVE").dark(), "the cave is dark");
		helper.assertTrue(Files.isRegularFile(worldDir.resolve(POOLS + "johto_g2_test_route.json")), "the route's pool isn't in the datapack");
		helper.assertTrue(Files.isRegularFile(worldDir.resolve(POOLS + "johto_g2_test_cave.json")), "the cave's pool isn't in the datapack");
		// the cave in the 3D area (HEIGHTS_ALL_GAMES_PLAN D1): its top floor at 64, its habitat blocks reaching 6, so two
		MapPlacement cave = index.map("G2_TEST_CAVE");
		helper.assertTrue(WorldPlan.BIOME_CAVE_3D.equals(cave.biome()) && cave.y() == 64, "the cave in the 3D area " + cave);
		helper.assertTrue(index.habitats().size() == 3, "johto's habitats " + index.habitats().size());

		ServerLevel level = helper.getLevel();
		// Ground: a full tile block showing its tile's slot in johto's range, open above.
		BlockPos ground = index.cellOrigin("G2_TEST_TOWN", 0, 2);
		BlockState groundState = level.getBlockState(ground.below());
		int tile = groundTile();
		SlotMap.Slot slot = slots.slot(JOHTO, new SlotMap.Key(QuarterPages.page(tile), QuarterPages.PALETTE, QuarterPages.index(tile), SlotMap.Shape.FULL));
		helper.assertTrue(slot != null && slot.family() >= familyLo && slot.family() <= familyHi, "the ground's slot " + slot);
		helper.assertTrue(groundState.is(KbBlocks.tileFull().get(slot.family())) && TileBlock.slot(groundState) == slot.slot(), "ground shows the wrong slot");
		helper.assertTrue(level.getBlockState(ground).isAir(), "something stands on open ground");
		// The ledges hop down (south).
		BlockState collider = level.getBlockState(index.cellOrigin("G2_TEST_TOWN", 0, 7));
		helper.assertTrue(collider.is(KbBlocks.LEDGE_COLLIDER) && collider.getValue(LedgeColliderBlock.FACING) == Direction.SOUTH, "the ledge's collider");
		// Water: a half block and its story cell.
		BlockPos water = index.cellOrigin("G2_TEST_TOWN", 8, 4);
		helper.assertTrue(level.getBlockState(water.below()).getBlock() instanceof TileBlock half && KbBlocks.tileHalf().contains(half),
				"water is not a half tile");
		helper.assertTrue(level.getBlockState(water).is(KbBlocks.STORY_CELL) && level.getBlockEntity(water) instanceof StoryCellBlockEntity cell
				&& cell.type() == StoryCellType.WATER && "G2_TEST_TOWN:8:4".equals(cell.cellKey()), "the water's story cell");
		// The Cut tree, built open: its tile story cell, whose closed art the client draws per player.
		BlockPos tree = index.cellOrigin("G2_TEST_TOWN", 6, 4);
		helper.assertTrue(level.getBlockState(tree).is(KbBlocks.STORY_TILE) && level.getBlockEntity(tree) instanceof StoryCellBlockEntity cell
				&& cell.type() == StoryCellType.TILE && "G2_TEST_TOWN:6:4".equals(cell.cellKey()), "the Cut tree's story cell");
		// The standing person's story cell; none for the walker.
		helper.assertTrue(level.getBlockEntity(index.cellOrigin("G2_TEST_TOWN", 1, 4)) instanceof StoryCellBlockEntity cell
				&& cell.type() == StoryCellType.OBJECT, "the standing person has no story cell");
		helper.assertTrue(level.getBlockState(index.cellOrigin("G2_TEST_TOWN", 2, 5)).isAir(), "the walker got a story cell");
		// The fence's one-way wall: an edge wall on its north side.
		BlockPos feet = index.cellOrigin("G2_TEST_TOWN", 0, 4);
		BlockState wall = level.getBlockState(feet).is(KbBlocks.EDGE_WALL) ? level.getBlockState(feet) : level.getBlockState(feet.above());
		helper.assertTrue(wall.is(KbBlocks.EDGE_WALL) && wall.getValue(EdgeWallBlock.NORTH) && !wall.getValue(EdgeWallBlock.EAST), "the fence's wall " + wall);
		// The house under its door, a layer down (the cave left the layers for the 3D area): a floor, open above.
		MapPlacement house = index.map("G2_TEST_HOUSE");
		helper.assertTrue(house.layer() == 1 && "G2_TEST_TOWN".equals(house.parent()), "the house's layer and parent");
		BlockPos floor = index.cellOrigin("G2_TEST_HOUSE", 0, 3);
		helper.assertTrue(level.getBlockState(floor.below()).getBlock() instanceof TileBlock && level.getBlockState(floor).isAir(), "the house's floor");
	}
}
