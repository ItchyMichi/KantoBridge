package dev.kantobridge.builder.shape;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.scene.profile.Profile;
import dev.kantobridge.world.Dir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * The landscape sections' models in the planner (docs/specs/LANDSCAPE_MODELS.md, the pilot): each model claims its
 * marked cells (and the door cells its arch reaches over) and is placed as a parts template ({@link FittedBuildings},
 * built on every client by {@code PartsBuilding} from its own tiles), as tall as its spec: an outdoor map, on the surface or
 * under the open sky, has no height limit but a column's block mask (30 blocks; the user's rule, 2026-10-21). The
 * table is read once from the file the system property {@value #PROPERTY} names (tools/landscape_sections/
 * models_table.py writes it); without it no map has models. A tile another claim holds stays that claim's.
 *
 * <pre>
 * table     {"maps": {"&lt;map&gt;": [{"name", "tx", "ty", "w", "h" (its grid in the map's tiles), "claim": [[x, y], ...]
 *           (the grid's tiles it takes: its cells', a door's), "spec": a parts spec in the grid's pixels with its
 *           footprint ("keep", "doors", "doorClear"), "height", "quadCap" and "roofRows", "trees" (optional: a model of
 *           trees, each standing on its own ground)}]},
 *  "slopes": {"&lt;map&gt;": [[x, y, "up"|"down"|"left"|"right"], ...]}, (optional: the slopes and stairs its marks name, map
 *           cells and their way up; a map listed with none had its drawing checked and has none)
 *  "pins":   {"&lt;map&gt;": [[x, y, level], ...]}} (optional: a review's corrections, docs/specs/LEVELS_PIPELINE.md: the ground
 *           round each cell, as far as it stands level with it, at that level, blocks over the map's floor)
 * </pre>
 */
public final class LandscapeModels {
	/** The system property naming the models table, a JSON file. */
	static final String PROPERTY = "kantobridge.landscape.models";

	/** One model on its map ({@code trees}: a model of trees, each on its own ground where the ground has levels). */
	record Model(String name, int tx, int ty, int w, int h, List<int[]> claim, JsonObject spec, boolean trees) {
		Model(String name, int tx, int ty, int w, int h, List<int[]> claim, JsonObject spec) {
			this(name, tx, ty, w, h, claim, spec, false);
		}
	}

	/** A cell of a slope or stairs the marks name, and its way up. */
	/** A review's correction: the ground round cell (x, y), as far as it stands level with it, at {@code level} blocks. */
	public record Pin(int x, int y, int level) {
	}

	record Slope(int x, int y, Dir climb) {
	}

	private static @Nullable Map<String, List<Model>> table;
	private static @Nullable Map<String, List<Slope>> slopeTable;
	private static @Nullable Map<String, List<Pin>> pinTable;
	private static boolean loaded;

	private LandscapeModels() {
	}

	/**
	 * Per model of map {@code id} that is one shape (not trees), the cells it claims (cell indexes {@code cy * mapWidth + cx},
	 * its tiles' cells): the levels pilot stands each one's rock on one level (OutdoorLevels.rigid); a model of trees is
	 * built column by column on its cells' own ground, each tree where it stands.
	 */
	public static List<java.util.Set<Integer>> cells(String id, int mapWidth, int perCell) {
		List<java.util.Set<Integer>> out = new ArrayList<>();
		for (Model m : of(id)) {
			if (m.trees()) {
				continue;
			}
			java.util.Set<Integer> cells = new java.util.TreeSet<>();
			for (int[] t : m.claim()) {
				cells.add(((m.ty() + t[1]) / perCell) * mapWidth + (m.tx() + t[0]) / perCell);
			}
			out.add(cells);
		}
		return out;
	}

	/**
	 * The slopes and stairs the marks name on map {@code id}, per cell (index {@code y * w + x}) its way up, else null; null
	 * when the table doesn't list the map: its drawing wasn't checked for them (the levels pilot then reads the data).
	 */
	public static Dir @Nullable [] slopes(String id, int w, int h) {
		current();
		Map<String, List<Slope>> t = slopeTable;
		List<Slope> cells = t == null ? null : t.get(id);
		if (cells == null) {
			return null;
		}
		Dir[] out = new Dir[w * h];
		for (Slope c : cells) {
			if (c.x() >= 0 && c.y() >= 0 && c.x() < w && c.y() < h) {
				out[c.y() * w + c.x()] = c.climb();
			}
		}
		return out;
	}

	/** The models of map {@code id} (none without a table). */
	static List<Model> of(String id) {
		Map<String, List<Model>> t = current();
		return t == null ? List.of() : t.getOrDefault(id, List.of());
	}

	private static synchronized @Nullable Map<String, List<Model>> current() {
		if (!loaded) {
			loaded = true;
			String path = System.getProperty(PROPERTY);
			if (path != null && !path.isBlank()) {
				try {
					String json = Files.readString(Path.of(path), StandardCharsets.UTF_8);
					table = parse(json);
					slopeTable = parseSlopes(json);
					pinTable = parsePins(json);
				} catch (IOException e) {
					throw new IllegalStateException("landscape models: can't read " + path, e);
				}
			}
		}
		return table;
	}

	/** The pins a review set on map {@code id} (none without a table or a review). */
	public static List<Pin> pins(String id) {
		current();
		Map<String, List<Pin>> t = pinTable;
		return t == null ? List.of() : t.getOrDefault(id, List.of());
	}

	/** Sets the pins (tests), returning the ones before. */
	static synchronized @Nullable Map<String, List<Pin>> usePins(@Nullable Map<String, List<Pin>> next) {
		current();
		Map<String, List<Pin>> before = pinTable;
		pinTable = next;
		return before;
	}

	/** A table's pins from its JSON (see the class comment); none when it has no "pins". */
	static Map<String, List<Pin>> parsePins(String json) {
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		if (!root.has("pins")) {
			return Map.of();
		}
		Map<String, List<Pin>> out = new TreeMap<>();
		for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("pins").entrySet()) {
			List<Pin> pins = new ArrayList<>();
			for (JsonElement el : e.getValue().getAsJsonArray()) {
				JsonArray a = el.getAsJsonArray();
				int level = a.get(2).getAsInt();
				if (level < 0 || level > dev.kantobridge.world.MapLayout.MAX_LEVEL) {
					throw new IllegalArgumentException("landscape models: pin " + a + " on " + e.getKey() + " is out of range");
				}
				pins.add(new Pin(a.get(0).getAsInt(), a.get(1).getAsInt(), level));
			}
			out.put(e.getKey(), List.copyOf(pins));
		}
		return Map.copyOf(out);
	}

	/** Sets the slopes (tests), returning the ones before. */
	static synchronized @Nullable Map<String, List<Slope>> useSlopes(@Nullable Map<String, List<Slope>> next) {
		current();
		Map<String, List<Slope>> before = slopeTable;
		slopeTable = next;
		return before;
	}

	/** A table's slopes from its JSON (see the class comment); none when it has no "slopes". */
	static Map<String, List<Slope>> parseSlopes(String json) {
		Map<String, List<Slope>> out = new TreeMap<>();
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		if (!root.has("slopes")) {
			return Map.of();
		}
		for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("slopes").entrySet()) {
			List<Slope> cells = new ArrayList<>();
			for (JsonElement el : e.getValue().getAsJsonArray()) {
				JsonArray a = el.getAsJsonArray();
				Dir climb = Dir.byId(a.get(2).getAsString());
				if (climb == null) {
					throw new IllegalArgumentException("landscape models: slope " + a + " on " + e.getKey() + " has no way up");
				}
				cells.add(new Slope(a.get(0).getAsInt(), a.get(1).getAsInt(), climb));
			}
			out.put(e.getKey(), List.copyOf(cells));
		}
		return Map.copyOf(out);
	}

	/** Sets the table (tests), returning the one before. */
	static synchronized @Nullable Map<String, List<Model>> use(@Nullable Map<String, List<Model>> next) {
		Map<String, List<Model>> before = current();
		table = next;
		return before;
	}

	/** A table from its JSON (see the class comment). */
	static Map<String, List<Model>> parse(String json) {
		Map<String, List<Model>> out = new TreeMap<>();
		JsonObject maps = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("maps");
		for (Map.Entry<String, JsonElement> e : maps.entrySet()) {
			List<Model> models = new ArrayList<>();
			for (JsonElement el : e.getValue().getAsJsonArray()) {
				JsonObject o = el.getAsJsonObject();
				List<int[]> claim = new ArrayList<>();
				for (JsonElement t : o.getAsJsonArray("claim")) {
					JsonArray a = t.getAsJsonArray();
					claim.add(new int[] {a.get(0).getAsInt(), a.get(1).getAsInt()});
				}
				models.add(new Model(o.get("name").getAsString(), o.get("tx").getAsInt(), o.get("ty").getAsInt(), o.get("w").getAsInt(),
						o.get("h").getAsInt(), List.copyOf(claim), o.getAsJsonObject("spec"), o.has("trees") && o.get("trees").getAsBoolean()));
			}
			out.put(e.getKey(), List.copyOf(models));
		}
		return Map.copyOf(out);
	}

	/** The blocks a model may stand on an outdoor map: a column's block mask holds 30 (the placer has no other limit). */
	static final int ROOM = 30;

	/**
	 * The models' claims on {@code map}: per model its grid as an entry ({@code of} maps it to its spec) and the tiles of
	 * its claim no other claim holds; {@code held} filled in.
	 */
	static List<Buildings.Claim> claims(SceneMap map, boolean[] held, Map<String, Integer> tally, Map<Profile.Building, FittedShapes.Building> of) {
		List<Buildings.Claim> out = new ArrayList<>();
		int cols = map.artCols();
		int rows = map.artRows();
		int index = 0;
		for (Model m : of(map.id())) {
			if (m.tx() < 0 || m.ty() < 0 || m.w() < 1 || m.h() < 1 || m.tx() + m.w() > cols || m.ty() + m.h() > rows) {
				tally.merge("landscape_model_outside", 1, Integer::sum);
				continue;
			}
			boolean[] claimed = new boolean[m.w() * m.h()];
			int taken = 0;
			for (int[] t : m.claim()) {
				if (t[0] < 0 || t[1] < 0 || t[0] >= m.w() || t[1] >= m.h()) {
					continue;
				}
				int i = (m.ty() + t[1]) * cols + m.tx() + t[0];
				if (held[i]) {
					taken++;
					continue;
				}
				claimed[t[1] * m.w() + t[0]] = true;
				held[i] = true;
			}
			List<int[]> tiles = new ArrayList<>();
			for (int y = 0; y < m.h(); y++) {
				int[] row = new int[m.w()];
				for (int x = 0; x < m.w(); x++) {
					row[x] = map.tile(m.tx() + x, m.ty() + y);
				}
				tiles.add(row);
			}
			Profile.Building entry = new Profile.Building(index++, tiles, List.of(), false, Set.of(), null);
			of.put(entry, new FittedShapes.Building(map.atlas(), tiles, m.spec()));
			out.add(new Buildings.Claim(entry, m.tx(), m.ty(), claimed));
			tally.merge("landscape_model", 1, Integer::sum);
			if (taken > 0) {
				tally.merge("landscape_tiles_held_elsewhere", taken, Integer::sum);
			}
		}
		return out;
	}
}
