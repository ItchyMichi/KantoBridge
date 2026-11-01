package dev.kantobridge.review;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import dev.kantobridge.builder.shape.ShapeKey;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/** Reads only one shape's provenance from a captured index; no full JSON tree or game art is retained. */
public final class ReviewShapeContext {
	private ReviewShapeContext() {
	}

	/** Adds key, atlas, palette and whole-model origin to a copy of the surface context. Run off-thread. */
	public static JsonObject describe(String text, int shapeId, JsonObject surface) throws IOException {
		JsonObject out = surface.deepCopy();
		int[] instance = instance(text, shapeId);
		if (instance == null) {
			out.addProperty("provenance", "shape_not_found");
			return out;
		}
		List<String> atlases = new ArrayList<>();
		List<String> palettes = new ArrayList<>();
		String keyText = null;
		try (JsonReader reader = new JsonReader(new StringReader(text))) {
			reader.beginObject();
			while (reader.hasNext()) {
				switch (reader.nextName()) {
					case "atlases" -> strings(reader, atlases);
					case "palettes" -> strings(reader, palettes);
					case "planner" -> out.addProperty("planner", reader.nextString());
					case "version" -> out.addProperty("shapeIndexVersion", reader.nextInt());
					case "geoms" -> {
						reader.beginArray();
						int i = 0;
						while (reader.hasNext()) {
							if (i++ == instance[0]) {
								keyText = reader.nextString();
								while (reader.hasNext()) {
									reader.skipValue();
									}
							} else {
								reader.skipValue();
							}
						}
						reader.endArray();
					}
					default -> reader.skipValue();
				}
			}
			reader.endObject();
		}
		if (keyText == null) {
			out.addProperty("provenance", "geometry_not_found");
			return out;
		}
		ShapeKey key = ShapeKey.parse(keyText);
		out.addProperty("shapeKey", key.text());
		out.addProperty("shapeKind", key.kind().id());
		if (key.atlas() < atlases.size()) {
			out.addProperty("atlas", atlases.get(key.atlas()));
		}
		if (instance[1] >= 0 && instance[1] < palettes.size()) {
			out.addProperty("palette", palettes.get(instance[1]));
		}
		if (key.sig() != null) {
			out.addProperty("templateSignature", key.sig());
		}
		JsonArray block = out.getAsJsonArray("block");
		if (block != null && block.size() == 3 && (key.kind() == ShapeKey.Kind.CELL || key.kind() == ShapeKey.Kind.TMPL)) {
			int offset = key.kind() == ShapeKey.Kind.CELL ? 5 : 0;
			JsonArray origin = new JsonArray();
			for (int axis = 0; axis < 3; axis++) {
				origin.add(block.get(axis).getAsInt() - key.number(offset + axis));
			}
			out.add("modelOrigin", origin);
			ShapeKey family = key.kind() == ShapeKey.Kind.TMPL ? ShapeKey.tmpl(key.atlas(), key.sig(), 0, 0, 0)
					: ShapeKey.cell(key.atlas(), new int[] {key.number(0), key.number(1), key.number(2), key.number(3)}, key.number(4), 0, 0, 0);
			out.addProperty("modelKey", family.withTint(key.tint()).withVariant(key.variant()).text());
		}
		out.addProperty("provenance", "resolved");
		return out;
	}

	private static int[] instance(String text, int shapeId) throws IOException {
		if (shapeId < 1) {
			return null;
		}
		try (JsonReader reader = new JsonReader(new StringReader(text))) {
			reader.beginObject();
			while (reader.hasNext()) {
				if (!"instances".equals(reader.nextName())) {
					reader.skipValue();
					continue;
				}
				reader.beginArray();
				for (int id = 1; reader.hasNext(); id++) {
					if (id == shapeId) {
						reader.beginArray();
						return new int[] {reader.nextInt(), reader.nextInt()};
					}
					reader.skipValue();
				}
				return null;
			}
		}
		return null;
	}

	private static void strings(JsonReader reader, List<String> into) throws IOException {
		reader.beginArray();
		while (reader.hasNext()) {
			into.add(reader.nextString());
		}
		reader.endArray();
	}
}
