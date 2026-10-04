package dev.kantobridge.gametest;

import dev.kantobridge.builder.BuildListener;
import dev.kantobridge.builder.BuildOptions;
import dev.kantobridge.builder.BuildReport;
import dev.kantobridge.builder.BuildTask;
import dev.kantobridge.builder.KantoWorldBuilder;
import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.ShapeIndex;
import dev.kantobridge.world.ShapeMap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * The voxel plan's builder, in a real level (docs/specs/VOXEL_BLOCKS_PLAN.md §6.2). V2 ({@link #dataFilesOnly}): a shaped
 * build of the synthetic bundle over an unshaped one changes no block and only adds the two shape files, which name the
 * build's WorldIndex and each other. Builds go far from every other test, into their own world folder, and publish
 * nothing.
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
	public void dataFilesOnly(GameTestHelper helper) {
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
				.thenExecute(() -> verifyShaped(helper, plain[0], shaped[0]))
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
		int byKind = report.blocksByKind.values().stream().mapToInt(Integer::intValue).sum();
		helper.assertTrue(byKind > 0 && report.blocksByKind.get("tile_full") > 0, "blocks_by_kind " + report.blocksByKind);
		helper.assertTrue(report.cellsByKind.get("solid") > 0 && report.solidCellsByHeight.containsKey("2"), "cells_by_kind "
				+ report.cellsByKind + ", solid_cells_by_height " + report.solidCellsByHeight);
	}

	private static void verifyShaped(GameTestHelper helper, BuildTask plain, BuildTask task) {
		BuildReport report = task.report();
		helper.assertTrue(task.succeeded(), "shaped build failed: " + report.error);
		// V2 changes no block: the shaped build over the unshaped one leaves every block alone.
		helper.assertTrue(report.blocksPlaced == 0 && report.blocksCleared == 0,
				"shapes changed blocks: placed " + report.blocksPlaced + ", cleared " + report.blocksCleared);
		helper.assertTrue(report.worldIndexHash.equals(plain.report().worldIndexHash), "shapes changed the WorldIndex");
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
			helper.assertTrue(report.shapeKeys == index.geoms().size() && report.shapeInstances == index.size(), "shape key counts");
			helper.assertTrue("kb-shapes/1".equals(report.shapePlanner), "planner " + report.shapePlanner);
		} catch (IOException | DataException e) {
			throw new GameTestAssertException("shape files unreadable: " + e.getClass().getSimpleName());
		}
		helper.assertTrue(report.selfCheck != null && report.selfCheck.maps() == 4 && report.selfCheck.holes() == 0,
				"self-check " + report.selfCheck);
		helper.assertTrue(Files.isRegularFile(data.resolve(BuildTask.SELFCHECK_FILE)), "no shape_selfcheck.json beside the report");
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
