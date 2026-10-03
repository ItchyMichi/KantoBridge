package dev.kantobridge.client.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.Nullable;

/**
 * Typed reads from message data. Java checks envelopes and the catalog but not the per-type schemas, so every read
 * tolerates a missing or mistyped field instead of throwing.
 */
public final class JsonFields {
	private JsonFields() {
	}

	public static @Nullable String str(@Nullable JsonObject object, String key) {
		JsonElement value = object == null ? null : object.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
	}

	/** An integer field (a JSON number without fraction that fits a long), or {@code fallback}. */
	public static long integer(@Nullable JsonObject object, String key, long fallback) {
		JsonElement value = object == null ? null : object.get(key);
		if (value == null || !value.isJsonPrimitive()) {
			return fallback;
		}
		JsonPrimitive primitive = value.getAsJsonPrimitive();
		if (!primitive.isNumber()) {
			return fallback;
		}
		Number number = primitive.getAsNumber();
		return number instanceof Long || number instanceof Integer ? number.longValue() : fallback;
	}

	public static boolean bool(@Nullable JsonObject object, String key, boolean fallback) {
		JsonElement value = object == null ? null : object.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : fallback;
	}

	public static @Nullable JsonObject obj(@Nullable JsonObject object, String key) {
		JsonElement value = object == null ? null : object.get(key);
		return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
	}
}
