package dev.kantobridge.builder.pack;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * The per-world datapack {@code <world>/datapacks/kantobridge_generated/} (docs/PLAN.md §4.5 "Datapack writer"),
 * {@code pack_format} 48: the habitat pools ({@link HabitatPoolWriter}). It must never hold worldgen registry entries: a
 * folder pack has no known-pack identity, so those would make every open of the world ask for a backup
 * (refs/_research/P5_mc_content_testing.md §3.5). Minecraft enables a new world pack on the next load; the builder also
 * hands its pools to Cobblemon at once ({@code HabitatService.ensurePools}), so a fresh build spawns before any reload.
 */
public final class DatapackWriter {
	public static final int PACK_FORMAT = 48;
	public static final String FOLDER = "kantobridge_generated";
	/** The pack's id in Minecraft's pack repository. */
	public static final String PACK_ID = "file/" + FOLDER;

	private DatapackWriter() {
	}

	/** The pack with no pools. */
	public static PackFiles generate() {
		return generate(Map.of());
	}

	/**
	 * @param pools habitat pools: canonical JSON text by pool id ({@code kantobridge:route_1})
	 */
	public static PackFiles generate(Map<String, String> pools) {
		PackFiles files = new PackFiles();
		JsonObject pack = new JsonObject();
		JsonObject meta = new JsonObject();
		meta.addProperty("pack_format", PACK_FORMAT);
		meta.addProperty("description", "Kanto Bridge (generated): habitat pools");
		pack.add("pack", meta);
		files.put("pack.mcmeta", ProtoJson.write(pack));
		pools.forEach((id, json) -> files.put(HabitatPoolWriter.path(id), json));
		return files;
	}

	/** Writes the pack into {@code <worldDir>/datapacks/kantobridge_generated/}, replacing an older one. */
	public static PackFiles write(Path worldDir, Map<String, String> pools) throws IOException {
		PackFiles files = generate(pools);
		files.writeTo(worldDir.resolve("datapacks").resolve(FOLDER), "pack.mcmeta");
		return files;
	}

	/** {@link #write(Path, Map)} without pools. */
	public static PackFiles write(Path worldDir) throws IOException {
		return write(worldDir, Map.of());
	}
}
