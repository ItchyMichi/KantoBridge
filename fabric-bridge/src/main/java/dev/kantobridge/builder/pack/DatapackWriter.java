package dev.kantobridge.builder.pack;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The per-world datapack {@code <world>/datapacks/kantobridge_generated/} (docs/PLAN.md §4.5 "Datapack writer"),
 * {@code pack_format} 48. It must never hold worldgen registry entries: a folder pack has no known-pack identity, so
 * those would make every open of the world ask for a backup (refs/_research/P5_mc_content_testing.md §3.5). Habitat
 * pools join it in M6; until then it is only the pack metadata.
 */
public final class DatapackWriter {
	public static final int PACK_FORMAT = 48;
	public static final String FOLDER = "kantobridge_generated";

	private DatapackWriter() {
	}

	public static PackFiles generate() {
		PackFiles files = new PackFiles();
		JsonObject pack = new JsonObject();
		JsonObject meta = new JsonObject();
		meta.addProperty("pack_format", PACK_FORMAT);
		meta.addProperty("description", "Kanto Bridge (generated): habitat pools");
		pack.add("pack", meta);
		files.put("pack.mcmeta", ProtoJson.write(pack));
		return files;
	}

	/** Writes the pack into {@code <worldDir>/datapacks/kantobridge_generated/}, replacing an older one. */
	public static PackFiles write(Path worldDir) throws IOException {
		PackFiles files = generate();
		files.writeTo(worldDir.resolve("datapacks").resolve(FOLDER), "pack.mcmeta");
		return files;
	}
}
