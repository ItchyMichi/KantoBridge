package dev.kantobridge.client.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.pack.PackFiles;
import dev.kantobridge.builder.pack.ResourcePackWriter;
import dev.kantobridge.builder.plan.Warnings;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.builder.plan.WorldPlanner;
import dev.kantobridge.world.WorldIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The client-side pack (Create Kanto world, step 3) is exactly the pack a build of the same export writes, and its slot
 * map is the one the build's WorldIndex names.
 */
class PackGeneratorTest {
	@Test
	void matchesWhatTheBuilderWrites(@TempDir Path gameDir) throws Exception {
		Path packDir = GeneratedPack.directory(gameDir);
		PackGenerator.Result result = PackGenerator.generate(TestData.syntheticBundle(), packDir);
		assertTrue(GeneratedPack.present(gameDir), "pack.mcmeta written where the pack-source mixin looks");

		ExportBundle bundle = ExportBundle.open(TestData.syntheticBundle());
		WorldPlan plan = WorldPlanner.plan(bundle, new WorldPlanner.Options(null, 0, 0, TestData.MIN_BUILD_HEIGHT));
		ResourcePackWriter.Result built = ResourcePackWriter.generate(plan, bundle, new PackFiles(), new Warnings());
		assertEquals(built.hash(), result.pack().hash(), "the same pack, file for file");
		assertEquals(plan.slotMap().hash(), result.slotMapHash());
		WorldIndex golden = WorldIndex.fromJson(Files.readString(TestData.goldenIndex(), StandardCharsets.UTF_8));
		assertEquals(golden.slotMapHash(), result.slotMapHash(), "the WorldIndex a build writes names this slot map");
		assertEquals("red", result.game());
		assertEquals(0, result.warnings());
	}
}
