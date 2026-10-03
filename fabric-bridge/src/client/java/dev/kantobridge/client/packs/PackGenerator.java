package dev.kantobridge.client.packs;

import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.pack.ResourcePackWriter;
import dev.kantobridge.builder.plan.ArtCatalog;
import dev.kantobridge.builder.plan.PlanException;
import dev.kantobridge.builder.plan.Warnings;
import dev.kantobridge.world.Hashes;
import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates this player's resource pack from their own export bundle, on the client (docs/PLAN.md §4.3 world/ step 3):
 * the builder's pack writer over the slot map of the <b>whole</b> export ({@link ArtCatalog}), so the pack is exactly
 * the one any build of that export writes, and the WorldIndex's slot-map hash matches it. No Minecraft classes; run it
 * off the client thread. Logs carry counts and hashes only.
 */
public final class PackGenerator {
	private static final Logger LOGGER = LoggerFactory.getLogger("kantobridge");

	/** What was generated. */
	public record Result(String game, String exportHash, String slotMapHash, ResourcePackWriter.Result pack, int warnings) {
	}

	private PackGenerator() {
	}

	/**
	 * Reads the bundle in {@code bundleDir} (checking every hash) and writes the pack to {@code packDir}, replacing the
	 * previous generated pack.
	 */
	public static Result generate(Path bundleDir, Path packDir) throws BundleException, PlanException, IOException {
		long started = System.nanoTime();
		ExportBundle bundle = ExportBundle.open(bundleDir);
		Warnings warnings = new Warnings();
		ArtCatalog art = ArtCatalog.of(bundle, warnings);
		ResourcePackWriter.Result pack = ResourcePackWriter.write(art, bundle, packDir, warnings);
		Result result = new Result(bundle.game(), bundle.exportHash(), art.slotMap().hash(), pack, warnings.total());
		LOGGER.info("pack: generated {} textures ({} tiles, {} NPC sheets, {} animated), {} models, {} blockstates, slot map {}, pack {}, "
				+ "{} warnings, {} ms", pack.textures(), pack.tileTextures(), pack.npcTextures(), pack.animated(), pack.models(), pack.blockstates(),
				Hashes.shortHash(result.slotMapHash()), Hashes.shortHash(pack.hash()), warnings.total(), (System.nanoTime() - started) / 1_000_000L);
		warnings.warnings().forEach((code, count) -> LOGGER.warn("pack: warning {} x{}", code, count));
		return result;
	}
}
