package dev.kantobridge.client.packs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.kantobridge.builder.pack.PackKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * A texture pack's manifest ({@code assets/kantobridge/kb_pack/<name>.json}; docs/specs/VOXEL_BLOCKS_PLAN.md §4.9,
 * §5.10), parsed and validated. A manifest of another format, without an id or for a newer geometry is dropped whole
 * ({@code pack.format}); otherwise each entry that fails is dropped with a counted code and the rest is kept, so a pack
 * can never leave a hole. The pack reload checks what needs the resource manager (textures, {@code .vox} files) and the
 * world (unknown keys). Codes, counts and keys only ever leave it. Pure.
 */
public final class KbPackManifest {
	/** The manifest format this client reads ({@code kantobridge_pack}). */
	public static final int FORMAT = 1;
	/** The newest geometry a manifest may target ({@code targets.geometry}). */
	public static final int GEOMETRY = 1;
	/** Unit and object styles a {@code class:tree} or {@code class:prop} rule may choose. */
	public static final Set<String> UNIT_STYLES = Set.of("hull", "object", "columns");
	/** Tuft styles a {@code class:grass} or {@code class:flower} rule may choose. */
	public static final Set<String> TUFT_STYLES = Set.of("tuft", "flat");
	/** Character styles (V8). */
	public static final Set<String> CHARACTER_STYLES = Set.of("thick", "carved", "rigged", "model");

	private static final Pattern PACK_ID = Pattern.compile("^[a-z0-9_.-]{1,64}$");
	private static final Pattern RESOURCE = Pattern.compile("^[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,256}$");

	/** A texture override: a tile's or a sprite's texture, a resource id ({@code namespace:path}). */
	public record Texture(PackKey key, String texture) {
	}

	/**
	 * A model override: a {@code .vox} file for a cell unit, a structure, one placement or a sprite.
	 *
	 * @param origin offset of the model's minimum corner, in voxels (x east, y up, z south)
	 * @param turn   0, 90, 180 or 270 degrees, clockwise seen from above
	 * @param flip   mirror east-west before turning
	 */
	public record Model(PackKey key, String vox, int voxelsPerBlock, int[] origin, int turn, boolean flip) {
	}

	/**
	 * A shape rule: for a {@code class:} key its style (and an object's slab depth, 0 for the default); for a
	 * {@code tile:} key the class it pins; for a {@code struct:} key its roof rows (V7).
	 */
	public record Rule(PackKey key, @Nullable String style, int depth, @Nullable String clazz, int roofRows) {
	}

	/** Per-map dressing (V7): the ceiling's art and the headroom in blocks. */
	public record Dressing(@Nullable String ceiling, int headroom) {
	}

	/** A manifest's valid entries. */
	public record Manifest(String id, String name, String version, Set<String> games, List<Texture> textures, List<Model> models,
			List<Rule> rules, @Nullable String characterStyle, Map<String, String> characterSprites, Map<String, Dressing> dressing) {
	}

	/** The parse: the manifest (null when dropped whole) and the codes of what was dropped, counted. */
	public record Result(@Nullable Manifest manifest, Map<String, Integer> warnings) {
	}

	private KbPackManifest() {
	}

	public static Result parse(String text) {
		Map<String, Integer> warnings = new TreeMap<>();
		JsonObject root;
		try {
			JsonElement element = JsonParser.parseString(text);
			if (!element.isJsonObject()) {
				return dropped(warnings);
			}
			root = element.getAsJsonObject();
		} catch (JsonParseException e) {
			return dropped(warnings);
		}
		Integer format = integer(root.get("kantobridge_pack"));
		String id = string(root.get("id"));
		JsonObject targets = object(root.get("targets"));
		Integer geometry = targets == null ? null : integer(targets.get("geometry"));
		if (format == null || format != FORMAT || id == null || !PACK_ID.matcher(id).matches() || targets == null
				|| (geometry != null && (geometry < 1 || geometry > GEOMETRY))) {
			return dropped(warnings);
		}
		Set<String> games = new TreeSet<>();
		JsonArray gameList = targets.get("games") instanceof JsonArray array ? array : null;
		if (gameList != null) {
			for (JsonElement game : gameList) {
				String name = string(game);
				if (name != null) {
					games.add(name);
				}
			}
		}
		if (games.isEmpty()) {
			return dropped(warnings);
		}
		String name = string(root.get("name"));
		String version = string(root.get("version"));
		List<Texture> textures = new ArrayList<>();
		for (Map.Entry<String, JsonElement> e : entries(root.get("textures"))) {
			PackKey key = key(e.getKey(), warnings);
			String texture = string(e.getValue());
			if (key == null) {
				continue;
			}
			if (key.kind() != PackKey.Kind.TILE && key.kind() != PackKey.Kind.SPRITE) {
				count(warnings, "pack.unknown_key");
				continue;
			}
			boolean ok = texture != null && RESOURCE.matcher(texture).matches()
					&& (key.kind() == PackKey.Kind.TILE ? path(texture).startsWith("block/") : path(texture).startsWith("textures/") && texture.endsWith(".png"));
			if (!ok) {
				// A block face must be a sprite of the block atlas (under textures/block/), a sheet a texture file.
				count(warnings, "pack.texture_missing");
				continue;
			}
			textures.add(new Texture(key, texture));
		}
		List<Model> models = new ArrayList<>();
		for (Map.Entry<String, JsonElement> e : entries(root.get("models"))) {
			PackKey key = key(e.getKey(), warnings);
			if (key == null) {
				continue;
			}
			if (key.kind() != PackKey.Kind.CELL && key.kind() != PackKey.Kind.STRUCT && key.kind() != PackKey.Kind.PLACE
					&& key.kind() != PackKey.Kind.SPRITE) {
				count(warnings, "pack.unknown_key");
				continue;
			}
			Model model = model(key, object(e.getValue()));
			if (model == null) {
				count(warnings, "pack.vox_format");
				continue;
			}
			models.add(model);
		}
		List<Rule> rules = new ArrayList<>();
		for (Map.Entry<String, JsonElement> e : entries(root.get("rules"))) {
			PackKey key = key(e.getKey(), warnings);
			if (key == null) {
				continue;
			}
			Rule rule = rule(key, object(e.getValue()));
			if (rule == null) {
				count(warnings, "pack.rule");
				continue;
			}
			rules.add(rule);
		}
		String characterStyle = null;
		Map<String, String> characterSprites = new TreeMap<>();
		JsonObject characters = object(root.get("characters"));
		if (characters != null) {
			String style = string(characters.get("style"));
			if (style != null && CHARACTER_STYLES.contains(style) && !"model".equals(style)) {
				characterStyle = style;
			} else if (characters.has("style")) {
				count(warnings, "pack.rule");
			}
			for (Map.Entry<String, JsonElement> e : entries(characters.get("sprites"))) {
				String value = string(e.getValue());
				PackKey sprite = sprite(e.getKey());
				if (sprite == null || value == null || !CHARACTER_STYLES.contains(value)) {
					count(warnings, "pack.rule");
					continue;
				}
				characterSprites.put(sprite.scope(), value);
			}
		}
		Map<String, Dressing> dressing = new TreeMap<>();
		for (Map.Entry<String, JsonElement> e : entries(root.get("dressing"))) {
			PackKey key = key(e.getKey(), warnings);
			if (key == null) {
				continue;
			}
			JsonObject value = object(e.getValue());
			Integer headroom = value == null ? null : integer(value.get("headroom"));
			String ceiling = value == null ? null : string(value.get("ceiling"));
			if (key.kind() != PackKey.Kind.MAP || value == null || (value.has("headroom") && (headroom == null || headroom < 2 || headroom > 8))
					|| (value.has("ceiling") && ceiling == null)) {
				count(warnings, "pack.rule");
				continue;
			}
			dressing.put(key.scope(), new Dressing(ceiling, headroom == null ? 0 : headroom));
		}
		Manifest manifest = new Manifest(id, name == null ? id : name, version == null ? "" : version, Collections.unmodifiableSet(games),
				List.copyOf(textures), List.copyOf(models), List.copyOf(rules), characterStyle, Collections.unmodifiableMap(characterSprites),
				Collections.unmodifiableMap(dressing));
		return new Result(manifest, Collections.unmodifiableMap(warnings));
	}

	private static Result dropped(Map<String, Integer> warnings) {
		count(warnings, "pack.format");
		return new Result(null, Collections.unmodifiableMap(warnings));
	}

	static void count(Map<String, Integer> warnings, String code) {
		warnings.merge(code, 1, Integer::sum);
	}

	private static @Nullable PackKey key(String text, Map<String, Integer> warnings) {
		try {
			return PackKey.parse(text);
		} catch (IllegalArgumentException e) {
			count(warnings, "pack.unknown_key");
			return null;
		}
	}

	private static @Nullable PackKey sprite(String id) {
		try {
			return PackKey.sprite(id);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static @Nullable Model model(PackKey key, @Nullable JsonObject value) {
		if (value == null) {
			return null;
		}
		String vox = string(value.get("vox"));
		Integer vpb = value.has("voxelsPerBlock") ? integer(value.get("voxelsPerBlock")) : Integer.valueOf(8);
		Integer turn = value.has("turn") ? integer(value.get("turn")) : Integer.valueOf(0);
		Boolean flip = value.has("flip") ? bool(value.get("flip")) : Boolean.FALSE;
		int[] origin = {0, 0, 0};
		if (value.has("origin")) {
			if (!(value.get("origin") instanceof JsonArray array) || array.size() != 3) {
				return null;
			}
			for (int i = 0; i < 3; i++) {
				Integer v = integer(array.get(i));
				if (v == null || v < -256 || v > 256) {
					return null;
				}
				origin[i] = v;
			}
		}
		if (vox == null || !RESOURCE.matcher(vox).matches() || !vox.endsWith(".vox") || vpb == null || (vpb != 8 && vpb != 16 && vpb != 32)
				|| turn == null || turn % 90 != 0 || turn < 0 || turn > 270 || flip == null) {
			return null;
		}
		return new Model(key, vox, vpb, origin, turn, flip);
	}

	private static @Nullable Rule rule(PackKey key, @Nullable JsonObject value) {
		if (value == null) {
			return null;
		}
		switch (key.kind()) {
			case CLASS -> {
				String clazz = key.scope();
				String style = string(value.get("style"));
				Integer depth = value.has("depth") ? integer(value.get("depth")) : Integer.valueOf(0);
				boolean units = clazz.equals("tree") || clazz.equals("prop");
				boolean tufts = clazz.equals("grass") || clazz.equals("flower");
				if (!onlyFields(value, units ? Set.of("style", "depth") : Set.of("style")) || depth == null) {
					return null;
				}
				if (units && (style == null || UNIT_STYLES.contains(style)) && (depth == 0 || (depth >= 1 && depth <= 8))
						&& (style != null || depth > 0)) {
					return new Rule(key, style, depth, null, 0);
				}
				if (tufts && style != null && TUFT_STYLES.contains(style)) {
					return new Rule(key, style, 0, null, 0);
				}
				return null;
			}
			case TILE -> {
				String clazz = string(value.get("class"));
				return onlyFields(value, Set.of("class")) && clazz != null && PackKey.CLASSES.contains(clazz) ? new Rule(key, null, 0, clazz, 0) : null;
			}
			case STRUCT -> {
				JsonObject bands = object(value.get("bands"));
				Integer roofRows = bands == null ? null : integer(bands.get("roofRows"));
				return onlyFields(value, Set.of("bands")) && bands != null && onlyFields(bands, Set.of("roofRows")) && roofRows != null
						&& roofRows >= 0 && roofRows <= 6 ? new Rule(key, null, 0, null, roofRows) : null;
			}
			default -> {
				return null;
			}
		}
	}

	private static boolean onlyFields(JsonObject value, Set<String> allowed) {
		for (String field : value.keySet()) {
			if (!allowed.contains(field)) {
				return false;
			}
		}
		return true;
	}

	private static List<Map.Entry<String, JsonElement>> entries(@Nullable JsonElement element) {
		return element instanceof JsonObject object ? new ArrayList<>(new TreeMap<>(object.asMap()).entrySet()) : List.of();
	}

	private static @Nullable JsonObject object(@Nullable JsonElement element) {
		return element instanceof JsonObject object ? object : null;
	}

	private static @Nullable String string(@Nullable JsonElement element) {
		return element instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
	}

	private static @Nullable Integer integer(@Nullable JsonElement element) {
		if (!(element instanceof JsonPrimitive p) || !p.isNumber()) {
			return null;
		}
		double d = p.getAsDouble();
		return d == Math.rint(d) && Math.abs(d) <= Integer.MAX_VALUE ? (int) d : null;
	}

	private static @Nullable Boolean bool(@Nullable JsonElement element) {
		return element instanceof JsonPrimitive p && p.isBoolean() ? p.getAsBoolean() : null;
	}

	/** The path of a resource id. */
	static String path(String id) {
		return id.substring(id.indexOf(':') + 1);
	}
}
