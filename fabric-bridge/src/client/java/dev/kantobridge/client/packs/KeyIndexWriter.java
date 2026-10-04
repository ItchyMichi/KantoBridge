package dev.kantobridge.client.packs;

import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.plan.PlanException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the key index of the player's export (docs/specs/VOXEL_BLOCKS_PLAN.md §4.8) to
 * {@code <gameDir>/kantobridge/keys/<game>.json} whenever its generated pack is made, for pack makers and the pack check.
 * ROM-derived (tile and sprite ids), so it stays on the player's machine.
 */
public final class KeyIndexWriter {
	private KeyIndexWriter() {
	}

	/** Writes the index of an export bundle's keys; returns it. */
	public static KeyIndex write(Path gameDir, ExportBundle bundle) throws BundleException, PlanException, IOException {
		KeyIndex index = KeyIndex.of(bundle);
		Path file = KeyIndex.file(gameDir, index.game());
		Files.createDirectories(file.getParent());
		Files.writeString(file, index.toText(), StandardCharsets.UTF_8);
		return index;
	}
}
