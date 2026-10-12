package dev.kantobridge.builder.export;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jetbrains.annotations.Nullable;

/**
 * The engine field tables the builder reads (protocol/export/field.schema.json, the {@code field} resource): which
 * maps roll their grass table on every floor cell ({@code indoorEncounters}: map index from {@code firstIndoorMap} on,
 * any tileset but {@code excludedTileset}, as Gen 1's wild_encounters.asm does for caves, towers, the Mansion and the
 * Power Plant), the Safari Zone's maps ({@code safari.stepMaps}), whose battles stay native (P9), the player's
 * surfing sprite ({@code playerSprites.surf}), drawn under a surfing player (M8), and the tile pairs
 * ({@code tilePairs}), steps the engine refuses (docs/specs/CAVES_3D_PLAN.md §3.2). Other tables are left alone.
 *
 * @param indoor     null when the export has no {@code indoorEncounters} table
 * @param safariMaps the Safari Zone's maps (empty without a {@code safari} table)
 * @param surfSprite the sprite the hidden game draws for a surfing player, or null when the export doesn't say
 * @param display    the player's display options that change what the engine draws (the voxel plan's V2), or null
 * @param tilePairs  the tile pairs, {@link TilePairs#NONE} without a {@code tilePairs} table
 */
public record ExportField(@Nullable Indoor indoor, Set<String> safariMaps, @Nullable String surfSprite, @Nullable Display display,
		TilePairs tilePairs) {
	/** Gen 1's indoor encounter rule. */
	public record Indoor(int firstIndoorMap, String excludedTileset) {
	}

	/** The void-fill option the export was made under (outdoor borders), and whether it is the engine's default. */
	public record Display(String voidFill, boolean voidFillDefault) {
	}

	/**
	 * Gen 1's tile pairs: for each tileset, the pairs of collision tiles (a cell's bottom-left tile) between which the
	 * engine refuses a step, walking ({@code land}) or surfing ({@code water}), in either direction. The caves fake
	 * raised ground with them; the forest tileset has some too.
	 *
	 * @param land  pair keys ({@link #key}) by tileset id
	 * @param water the same while surfing
	 */
	public record TilePairs(Map<String, Set<Integer>> land, Map<String, Set<Integer>> water) {
		public static final TilePairs NONE = new TilePairs(Map.of(), Map.of());

		public TilePairs {
			land = copy(land);
			water = copy(water);
		}

		private static Map<String, Set<Integer>> copy(Map<String, Set<Integer>> pairs) {
			Map<String, Set<Integer>> out = new HashMap<>();
			pairs.forEach((tileset, keys) -> out.put(tileset, Set.copyOf(keys)));
			return Map.copyOf(out);
		}

		/** One pair's key, the same for either order of its tiles. */
		public static int key(int a, int b) {
			return Math.min(a, b) << 16 | Math.max(a, b);
		}

		/** True when walking between collision tiles {@code a} and {@code b} of {@code tileset} is refused. */
		public boolean land(String tileset, int a, int b) {
			Set<Integer> keys = this.land.get(tileset);
			return keys != null && keys.contains(key(a, b));
		}

		/** True when surfing between collision tiles {@code a} and {@code b} of {@code tileset} is refused. */
		public boolean water(String tileset, int a, int b) {
			Set<Integer> keys = this.water.get(tileset);
			return keys != null && keys.contains(key(a, b));
		}

		/** True when the tileset has any pair. */
		public boolean any(String tileset) {
			return this.land.containsKey(tileset) || this.water.containsKey(tileset);
		}

		/** Every pair, land and water. */
		public int size() {
			return this.land.values().stream().mapToInt(Set::size).sum() + this.water.values().stream().mapToInt(Set::size).sum();
		}

		/**
		 * The {@code tilePairs} table: {@code {"land": [...], "water": [...]}}, each entry {@code {"tileset", "a", "b"}}
		 * (or {@code [tileset, a, b]}), tiles 0..255. A table or entry shaped otherwise is left out, so the build goes on
		 * without walls there rather than failing.
		 */
		public static TilePairs parse(@Nullable JsonElement table) {
			if (table == null || !table.isJsonObject()) {
				return NONE;
			}
			return new TilePairs(list(table.getAsJsonObject().get("land")), list(table.getAsJsonObject().get("water")));
		}

		private static Map<String, Set<Integer>> list(@Nullable JsonElement entries) {
			Map<String, Set<Integer>> out = new HashMap<>();
			if (entries == null || !entries.isJsonArray()) {
				return out;
			}
			for (JsonElement entry : entries.getAsJsonArray()) {
				JsonElement tileset;
				JsonElement a;
				JsonElement b;
				if (entry.isJsonObject()) {
					JsonObject o = entry.getAsJsonObject();
					tileset = o.get("tileset");
					a = o.get("a");
					b = o.get("b");
				} else if (entry.isJsonArray() && entry.getAsJsonArray().size() == 3) {
					JsonArray o = entry.getAsJsonArray();
					tileset = o.get(0);
					a = o.get(1);
					b = o.get(2);
				} else {
					continue;
				}
				String id = symbol(tileset);
				int ta = tile(a);
				int tb = tile(b);
				if (id != null && ta >= 0 && tb >= 0) {
					out.computeIfAbsent(id, k -> new HashSet<>()).add(key(ta, tb));
				}
			}
			return out;
		}

		private static @Nullable String symbol(@Nullable JsonElement value) {
			if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
				return null;
			}
			String s = value.getAsString();
			return DataReader.SYMBOL.matcher(s).matches() ? s : null;
		}

		private static int tile(@Nullable JsonElement value) {
			if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
				return -1;
			}
			double d = value.getAsDouble();
			return d >= 0 && d <= 255 && d == Math.floor(d) ? (int) d : -1;
		}
	}

	public ExportField {
		safariMaps = Set.copyOf(safariMaps);
		java.util.Objects.requireNonNull(tilePairs, "tilePairs");
	}

	public ExportField(@Nullable Indoor indoor, Set<String> safariMaps, @Nullable String surfSprite, @Nullable Display display) {
		this(indoor, safariMaps, surfSprite, display, TilePairs.NONE);
	}

	public ExportField(@Nullable Indoor indoor, Set<String> safariMaps, @Nullable String surfSprite) {
		this(indoor, safariMaps, surfSprite, null);
	}

	public ExportField(@Nullable Indoor indoor, Set<String> safariMaps) {
		this(indoor, safariMaps, null, null);
	}

	public static ExportField empty() {
		return new ExportField(null, Set.of(), null, null);
	}

	/** True when map {@code index} with tileset {@code tileset} rolls encounters on every floor cell. */
	public boolean indoorEncounters(int index, String tileset) {
		return this.indoor != null && index >= this.indoor.firstIndoorMap() && !tileset.equals(this.indoor.excludedTileset());
	}

	static ExportField parse(DataReader r) throws DataException {
		DataReader tables = r.object("tables");
		Indoor indoor = null;
		DataReader rule = tables.optionalObject("indoorEncounters");
		if (rule != null) {
			indoor = new Indoor(rule.integer("firstIndoorMap", 0, 255), rule.string("excludedTileset", DataReader.SYMBOL));
		}
		Set<String> safari = new TreeSet<>();
		DataReader safariTable = tables.optionalObject("safari");
		if (safariTable != null && safariTable.has("stepMaps")) {
			int i = 0;
			for (JsonElement map : safariTable.array("stepMaps")) {
				safari.add(DataReader.asString(map, safariTable.path("stepMaps") + "/" + i++, DataReader.MAP_ID));
			}
		}
		// Cosmetic only: a table shaped otherwise leaves the surfer without a sprite rather than failing the build.
		String surf = null;
		JsonElement sprites = tables.json().get("playerSprites");
		if (sprites != null && sprites.isJsonObject()) {
			JsonElement value = sprites.getAsJsonObject().get("surf");
			if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
					&& DataReader.SYMBOL.matcher(value.getAsString()).matches()) {
				surf = value.getAsString();
			}
		}
		Display display = null;
		DataReader options = r.optionalObject("display");
		if (options != null) {
			display = new Display(options.string("voidFill"), options.bool("voidFillDefault"));
		}
		return new ExportField(indoor, safari, surf, display, TilePairs.parse(tables.json().get("tilePairs")));
	}
}
