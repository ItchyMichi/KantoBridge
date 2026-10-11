package dev.kantobridge.gametest;

import dev.kantobridge.builder.BuildListener;
import dev.kantobridge.builder.BuildOptions;
import dev.kantobridge.builder.BuildReport;
import dev.kantobridge.builder.BuildTask;
import dev.kantobridge.builder.KantoWorldBuilder;
import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.place.CodeStates;
import dev.kantobridge.builder.plan.BlockCode;
import dev.kantobridge.builder.plan.ChunkCodes;
import dev.kantobridge.builder.plan.MapModel;
import dev.kantobridge.builder.plan.PlacedMap;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.ShapeIndex;
import dev.kantobridge.world.ShapeMap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The voxel plan's builder in a real level (docs/specs/VOXEL_BLOCKS_PLAN.md §6.2). {@link #carriers}: the synthetic
 * bundle built unshaped and then shaped over itself: the unshaped build writes no shape files; the shaped one places
 * every planned block state where planned (tile and shape carriers, ledge colliders with their lips), writes the two
 * shape files bound to its WorldIndex, and keeps walkability (I1): a player fits in every walkable cell and collides in
 * every solid or counter cell. Builds go far from every other test, into their own world folder, and publish nothing.
 */
public class ShapeBuildGameTests implements FabricGameTest {
	/** Far from the synthetic build (3,200,000) and every structure-based test. */
	static final int ORIGIN_X = 3_600_000;
	static final int ORIGIN_Z = 3_600_000;
	private static final String BUNDLE = "kantobridge/synthetic_bundle/red/";

	private static ExportBundle bundle() {
		try {
			return ExportBundle.fromClasspath(ShapeBuildGameTests.class.getClassLoader(), BUNDLE);
		} catch (BundleException e) {
			throw new GameTestAssertException("synthetic bundle unreadable: " + e.code());
		}
	}

	private static BuildTask start(GameTestHelper helper, Path dir, boolean shapes) {
		BuildOptions options = BuildOptions.defaults().withOrigin(ORIGIN_X, ORIGIN_Z).withWorldSpawn(false).withWorldDir(dir)
				.withPackDir(dir.resolve("resourcepack")).withPublishIndex(false).withShapes(shapes);
		return KantoWorldBuilder.start(helper.getLevel(), bundle(), null, options, BuildListener.NONE);
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 6000)
	public void carriers(GameTestHelper helper) {
		Path dir;
		try {
			dir = Files.createTempDirectory("kantobridge-shapes");
		} catch (IOException e) {
			throw new GameTestAssertException("no temp folder");
		}
		BuildTask[] plain = {start(helper, dir, false)};
		BuildTask[] shaped = new BuildTask[1];
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(plain[0].isDone(), "unshaped build still running"))
				.thenExecute(() -> verifyUnshaped(helper, plain[0]))
				.thenExecute(() -> shaped[0] = start(helper, dir, true))
				.thenWaitUntil(() -> helper.assertTrue(shaped[0] != null && shaped[0].isDone(), "shaped build still running"))
				.thenExecute(() -> verifyShapeFiles(helper, plain[0], shaped[0]))
				.thenExecute(() -> verifyPlacedAsPlanned(helper, shaped[0].plan()))
				.thenExecute(() -> verifyWalkability(helper, shaped[0].plan()))
				.thenExecute(() -> delete(dir))
				.thenSucceed();
	}

	private static void verifyUnshaped(GameTestHelper helper, BuildTask task) {
		BuildReport report = task.report();
		helper.assertTrue(task.succeeded(), "unshaped build failed: " + report.error);
		helper.assertTrue(report.blocksPlaced > 5000, "blocks_placed " + report.blocksPlaced);
		Path data = task.worldDir().resolve("kantobridge");
		helper.assertTrue(Files.isRegularFile(data.resolve(BuildTask.INDEX_FILE)), "no world index");
		helper.assertFalse(Files.exists(data.resolve(BuildTask.SHAPE_INDEX_FILE)) || Files.exists(data.resolve(BuildTask.SHAPE_MAP_FILE)),
				"an unshaped build wrote shape files");
		helper.assertTrue(report.shapeIndexHash.isEmpty() && report.selfCheck == null, "an unshaped build reported shapes");
		helper.assertTrue(report.blocksByKind.get("shape_full") == 0, "an unshaped build placed shape tiles");
		helper.assertTrue(report.cellsByKind.get("solid") > 0 && report.solidCellsByHeight.containsKey("2"), "cells_by_kind "
				+ report.cellsByKind + ", solid_cells_by_height " + report.solidCellsByHeight);
	}

	private static void verifyShapeFiles(GameTestHelper helper, BuildTask plain, BuildTask task) {
		BuildReport report = task.report();
		helper.assertTrue(task.succeeded(), "shaped build failed: " + report.error);
		helper.assertTrue(report.worldIndexHash.equals(plain.report().worldIndexHash), "shapes changed the WorldIndex");
		helper.assertTrue(report.blocksByKind.get("shape_full") > 0 && report.shapeHostsAdded > 0, "no shape tiles placed");
		Path data = task.worldDir().resolve("kantobridge");
		try {
			String indexText = Files.readString(data.resolve(BuildTask.INDEX_FILE), StandardCharsets.UTF_8);
			String shapeText = Files.readString(data.resolve(BuildTask.SHAPE_INDEX_FILE), StandardCharsets.UTF_8);
			ShapeIndex index = ShapeIndex.fromJson(shapeText);
			ShapeMap map = ShapeMap.decode(Files.readAllBytes(data.resolve(BuildTask.SHAPE_MAP_FILE)));
			helper.assertTrue(index.hash().equals(Hashes.sha256(shapeText)) && index.hash().equals(report.shapeIndexHash), "shape index hash");
			helper.assertTrue(map.hash().equals(report.shapeMapHash), "shape map hash");
			helper.assertTrue(map.indexHash().equals(Hashes.sha256(indexText)), "the shape map doesn't name the build's WorldIndex");
			helper.assertTrue(map.shapeHash().equals(index.hash()), "the shape map doesn't name the shape index");
			helper.assertTrue(map.shapedBlocks() == report.shapedBlocks && report.shapedBlocks > 0, "shaped_blocks " + report.shapedBlocks);
			helper.assertTrue("kb-shapes/10".equals(report.shapePlanner), "planner " + report.shapePlanner);
			helper.assertTrue(report.shapeTemplates == index.templates().size() && report.shapeTemplates > 0, "templates " + report.shapeTemplates);
		} catch (IOException | DataException e) {
			throw new GameTestAssertException("shape files unreadable: " + e.getClass().getSimpleName());
		}
		helper.assertTrue(report.selfCheck != null && report.selfCheck.maps() == 4 && report.selfCheck.holes() == 0,
				"self-check " + report.selfCheck);
		helper.assertTrue(report.shapeQuadsMaxBlock <= 96 && report.shapeQuadsSectionMax <= 30_000, "quads " + report.shapeQuadsMaxBlock + " per block, "
				+ report.shapeQuadsSectionMax + " per section");
		// Tier B (V4): tufts on decor carriers, units in the solid cells and in the void fill's ring, never beyond it.
		helper.assertTrue(report.blocksByKind.get("decor") > 0, "no tuft placed");
		helper.assertTrue(report.voidRingUnits > 0 && report.voidHullCells == 0, "void ring " + report.voidRingUnits + ", beyond " + report.voidHullCells);
		helper.assertTrue(report.structures > 0 && report.structuresByKind.get("object") > 0, "structures " + report.structuresByKind);
		// Tier C (V7): the synthetic buildings are templates, drawn as buildings or as their columns; a failure is counted.
		helper.assertTrue(report.structuresByKind.get("building") + report.structuresByKind.get("fallback") >= 2, "templates drawn: "
				+ report.structuresByKind);
		helper.assertTrue(report.selfCheck.templatesFailed() <= report.structuresByKind.get("fallback"), "templates failed "
				+ report.selfCheck.templatesFailed());
		helper.assertTrue(Files.isRegularFile(data.resolve(BuildTask.SELFCHECK_FILE)), "no shape_selfcheck.json beside the report");
	}

	/** Every position of the build's volumes holds exactly the planned state. */
	private static void verifyPlacedAsPlanned(GameTestHelper helper, WorldPlan plan) {
		ServerLevel level = helper.getLevel();
		CodeStates states = new CodeStates();
		int checked = 0;
		int shapes = 0;
		for (long[] chunk : WorldPlan.chunks(plan.volumes())) {
			ChunkCodes codes = plan.renderChunk((int) chunk[0], (int) chunk[1], List.of());
			for (Map.Entry<Integer, int[]> levelCodes : codes.levels().entrySet()) {
				int y = levelCodes.getKey();
				int[] values = levelCodes.getValue();
				for (int i = 0; i < 256; i++) {
					if (values[i] == BlockCode.UNOWNED) {
						continue;
					}
					BlockPos pos = new BlockPos(codes.minX() + (i & 15), y, codes.minZ() + (i >> 4));
					BlockState want = states.state(values[i]);
					BlockState have = level.getBlockState(pos);
					if (!have.equals(want)) {
						throw new GameTestAssertException("at " + pos.toShortString() + " the level has " + have + ", the plan " + want);
					}
					checked++;
					shapes += BlockCode.kind(values[i]) == BlockCode.KIND_SHAPE ? 1 : 0;
				}
			}
		}
		helper.assertTrue(checked > 10_000 && shapes > 0, "checked " + checked + " positions, " + shapes + " shape tiles");
	}

	/** I1 in the level: a player's box fits in every walkable cell and collides in every solid or counter cell. */
	private static void verifyWalkability(GameTestHelper helper, WorldPlan plan) {
		ServerLevel level = helper.getLevel();
		MockPlayers.with(helper, player -> {
			for (PlacedMap placed : plan.maps()) {
				MapPlacement p = placed.placement();
				MapModel model = placed.model();
				for (int cy = 0; cy < model.cellHeight(); cy++) {
					for (int cx = 0; cx < model.cellWidth(); cx++) {
						MapModel.Kind kind = model.kind(cx, cy);
						if (kind == MapModel.Kind.WATER) {
							continue;
						}
						double x = p.x() + cx * 2 + 1.0;
						double z = p.z() + cy * 2 + 1.0;
						player.setPos(x, p.y(), z);
						AABB box = new AABB(x - 0.3, p.y() + 0.01, z - 0.3, x + 0.3, p.y() + 1.8, z + 0.3);
						boolean free = level.noCollision(player, box);
						boolean walkable = kind == MapModel.Kind.GROUND || kind == MapModel.Kind.LEDGE;
						if (free != walkable) {
							throw new GameTestAssertException(p.id() + " cell " + cx + "," + cy + " (" + kind + "): " + (free ? "a player fits" : "a player collides"));
						}
					}
				}
			}
		});
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
}
