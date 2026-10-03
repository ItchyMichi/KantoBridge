package dev.kantobridge.client.packs;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.pack.ResourcePackWriter;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import dev.kantobridge.world.DataReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jetbrains.annotations.Nullable;

/**
 * What the generated pack says about itself: {@code kantobridge.json} at its root (docs/PROTOCOL.md "The generated
 * resource pack"), written by {@link ResourcePackWriter}: the game and export it was drawn from, the slot map its tile
 * blocks follow, and how many textures it holds. A client compares {@link #slotMapHash} with the world's to tell whether
 * its pack draws that world right (M9). Plain Java; the file holds hashes and counts only.
 *
 * @param textures tile and sprite textures, 0 when an older pack didn't say
 */
public record PackInfo(String game, String exportHash, String slotMapHash, int textures) {
	/** Reads {@code <packDir>/kantobridge.json}; null when there is no pack, or its info is missing or unreadable. */
	public static @Nullable PackInfo read(Path packDir) {
		Path file = packDir.resolve(ResourcePackWriter.INFO_FILE);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try {
			return parse(Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException e) {
			return null;
		}
	}

	/** Parses the file's text; null when it isn't a valid pack info. */
	public static @Nullable PackInfo parse(String text) {
		JsonObject root;
		try {
			root = ProtoJson.parseObject(text);
		} catch (ProtocolException e) {
			return null;
		}
		String game = string(root, "game");
		String exportHash = string(root, "exportHash");
		String slotMapHash = string(root, "slotMapHash");
		if (game == null || exportHash == null || slotMapHash == null || !DataReader.HASH.matcher(slotMapHash).matches()
				|| !DataReader.HASH.matcher(exportHash).matches()) {
			return null;
		}
		JsonElement textures = root.get("textures");
		int count = 0;
		if (textures != null && textures.isJsonPrimitive() && textures.getAsJsonPrimitive().isNumber()) {
			double value = textures.getAsDouble();
			count = value >= 0 && value <= Integer.MAX_VALUE && value == Math.rint(value) ? (int) value : 0;
		}
		return new PackInfo(game, exportHash, slotMapHash, count);
	}

	private static @Nullable String string(JsonObject root, String key) {
		JsonElement value = root.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
	}
}
