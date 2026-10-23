package dev.kantobridge.scene;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Map;

/**
 * The scene's serialised form (docs/specs/VOXEL_BLOCKS_PLAN.md §5.3), in the shape of the draft
 * {@code docs/research/scene_description.draft.json} for the fields the model holds: per-map arrays as Base64 (art tiles
 * u16, cell kinds u8, tags u16, directions u8, row-major), everything else as plain JSON, written canonically. It exists
 * for golden tests and debugging. A scene of a real game is ROM-derived as a whole and is never written to disk by
 * default.
 */
public final class SceneJson {
	public static final int VERSION = 1;

	private SceneJson() {
	}

	/** Canonical text of a scene. */
	public static String write(Scene scene) {
		return ProtoJson.write(toJson(scene));
	}

	public static JsonObject toJson(Scene scene) {
		JsonObject root = new JsonObject();
		root.addProperty("scene", VERSION);
		root.addProperty("game", scene.game());
		root.addProperty("family", scene.family());
		root.addProperty("frontEnd", scene.frontEnd());
		root.addProperty("exportHash", scene.exportHash());
		JsonObject units = new JsonObject();
		units.addProperty("cellPx", scene.cellPx());
		units.addProperty("artPx", scene.artPx());
		root.add("units", units);
		root.addProperty("paletteModel", scene.paletteModel().id());
		JsonObject atlases = new JsonObject();
		for (Map.Entry<String, SceneAtlas> entry : scene.atlases().entrySet()) {
			atlases.add(entry.getKey(), atlas(entry.getValue()));
		}
		root.add("atlases", atlases);
		JsonObject maps = new JsonObject();
		for (Map.Entry<String, SceneMap> entry : scene.maps().entrySet()) {
			maps.add(entry.getKey(), map(entry.getValue()));
		}
		root.add("maps", maps);
		JsonObject sprites = new JsonObject();
		for (Map.Entry<String, SceneSprite> entry : scene.sprites().entrySet()) {
			SceneSprite sprite = entry.getValue();
			JsonObject one = new JsonObject();
			one.addProperty("image", sprite.image());
			one.addProperty("frameW", sprite.frameW());
			one.addProperty("frameH", sprite.frameH());
			one.addProperty("frames", sprite.frames());
			one.addProperty("mirrorRight", sprite.mirrorRight());
			sprites.add(entry.getKey(), one);
		}
		root.add("sprites", sprites);
		JsonObject layout = new JsonObject();
		layout.addProperty("root", scene.layoutRoot());
		root.add("layout", layout);
		return root;
	}

	private static JsonObject atlas(SceneAtlas atlas) {
		JsonObject out = new JsonObject();
		out.addProperty("image", atlas.id());
		out.addProperty("tilesPerRow", atlas.tilesPerRow());
		out.addProperty("tiles", atlas.tiles());
		out.addProperty("artPx", atlas.artPx());
		JsonArray animated = new JsonArray();
		for (Animated entry : atlas.animated()) {
			JsonObject one = new JsonObject();
			one.add("tiles", ints(entry.tiles()));
			one.addProperty("kind", entry.kind());
			one.addProperty("period", entry.period());
			if (entry.offsets().length > 0) {
				one.add("offsets", ints(entry.offsets()));
			}
			if (!entry.images().isEmpty()) {
				JsonArray images = new JsonArray();
				entry.images().forEach(images::add);
				one.add("images", images);
			}
			if (entry.sequence().length > 0) {
				one.add("sequence", ints(entry.sequence()));
			}
			if (entry.image() != null) {
				one.addProperty("image", entry.image());
			}
			if (entry.stripOffsets().length > 0) {
				one.add("stripOffsets", ints(entry.stripOffsets()));
			}
			if (entry.gate() != null) {
				one.addProperty("gate", entry.gate());
			}
			animated.add(one);
		}
		out.add("animated", animated);
		JsonArray grass = new JsonArray();
		atlas.grassTiles().forEach(grass::add);
		out.add("grass", grass);
		JsonObject hints = new JsonObject();
		for (Map.Entry<Integer, String> hint : atlas.hints().entrySet()) {
			JsonObject one = new JsonObject();
			one.addProperty("class", hint.getValue());
			hints.add(Integer.toString(hint.getKey()), one);
		}
		out.add("hints", hints);
		return out;
	}

	private static JsonObject map(SceneMap map) {
		JsonObject out = new JsonObject();
		out.addProperty("index", map.index());
		out.addProperty("w", map.w());
		out.addProperty("h", map.h());
		out.addProperty("perCell", map.perCell());
		out.addProperty("surface", map.surface());
		JsonObject env = new JsonObject();
		env.addProperty("outdoor", map.env().outdoor());
		env.addProperty("openAir", map.env().openAir());
		env.addProperty("cave", map.env().cave());
		env.addProperty("dark", map.env().dark());
		if (map.env().canopy()) {
			// The caves and woods plan's CW2; absent (false) in older scenes.
			env.addProperty("canopy", true);
		}
		if (map.env().wood()) {
			// Absent (false) in older scenes.
			env.addProperty("wood", true);
		}
		out.add("env", env);
		out.addProperty("tileset", map.tileset());
		out.addProperty("atlas", map.atlas());
		JsonObject palette = new JsonObject();
		palette.addProperty("default", map.palette());
		out.add("palette", palette);
		JsonObject art = new JsonObject();
		art.addProperty("cols", map.artCols());
		art.addProperty("rows", map.artRows());
		JsonArray planes = new JsonArray();
		JsonObject under = new JsonObject();
		under.addProperty("plane", "under");
		under.addProperty("tiles", u16(map.tiles()));
		planes.add(under);
		art.add("planes", planes);
		art.add("closed", map.closedTiles() == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(u16(map.closedTiles())));
		out.add("art", art);
		JsonObject cells = new JsonObject();
		cells.addProperty("kind", Base64.getEncoder().encodeToString(map.kinds()));
		cells.addProperty("tags", u16(map.tags()));
		cells.addProperty("dir", Base64.getEncoder().encodeToString(map.dirs()));
		out.add("cells", cells);
		out.add("border", border(map.border(), map.borderSize()));
		out.add("ownBorder", border(map.ownBorder(), map.borderSize()));
		JsonArray objects = new JsonArray();
		for (SceneMap.SceneObject object : map.objects()) {
			JsonObject one = new JsonObject();
			one.addProperty("id", object.name());
			one.addProperty("sprite", object.sprite());
			one.addProperty("x", object.x());
			one.addProperty("y", object.y());
			one.addProperty("stays", object.stays());
			one.addProperty("hidden", object.hidden());
			one.addProperty("kind", object.kind());
			objects.add(one);
		}
		out.add("objects", objects);
		JsonArray sites = new JsonArray();
		for (SwapSite site : map.swapSites()) {
			JsonObject one = new JsonObject();
			one.addProperty("id", site.id());
			one.addProperty("kind", site.kind());
			JsonArray siteCells = new JsonArray();
			for (SwapSite.SwapCell cell : site.cells()) {
				JsonObject c = new JsonObject();
				c.addProperty("x", cell.x());
				c.addProperty("y", cell.y());
				c.add("closed", state(cell.closed()));
				c.add("open", state(cell.open()));
				siteCells.add(c);
			}
			one.add("cells", siteCells);
			sites.add(one);
		}
		out.add("swapSites", sites);
		return out;
	}

	private static JsonObject state(SwapSite.CellState state) {
		JsonObject out = new JsonObject();
		out.addProperty("kind", state.kind().id());
		JsonArray tags = new JsonArray();
		CellTag.ids(state.tags()).forEach(tags::add);
		out.add("tags", tags);
		out.add("art", ints(state.art()));
		return out;
	}

	private static JsonElement border(int[] tiles, int side) {
		if (tiles == null) {
			return JsonNull.INSTANCE;
		}
		JsonObject out = new JsonObject();
		out.addProperty("cols", side);
		out.addProperty("rows", side);
		out.add("tiles", ints(tiles));
		return out;
	}

	private static JsonArray ints(int[] values) {
		JsonArray out = new JsonArray();
		for (int v : values) {
			out.add(v);
		}
		return out;
	}

	/** Base64 of big-endian u16 values. */
	private static String u16(int[] values) {
		ByteBuffer buffer = ByteBuffer.allocate(values.length * 2);
		for (int v : values) {
			if (v < 0 || v > 0xFFFF) {
				throw new IllegalArgumentException("a value does not fit 16 bits");
			}
			buffer.putShort((short) v);
		}
		return Base64.getEncoder().encodeToString(buffer.array());
	}
}
