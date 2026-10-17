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
	 * (0..99; a voxel shape's are its hash), and a voxel shape's bits (else null).
	 */
	public record Entry(String cls, List<Integer> params, byte @Nullable [] voxels) {
		public Entry {
			params = List.copyOf(params);
		}

		public Entry(String cls, List<Integer> params) {
			this(cls, params, null);
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Entry e && e.cls.equals(this.cls) && e.params.equals(this.params) && java.util.Arrays.equals(e.voxels, this.voxels);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(this.cls, this.params) * 31 + java.util.Arrays.hashCode(this.voxels);
		}
	}

	/** One game's table: its cells' fits and its buildings' roof rows. */
	record Table(Map<String, Entry> cells, Map<String, Integer> roofs) {
	}

	private static volatile @Nullable Map<String, Table> bundled;
	private static volatile @Nullable Table forTest;
	private static final ThreadLocal<String> GAME = new ThreadLocal<>();
	/** Off for FitTool's dump, which must see the planner's own pick. */
	private static volatile boolean enabled = true;

	private FittedShapes() {
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
		String key = key(atlas, tiles);
		List<Table> all = new ArrayList<>(tables().values());
		Table test = forTest;
		if (test != null) {
			all.add(0, test);
		}
		for (Table table : all) {
			Entry e = table.cells().get(key);
			if (e != null && e.voxels() != null && VOXELS.equals(e.cls()) && e.params().equals(params)) {
				boolean[] out = new boolean[VOXEL_BYTES * 8];
				for (int i = 0; i < out.length; i++) {
					out[i] = (e.voxels()[i >> 3] >> (i & 7) & 1) != 0;
				}
				return out;
			}
		}
		return null;
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
								parse(text, cells);
								parseRoofs(text, roofs);
								tables.put(game, new Table(Collections.unmodifiableMap(cells), Collections.unmodifiableMap(roofs)));
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
					if (v.length == VOXEL_BYTES) {
						into.put(e.getKey(), new Entry(cls, hashParams(v), v));
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
