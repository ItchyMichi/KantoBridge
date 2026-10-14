package dev.kantobridge.client.packs;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.pack.ArtKeys;
import dev.kantobridge.builder.pack.ResourcePackWriter;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import dev.kantobridge.world.DataReader;
import dev.kantobridge.world.Games;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * What the generated pack says about itself: {@code kantobridge.json} at its root (docs/PROTOCOL.md "The generated
 * resource pack"), written by {@link ResourcePackWriter}. Layout 2 (docs/specs/VOXEL_BLOCKS_PLAN.md §4.7): its art set
 * (the path segment of every texture), the game and export it was drawn from, and its counts. A pack without
 * {@code layout} is layout 1 (made before the runtime model): it counts as no pack. Plain Java; the file holds ids,
 * hashes and counts only.
 *
 * <p>MG7 (docs/specs/GEN3_BRIDGE_PLAN.md §6): a pack may hold several games' art, one art set per game family; the top
 * level names the pack's own (first) set and {@code sets} lists them all. A pack without {@code sets} holds its one set.
 *
 * @param layout   2 for a pack the runtime model can draw from; 1 for an older one
 * @param artSet   the art set (layout 2), or null
 * @param tiles    tile textures; 0 when the pack didn't say
 * @param sprites  NPC and surf sheets; 0 when the pack didn't say
 * @param textures both, 0 when an older pack didn't say
 * @param sets     every art set the pack holds, its own first (layout 2); empty for an older pack
 */
public record PackInfo(int layout, @Nullable String artSet, String game, String exportHash, int tiles, int sprites, int textures,
		List<ArtSet> sets) {
	/** An art set is a game id: a short lowercase word. */
	private static final Pattern ART_SET = Pattern.compile("^[a-z0-9_]{1,32}$");

	/** One art set of the pack: the game and export it was drawn from, and its counts. */
	public record ArtSet(String artSet, String game, String exportHash, int tiles, int sprites) {
	}

	public PackInfo {
		sets = List.copyOf(sets);
	}

	/** A pack of one art set (or none, for layout 1). */
	public PackInfo(int layout, @Nullable String artSet, String game, String exportHash, int tiles, int sprites, int textures) {
		this(layout, artSet, game, exportHash, tiles, sprites, textures,
				artSet == null ? List.of() : List.of(new ArtSet(artSet, game, exportHash, tiles, sprites)));
	}

	/** True for a pack the runtime model can draw from: layout 2 with an art set. */
	public boolean usable() {
		return this.layout == ArtKeys.LAYOUT && this.artSet != null;
	}

	/**
	 * Of the loaded art sets, the one that draws a key of art set {@code keySet} (MG7): that set, else a compatible game's;
	 * null when none.
	 */
	public static @Nullable String drawSet(List<String> artSets, String keySet) {
		if (artSets.contains(keySet)) {
			return keySet;
		}
		for (String set : artSets) {
			if (Games.compatible(set, keySet)) {
				return set;
			}
		}
		return null;
	}

	/** The art sets the pack holds, its own first. */
	public List<String> artSets() {
		return this.sets.stream().map(ArtSet::artSet).toList();
	}

	/**
	 * The art set that draws {@code game}'s art: the set drawn from that game, else one of a compatible game (Gen 1: Red and
	 * Blue share their art); null when the pack holds none (or isn't usable).
	 */
	public @Nullable ArtSet setFor(@Nullable String game) {
		if (!this.usable() || game == null) {
			return null;
		}
		for (ArtSet set : this.sets) {
			if (set.game().equals(game)) {
				return set;
			}
		}
		for (ArtSet set : this.sets) {
			if (Games.compatible(set.game(), game)) {
				return set;
			}
		}
		return null;
	}

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
		if (game == null || exportHash == null || !DataReader.HASH.matcher(exportHash).matches()) {
			return null;
		}
		int layout = count(root, "layout");
		if (layout != ArtKeys.LAYOUT) {
			// Layout 1 (no field): its blockstates followed one world's slot map; nothing in it is drawn from any more.
			return new PackInfo(1, null, game, exportHash, 0, 0, count(root, "textures"));
		}
		String artSet = string(root, "artSet");
		if (artSet == null || !ART_SET.matcher(artSet).matches()) {
			return null;
		}
		JsonElement list = root.get("sets");
		if (list == null) {
			return new PackInfo(layout, artSet, game, exportHash, count(root, "tiles"), count(root, "sprites"), count(root, "textures"));
		}
		if (!list.isJsonArray() || list.getAsJsonArray().isEmpty()) {
			return null;
		}
		List<ArtSet> sets = new ArrayList<>();
		for (JsonElement element : list.getAsJsonArray()) {
			if (!element.isJsonObject()) {
				return null;
			}
			JsonObject entry = element.getAsJsonObject();
			String set = string(entry, "artSet");
			String setGame = string(entry, "game");
			String setHash = string(entry, "exportHash");
			if (set == null || !ART_SET.matcher(set).matches() || setGame == null || setHash == null || !DataReader.HASH.matcher(setHash).matches()) {
				return null;
			}
			sets.add(new ArtSet(set, setGame, setHash, count(entry, "tiles"), count(entry, "sprites")));
		}
		if (!sets.get(0).artSet().equals(artSet)) {
			// The pack's own set comes first.
			return null;
		}
		return new PackInfo(layout, artSet, game, exportHash, count(root, "tiles"), count(root, "sprites"), count(root, "textures"), sets);
	}

	private static @Nullable String string(JsonObject root, String key) {
		JsonElement value = root.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
	}

	private static int count(JsonObject root, String key) {
		JsonElement value = root.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			return 0;
		}
		double number = value.getAsDouble();
		return number >= 0 && number <= Integer.MAX_VALUE && number == Math.rint(number) ? (int) number : 0;
	}
}
