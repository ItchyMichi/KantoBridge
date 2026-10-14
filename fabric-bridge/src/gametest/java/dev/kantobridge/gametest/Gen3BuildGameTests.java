package dev.kantobridge.gametest;

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
import dev.kantobridge.scene.gen3.QuarterPages;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.RegionRecord;
import dev.kantobridge.world.RegionTable;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.StoryCellType;
import dev.kantobridge.world.WorldIndex;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
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
 * MG7 (G3E, docs/specs/GEN3_BRIDGE_PLAN.md §6): the synthetic Gen 3 export (tools/fake_gen1/synth3.py) built as the
 * region {@code hoenn} on a real server: alone at slot 1 (its 80 families from 0), then after the synthetic Kanto at
 * slot 0 in one world (16..95). Far from every other test; each build writes into its own temp folder, publishes nothing
 * and writes no pack.
 */
public class Gen3BuildGameTests implements FabricGameTest {
	private static final String BATCH = "kantobridge_gen3";
	/** {@code TestData.G3_ORIGIN_X/Z}: far from the other builds (3.2M .. 7.6M on other axes or offsets). */
	static final int ORIGIN_X = 4_800_000;
	static final int ORIGIN_Z = 3_200_000;
	private static final String EMERALD = "kantobridge/synthetic_bundle/emerald/";
	private static final String RED = "kantobridge/synthetic_bundle/red/";
	/** MG8: the synthetic Hoenn's town with the Hoenn half of a connecting route (synth3's pass variant). */
	private static final String EMERALD_PASS = "kantobridge/synthetic_pass/emerald/";
	private static final String HOENN = "hoenn";
	private static final String DATA = "kantobridge";
	private static final String POOLS = "datapacks/kantobridge_generated/data/kantobridge/habitat_pools/";

	private static ExportBundle bundle(String prefix) {
		try {
			return ExportBundle.fromClasspath(Gen3BuildGameTests.class.getClassLoader(), prefix);
		} catch (BundleException e) {
			throw new GameTestAssertException("synthetic bundle unreadable: " + e.code());
		}
	}

	/** The synthetic ground metatile's first quarter. */
	private static int groundQuarter() {
		try {
			return bundle(EMERALD).g3tiles("TEST_PAIR_A").quarters(0)[0];
		} catch (BundleException e) {
			throw new GameTestAssertException("TEST_PAIR_A unreadable: " + e.code());
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

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 9000)
	public void hoennBuildsAsARegion(GameTestHelper helper) {
		Path dir = temp("kantobridge-gen3");
		BuildTask[] task = {start(helper, dir, EMERALD, HOENN, 1)};
		helper.startSequence()
				.thenWaitUntil(() -> waitFor(helper, task))
				.thenExecute(() -> verifyHoenn(helper, task[0], 0, 79))
				.thenExecute(() -> delete(dir))
				.thenSucceed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH + "_2", timeoutTicks = 12000)
	public void kantoThenHoennShareOneWorld(GameTestHelper helper) {
		Path dir = temp("kantobridge-gen3-two");
		BuildTask[] kanto = {start(helper, dir, RED, RegionRecord.LEGACY_ID, 0)};
		BuildTask[] hoenn = new BuildTask[1];
		helper.startSequence()
				.thenWaitUntil(() -> waitFor(helper, kanto))
				.thenExecute(() -> helper.assertTrue(kanto[0].succeeded(), "kanto build failed: " + kanto[0].report().error))
				.thenExecute(() -> hoenn[0] = start(helper, dir, EMERALD, HOENN, 1))
				.thenWaitUntil(() -> waitFor(helper, hoenn))
				.thenExecute(() -> {
					verifyHoenn(helper, hoenn[0], 16, 95);
					SlotMap slots = slots(dir);
					SlotMap.Range kantoFamilies = slots.families(RegionRecord.LEGACY_ID);
					helper.assertTrue(kantoFamilies != null && kantoFamilies.lo() == 0 && kantoFamilies.hi() == 15, "kanto's range moved: " + kantoFamilies);
					helper.assertTrue("red".equals(slots.artSet(RegionRecord.LEGACY_ID)) && "emerald".equals(slots.artSet(HOENN)),
							"the slot map's art sets: " + slots.artSet(RegionRecord.LEGACY_ID) + ", " + slots.artSet(HOENN));
					helper.assertTrue(Files.isRegularFile(dir.resolve(POOLS + "test_town.json")), "kanto's pool left the datapack");
				})
				.thenExecute(() -> delete(dir))
				.thenSucceed();
	}

	/**
	 * MG8 (docs/specs/HOENN_PASS_PLAN.md HP5): the synthetic Hoenn's town with the Hoenn half of a connecting route,
	 * built as region hoenn: the half in the region's index with its pass to Kanto and its gate house, trainers in the
	 * trainers' graphic with the town's neighbours in their parties, the route map placed at the trailhead, and blocks in
	 * the zone's box.
	 */
	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH + "_pass", timeoutTicks = 12000)
	public void hoennsHalfOfThePassIsBuilt(GameTestHelper helper) {
		Path dir = temp("kantobridge-gen3-pass");
		BuildTask[] task = {start(helper, dir, EMERALD_PASS, HOENN, 1)};
		helper.startSequence()
				.thenWaitUntil(() -> waitFor(helper, task))
				.thenExecute(() -> {
					helper.assertTrue(task[0].succeeded(), "hoenn build failed: " + task[0].report().error);
					WorldIndex index = index(dir, HOENN);
					helper.assertTrue(index.zones().size() == 1, "zones: " + index.zones().size());
					WorldIndex.Zone zone = index.zones().get(0);
					helper.assertTrue("test_pass".equals(zone.id()) && "KB_ROUTE_TEST_PASS_TO".equals(zone.routeMap()), "zone " + zone.id());
					helper.assertTrue(zone.pass() != null && "kanto".equals(zone.pass().toRegion()) && "to".equals(zone.pass().end()),
							"the pass to Kanto");
					helper.assertTrue(zone.gateHouse() != null, "the gate house");
					helper.assertTrue(!zone.trainers().isEmpty(), "trainers from the town's neighbours");
					for (WorldIndex.ZoneTrainer trainer : zone.trainers()) {
						helper.assertTrue("OBJ_EVENT_GFX_TEST_TRAINER".equals(trainer.sprite()), "sprite " + trainer.sprite());
						for (WorldIndex.PartyMember member : trainer.party()) {
							helper.assertTrue(member.species().startsWith("TEST_"), "species " + member.species());
						}
					}
					helper.assertTrue(index.map("KB_ROUTE_TEST_PASS_TO") != null, "the route map's placement");
					// something stands in the zone's box (its floor and walls)
					int solid = 0;
					for (int x = zone.x(); x < zone.x() + zone.w(); x += 4) {
						for (int z = zone.z(); z < zone.z() + zone.d(); z += 4) {
							for (int y = zone.y() - 2; y <= zone.y() + 2; y++) {
								if (!helper.getLevel().getBlockState(new net.minecraft.core.BlockPos(x, y, z)).isAir()) {
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

	/** The hoenn region's files, record, slot-map range, pools and blocks. */
	private static void verifyHoenn(GameTestHelper helper, BuildTask task, int familyLo, int familyHi) {
		BuildReport report = task.report();
		helper.assertTrue(task.succeeded(), "hoenn build failed: " + report.error);
		helper.assertTrue(report.region != null && HOENN.equals(report.region.region()) && report.region.slot() == 1, "report region " + report.region);
		helper.assertTrue(report.region.familyLo() == familyLo && report.region.familyHi() == familyHi,
				"hoenn families " + report.region.familyLo() + ".." + report.region.familyHi());
		helper.assertTrue(report.warnings == 0, "warnings " + report.warnings);
		Path worldDir = task.worldDir();
		helper.assertTrue(Files.isRegularFile(regionDir(worldDir, HOENN).resolve(BuildTask.INDEX_FILE)), "no hoenn index");
		RegionTable.Entry entry = null;
		try {
			entry = RegionTable.fromJson(text(RegionTable.file(worldDir.resolve(DATA)))).byId(HOENN);
		} catch (DataException e) {
			throw new GameTestAssertException("regions.json is invalid: " + e.problem());
		}
		helper.assertTrue(entry != null && entry.slot() == 1 && "emerald".equals(entry.game()), "regions.json's hoenn entry");
		RegionRecord record = record(worldDir, HOENN);
		helper.assertTrue("gen3".equals(record.family()), "hoenn's family " + record.family());
		helper.assertTrue("emerald".equals(record.namespaces().artSet()), "hoenn's art set " + record.namespaces().artSet());
		helper.assertTrue(record.families().lo() == familyLo && record.families().hi() == familyHi, "hoenn's record families " + record.families());
		helper.assertTrue(record.poolPrefix().equals(HOENN + "_"), "hoenn's pool prefix " + record.poolPrefix());
		SlotMap slots = slots(worldDir);
		SlotMap.Range range = slots.families(HOENN);
		helper.assertTrue(range != null && range.lo() == familyLo && range.hi() == familyHi, "hoenn's slot-map range " + range);
		WorldIndex index = index(worldDir, HOENN);
		helper.assertTrue(index.slotMapHash().equals(slots.regionHash(HOENN)), "hoenn's index doesn't name its own slice");
		helper.assertTrue(index.maps().keySet().equals(java.util.Set.of("EM_TEST_TOWN", "EM_TEST_ROUTE", "EM_TEST_HOUSE")), "maps " + index.maps().keySet());
		MapPlacement town = index.map("EM_TEST_TOWN");
		helper.assertTrue(town.x() == ORIGIN_X + RegionTable.PITCH && town.z() == ORIGIN_Z && town.y() == 64, "the town's origin");
		helper.assertTrue(QuarterPages.PALETTE.equals(town.palette()), "the town's palette " + town.palette());
		helper.assertTrue(!index.habitats().isEmpty() && index.habitats().stream().allMatch(h -> h.pool().equals("kantobridge:hoenn_em_test_route")),
				"hoenn's habitats");
		helper.assertTrue(Files.isRegularFile(worldDir.resolve(POOLS + "hoenn_em_test_route.json")), "the route's pool isn't in the datapack");

		ServerLevel level = helper.getLevel();
		// Ground: a full tile block showing its quarter's slot in hoenn's range, open above.
		BlockPos ground = index.cellOrigin("EM_TEST_TOWN", 1, 1);
		BlockState groundState = level.getBlockState(ground.below());
		int quarter = groundQuarter();
		SlotMap.Slot slot = slots.slot(HOENN, new SlotMap.Key(QuarterPages.page(quarter), QuarterPages.PALETTE, QuarterPages.index(quarter),
				SlotMap.Shape.FULL));
		helper.assertTrue(slot != null && slot.family() >= familyLo && slot.family() <= familyHi, "the ground's slot " + slot);
		helper.assertTrue(groundState.is(KbBlocks.tileFull().get(slot.family())) && TileBlock.slot(groundState) == slot.slot(), "ground shows the wrong slot");
		helper.assertTrue(level.getBlockState(ground).isAir(), "something stands on open ground");
		// The ledge row jumps south.
		BlockState collider = level.getBlockState(index.cellOrigin("EM_TEST_TOWN", 0, 4));
		helper.assertTrue(collider.is(KbBlocks.LEDGE_COLLIDER) && collider.getValue(LedgeColliderBlock.FACING) == Direction.SOUTH, "the ledge's collider");
		// Water on the route: a half block and its story cell.
		BlockPos water = index.cellOrigin("EM_TEST_ROUTE", 4, 0);
		helper.assertTrue(level.getBlockState(water.below()).getBlock() instanceof TileBlock tile && KbBlocks.tileHalf().contains(tile),
				"water is not a half tile");
		helper.assertTrue(level.getBlockState(water).is(KbBlocks.STORY_CELL) && level.getBlockEntity(water) instanceof StoryCellBlockEntity cell
				&& cell.type() == StoryCellType.WATER && "EM_TEST_ROUTE:4:0".equals(cell.cellKey()), "the water's story cell");
		// The standing person's story cell; none for the walker.
		BlockPos person = index.cellOrigin("EM_TEST_TOWN", 5, 5);
		helper.assertTrue(level.getBlockEntity(person) instanceof StoryCellBlockEntity cell && cell.type() == StoryCellType.OBJECT,
				"the standing person has no story cell");
		helper.assertTrue(level.getBlockState(index.cellOrigin("EM_TEST_TOWN", 1, 5)).isAir(), "the walker got a story cell");
		// The raised grass patch's north wall: an edge wall on its north-west block, at the feet or, where a grass tuft
		// stands there, in the block above.
		BlockPos feet = index.cellOrigin("EM_TEST_ROUTE", 0, 3);
		BlockState wall = level.getBlockState(feet).is(KbBlocks.EDGE_WALL) ? level.getBlockState(feet) : level.getBlockState(feet.above());
		helper.assertTrue(wall.is(KbBlocks.EDGE_WALL) && wall.getValue(EdgeWallBlock.NORTH) && !wall.getValue(EdgeWallBlock.EAST),
				"the elevation wall " + wall);
		// The house, a layer down under its door: a floor, open above.
		MapPlacement house = index.map("EM_TEST_HOUSE");
		helper.assertTrue(house.layer() == 1 && "EM_TEST_TOWN".equals(house.parent()), "the house's layer and parent");
		BlockPos floor = index.cellOrigin("EM_TEST_HOUSE", 1, 2);
		helper.assertTrue(level.getBlockState(floor.below()).getBlock() instanceof TileBlock && level.getBlockState(floor).isAir(), "the house's floor");
	}
}
