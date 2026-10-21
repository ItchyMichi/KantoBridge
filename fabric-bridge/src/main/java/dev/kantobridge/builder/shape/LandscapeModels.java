package dev.kantobridge.builder.shape;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.scene.profile.Profile;
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
 *           footprint ("keep", "doors", "doorClear"), "height", "quadCap" and "roofRows"}]}}
 * </pre>
 */
final class LandscapeModels {
	/** The system property naming the models table, a JSON file. */
	static final String PROPERTY = "kantobridge.landscape.models";

	/** One model on its map. */
	record Model(String name, int tx, int ty, int w, int h, List<int[]> claim, JsonObject spec) {
	}

	private static @Nullable Map<String, List<Model>> table;
	private static boolean loaded;

	private LandscapeModels() {
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
					table = parse(Files.readString(Path.of(path), StandardCharsets.UTF_8));
				} catch (IOException e) {
					throw new IllegalStateException("landscape models: can't read " + path, e);
				}
			}
		}
		return table;
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
						o.get("h").getAsInt(), List.copyOf(claim), o.getAsJsonObject("spec")));
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
