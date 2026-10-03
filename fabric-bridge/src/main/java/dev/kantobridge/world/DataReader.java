package dev.kantobridge.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Strict, typed reads from one JSON object, for data files whose schemas say {@code additionalProperties: false}
 * (the WorldIndex, the slot map, export resources). Every failure is a {@link DataException} carrying the JSON path,
 * never the offending value.
 */
public final class DataReader {
	/** Engine symbols: map ids, palettes, sprites, object names. */
	public static final Pattern SYMBOL = Pattern.compile("^[A-Za-z0-9_]{1,96}$");
	/** Map ids. */
	public static final Pattern MAP_ID = Pattern.compile("^[A-Z0-9_]{1,64}$");
	/** Resource locations such as {@code kantobridge:kanto_outdoor}. */
	public static final Pattern RESOURCE_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");
	/** Lowercase hex SHA-256 (or a prefix of at least 8 digits). */
	public static final Pattern HASH = Pattern.compile("^[0-9a-f]{8,64}$");

	private final JsonObject object;
	private final String path;

	private DataReader(JsonObject object, String path) {
		this.object = object;
		this.path = path;
	}

	public static DataReader of(@Nullable JsonElement element, String path) throws DataException {
		if (element == null || !element.isJsonObject()) {
			throw new DataException(path, "not an object");
		}
		return new DataReader(element.getAsJsonObject(), path);
	}

	public JsonObject json() {
		return this.object;
	}

	public String path() {
		return this.path;
	}

	public String path(String name) {
		return this.path + "/" + name;
	}

	public boolean has(String name) {
		return this.object.has(name);
	}

	public boolean isNull(String name) {
		JsonElement value = this.object.get(name);
		return value == null || value.isJsonNull();
	}

	/** Fails on any key outside {@code allowed}. */
	public DataReader allowOnly(Set<String> allowed) throws DataException {
		for (String key : this.object.keySet()) {
			if (!allowed.contains(key)) {
				// The key itself is a schema name, not content, but stay strict and don't echo it.
				throw new DataException(this.path, "unexpected field");
			}
		}
		return this;
	}

	public JsonElement element(String name) throws DataException {
		JsonElement value = this.object.get(name);
		if (value == null) {
			throw new DataException(this.path(name), "missing");
		}
		return value;
	}

	public DataReader object(String name) throws DataException {
		return of(this.element(name), this.path(name));
	}

	public @Nullable DataReader optionalObject(String name) throws DataException {
		return this.isNull(name) ? null : this.object(name);
	}

	public JsonArray array(String name) throws DataException {
		JsonElement value = this.element(name);
		if (!value.isJsonArray()) {
			throw new DataException(this.path(name), "not an array");
		}
		return value.getAsJsonArray();
	}

	public String string(String name) throws DataException {
		return asString(this.element(name), this.path(name));
	}

	public String string(String name, Pattern pattern) throws DataException {
		String value = this.string(name);
		if (!pattern.matcher(value).matches()) {
			throw new DataException(this.path(name), "bad format");
		}
		return value;
	}

	public @Nullable String optionalString(String name, Pattern pattern) throws DataException {
		return this.isNull(name) ? null : this.string(name, pattern);
	}

	public boolean bool(String name) throws DataException {
		JsonElement value = this.element(name);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
			throw new DataException(this.path(name), "not a boolean");
		}
		return value.getAsBoolean();
	}

	public int integer(String name) throws DataException {
		return asInt(this.element(name), this.path(name), Integer.MIN_VALUE, Integer.MAX_VALUE);
	}

	public int integer(String name, int min, int max) throws DataException {
		return asInt(this.element(name), this.path(name), min, max);
	}

	public long longValue(String name, long min, long max) throws DataException {
		return asLong(this.element(name), this.path(name), min, max);
	}

	// ---- element helpers ----

	public static String asString(JsonElement value, String path) throws DataException {
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			throw new DataException(path, "not a string");
		}
		return value.getAsString();
	}

	public static String asString(JsonElement value, String path, Pattern pattern) throws DataException {
		String text = asString(value, path);
		if (!pattern.matcher(text).matches()) {
			throw new DataException(path, "bad format");
		}
		return text;
	}

	public static int asInt(JsonElement value, String path, int min, int max) throws DataException {
		return (int) asLong(value, path, min, max);
	}

	public static long asLong(JsonElement value, String path, long min, long max) throws DataException {
		if (!value.isJsonPrimitive()) {
			throw new DataException(path, "not an integer");
		}
		JsonPrimitive primitive = value.getAsJsonPrimitive();
		if (!primitive.isNumber()) {
			throw new DataException(path, "not an integer");
		}
		Number number = primitive.getAsNumber();
		// Strict parsing (ProtoJson) yields Long for integer literals; Gson's lazy numbers need checking.
		long n;
		if (number instanceof Long || number instanceof Integer || number instanceof Short || number instanceof Byte) {
			n = number.longValue();
		} else {
			try {
				n = new java.math.BigDecimal(number.toString()).longValueExact();
			} catch (ArithmeticException | NumberFormatException e) {
				throw new DataException(path, "not an integer");
			}
		}
		if (n < min || n > max) {
			throw new DataException(path, "out of range");
		}
		return n;
	}

	public static int[] intArray(JsonArray array, String path, int min, int max) throws DataException {
		int[] out = new int[array.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = asInt(array.get(i), path + "/" + i, min, max);
		}
		return out;
	}

	public static List<DataReader> objects(JsonArray array, String path) throws DataException {
		List<DataReader> out = new ArrayList<>(array.size());
		for (int i = 0; i < array.size(); i++) {
			out.add(of(array.get(i), path + "/" + i));
		}
		return out;
	}
}
