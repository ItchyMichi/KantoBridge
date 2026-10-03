package dev.kantobridge.proto;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Removes ROM-derived content before anything is logged (docs/PLAN.md §0.3). Each value at a catalog
 * {@code redact} path becomes {@code <rom:KIND:LEN:SHA8>} (protocol/README.md), so logs keep shapes, counts and
 * hashes but never text, teams, map data or images.
 */
public final class Redact {
	private Redact() {
	}

	/** A redacted copy of the whole envelope, using the catalog's paths for its type. */
	public static JsonObject envelope(Catalog catalog, Envelope envelope) {
		JsonObject out = envelope.toJsonObject().deepCopy();
		Catalog.Entry entry = catalog.get(envelope.type());
		if (entry == null) {
			// Unknown type: we can't know which fields are ROM-derived, so keep no data at all.
			out.add("data", new JsonPrimitive(placeholder(envelope.data())));
		} else {
			out.add("data", data(entry.redact(), envelope.data()));
		}
		return out;
	}

	/** A redacted copy of {@code data}; the input is not changed. Paths that don't match anything are skipped. */
	public static JsonObject data(List<String> paths, JsonObject data) {
		JsonObject copy = data.deepCopy();
		for (String path : paths) {
			List<String> tokens = pointerTokens(path);
			if (!tokens.isEmpty()) {
				apply(copy, tokens, 0);
			}
		}
		return copy;
	}

	/** One-line log summary of a message: type, numbers and size only. */
	public static String summary(Envelope envelope, int bytes) {
		return "type=" + envelope.type() + " seq=" + envelope.seq() + (envelope.re() != null ? " re=" + envelope.re() : "")
				+ " bytes=" + bytes;
	}

	/** {@code <rom:KIND:LEN:SHA8>} for one value. */
	public static String placeholder(JsonElement value) {
		String kind;
		long length;
		if (value == null || value.isJsonNull()) {
			kind = "null";
			length = 0;
		} else if (value.isJsonObject()) {
			kind = "obj";
			length = value.getAsJsonObject().size();
		} else if (value.isJsonArray()) {
			kind = "arr";
			length = value.getAsJsonArray().size();
		} else {
			JsonPrimitive primitive = value.getAsJsonPrimitive();
			if (primitive.isString()) {
				kind = "str";
				String text = primitive.getAsString();
				length = text.codePointCount(0, text.length());
			} else if (primitive.isBoolean()) {
				kind = "bool";
				length = 0;
			} else {
				kind = "num";
				length = 0;
			}
		}
		return "<rom:" + kind + ":" + length + ":" + sha8(value) + ">";
	}

	/** First 8 hex digits of the SHA-256 of the value's canonical JSON. */
	public static String sha8(JsonElement value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(ProtoJson.canonicalUtf8(value));
			return HexFormat.of().formatHex(hash, 0, 4);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is always available", e);
		}
	}

	/** Short hash of a raw string, for logging ids or sizes of things that must not be printed. */
	public static String sha8(String text) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash, 0, 4);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is always available", e);
		}
	}

	private static void apply(JsonElement node, List<String> tokens, int index) {
		String token = tokens.get(index);
		boolean last = index == tokens.size() - 1;
		if (node.isJsonObject()) {
			JsonObject object = node.getAsJsonObject();
			List<String> keys = token.equals("*") ? new ArrayList<>(object.keySet()) : List.of(token);
			for (String key : keys) {
				JsonElement child = object.get(key);
				if (child == null) {
					continue;
				}
				if (last) {
					object.add(key, new JsonPrimitive(placeholder(child)));
				} else {
					apply(child, tokens, index + 1);
				}
			}
		} else if (node.isJsonArray()) {
			JsonArray array = node.getAsJsonArray();
			if (token.equals("*")) {
				for (int i = 0; i < array.size(); i++) {
					applyToElement(array, i, tokens, index, last);
				}
			} else {
				int i = arrayIndex(token);
				if (i >= 0 && i < array.size()) {
					applyToElement(array, i, tokens, index, last);
				}
			}
		}
	}

	private static void applyToElement(JsonArray array, int i, List<String> tokens, int index, boolean last) {
		if (last) {
			array.set(i, new JsonPrimitive(placeholder(array.get(i))));
		} else {
			apply(array.get(i), tokens, index + 1);
		}
	}

	private static int arrayIndex(String token) {
		if (token.isEmpty() || token.length() > 9 || (token.length() > 1 && token.charAt(0) == '0')) {
			return -1;
		}
		for (int i = 0; i < token.length(); i++) {
			if (token.charAt(i) < '0' || token.charAt(i) > '9') {
				return -1;
			}
		}
		return Integer.parseInt(token);
	}

	/** Splits a JSON Pointer ({@code /a/~1b/*}) into unescaped tokens. */
	static List<String> pointerTokens(String pointer) {
		List<String> tokens = new ArrayList<>();
		if (pointer.isEmpty()) {
			return tokens;
		}
		for (String raw : pointer.substring(1).split("/", -1)) {
			tokens.add(raw.replace("~1", "/").replace("~0", "~"));
		}
		return tokens;
	}
}
