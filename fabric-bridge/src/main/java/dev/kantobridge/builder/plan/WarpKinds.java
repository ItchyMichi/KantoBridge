package dev.kantobridge.builder.plan;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kantobridge.builder.shape.FittedShapes;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * What a warp cell is, where the game data merges kinds (docs/specs/HEIGHTS_ALL_GAMES_PLAN.md D2): the jar's table
 * {@code kantobridge/warp_kinds/<game>.json}, the agents' review of every warp-cell drawing (docs/specs/WARP_MODELS.md),
 * keyed by the cell's drawing as the fitted-shape tables key it ({@link FittedShapes#key}). Gen 1's warp tiles are
 * stairs, ladders and mats alike, and Gen 2's "stairs" are a ladder or a staircase; the review tells them apart, and
 * which way each leads. Pixel-free: tile ids and a kind. Pure Java.
 */
public final class WarpKinds {
	/** The review's kinds. */
	public enum Drawn {
		LADDER_UP("ladder_up"),
		LADDER_DOWN("ladder_down"),
		STAIRS_UP("stairs_up"),
		STAIRS_DOWN("stairs_down"),
		HOLE("hole"),
		DOOR("building_door"),
		CAVE_MOUTH("cave_mouth"),
		WALL_EXIT("wall_exit"),
		MAT("mat"),
		PAD("pad"),
		FLOOR("floor"),
		OTHER("other");

		private final String id;

		Drawn(String id) {
			this.id = id;
		}

		static @Nullable Drawn byId(String id) {
			for (Drawn d : values()) {
				if (d.id.equals(id)) {
					return d;
				}
			}
			return null;
		}

		public boolean ladder() {
			return this == LADDER_UP || this == LADDER_DOWN;
		}

		public boolean stairs() {
			return this == STAIRS_UP || this == STAIRS_DOWN;
		}

		/** +1 up, -1 down, 0 for a kind with no way (a hole leads down: -1). */
		public int way() {
			return switch (this) {
				case LADDER_UP, STAIRS_UP -> 1;
				case LADDER_DOWN, STAIRS_DOWN, HOLE -> -1;
				default -> 0;
			};
		}
	}

	private static final String DIR = "/kantobridge/warp_kinds/";
	private static final Map<String, Map<String, Drawn>> TABLES = new HashMap<>();

	private WarpKinds() {
	}

	/** The review's kind of a warp cell of {@code game} drawn with {@code tiles} of {@code atlas}, or null when it has none. */
	public static @Nullable Drawn drawn(String game, String atlas, int[] tiles) {
		return table(game).get(FittedShapes.key(atlas, tiles));
	}

	/** The table of one game: empty when the jar has none. */
	static synchronized Map<String, Drawn> table(String game) {
		return TABLES.computeIfAbsent(game, WarpKinds::load);
	}

	private static Map<String, Drawn> load(String game) {
		Map<String, Drawn> out = new HashMap<>();
		try (InputStream in = WarpKinds.class.getResourceAsStream(DIR + game + ".json")) {
			if (in == null) {
				return out;
			}
			JsonObject root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
			for (Map.Entry<String, com.google.gson.JsonElement> e : root.getAsJsonObject("kinds").entrySet()) {
				Drawn kind = Drawn.byId(e.getValue().getAsString());
				if (kind != null) {
					out.put(e.getKey(), kind);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return out;
	}
}
