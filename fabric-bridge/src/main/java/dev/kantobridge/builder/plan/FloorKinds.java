package dev.kantobridge.builder.plan;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kantobridge.builder.shape.FittedShapes;
import dev.kantobridge.world.Dir;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * What a walkable cell's drawing is where the game data can't tell (docs/specs/HEIGHTS_ALL_GAMES_PLAN.md D3b, W4): the
 * jar's table {@code kantobridge/floor_kinds/<game>.json}, a review of the walkable drawings, keyed as the fitted-shape
 * tables key a cell ({@link FittedShapes#key}). Crystal's caves draw their stairs as floor (the collision says nothing):
 * the table names them and the way they climb. Pixel-free: tile ids and a kind. Pure Java.
 */
public final class FloorKinds {
	private static final String DIR = "/kantobridge/floor_kinds/";
	private static final Map<String, Map<String, Dir>> TABLES = new HashMap<>();

	private FloorKinds() {
	}

	/** The way a stairs drawing of {@code game} climbs, or null when the drawing isn't stairs (or the game has no table). */
	public static @Nullable Dir climb(String game, String atlas, int[] tiles) {
		return table(game).get(FittedShapes.key(atlas, tiles));
	}

	static synchronized Map<String, Dir> table(String game) {
		return TABLES.computeIfAbsent(game, FloorKinds::load);
	}

	private static Map<String, Dir> load(String game) {
		Map<String, Dir> out = new HashMap<>();
		try (InputStream in = FloorKinds.class.getResourceAsStream(DIR + game + ".json")) {
			if (in == null) {
				return out;
			}
			JsonObject root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
			for (Map.Entry<String, com.google.gson.JsonElement> e : root.getAsJsonObject("kinds").entrySet()) {
				String kind = e.getValue().getAsString();
				Dir dir = kind.startsWith("stairs_") ? Dir.byId(kind.substring("stairs_".length())) : null;
				if (dir != null) {
					out.put(e.getKey(), dir);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return out;
	}
}
