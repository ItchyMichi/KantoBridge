package dev.kantobridge.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.kantobridge.proto.ProtoJson;
import java.util.Map;

/**
 * The repository's file format for JSON that people read, byte for byte as {@code tools/kb_protocol/jsonfmt.pretty}
 * writes it: 2-space indent, and a container whose one-line form (", " and ": " separators) fits within 100 columns
 * stays on one line. Keys keep the object's own order, like a Python dict; strings and numbers are written as in the
 * canonical form ({@link ProtoJson}), non-ASCII unescaped. A trailing newline ends the text.
 *
 * <p>An export bundle's {@code index.json} uses it, so a bundle downloaded here is identical to one {@code fake_gen1
 * --write-bundle} writes.
 */
final class PrettyJson {
	static final int WIDTH = 100;

	private PrettyJson() {
	}

	static String write(JsonElement value) {
		return format(value, 0, 0) + "\n";
	}

	private static String format(JsonElement value, int level, int prefixLength) {
		String flat = flat(value);
		if (!isContainer(value) || isEmpty(value) || level * 2 + prefixLength + length(flat) <= WIDTH) {
			return flat;
		}
		String pad = "  ".repeat(level + 1);
		StringBuilder out = new StringBuilder();
		if (value.isJsonObject()) {
			out.append("{\n");
			boolean first = true;
			for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
				if (!first) {
					out.append(",\n");
				}
				first = false;
				String head = ProtoJson.write(new JsonPrimitive(entry.getKey())) + ": ";
				out.append(pad).append(head).append(format(entry.getValue(), level + 1, length(head)));
			}
			out.append('\n').append("  ".repeat(level)).append('}');
		} else {
			out.append("[\n");
			JsonArray array = value.getAsJsonArray();
			for (int i = 0; i < array.size(); i++) {
				if (i > 0) {
					out.append(",\n");
				}
				out.append(pad).append(format(array.get(i), level + 1, 0));
			}
			out.append('\n').append("  ".repeat(level)).append(']');
		}
		return out.toString();
	}

	/** Python's {@code json.dumps(value, ensure_ascii=False, separators=(", ", ": "))}. */
	static String flat(JsonElement value) {
		if (value.isJsonObject()) {
			StringBuilder out = new StringBuilder("{");
			boolean first = true;
			for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
				if (!first) {
					out.append(", ");
				}
				first = false;
				out.append(ProtoJson.write(new JsonPrimitive(entry.getKey()))).append(": ").append(flat(entry.getValue()));
			}
			return out.append('}').toString();
		}
		if (value.isJsonArray()) {
			StringBuilder out = new StringBuilder("[");
			JsonArray array = value.getAsJsonArray();
			for (int i = 0; i < array.size(); i++) {
				if (i > 0) {
					out.append(", ");
				}
				out.append(flat(array.get(i)));
			}
			return out.append(']').toString();
		}
		// Primitives and null have one form; the canonical writer's matches Python's.
		return ProtoJson.write(value);
	}

	private static boolean isContainer(JsonElement value) {
		return value.isJsonObject() || value.isJsonArray();
	}

	private static boolean isEmpty(JsonElement value) {
		return value instanceof JsonObject object ? object.size() == 0 : value instanceof JsonArray array && array.isEmpty();
	}

	/** Python's {@code len()}: code points, not UTF-16 units. */
	private static int length(String text) {
		return text.codePointCount(0, text.length());
	}
}
