package dev.kantobridge.builder.shape;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kantobridge.scene.profile.Profile;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * The fitted shapes table (docs/specs/FITTED_SHAPES.md): per cell (its atlas and four tiles, as a pack's {@code cell:}
 * key names it), the builder and settings whose voxels best matched a 3D model made from the cell's drawing, where they
 * beat the planner's own pick. An entry is a profile class and its parameters ({@code {"class": "can", "k": [3, 0, 8, 4,
 * 2]}}), or {@code columns} (no unit: the cell stays what Tier A makes it). No pixels: the class and parameters travel in
 * the unit's key, and each client builds them from its own art.
 *
 * <p>Read from the jar's {@code /kantobridge/fitted_shapes/<game>.json} files (FitTool writes them), every game's into one
 * map: a key names its atlas, and games that share atlases (Red and Blue) share their entries. The profile's own pins
 * win over a fit.
 */
public final class FittedShapes {
	static final String DIR = "/kantobridge/fitted_shapes/";
	/** The games whose tables the jar may carry. */
	static final List<String> GAMES = List.of("blue", "crystal", "emerald");
	/** The class of a fit that keeps no unit. */
	public static final String COLUMNS = "columns";

	/** A cell's fit: a profile class ({@link Profile#CLASSES}) or {@link #COLUMNS}, and its parameters (0..99). */
	public record Entry(String cls, List<Integer> params) {
		public Entry {
			params = List.copyOf(params);
		}
	}

	private static volatile @Nullable Map<String, Entry> bundled;
	private static volatile @Nullable Map<String, Integer> bundledRoofs;
	/** Off for FitTool's dump, which must see the planner's own pick. */
	private static volatile boolean enabled = true;

	private FittedShapes() {
	}

	public static void setEnabled(boolean on) {
		enabled = on;
	}

	/** Tests: a table in place of the jar's (null reads the jar's again). */
	static void useForTest(@Nullable Map<String, Entry> table) {
		useForTest(table, Map.of());
	}

	/** Tests: cells and buildings' roof rows in place of the jar's (a null table reads the jar's again). */
	static void useForTest(@Nullable Map<String, Entry> table, Map<String, Integer> roofs) {
		bundledRoofs = table == null ? null : Map.copyOf(roofs);
		bundled = table == null ? null : Collections.unmodifiableMap(new TreeMap<>(table));
	}

	/** The fit of a cell, or null (no table, no entry, or turned off). */
	static @Nullable Entry get(String atlas, int[] tiles) {
		if (!enabled) {
			return null;
		}
		return table().get(key(atlas, tiles));
	}

	/** A building template's fitted roof rows (0 the column version), by its atlas and signature; -1 for none. */
	public static int roofRows(String atlas, String sig) {
		if (!enabled) {
			return -1;
		}
		table();
		Map<String, Integer> roofs = bundledRoofs;
		Integer rows = roofs == null ? null : roofs.get(atlas + "/" + sig);
		return rows == null ? -1 : rows;
	}

	/** A cell's key: {@code <atlas>/<t0>-<t1>-<t2>-<t3>}. */
	public static String key(String atlas, int[] tiles) {
		StringBuilder out = new StringBuilder(atlas).append('/');
		for (int t = 0; t < tiles.length; t++) {
			out.append(t > 0 ? "-" : "").append(tiles[t]);
		}
		return out.toString();
	}

	static Map<String, Entry> table() {
		Map<String, Entry> table = bundled;
		if (table == null) {
			synchronized (FittedShapes.class) {
				table = bundled;
				if (table == null) {
					table = new TreeMap<>();
					Map<String, Integer> roofs = new TreeMap<>();
					for (String game : GAMES) {
						try (InputStream in = FittedShapes.class.getResourceAsStream(DIR + game + ".json")) {
							if (in != null) {
								String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
								parse(text, table);
								parseRoofs(text, roofs);
							}
						} catch (IOException e) {
							throw new UncheckedIOException(e);
						}
					}
					bundledRoofs = Collections.unmodifiableMap(roofs);
					table = Collections.unmodifiableMap(table);
					bundled = table;
				}
			}
		}
		return table;
	}

	/** Adds a table file's buildings ({@code structs}: key → {@code roofRows} 0..6) to {@code into}; the first one stays. */
	static void parseRoofs(String text, Map<String, Integer> into) {
		JsonObject root = JsonParser.parseString(text).getAsJsonObject();
		if (!root.has("structs")) {
			return;
		}
		for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("structs").entrySet()) {
			try {
				int rows = e.getValue().getAsJsonObject().get("roofRows").getAsInt();
				if (rows >= 0 && rows <= 6) {
					into.putIfAbsent(e.getKey(), rows);
				}
			} catch (RuntimeException ignored) {
				// A malformed entry: the planner's own roof stands.
			}
		}
	}

	/** Adds a table file's entries to {@code into} (a key already there keeps its entry); an unreadable entry is left out. */
	static void parse(String text, Map<String, Entry> into) {
		JsonObject root = JsonParser.parseString(text).getAsJsonObject();
		if (!root.has("cells")) {
			return;
		}
		for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("cells").entrySet()) {
			try {
				JsonObject o = e.getValue().getAsJsonObject();
				String cls = o.get("class").getAsString();
				if (!COLUMNS.equals(cls) && !Profile.CLASSES.containsKey(cls)) {
					continue;
				}
				List<Integer> params = new ArrayList<>();
				if (o.has("k")) {
					for (JsonElement k : o.getAsJsonArray("k")) {
						params.add(Math.max(0, Math.min(99, k.getAsInt())));
					}
				}
				into.putIfAbsent(e.getKey(), new Entry(cls, params));
			} catch (RuntimeException ignored) {
				// A malformed entry: the planner's own pick stands.
			}
		}
	}
}
