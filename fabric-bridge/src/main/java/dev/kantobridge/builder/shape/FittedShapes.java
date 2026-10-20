package dev.kantobridge.builder.shape;

import com.google.gson.JsonArray;
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
 * <p>Read from the jar's {@code /kantobridge/fitted_shapes/<game>.json} files (FitTool writes them), one table per game:
 * games of one engine may share an atlas name ({@code g3q} is Crystal's and Emerald's) with other art behind the same
 * tile numbers. Red reads Blue's table (one art). The planner looks a cell up in the table of the scene it is planning
 * ({@link #enter}); outside a scene there is none. The profile's own pins win over a fit.
 */
public final class FittedShapes {
	static final String DIR = "/kantobridge/fitted_shapes/";
	/** The games whose tables the jar may carry, and the games that read another's. */
	static final List<String> GAMES = List.of("blue", "crystal", "emerald");
	static final Map<String, String> SHARED = Map.of("red", "blue");
	/** The class of a fit that keeps no unit. */
	public static final String COLUMNS = "columns";
	/** The class of a fitted voxel shape (section 10): the entry's {@code v} holds it, its key's parameters hash it. */
	public static final String VOXELS = "voxels";
	/** A unit's voxels: 16 x 16 x 16, one bit each. */
	static final int VOXEL_BYTES = 16 * 16 * 16 / 8;
	/** How many two-digit parameters a voxel shape's hash takes in its key. */
	static final int HASH_PARAMS = 6;

	/**
	 * A cell's fit: a profile class ({@link Profile#CLASSES}), {@link #COLUMNS} or {@link #VOXELS}, its parameters
	 * (0..99; a voxel shape's are its hash), a voxel shape's bits (else null) and its paint (else null: a hand model's
	 * own colouring, docs/specs/HAND_MODELS.md section 3: per voxel, the pixel of the cell's drawing it shows, row * 16 + column).
	 */
	public record Entry(String cls, List<Integer> params, byte @Nullable [] voxels, byte @Nullable [] paint) {
		public Entry {
			params = List.copyOf(params);
		}

		public Entry(String cls, List<Integer> params, byte @Nullable [] voxels) {
			this(cls, params, voxels, null);
		}

		public Entry(String cls, List<Integer> params) {
			this(cls, params, null, null);
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Entry e && e.cls.equals(this.cls) && e.params.equals(this.params) && java.util.Arrays.equals(e.voxels, this.voxels)
					&& java.util.Arrays.equals(e.paint, this.paint);
		}

		@Override
		public int hashCode() {
			return (java.util.Objects.hash(this.cls, this.params) * 31 + java.util.Arrays.hashCode(this.voxels)) * 31
					+ java.util.Arrays.hashCode(this.paint);
		}
	}

	/**
	 * One more cell of height over a voxel entry (docs/specs/HAND_MODELS.md section 8): its voxels (16 x 16 x 16, as the
	 * entry's) and its paint, or null.
	 */
	public record Layer(byte[] voxels, byte @Nullable [] paint) {
	}

	/**
	 * A generated building (docs/specs/HAND_MODELS.md section 7): its atlas, its tile grid (north row first, art tiles)
	 * and the numbers its model takes ({@code roofRows}, {@code roof} hip or flat, {@code bands}).
	 */
	public record Building(String atlas, List<int[]> tiles, JsonObject box) {
	}

	/** The most layers a cell takes over its own (its column stays within a block mask: 2 x 15 blocks). */
	static final int MAX_LAYERS = 14;

	/** One game's table: its cells' fits and its buildings' roof rows. */
	record Table(Map<String, Entry> cells, Map<String, Integer> roofs, Map<String, String> stacks, java.util.Set<String> seats,
			Map<String, List<Layer>> layers, List<Building> buildings) {
		Table(Map<String, Entry> cells, Map<String, Integer> roofs) {
			this(cells, roofs, Map.of(), java.util.Set.of(), Map.of(), List.of());
		}

		Table(Map<String, Entry> cells, Map<String, Integer> roofs, Map<String, String> stacks) {
			this(cells, roofs, stacks, java.util.Set.of(), Map.of(), List.of());
		}

		Table(Map<String, Entry> cells, Map<String, Integer> roofs, Map<String, String> stacks, java.util.Set<String> seats) {
			this(cells, roofs, stacks, seats, Map.of(), List.of());
		}

		Table(Map<String, Entry> cells, Map<String, Integer> roofs, Map<String, String> stacks, java.util.Set<String> seats,
				Map<String, List<Layer>> layers) {
			this(cells, roofs, stacks, seats, layers, List.of());
		}

	}

	private static volatile @Nullable Map<String, Table> bundled;
	private static volatile @Nullable Table forTest;
	private static final ThreadLocal<String> GAME = new ThreadLocal<>();
	/** Off for FitTool's dump, which must see the planner's own pick. */
	private static volatile boolean enabled = true;

	private FittedShapes() {
	}

	/** Whether the tables hold a voxel shape for the cell (the reviewed models, docs/specs/FITTED_SHAPES.md section 10). */
	static boolean voxels(String atlas, int[] tiles) {
		Entry e = get(atlas, tiles);
		return e != null && VOXELS.equals(e.cls());
	}

	/** FitTool's candidates: count the solid low cells no rule makes a unit into {@code into}, as {count, ground tile, raised} (null stops). */
	public static void collectSkipped(@Nullable Map<String, int[]> into) {
		Units.collectSkipped(into);
	}

	/** FitTool cells: record why each cell is or isn't a unit into {@code into}, by "map:cx:cy" (null stops). */
	public static void collectWhy(@Nullable Map<String, String> into) {
		Units.WHY = into;
	}

	public static void setEnabled(boolean on) {
		enabled = on;
	}

	/** Planning {@code game} on this thread from now on; returns the game planned before, for {@link #leave}. */
	static @Nullable String enter(String game) {
		String before = GAME.get();
		GAME.set(game);
		return before;
	}

	static void leave(@Nullable String before) {
		if (before == null) {
			GAME.remove();
		} else {
			GAME.set(before);
		}
	}

	/** Tests: a table in place of the jar's, for every game and outside a scene too (null reads the jar's again). */
	static void useForTest(@Nullable Map<String, Entry> table) {
		useForTest(table, Map.of());
	}

	/** Tests: cells and buildings' roof rows in place of the jar's (a null table reads the jar's again). */
	static void useForTest(@Nullable Map<String, Entry> table, Map<String, Integer> roofs) {
		forTest = table == null ? null : new Table(Map.copyOf(table), Map.copyOf(roofs));
	}

	/** Tests: the jar's tables replaced, per game (null reads the jar's again). */
	static void useTablesForTest(@Nullable Map<String, Table> tables) {
		bundled = tables == null ? null : Map.copyOf(tables);
	}

	/** The table of the game this thread plans, or null. */
	private static @Nullable Table current() {
		if (!enabled) {
			return null;
		}
		Table test = forTest;
		if (test != null) {
			return test;
		}
		String game = GAME.get();
		return game == null ? null : tables().get(SHARED.getOrDefault(game, game));
	}

	/** The fit of a cell, or null (no table, no entry, or turned off). */
	static @Nullable Entry get(String atlas, int[] tiles) {
		Table table = current();
		return table == null ? null : table.cells().get(key(atlas, tiles));
	}

	/**
	 * The cell a stack's top stands on (docs/specs/FITTED_SHAPES.md section 15): the key of the cell below whose unit this
	 * cell's unit stands on top of, or null.
	 */
	static @Nullable String stackedOn(String atlas, int[] tiles) {
		Table table = current();
		return table == null ? null : table.stacks().get(key(atlas, tiles));
	}

	/**
	 * The seat on a walkable cell (docs/specs/HAND_MODELS.md section 6): the cell's voxel entry when the table marks it a
	 * seat, else null.
	 */
	static @Nullable Entry seat(String atlas, int[] tiles) {
		Table table = current();
		String key = key(atlas, tiles);
		return table == null || !table.seats().contains(key) ? null : table.cells().get(key);
	}

	/** How many layers a cell's voxel entry has over its own (a building's storeys), 0 for none. */
	static int layers(String atlas, int[] tiles) {
		Table table = current();
		List<Layer> layers = table == null ? null : table.layers().get(key(atlas, tiles));
		return layers == null ? 0 : layers.size();
	}

	/** Tests: layers (by key) with the given table. */
	static void useLayersForTest(Map<String, Entry> table, Map<String, List<Layer>> layers) {
		forTest = new Table(Map.copyOf(table), Map.of(), Map.of(), java.util.Set.of(), Map.copyOf(layers));
	}

	/** Tests: seats (their keys) with the given table. */
	static void useSeatsForTest(Map<String, Entry> table, java.util.Set<String> seats) {
		forTest = new Table(Map.copyOf(table), Map.of(), Map.of(), java.util.Set.copyOf(seats));
	}

	/** Tests: stacks (top key to the key below) with the given table. */
	static void useStacksForTest(Map<String, Entry> table, Map<String, String> stacks) {
		forTest = new Table(Map.copyOf(table), Map.of(), Map.copyOf(stacks));
	}

	/** A building template's fitted roof rows (0 the column version), by its atlas and signature; -1 for none. */
	public static int roofRows(String atlas, String sig) {
		Table table = current();
		Integer rows = table == null ? null : table.roofs().get(atlas + "/" + sig);
		return rows == null ? -1 : rows;
	}

	/**
	 * A fitted voxel shape by its cell and the hash its key carries, from any game's table (a client builds it without
	 * knowing which game the key came from; the hash tells same-named atlases apart), or null.
	 */
	public static boolean @Nullable [] voxels(String atlas, int[] tiles, List<Integer> params) {
		byte[] bits = layered(atlas, tiles, params, false);
		if (bits == null) {
			return null;
		}
		boolean[] out = new boolean[VOXEL_BYTES * 8];
		for (int i = 0; i < out.length; i++) {
			out[i] = (bits[i >> 3] >> (i & 7) & 1) != 0;
		}
		return out;
	}

	/**
	 * A voxel entry's bits or paint by its cell and its key's parameters: the hash (HASH_PARAMS numbers), and for a layer
	 * one more, the layer (1 the first over the cell's own); null when nothing matches.
	 */
	private static byte @Nullable [] layered(String atlas, int[] tiles, List<Integer> params, boolean paint) {
		String key = key(atlas, tiles);
		int layer = params.size() == HASH_PARAMS + 1 ? params.get(HASH_PARAMS) : 0;
		List<Integer> hash = layer > 0 ? params.subList(0, HASH_PARAMS) : params;
		List<Table> all = new ArrayList<>(tables().values());
		Table test = forTest;
		if (test != null) {
			all.add(0, test);
		}
		for (Table table : all) {
			Entry e = table.cells().get(key);
			if (e != null && e.voxels() != null && VOXELS.equals(e.cls()) && e.params().equals(hash)) {
				if (layer == 0) {
					return paint ? e.paint() : e.voxels();
				}
				List<Layer> layers = table.layers().get(key);
				if (layers == null || layer > layers.size()) {
					return null;
				}
				return paint ? layers.get(layer - 1).paint() : layers.get(layer - 1).voxels();
			}
		}
		return null;
	}

	/** A fitted voxel shape's paint (see {@link Entry}) by its cell and the hash its key carries, or null (front projection). */
	public static byte @Nullable [] paint(String atlas, int[] tiles, List<Integer> params) {
		return layered(atlas, tiles, params, true);
	}

	/** A voxel shape's hash as its key's parameters: the first HASH_PARAMS bytes of its SHA-256, each mod 100. */
	public static List<Integer> hashParams(byte[] voxels) {
		byte[] digest;
		try {
			digest = java.security.MessageDigest.getInstance("SHA-256").digest(voxels);
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < HASH_PARAMS; i++) {
			out.add((digest[i] & 0xFF) % 100);
		}
		return out;
	}

	/** A cell's key: {@code <atlas>/<t0>-<t1>-<t2>-<t3>}. */
	public static String key(String atlas, int[] tiles) {
		StringBuilder out = new StringBuilder(atlas).append('/');
		for (int t = 0; t < tiles.length; t++) {
			out.append(t > 0 ? "-" : "").append(tiles[t]);
		}
		return out.toString();
	}

	static Map<String, Table> tables() {
		Map<String, Table> tables = bundled;
		if (tables == null) {
			synchronized (FittedShapes.class) {
				tables = bundled;
				if (tables == null) {
					tables = new TreeMap<>();
					for (String game : GAMES) {
						try (InputStream in = FittedShapes.class.getResourceAsStream(DIR + game + ".json")) {
							if (in != null) {
								String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
								Map<String, Entry> cells = new TreeMap<>();
								Map<String, Integer> roofs = new TreeMap<>();
								Map<String, String> stacks = new TreeMap<>();
								java.util.Set<String> seats = new java.util.TreeSet<>();
								Map<String, List<Layer>> layers = new TreeMap<>();
								List<Building> buildings = new ArrayList<>();
								parse(text, cells);
								parseRoofs(text, roofs);
								parseStacks(text, stacks);
								parseSeats(text, seats);
								parseLayers(text, layers);
								parseBuildings(text, buildings);
								tables.put(game, new Table(Collections.unmodifiableMap(cells), Collections.unmodifiableMap(roofs), Collections.unmodifiableMap(stacks),
										Collections.unmodifiableSet(seats), Collections.unmodifiableMap(layers), List.copyOf(buildings)));
							}
						} catch (IOException e) {
							throw new UncheckedIOException(e);
						}
					}
					tables = Collections.unmodifiableMap(tables);
					bundled = tables;
				}
			}
		}
		return tables;
	}

	/** Adds a table file's buildings ({@code structs}: key → {@code roofRows} 0..6) to {@code into}. */
	/** A voxel entry's layers ("up": [{"v": bits, "t": paint}, ...]), at most MAX_LAYERS; malformed ones end the list. */
	static List<Layer> layers(JsonObject o) {
		List<Layer> out = new ArrayList<>();
		if (!o.has("up") || !o.get("up").isJsonArray()) {
			return out;
		}
		for (JsonElement el : o.getAsJsonArray("up")) {
			if (out.size() >= MAX_LAYERS || !el.isJsonObject() || !el.getAsJsonObject().has("v")) {
				break;
			}
			byte[] v = java.util.Base64.getDecoder().decode(el.getAsJsonObject().get("v").getAsString());
			byte[] t = el.getAsJsonObject().has("t") ? java.util.Base64.getDecoder().decode(el.getAsJsonObject().get("t").getAsString()) : null;
			if (v.length != VOXEL_BYTES) {
				break;
			}
			out.add(new Layer(v, t != null && t.length == VOXEL_BYTES * 8 ? t : null));
		}
		return out;
	}

	/** Each voxel entry's layers, by key (none listed for an entry without). */
	static void parseLayers(String text, Map<String, List<Layer>> into) {
		JsonObject root = JsonParser.parseString(text).getAsJsonObject();
		if (!root.has("cells")) {
			return;
		}
		for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("cells").entrySet()) {
			try {
				JsonObject o = e.getValue().getAsJsonObject();
				List<Layer> layers = VOXELS.equals(o.get("class").getAsString()) ? layers(o) : List.of();
				if (!layers.isEmpty()) {
					into.put(e.getKey(), List.copyOf(layers));
				}
			} catch (RuntimeException ignored) {
				// A malformed entry: no layers.
			}
		}
	}

	/**
	 * The generated buildings ("buildings": [{"atlas", "tiles": [[ids], ...], "roofRows", "roof", "bands"}], or in place of
	 * the roof numbers a parts spec: "parts", "height", "terrain").
	 */
	static void parseBuildings(String text, List<Building> into) {
		JsonObject root = JsonParser.parseString(text).getAsJsonObject();
		if (!root.has("buildings")) {
			return;
		}
		for (JsonElement el : root.getAsJsonArray("buildings")) {
			try {
				JsonObject o = el.getAsJsonObject();
				List<int[]> rows = new ArrayList<>();
				for (JsonElement row : o.getAsJsonArray("tiles")) {
					JsonArray r = row.getAsJsonArray();
					int[] ids = new int[r.size()];
					for (int i = 0; i < ids.length; i++) {
						ids[i] = r.get(i).getAsInt();
					}
					rows.add(ids);
				}
				JsonObject box = new JsonObject();
				if (o.has("parts")) {
					// a building described as parts (docs/specs/BUILDING_PARTS.md): its spec, and the grid's cells that
					// hold no part of it ("terrain": [[col, row], ...], left to the map's own floor)
					box.addProperty("roofRows", 0);
					box.addProperty("roof", "flat");
					box.add("parts", o.getAsJsonArray("parts").deepCopy());
					box.addProperty("height", o.has("height") ? o.get("height").getAsInt() : 64);
					box.add("terrain", o.has("terrain") ? o.getAsJsonArray("terrain").deepCopy() : new JsonArray());
				} else {
					box.addProperty("roofRows", o.get("roofRows").getAsInt());
					box.addProperty("roof", o.get("roof").getAsString());
				}
				box.add("bands", o.has("bands") ? o.getAsJsonArray("bands").deepCopy() : new JsonArray());
				if (o.has("outline")) {
					box.addProperty("outline", o.get("outline").getAsInt()); // Emerald's lighter outlines: shade 2
				}
				if (!rows.isEmpty() && rows.get(0).length > 0) {
					into.add(new Building(o.get("atlas").getAsString(), List.copyOf(rows), box));
				}
			} catch (RuntimeException ignored) {
				// A malformed building: left out.
			}
		}
	}

	/** The generated buildings of the game this thread plans, for an atlas (none without a table). */
	static List<Building> buildings(String atlas) {
		Table table = current();
		if (table == null) {
			return List.of();
		}
		List<Building> out = new ArrayList<>();
		for (Building b : table.buildings()) {
			if (b.atlas().equals(atlas)) {
				out.add(b);
			}
		}
		return out;
	}

	/** Tests: generated buildings with the given table. */
	static void useBuildingsForTest(List<Building> buildings) {
		forTest = new Table(Map.of(), Map.of(), Map.of(), java.util.Set.of(), Map.of(), List.copyOf(buildings));
	}

	/** Each voxel entry marked "seat" (a stool or chair on a walkable cell). */
	static void parseSeats(String text, java.util.Set<String> into) {
		JsonObject root = JsonParser.parseString(text).getAsJsonObject();
		if (!root.has("cells")) {
			return;
		}
		for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("cells").entrySet()) {
			JsonObject o = e.getValue().getAsJsonObject();
			if (o.has("seat") && o.get("seat").isJsonPrimitive() && o.get("seat").getAsBoolean() && VOXELS.equals(o.get("class").getAsString())) {
				into.add(e.getKey());
			}
		}
	}

	/** Each voxel entry's "on" (the key of the cell it stands on: a stack's top). */
	static void parseStacks(String text, Map<String, String> into) {
		JsonObject root = JsonParser.parseString(text).getAsJsonObject();
		if (!root.has("cells")) {
			return;
		}
		for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("cells").entrySet()) {
			JsonObject o = e.getValue().getAsJsonObject();
			if (o.has("on") && o.get("on").isJsonPrimitive()) {
				into.put(e.getKey(), o.get("on").getAsString());
			}
		}
	}

	static void parseRoofs(String text, Map<String, Integer> into) {
		JsonObject root = JsonParser.parseString(text).getAsJsonObject();
		if (!root.has("structs")) {
			return;
		}
		for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("structs").entrySet()) {
			try {
				int rows = e.getValue().getAsJsonObject().get("roofRows").getAsInt();
				if (rows >= 0 && rows <= 6) {
					into.put(e.getKey(), rows);
				}
			} catch (RuntimeException ignored) {
				// A malformed entry: the planner's own roof stands.
			}
		}
	}

	/** Adds a table file's entries to {@code into}; an unreadable entry is left out. */
	static void parse(String text, Map<String, Entry> into) {
		JsonObject root = JsonParser.parseString(text).getAsJsonObject();
		if (!root.has("cells")) {
			return;
		}
		for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("cells").entrySet()) {
			try {
				JsonObject o = e.getValue().getAsJsonObject();
				String cls = o.get("class").getAsString();
				if (VOXELS.equals(cls)) {
					byte[] v = java.util.Base64.getDecoder().decode(o.get("v").getAsString());
					byte[] t = o.has("t") ? java.util.Base64.getDecoder().decode(o.get("t").getAsString()) : null;
					if (t != null && t.length != VOXEL_BYTES * 8) {
						t = null;
					}
					if (v.length == VOXEL_BYTES) {
						// the hash covers the paint and the layers too: a changed shape is a new key, rebuilt
						java.io.ByteArrayOutputStream hashed = new java.io.ByteArrayOutputStream();
						hashed.writeBytes(v);
						if (t != null) {
							hashed.writeBytes(t);
						}
						for (Layer layer : layers(o)) {
							hashed.writeBytes(layer.voxels());
							if (layer.paint() != null) {
								hashed.writeBytes(layer.paint());
							}
						}
						into.put(e.getKey(), new Entry(cls, hashParams(hashed.toByteArray()), v, t));
					}
					continue;
				}
				if (!COLUMNS.equals(cls) && !Profile.CLASSES.containsKey(cls)) {
					continue;
				}
				List<Integer> params = new ArrayList<>();
				if (o.has("k")) {
					for (JsonElement k : o.getAsJsonArray("k")) {
						params.add(Math.max(0, Math.min(99, k.getAsInt())));
					}
				}
				into.put(e.getKey(), new Entry(cls, params));
			} catch (RuntimeException ignored) {
				// A malformed entry: the planner's own pick stands.
			}
		}
	}
}
