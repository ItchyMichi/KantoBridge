package dev.kantobridge.gametest;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.CobblemonBlocks;
import com.cobblemon.mod.common.CobblemonPoiTypes;
import com.cobblemon.mod.common.api.habitats.HabitatPhaseOrder;
import com.cobblemon.mod.common.api.habitats.spawningstyle.NaturalHabitatSpawning;
import com.cobblemon.mod.common.api.spawning.SpawnCause;
import com.cobblemon.mod.common.api.spawning.SpawningZone;
import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail;
import com.cobblemon.mod.common.api.spawning.detail.SpawnDetail;
import com.cobblemon.mod.common.api.spawning.influence.SpawningZoneInfluence;
import com.cobblemon.mod.common.api.spawning.influence.detector.HabitatBlockDetector;
import com.cobblemon.mod.common.api.spawning.position.AreaSpawnablePosition;
import com.cobblemon.mod.common.api.spawning.position.calculators.SpawnablePositionCalculator;
import com.cobblemon.mod.common.api.spawning.spawner.PlayerSpawner;
import com.cobblemon.mod.common.api.spawning.spawner.SpawningZoneInput;
import com.cobblemon.mod.common.block.habitat.HabitatBlock;
import com.cobblemon.mod.common.block.habitat.HabitatBlockEntity;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.block.KbBlocks;
import dev.kantobridge.block.LedgeColliderBlock;
import dev.kantobridge.block.ShapeBlock;
import dev.kantobridge.block.StoryCellBlockEntity;
import dev.kantobridge.block.TileBlock;
import dev.kantobridge.builder.BuildOptions;
import dev.kantobridge.builder.BuildReport;
import dev.kantobridge.builder.BuildTask;
import dev.kantobridge.builder.KantoWorldBuilder;
import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import dev.kantobridge.server.HabitatService;
import dev.kantobridge.server.WorldIndexSync;
import dev.kantobridge.story.StoryRules;
import dev.kantobridge.story.StoryStateMessages;
import dev.kantobridge.story.StoryStates;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.StoryCellType;
import dev.kantobridge.world.WorldIndex;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

/**
 * Builds the synthetic export bundle (a made-up 10×9-block town, a hill north of it, a house and its upper floor) into the
 * test level, far from every other test, and checks the result (docs/PLAN.md §6 M2 [T]): blocks and heights, story cells, the ledge
 * collider, biomes, the written WorldIndex (against the golden file) and the report. The town's made-up grass table gets
 * a habitat pool and a configured habitat block under its grass, whose influence offers the pool's Pokémon on the
 * grass floor and nothing on the trees beside it (M6). Then rebuilds twice: the same plan again must change nothing, and
 * a town-only rebuild must clear the interiors the first build left (and keep the town's habitat block).
 *
 * <p>M8: two mock players whose made-up hidden games differ get their story cells from {@link StoryRules} and relay them
 * as {@code story.state}; in the built town the cut tree, the item ball and the water then close for one and open for
 * the other, a surfer rides on the water's surface, and a later delta (the tree cut) reaches only its own player.
 */
public class SyntheticBuildGameTests implements FabricGameTest {
	/** Must equal {@code TestData.ORIGIN_X/Z} in the JUnit tests, which hold the golden WorldIndex to the same origin. */
	public static final int ORIGIN_X = 3_200_000;
	public static final int ORIGIN_Z = 3_200_000;
	private static final String BUNDLE = "kantobridge/synthetic_bundle/red/";
	private static final String GOLDEN = "kantobridge/golden/synthetic_world_index.json";
	/** The JUnit tests' golden shapes of the same build: its shape index and the shape map's hash. */
	private static final String GOLDEN_SHAPES = "kantobridge/golden/synthetic_shapes.json";

	private static ExportBundle bundle() {
		try {
			return ExportBundle.fromClasspath(SyntheticBuildGameTests.class.getClassLoader(), BUNDLE);
		} catch (BundleException e) {
			throw new GameTestAssertException("synthetic bundle unreadable: " + e.code());
		}
	}

	private static BuildTask start(GameTestHelper helper, @Nullable List<String> maps) {
		BuildOptions options = BuildOptions.defaults().withOrigin(ORIGIN_X, ORIGIN_Z).withWorldSpawn(false);
		return KantoWorldBuilder.start(helper.getLevel(), bundle(), maps, options, dev.kantobridge.builder.BuildListener.NONE);
	}

	private static void waitFor(GameTestHelper helper, BuildTask[] task) {
		helper.assertTrue(task[0] != null && task[0].isDone(), "build still running");
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 6000)
	public void syntheticBundleBuilds(GameTestHelper helper) {
		BuildTask[] first = {start(helper, null)};
		BuildTask[] second = new BuildTask[1];
		BuildTask[] third = new BuildTask[1];
		helper.startSequence()
				.thenWaitUntil(() -> waitFor(helper, first))
				.thenExecute(() -> verifyFirstBuild(helper, first[0]))
				.thenExecute(() -> verifyRaisedCave(helper, first[0]))
				.thenExecute(() -> verifyShafts(helper, first[0]))
				.thenExecute(() -> verifyStoryCells(helper, first[0]))
				.thenExecute(() -> second[0] = start(helper, null))
				.thenWaitUntil(() -> waitFor(helper, second))
				.thenExecute(() -> verifyRebuild(helper, first[0], second[0]))
				.thenExecute(() -> third[0] = start(helper, List.of("TEST_TOWN")))
				.thenWaitUntil(() -> waitFor(helper, third))
				.thenExecute(() -> verifyTownOnly(helper, first[0], third[0]))
				.thenSucceed();
	}

	private static void verifyFirstBuild(GameTestHelper helper, BuildTask task) {
		BuildReport report = task.report();
		helper.assertTrue(task.succeeded(), "build failed: " + report.error);
		// The town, the hill, the house's two floors and the cave's two floors in the cave area (CAVES_3D_PLAN §3.3).
		helper.assertTrue(report.mapsBuilt == 6, "maps_built " + report.mapsBuilt);
		helper.assertTrue(report.overlaps == 0, "overlaps " + report.overlaps);
		helper.assertTrue(report.warnings == 0, "warnings " + report.warningCodes);
		helper.assertTrue(report.storyCells == 20, "story_cells " + report.storyCells);
		helper.assertTrue(report.storyCellBlocks == 80, "story-cell blocks " + report.storyCellBlocks);
		helper.assertTrue(report.ledgeColliders == 24, "ledge colliders " + report.ledgeColliders);
		helper.assertTrue(report.packTextures == 32, "pack_textures " + report.packTextures);
		helper.assertTrue(report.worldIndex, "world_index not written");
		helper.assertTrue(report.layers == 2, "layers " + report.layers);
		// The cave's rooms and shafts take 2 × 2 chunks (CAVES_3D_PLAN §3.5: no room draws the rock far from it).
		helper.assertTrue(report.chunks == 34, "chunks " + report.chunks);
		helper.assertTrue(report.biomeChunks == 34, "biome chunks " + report.biomeChunks);
		helper.assertTrue(report.blocksPlaced > 5000, "blocks_placed " + report.blocksPlaced);
		helper.assertTrue(report.lightBlocks > 0, "no interior lights");
		helper.assertTrue("TEST_TOWN".equals(report.spawnMap), "spawn map " + report.spawnMap);
		helper.assertTrue("ok".equals(report.status), "status " + report.status);

		WorldIndex index = readIndex(helper, task);
		helper.assertTrue(index.toJson().equals(golden(helper)), "the built WorldIndex differs from the golden file");
		helper.assertTrue(index.hash().equals(WorldIndexSync.currentHash(helper.getLevel().getServer())), "the new index was not published");
		helper.assertTrue(Files.isRegularFile(task.worldDir().resolve("kantobridge/slot_map.json")), "slot map not written");
		// The voxel plan's V3: the shape map the build wrote is the golden one, byte for byte.
		try {
			JsonObject shapes = ProtoJson.parseObject(resource(helper, GOLDEN_SHAPES));
			byte[] written = Files.readAllBytes(task.worldDir().resolve("kantobridge/" + BuildTask.SHAPE_MAP_FILE));
			helper.assertTrue(dev.kantobridge.world.Hashes.sha256(written).equals(shapes.get("mapHash").getAsString()),
					"the built shape map differs from the golden one");
			String shapeIndex = Files.readString(task.worldDir().resolve("kantobridge/" + BuildTask.SHAPE_INDEX_FILE), StandardCharsets.UTF_8);
			helper.assertTrue(dev.kantobridge.world.Hashes.sha256(shapeIndex).equals(shapes.get("indexHash").getAsString()),
					"the built shape index differs from the golden one");
		} catch (IOException | ProtocolException e) {
			throw new GameTestAssertException("shape files unreadable: " + e.getClass().getSimpleName());
		}
		// M9: the build's slot map goes out with its index (world.slots), and the files read back on a restart do the same.
		helper.assertTrue(index.slotMapHash().equals(WorldIndexSync.currentSlotsHash(helper.getLevel().getServer())),
				"the build's slot map was not published with its index");
		if (task.worldDir().equals(KantoWorldBuilder.worldDir(helper.getLevel()))) {
			WorldIndexSync.load(helper.getLevel().getServer());
			helper.assertTrue(index.hash().equals(WorldIndexSync.currentHash(helper.getLevel().getServer()))
					&& index.slotMapHash().equals(WorldIndexSync.currentSlotsHash(helper.getLevel().getServer())),
					"the world's index and slot map, read back from its folder, aren't the ones published");
		}
		helper.assertTrue(Files.isRegularFile(task.worldDir().resolve("kantobridge/build_report.json")), "report not written");
		helper.assertTrue(Files.isRegularFile(task.worldDir().resolve("datapacks/kantobridge_generated/pack.mcmeta")), "datapack not written");
		helper.assertTrue(Files.isRegularFile(task.worldDir().resolve("datapacks/kantobridge_generated/data/kantobridge/habitat_pools/test_town.json")),
				"the town's habitat pool isn't in the datapack");
		// The town's grass takes one; the cave's two floors six of a smaller reach (CAVES_3D_PLAN §3.3, HD8), 1F's
		// plateau reached from under the map's floor (§3.4).
		helper.assertTrue(report.habitatBlocks == 7, "habitat_blocks " + report.habitatBlocks);
		helper.assertTrue(report.pools == 3, "pools " + report.pools + " (the town's and the cave's two floors')");
		helper.assertTrue(Files.isRegularFile(task.packDir().resolve("pack.mcmeta")), "resource pack not written");

		ServerLevel level = helper.getLevel();
		SlotMap slots = task.plan().slotMap();
		// A border tree: a unit (V4), two shape_full blocks on its ground block, nothing above.
		BlockPos tree = index.cellOrigin("TEST_TOWN", 0, 0);
		helper.assertTrue(isTile(level, tree.below(), true) && level.getBlockState(tree).getBlock() instanceof ShapeBlock
				&& level.getBlockState(tree.above()).getBlock() instanceof ShapeBlock, "tree column incomplete");
		helper.assertTrue(level.getBlockState(tree.above(2)).isAir(), "tree column too tall");
		// Ground: one tile block under the feet, showing the slot of (atlas, palette, tile 0, full).
		BlockPos ground = index.cellOrigin("TEST_TOWN", 2, 2);
		BlockState groundState = level.getBlockState(ground.below());
		SlotMap.Slot groundSlot = slots.slot(new SlotMap.Key("tilesets.test", "TEST_PALETTE", 0, SlotMap.Shape.FULL));
		helper.assertTrue(groundState.is(KbBlocks.tileFull().get(groundSlot.family())) && TileBlock.slot(groundState) == groundSlot.slot(),
				"ground shows the wrong slot");
		helper.assertTrue(level.getBlockState(ground).isAir(), "something stands on open ground");
		// Water: a half block, and a story cell above it that knows its cell.
		BlockPos water = index.cellOrigin("TEST_TOWN", 12, 12);
		helper.assertTrue(level.getBlockState(water.below()).getBlock() instanceof TileBlock tile && KbBlocks.tileHalf().contains(tile),
				"water is not a half tile");
		assertStoryCell(helper, level, water, "TEST_TOWN:12:12", StoryCellType.WATER);
		assertStoryCell(helper, level, water.offset(1, 0, 1), "TEST_TOWN:12:12", StoryCellType.WATER);
		// The ledge row: a collider facing the hop direction (down = south).
		BlockPos ledge = index.cellOrigin("TEST_TOWN", 2, 13);
		BlockState collider = level.getBlockState(ledge);
		helper.assertTrue(collider.is(KbBlocks.LEDGE_COLLIDER) && collider.getValue(LedgeColliderBlock.FACING) == Direction.SOUTH,
				"ledge collider missing or facing the wrong way");
		helper.assertTrue(isTile(level, ledge.below(), true), "ledge art missing");
		// The cut tree: built open (ground), closed per player by a tile story cell. From V5 its closed art is meshed: the
		// story tile (a model block) carries the bottom, decor_leaves (no collision) the block above, in each column.
		BlockPos cut = index.cellOrigin("TEST_TOWN", 10, 14);
		helper.assertTrue(isTile(level, cut.below(), true), "the cut tree's ground is missing");
		assertStoryCell(helper, level, cut, "TEST_TOWN:10:14", StoryCellType.TILE);
		assertStoryCell(helper, level, cut.offset(1, 0, 1), "TEST_TOWN:10:14", StoryCellType.TILE);
		helper.assertTrue(level.getBlockState(cut).getRenderShape() == RenderShape.MODEL, "the story tile is drawn by the model");
		for (int dz = 0; dz < 2; dz++) {
			for (int dx = 0; dx < 2; dx++) {
				BlockState above = level.getBlockState(cut.offset(dx, 1, dz));
				helper.assertTrue(above.is(KbBlocks.DECOR_LEAVES), "no story carrier above the cut tree's story tile");
				helper.assertTrue(above.getCollisionShape(level, cut.offset(dx, 1, dz)).isEmpty(), "a story carrier collides");
				helper.assertTrue(level.getBlockState(cut.offset(dx, 2, dz)).isAir(), "the closed tree is two blocks high");
			}
		}
		helper.assertTrue(report.closedShapeCells == report.storyCellsByType.getOrDefault("tile", 0) && report.closedShapeCells == 1,
				"closed_shape_cells " + report.closedShapeCells);
		// STAY objects carry story cells; the walker doesn't.
		assertStoryCell(helper, level, index.cellOrigin("TEST_TOWN", 15, 4), "TEST_TOWN:15:4", StoryCellType.OBJECT);
		helper.assertTrue(level.getBlockState(index.cellOrigin("TEST_TOWN", 10, 8)).isAir(), "a walking NPC got a story cell");
		// Void fill around the town: within two cells of it, border trees as units (V4: two shape_full blocks on the
		// ground); beyond, 3 blocks above the ground.
		MapPlacement town = index.map("TEST_TOWN");
		BlockPos ring = new BlockPos(town.x() - 1, 63, town.z() - 1);
		helper.assertTrue(isTile(level, ring, true) && level.getBlockState(ring.above(2)).getBlock() instanceof ShapeBlock
				&& level.getBlockState(ring.above(3)).isAir(), "the void fill's ring is not a unit");
		BlockPos gap = new BlockPos(town.x() - 9, 63, town.z() - 9);
		helper.assertTrue(isTile(level, gap.above(3), true) && level.getBlockState(gap.above(4)).isAir(), "void fill is not 3 high");

		// The house, one layer down: floor, walls, counter, ceiling and lights; enclosed by its ring.
		MapPlacement house = index.map("TEST_HOUSE");
		helper.assertTrue(house.layer() == 1 && house.y() == 42, "house layer");
		int feet = house.y();
		BlockPos floor = new BlockPos(house.x() + 4, feet, house.z() + 6);
		helper.assertTrue(isTile(level, floor.below()) && level.getBlockState(floor).isAir(), "house floor");
		helper.assertTrue(isTile(level, new BlockPos(floor.getX(), feet + 3, floor.getZ())), "house ceiling");
		BlockState light = level.getBlockState(new BlockPos(house.x() + 5, feet + 2, house.z() + 5));
		helper.assertTrue(light.is(Blocks.LIGHT) && light.getValue(LightBlock.LEVEL) == 15, "house light");
		BlockPos counter = new BlockPos(house.x() + 8, feet, house.z() + 6);
		// The counter row is the synthetic profile's desk (P3's furniture): three blocks up to the ceiling.
		helper.assertTrue(isTile(level, counter) && isTile(level, counter.above()) && isTile(level, counter.above(2)), "the desk is not 3 blocks high");
		// A wall along the north edge rises to the ceiling (the voxel plan's V7 dressing).
		BlockPos wall = new BlockPos(house.x(), feet + 1, house.z());
		helper.assertTrue(isTile(level, wall) && isTile(level, wall.above()) && isTile(level, wall.above(2)), "interior wall doesn't reach the ceiling");
		for (int y = feet - 1; y <= feet + 3; y++) {
			helper.assertTrue(isTile(level, new BlockPos(house.x() - 1, y, house.z() + 3)), "enclosure ring open at y offset " + (y - feet));
		}
		assertStoryCell(helper, level, new BlockPos(house.x() + 12, feet, house.z() + 8), "TEST_HOUSE:6:4", StoryCellType.OBJECT);
		MapPlacement upstairs = index.map("TEST_HOUSE_2F");
		helper.assertTrue(upstairs.layer() == 2 && isTile(level, new BlockPos(upstairs.x() + 2, upstairs.y() - 1, upstairs.z() + 4)),
				"upper floor missing on layer 2");

		// Biomes per 4×4×4 cell.
		assertBiome(helper, level, new BlockPos(town.x() + 18, 64, town.z() + 16), "kanto_outdoor");
		assertBiome(helper, level, new BlockPos(house.x() + 10, feet, house.z() + 10), "kanto_interior");

		// The habitat block under the grass: configured, a point of interest, and its influence where the grass is. Its
		// chunk may have been saved and unloaded since the build (no player is near): the runtime check, which runs as
		// chunks load, prepares a reloaded block before its chunk ticks.
		helper.assertTrue(index.habitats().size() == 7, "habitats listed: " + index.habitats().size());
		HabitatBlockEntity habitat = assertHabitat(helper, level, town(index.habitats()));
		assertCaveHabitat(helper, level, index.habitats());
		assertInfluence(helper, level, index, habitat);
		helper.assertTrue(HabitatService.counts().missingBlock.get() == 0 && HabitatService.counts().missingPool.get() == 0,
				"the runtime check found a problem");
		KantoBridge.LOGGER.info("synthetic build: {} blocks placed, {} chunks, {} story cells, {} ms", report.blocksPlaced, report.chunks,
				report.storyCells, report.elapsedMs);
	}

	/**
	 * TEST_CAVE_1F's plateau as built (CAVES_3D_PLAN §3.4): 2 blocks up on rock; a player walks up its stairs, along its
	 * floor, but neither up its cliff nor off it.
	 */
	private static void verifyRaisedCave(GameTestHelper helper, BuildTask task) {
		WorldIndex index = readIndex(helper, task);
		helper.assertTrue(index.layout("TEST_CAVE_1F") != null, "the cave's levels aren't in the index");
		MapPlacement cave = index.map("TEST_CAVE_1F");
		ServerLevel level = helper.getLevel();
		int x0 = cave.x();
		int y0 = cave.y();
		int z0 = cave.z();
		for (int z = z0 - 16; z < z0 + 2 * cave.h() + 16; z += 16) {
			for (int x = x0 - 16; x < x0 + 2 * cave.w() + 16; x += 16) {
				level.getChunk(new BlockPos(x, y0, z));
			}
		}
		BlockPos plateau = new BlockPos(x0 + 14, y0 + 1, z0 + 6);
		helper.assertTrue(isTile(level, plateau) && isTile(level, plateau.below()) && level.getBlockState(plateau.above()).isAir(),
				"the plateau isn't 2 up on rock");
		helper.assertTrue(level.getBlockState(new BlockPos(x0 + 10, y0, z0 + 4)).is(KbBlocks.STEP_COLLIDER)
				&& level.getBlockState(new BlockPos(x0 + 11, y0 + 1, z0 + 4)).is(KbBlocks.STEP_COLLIDER), "the stairs have no steps");
		MockPlayers.with(helper, player -> {
			// Up the stairs, from (4,2) onto (6,2).
			player.setPos(x0 + 9.0, y0, z0 + 5.0);
			CollisionGameTests.walk(player, 0.25, 0, 16);
			helper.assertTrue(player.getX() > x0 + 12.3 && Math.abs(player.getY() - (y0 + 2)) < 1e-6, "the stairs didn't lead up: " + player.position());
			// Not up the cliff, from (5,4) into (6,4).
			player.setPos(x0 + 11.0, y0, z0 + 9.0);
			CollisionGameTests.walk(player, 0.25, 0, 8);
			helper.assertTrue(player.getX() < x0 + 12.0 && player.getY() < y0 + 0.1, "a player climbed the cliff");
			// Not off the plateau's west edge, from (6,4) to (5,4): its edge wall stops it.
			player.setPos(x0 + 13.0, y0 + 2, z0 + 9.0);
			CollisionGameTests.walk(player, -0.25, 0, 8);
			helper.assertTrue(player.getX() > x0 + 12.0 && Math.abs(player.getY() - (y0 + 2)) < 1e-6, "a player walked off the plateau");
			// Along the floor, from (2,4) to (3,4): free.
			player.setPos(x0 + 5.0, y0, z0 + 9.0);
			CollisionGameTests.walk(player, 0.25, 0, 8);
			helper.assertTrue(player.getX() > x0 + 6.9 && player.getY() < y0 + 0.1, "the floor stopped a player");
		});
	}

	/**
	 * The cave's shafts as built (CAVES_3D_PLAN §3.5): a player falls through the hole onto the cell it leads to, and the
	 * ladders are Minecraft's climbable ladders.
	 */
	private static void verifyShafts(GameTestHelper helper, BuildTask task) {
		WorldIndex index = readIndex(helper, task);
		ServerLevel level = helper.getLevel();
		helper.assertTrue(index.shafts().size() == 3, "shafts " + index.shafts().size());
		for (WorldIndex.Shaft shaft : index.shafts()) {
			for (int z = shaft.z() - 16; z <= shaft.z() + 16; z += 16) {
				for (int x = shaft.x() - 16; x <= shaft.x() + 16; x += 16) {
					level.getChunk(new BlockPos(x, shaft.y0(), z));
				}
			}
		}
		WorldIndex.Shaft hole = index.shafts().stream().filter(s -> WorldIndex.Shaft.HOLE.equals(s.kind())).findFirst().orElseThrow();
		WorldIndex.Shaft ladder = index.shafts().stream().filter(s -> WorldIndex.Shaft.LADDER.equals(s.kind())).findFirst().orElseThrow();
		BlockState rung = level.getBlockState(new BlockPos(ladder.x(), ladder.y0() + 3, ladder.z()));
		helper.assertTrue(rung.is(KbBlocks.SHAFT_LADDER) && rung.is(net.minecraft.tags.BlockTags.CLIMBABLE), "no climbable ladder up the shaft: " + rung);
		MockPlayers.with(helper, player -> {
			// Fallen into the hole from its top cell: down the shaft onto the bottom cell's floor.
			player.setPos(hole.x() + 1.0, hole.y1() + 1, hole.z() + 1.0);
			for (int i = 0; i < 20; i++) {
				player.move(MoverType.SELF, new Vec3(0, -1.0, 0));
			}
			helper.assertTrue(Math.abs(player.getY() - hole.y0()) < 1e-6, "the fall didn't end on the floor below: " + player.position());
			helper.assertTrue(index.toCell(player.blockPosition()).map(hole.bottom()::equals).orElse(false), "landed off the hole's bottom cell");
			// On the ladder, a player climbs.
			player.setPos(ladder.x() + 0.5, ladder.y0() + 3, ladder.z() + 0.5);
			helper.assertTrue(player.onClimbable(), "a player on the shaft's ladder can't climb it");
		});
	}

	private static void verifyRebuild(GameTestHelper helper, BuildTask first, BuildTask second) {
		BuildReport report = second.report();
		helper.assertTrue(second.succeeded(), "rebuild failed: " + report.error);
		helper.assertTrue(report.blocksPlaced == 0 && report.blocksCleared == 0,
				"an identical rebuild changed blocks: placed " + report.blocksPlaced + ", cleared " + report.blocksCleared);
		helper.assertTrue(report.worldIndexHash.equals(first.report().worldIndexHash), "an identical rebuild changed the index");
		helper.assertTrue(report.habitatBlocks == 7, "the rebuild's habitat_blocks " + report.habitatBlocks);
		assertHabitat(helper, helper.getLevel(), town(first.plan().habitats()));
	}

	private static void verifyTownOnly(GameTestHelper helper, BuildTask first, BuildTask third) {
		BuildReport report = third.report();
		helper.assertTrue(third.succeeded(), "town-only rebuild failed: " + report.error);
		helper.assertTrue(report.mapsBuilt == 1 && report.interiors == 0, "town-only build placed " + report.mapsBuilt + " maps");
		// The interiors and the hill go; void fill closes the town's ring where the hill stood (its 16 columns × the 12-block
		// ring, at most the surface's 6 layers each), and nothing else changes.
		MapPlacement hill = first.plan().index().map("TEST_HILL");
		int ringLimit = hill.blockWidth() * dev.kantobridge.builder.plan.WorldPlan.SURFACE_RING * 6;
		helper.assertTrue(report.blocksCleared > 0 && report.blocksPlaced > 0 && report.blocksPlaced <= ringLimit,
				"town-only rebuild: placed " + report.blocksPlaced + ", cleared " + report.blocksCleared);
		// The shared resource pack and the slot map depend on the whole export, never on the filter.
		helper.assertTrue(report.slotMapHash.equals(first.report().slotMapHash), "a filtered build changed the slot map");
		helper.assertTrue(report.packHash.equals(first.report().packHash), "a filtered build changed the resource pack");
		WorldIndex before = first.plan().index();
		MapPlacement house = before.map("TEST_HOUSE");
		ServerLevel level = helper.getLevel();
		helper.assertTrue(level.getBlockState(new BlockPos(house.x() + 4, house.y() - 1, house.z() + 6)).isAir(),
				"the old interior was not cleared");
		MapPlacement town = before.map("TEST_TOWN");
		helper.assertTrue(isTile(level, new BlockPos(town.x() + 4, 63, town.z() + 4)), "the town was cleared too");
		BlockPos wasHill = new BlockPos(hill.x() + 4, 63, town.z() - 6);
		helper.assertTrue(isTile(level, wasHill.above(3)) && level.getBlockState(wasHill.above(4)).isAir(), "the ring isn't void fill where the hill was");
		helper.assertTrue(level.getBlockState(new BlockPos(hill.x() + 4, 63, hill.z() + 2)).isAir(), "the hill outside the ring wasn't cleared");
		WorldIndex after = readIndex(helper, third);
		helper.assertTrue(after.maps().size() == 1 && after.map("TEST_HOUSE") == null, "the new index still lists the house");
		helper.assertTrue(after.habitats().equals(List.of(town(before.habitats()))), "the town keeps its habitat block");
		assertHabitat(helper, level, after.habitats().get(0));
	}

	/** The town's habitat block among a build's. */
	private static WorldIndex.Habitat town(List<WorldIndex.Habitat> habitats) {
		return habitats.stream().filter(h -> h.map().equals("TEST_TOWN")).findFirst().orElseThrow();
	}

	/** One of the cave's habitat blocks: configured with the cave area's smaller reach (CAVES_3D_PLAN §3.3, HD8). */
	private static void assertCaveHabitat(GameTestHelper helper, ServerLevel level, List<WorldIndex.Habitat> habitats) {
		WorldIndex.Habitat cave = habitats.stream().filter(h -> h.map().equals("TEST_CAVE_1F")).findFirst().orElseThrow();
		helper.assertTrue(cave.range() == dev.kantobridge.builder.plan.HabitatPlanner.CAVE_RANGE, "the cave's habitat range " + cave.range());
		BlockPos pos = new BlockPos(cave.x(), cave.y(), cave.z());
		level.getChunk(pos);
		helper.assertTrue(level.getBlockEntity(pos) instanceof HabitatBlockEntity entity
				&& entity.getSpawningStyle() instanceof NaturalHabitatSpawning style && style.getRangeOfInfluence() == cave.range()
				&& HabitatService.configuredFor(entity, cave.pool(), cave.range()), "the cave's habitat block isn't configured with its range");
	}

	/** A habitat block configured as PLAN §4.5 wants it, and a point of interest Cobblemon's spawner finds. */
	private static HabitatBlockEntity assertHabitat(GameTestHelper helper, ServerLevel level, WorldIndex.Habitat habitat) {
		BlockPos pos = new BlockPos(habitat.x(), habitat.y(), habitat.z());
		level.getChunk(pos);
		helper.assertTrue(HabitatService.verifyLoaded(level.getServer()) >= 1, "the runtime check didn't see the loaded habitat block");
		helper.assertTrue("kantobridge:test_town".equals(habitat.pool()) && habitat.y() == 61, "habitat listed wrong");
		BlockState state = level.getBlockState(pos);
		helper.assertTrue(state.is(CobblemonBlocks.HABITAT_BLOCK), "no habitat block 2 blocks under the grass");
		helper.assertFalse(state.getValue(HabitatBlock.Companion.getACTIVATED_STYLE()) || state.getValue(HabitatBlock.Companion.getCANCELS_REGULAR_SPAWNS()),
				"the block's state flags weren't refreshed");
		helper.assertTrue(level.getBlockEntity(pos) instanceof HabitatBlockEntity, "the habitat block has no block entity");
		HabitatBlockEntity entity = (HabitatBlockEntity) level.getBlockEntity(pos);
		helper.assertTrue(entity.getSpawningStyle() instanceof NaturalHabitatSpawning, "not natural spawning");
		NaturalHabitatSpawning style = (NaturalHabitatSpawning) entity.getSpawningStyle();
		helper.assertFalse(style.getReplaceSpawns(), "ReplaceSpawns must be off");
		helper.assertTrue(style.getRangeOfInfluence() == 14, "RangeOfInfluence " + style.getRangeOfInfluence());
		helper.assertTrue(style.getAffectsOverworld() && !style.getAffectsFishing(), "the pool's spawns weren't attached (initialized "
				+ entity.getInitialized() + ", details " + entity.getSpawnDetails().size() + ", pool spawns " + entity.getPool().getSpawns().size() + ")");
		helper.assertTrue(entity.getPool().getId().toString().equals("kantobridge:test_town"), "the pool isn't attached");
		helper.assertTrue(entity.getPhaseOrder() == HabitatPhaseOrder.SIMPLE && entity.getNumberOfPhases() == 1, "phases");
		helper.assertTrue(entity.getSpawnDetails().size() == 10, "one spawn detail per Gen 1 slot, got " + entity.getSpawnDetails().size());
		helper.assertTrue(HabitatService.configuredFor(entity, habitat.pool()), "the service doesn't see it configured");
		helper.assertTrue(level.getPoiManager().getType(pos).map(holder -> holder.is(CobblemonPoiTypes.HABITAT_BLOCK_KEY)).orElse(false),
				"the habitat block isn't a point of interest, so the spawner can't find it");
		return entity;
	}

	/**
	 * Cobblemon's own spawner, as {@code /checkspawn} runs it, around the town's grass for a mock player: the habitat
	 * detector finds the block, grass floor blocks get the pool's Pokémon, and the tree tops beside the grass (inside
	 * the sphere, above the walking level) get nothing, because of the spawn rule {@code walking_level_only}.
	 */
	private static void assertInfluence(GameTestHelper helper, ServerLevel level, WorldIndex index, HabitatBlockEntity habitat) {
		ServerPlayer player = MockPlayers.join(helper);
		try {
			PlayerSpawner spawner = PlayerExtensionsKt.getSpawner(player);
			helper.assertTrue(spawner != null, "the mock player has no Cobblemon spawner");
			BlockPos patch = index.cellOrigin("TEST_TOWN", 14, 4);
			for (int cx = (patch.getX() - 2) >> 4; cx <= (patch.getX() + 16) >> 4; cx++) {
				for (int cz = (patch.getZ() - 2) >> 4; cz <= (patch.getZ() + 16) >> 4; cz++) {
					level.getChunk(cx, cz);
				}
			}
			SpawningZoneInput input = new SpawningZoneInput(new SpawnCause(spawner, player), level, patch.getX() - 2, 60, patch.getZ() - 2, 16, 10,
					16);
			boolean detected = false;
			for (SpawningZoneInfluence influence : HabitatBlockDetector.INSTANCE.detectFromInput(spawner, input)) {
				detected |= influence.getInfluence() == habitat;
			}
			helper.assertTrue(detected, "Cobblemon's habitat detector doesn't find the block");
			SpawningZone zone = Cobblemon.INSTANCE.getSpawningZoneGenerator().generate(spawner, input);
			List<AreaSpawnablePosition> positions = Cobblemon.INSTANCE.getAreaSpawnablePositionResolver().resolve(spawner,
					SpawnablePositionCalculator.Companion.getPrioritizedAreaCalculators(), zone);
			int onGrass = 0;
			int underTufts = 0;
			int above = 0;
			Set<String> species = new HashSet<>();
			for (AreaSpawnablePosition position : positions) {
				List<SpawnDetail> details = spawner.getMatchingSpawns("common", position);
				if (position.getPosition().getY() == 63) {
					if (!details.isEmpty() && position.getPosition().distSqr(habitat.getBlockPos()) <= 14 * 14) {
						onGrass++;
						// The voxel plan's R3 (V4): a tuft's decor carrier above the grass leaves it spawnable.
						underTufts += level.getBlockState(position.getPosition().above()).is(KbBlocks.DECOR) ? 1 : 0;
						helper.assertTrue(details.size() == 10, "a grass floor block got " + details.size() + " spawns, want the 10 slots");
						for (SpawnDetail detail : details) {
							species.add(((PokemonSpawnDetail) detail).getPokemon().getSpecies());
						}
					}
				} else {
					helper.assertTrue(details.isEmpty(), "something may spawn above the walking level, at y offset "
							+ (position.getPosition().getY() - 63));
					if (position.getPosition().distSqr(habitat.getBlockPos()) <= 14 * 14) {
						// Inside the sphere: only the walking-level rule keeps the pool off these tree tops.
						above++;
					}
				}
			}
			helper.assertTrue(onGrass > 0, "no floor block near the habitat offers its spawns (" + positions.size() + " positions)");
			helper.assertTrue(underTufts > 0, "no grass under a tuft offers the pool's spawns");
			helper.assertTrue(above > 0, "the zone had no tree tops inside the sphere to check the walking-level rule on");
			helper.assertTrue(species.equals(Set.of("pidgey", "rattata")), "the pool's species don't come through: " + species);
			KantoBridge.LOGGER.info("synthetic build: habitat influence on {} floor positions, {} positions in the sphere above the walking level refused",
					onGrass, above);
		} finally {
			MockPlayers.leave(helper, player);
		}
	}

	private static WorldIndex readIndex(GameTestHelper helper, BuildTask task) {
		try {
			return WorldIndex.fromJson(Files.readString(task.worldDir().resolve("kantobridge/world_index.json"), StandardCharsets.UTF_8));
		} catch (IOException | DataException e) {
			throw new GameTestAssertException("world index unreadable: " + e.getClass().getSimpleName());
		}
	}

	private static String golden(GameTestHelper helper) {
		return resource(helper, GOLDEN);
	}

	private static String resource(GameTestHelper helper, String path) {
		try (InputStream in = SyntheticBuildGameTests.class.getClassLoader().getResourceAsStream(path)) {
			helper.assertTrue(in != null, path + " missing");
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new GameTestAssertException(path + " unreadable");
		}
	}

	private static boolean isTile(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).getBlock() instanceof TileBlock;
	}

	private static boolean isTile(ServerLevel level, BlockPos pos, boolean full) {
		return level.getBlockState(pos).getBlock() instanceof TileBlock tile && KbBlocks.tileFull().contains(tile) == full;
	}

	private static void assertStoryCell(GameTestHelper helper, ServerLevel level, BlockPos pos, String key, StoryCellType type) {
		// Tile cells are story_tile blocks with their own block-entity type (the one the client's renderer draws).
		boolean tile = type == StoryCellType.TILE;
		helper.assertTrue(level.getBlockState(pos).is(tile ? KbBlocks.STORY_TILE : KbBlocks.STORY_CELL), "no story cell for " + key);
		helper.assertTrue(level.getBlockEntity(pos) instanceof StoryCellBlockEntity cell && key.equals(cell.cellKey()) && cell.type() == type
				&& cell.getType() == (tile ? KbBlocks.STORY_TILE_ENTITY : KbBlocks.STORY_CELL_ENTITY), "story cell data wrong for " + key);
	}

	/**
	 * Two players, two hidden games: A's tree stands, its ball lies there and it walks; B cut the tree, took the ball and
	 * surfs. Their clients' rules ({@link StoryRules}, as {@code ClientStoryView} runs them) relay full states through the
	 * real relay, and the built cells close per player.
	 */
	private static void verifyStoryCells(GameTestHelper helper, BuildTask task) {
		ServerLevel level = helper.getLevel();
		WorldIndex index = task.plan().index();
		StoryRules rulesA = new StoryRules();
		rulesA.setIndex(index);
		rulesA.onSnapshot(json("{\"ingame\":true,\"player\":{\"map\":\"TEST_TOWN\",\"x\":12,\"y\":11,\"facing\":\"down\"},"
				+ "\"state\":" + state(false) + ",\"toggles\":{},\"blocks\":[],\"lit\":[]}"));
		StoryRules rulesB = new StoryRules();
		rulesB.setIndex(index);
		rulesB.onSnapshot(json("{\"ingame\":true,\"player\":{\"map\":\"TEST_TOWN\",\"x\":12,\"y\":11,\"facing\":\"down\"},"
				+ "\"state\":" + state(true) + ",\"toggles\":{\"TEST_TOWN\":{\"TESTTOWN_BALL\":false}},"
				+ "\"blocks\":[{\"map\":\"TEST_TOWN\",\"bx\":5,\"by\":7,\"block\":15}],\"lit\":[]}"));
		helper.assertTrue(rulesA.cells().active().containsAll(Set.of("TEST_TOWN:10:14", "TEST_TOWN:15:4")), "A's rules");
		helper.assertTrue(!rulesB.cells().active().contains("TEST_TOWN:10:14") && !rulesB.cells().active().contains("TEST_TOWN:15:4"),
				"B's rules");

		BlockPos tree = index.cellOrigin("TEST_TOWN", 10, 14);
		BlockPos ball = index.cellOrigin("TEST_TOWN", 15, 4);
		BlockPos water = index.cellOrigin("TEST_TOWN", 12, 12);
		for (BlockPos pos : new BlockPos[] {tree, ball, water}) {
			// Collision reads chunks without loading them: load them for this step.
			level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
		}
		ServerPlayer a = MockPlayers.join(helper);
		ServerPlayer b = MockPlayers.join(helper);
		try {
			relayState(a, 1, StoryStateMessages.full(rulesA.cells().active(), rulesA.surf(), rulesA.bike(), rulesA.lit()));
			relayState(b, 1, StoryStateMessages.full(rulesB.cells().active(), rulesB.surf(), rulesB.bike(), rulesB.lit()));
			helper.assertTrue(closedFor(level, tree, a) && !closedFor(level, tree, b), "the cut tree closes only for A");
			helper.assertTrue(closedFor(level, ball, a) && !closedFor(level, ball, b), "the item ball closes only for A");
			helper.assertTrue(closedFor(level, water, a) && !closedFor(level, water, b), "water opens only for the surfer");

			// From the shore cell north of the water, each walks half a block south.
			BlockPos shore = index.cellOrigin("TEST_TOWN", 12, 11);
			for (ServerPlayer player : List.of(a, b)) {
				player.setPos(shore.getX() + 1.0, 64.0, shore.getZ() + 2.0 - 0.31);
				player.move(MoverType.SELF, new Vec3(0.0, 0.0, 0.5));
			}
			helper.assertTrue(Math.abs(a.getZ() - (water.getZ() - 0.3)) < 1.0E-6, "A walked into the water");
			helper.assertTrue(b.getZ() > water.getZ() + 0.1, "the surfer was stopped at the shore");
			// Clear of the shore's ground, the surfer settles on the water: a half block, its top half a block down.
			b.move(MoverType.SELF, new Vec3(0.0, 0.0, 0.5));
			b.move(MoverType.SELF, new Vec3(0.0, -1.0, 0.0));
			helper.assertTrue(Math.abs(b.getY() - 63.5) < 1.0E-6, "the surfer rides on the water's surface, got y " + b.getY());

			// A's hidden game cuts the tree: the delta reaches A's state only.
			rulesA.onPlayerMap("TEST_TOWN");
			rulesA.onBlocks(json("{\"map\":\"TEST_TOWN\",\"changes\":[{\"bx\":5,\"by\":7,\"block\":15}]}"));
			Set<String> removed = new TreeSet<>(StoryStates.of(a).active());
			removed.removeAll(rulesA.cells().active());
			helper.assertTrue(removed.equals(Set.of("TEST_TOWN:10:14")), "the cut opens exactly the tree's cell");
			relayState(a, 2, StoryStateMessages.delta(null, removed, null, null, null));
			helper.assertTrue(!closedFor(level, tree, a), "A's tree was cut");
			helper.assertTrue(closedFor(level, ball, a), "A's ball is still there");
			helper.assertTrue(!closedFor(level, tree, b) && !closedFor(level, ball, b), "B didn't change");
		} finally {
			MockPlayers.leave(helper, a);
			MockPlayers.leave(helper, b);
		}
	}

	private static String state(boolean surf) {
		return "{\"mode\":\"idle\",\"bike\":false,\"surf\":" + surf + ",\"slope\":false,\"hidden\":false,\"dark\":false,\"lit\":false}";
	}

	private static JsonObject json(String text) {
		try {
			return ProtoJson.parseObject(text);
		} catch (ProtocolException e) {
			throw new GameTestAssertException("bad test JSON: " + e.code());
		}
	}

	/** Relays a {@code story.state} from the player's client, as the real relay receives it. */
	private static void relayState(ServerPlayer player, long seq, JsonObject data) {
		RelayService.get().receive(player, "{\"type\":\"story.state\",\"seq\":" + seq + ",\"data\":" + ProtoJson.write(data) + "}");
	}

	private static boolean closedFor(ServerLevel level, BlockPos pos, ServerPlayer player) {
		return !level.getBlockState(pos).getCollisionShape(level, pos, CollisionContext.of(player)).isEmpty();
	}

	private static void assertBiome(GameTestHelper helper, ServerLevel level, BlockPos pos, String name) {
		ResourceKey<Biome> expected = ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, name));
		helper.assertTrue(level.getNoiseBiome(QuartPos.fromBlock(pos.getX()), QuartPos.fromBlock(pos.getY()), QuartPos.fromBlock(pos.getZ()))
				.is(expected), "biome at a " + name + " position is wrong");
	}
}
