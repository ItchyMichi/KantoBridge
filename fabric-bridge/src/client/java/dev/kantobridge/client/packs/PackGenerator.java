package dev.kantobridge.client.packs;

import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.pack.ResourcePackWriter;
import dev.kantobridge.builder.plan.ArtCatalog;
import dev.kantobridge.builder.plan.PlanException;
import dev.kantobridge.builder.plan.Warnings;
import dev.kantobridge.world.Games;
import dev.kantobridge.world.Hashes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates this player's resource pack from their own export bundle, on the client (docs/PLAN.md §4.3 world/ step 3):
 * the builder's pack writer over the whole export ({@link ArtCatalog}), so the pack is exactly the one any build of that
 * export writes. Since layout 2 (docs/specs/VOXEL_BLOCKS_PLAN.md §4.7) the pack holds no slot numbers, so it fits every
 * world of a compatible game, a multiplayer host's included. No Minecraft classes; run it off the client thread. Logs
 * carry counts and hashes only.
 *
 * <p>Several games (MG7, docs/specs/GEN3_BRIDGE_PLAN.md §6 "Two art sets in one pack"): the player's other exports on
 * disk ({@code <gameDir>/kantobridge/export/<game>/}, one folder per game) join the pack as art sets of their own, one per
 * game family, so a world of Kanto and Hoenn draws both regions; the bundle asked for is the pack's own set.
 */
public final class PackGenerator {
	private static final Logger LOGGER = LoggerFactory.getLogger("kantobridge");

	/** What was generated: the game (the pack's own art set), the export, its own slot map's hash, the pack, the sets. */
	public record Result(String game, String exportHash, String slotMapHash, ResourcePackWriter.Result pack, int warnings, List<String> artSets) {
		public Result {
			artSets = List.copyOf(artSets);
		}
	}

	private PackGenerator() {
	}

	/**
	 * Reads the bundle in {@code bundleDir} (checking every hash) and writes its pack, one art set, to {@code packDir},
	 * replacing the previous generated pack.
	 */
	public static Result generate(Path bundleDir, Path packDir) throws BundleException, PlanException, IOException {
		return generate(bundleDir, List.of(), packDir);
	}

	/**
	 * The player's pack: the bundle in {@code bundleDir} as its own set, and the player's other exports beside it
	 * ({@link #otherExports}) as further sets (MG7).
	 */
	public static Result generateWithOthers(Path bundleDir, Path packDir) throws BundleException, PlanException, IOException {
		return generate(bundleDir, otherExports(bundleDir), packDir);
	}

	/**
	 * Writes the pack of the bundle in {@code bundleDir} and, as further art sets, each of {@code others} whose game no
	 * set already drawn can draw (Red's and Blue's art is one set's). An unreadable other export, or one whose art can't be catalogued, is left out (logged).
	 */
	public static Result generate(Path bundleDir, List<Path> others, Path packDir) throws BundleException, PlanException, IOException {
		long started = System.nanoTime();
		ExportBundle bundle = ExportBundle.open(bundleDir);
		Warnings warnings = new Warnings();
		ArtCatalog art = ArtCatalog.ofExport(bundle, warnings);
		List<ResourcePackWriter.SetSource> sets = new ArrayList<>();
		sets.add(new ResourcePackWriter.SetSource(art, bundle));
		for (Path other : others) {
			ExportBundle more;
			try {
				more = ExportBundle.open(other);
			} catch (BundleException e) {
				LOGGER.warn("pack: another export can't be read ({}); its art set is left out", e.code());
				continue;
			}
			if (sets.stream().anyMatch(set -> Games.compatible(set.bundle().game(), more.game()))) {
				continue;
			}
			try {
				sets.add(new ResourcePackWriter.SetSource(ArtCatalog.ofExport(more, warnings), more));
			} catch (PlanException e) {
				LOGGER.warn("pack: another export's art can't be catalogued ({}); its art set is left out", e.code());
			}
		}
		ResourcePackWriter.Result pack = ResourcePackWriter.write(sets, packDir, warnings);
		List<String> artSets = sets.stream().map(set -> set.bundle().game()).toList();
		Result result = new Result(bundle.game(), bundle.exportHash(), art.slotMap().hash(), pack, warnings.total(), artSets);
		LOGGER.info("pack: generated {} textures ({} tiles, {} NPC sheets, {} animated), art sets {}, export {}, pack {}, {} warnings, {} ms",
				pack.textures(), pack.tileTextures(), pack.npcTextures(), pack.animated(), artSets, Hashes.shortHash(bundle.exportHash()),
				Hashes.shortHash(pack.hash()), warnings.total(), (System.nanoTime() - started) / 1_000_000L);
		warnings.warnings().forEach((code, count) -> LOGGER.warn("pack: warning {} x{}", code, count));
		return result;
	}

	/** The player's other exports beside {@code bundleDir}: each sibling folder holding an export's index, sorted. */
	static List<Path> otherExports(Path bundleDir) throws IOException {
		Path own = bundleDir.toAbsolutePath().normalize();
		Path root = own.getParent();
		if (root == null || !Files.isDirectory(root)) {
			return List.of();
		}
		try (Stream<Path> list = Files.list(root)) {
			return list.filter(dir -> !dir.toAbsolutePath().normalize().equals(own) && Files.isRegularFile(dir.resolve("index.json"))).sorted().toList();
		}
	}
}
