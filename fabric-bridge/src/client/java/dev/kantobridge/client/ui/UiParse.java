package dev.kantobridge.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.client.bridge.JsonFields;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Reads {@code ui.open} and {@code ui.update} data (protocol/messages/ui.open.schema.json) into models. Java checks
 * envelopes but not per-type schemas, so everything is checked here: a malformed message is refused with a short code
 * (never the data) and changes nothing.
 */
final class UiParse {
	private static final Pattern SYMBOL = Pattern.compile("^[A-Za-z0-9_]{1,64}$");
	private static final int MAX_TEXT = 1000;

	/** A message that doesn't fit the schema; the code is safe to log. */
	static final class Invalid extends Exception {
		private final String code;

		Invalid(String code) {
			super(code, null, false, false);
			this.code = code;
		}

		String code() {
			return this.code;
		}
	}

	/** The changed fields of a {@code ui.update}; null when absent. */
	record Update(int id, @Nullable Integer index, @Nullable Integer top, @Nullable Integer page, @Nullable Integer line,
			@Nullable Boolean waiting, @Nullable Boolean done, @Nullable List<MenuModel.Item> items, @Nullable Integer qty) {
	}

	private UiParse() {
	}

	static UiSession open(JsonObject data, long now) throws Invalid {
		int id = id(data);
		UiKind kind = UiKind.byId(JsonFields.str(data, "kind"));
		if (kind == null) {
			throw new Invalid("bad_kind");
		}
		String screen = JsonFields.str(data, "screen");
		if (screen != null && !SYMBOL.matcher(screen).matches()) {
			screen = null;
		}
		Object model = switch (kind) {
			case TEXT -> new TextBoxModel(pages(data), cont(data), bool(data, "auto"), bool(data, "stay"), bool(data, "instant"),
					bool(data, "choice"), now);
			case CHOICE -> new ChoiceModel(strings(data, "labels", 1, 8, true), integer(data, "index", 0, 255, true),
					text(data, "question", false));
			case MENU, LIST -> new MenuModel(kind == UiKind.LIST, optBool(data, "wrap"), items(data, true),
					integer(data, "index", 0, 255, true), integer(data, "top", 0, 255, true), integer(data, "rows", 1, 32, true), text(data, "title", false),
					text(data, "footer", false), optInteger(data, "money", 0, 999_999), screen);
			case QUANTITY -> new QuantityModel(integer(data, "qty", 0, 999, true), integer(data, "max", 1, 999, true),
					optInteger(data, "unit", 0, 999_999));
			case NAMING -> new NamingModel(nonNull(text(data, "title", true)), integer(data, "max", 1, 999, true),
					strings(data, "presets", 0, 8, true), nonNull(text(data, "value", true)), optInteger(data, "mon", 1, 151), grid(data));
			case MIRROR -> new Object();
		};
		return new UiSession(id, kind, model, screen);
	}

	static Update update(JsonObject data) throws Invalid {
		return new Update(id(data), optInteger(data, "index", 0, 255), optInteger(data, "top", 0, 255), optInteger(data, "page", 0, 255),
				optInteger(data, "line", 0, 255), optBool(data, "waiting"), optBool(data, "done"),
				data.has("items") ? items(data, true) : null, optInteger(data, "qty", 0, 999));
	}

	static int id(JsonObject data) throws Invalid {
		long id = JsonFields.integer(data, "ui", -1);
		if (id < 1 || id > Integer.MAX_VALUE) {
			throw new Invalid("bad_id");
		}
		return (int) id;
	}

	// ---- fields ----

	private static List<List<String>> pages(JsonObject data) throws Invalid {
		JsonArray pages = array(data, "pages", 64, true);
		List<List<String>> out = new ArrayList<>();
		for (JsonElement page : pages) {
			if (!page.isJsonArray() || page.getAsJsonArray().size() > 8) {
				throw new Invalid("bad_pages");
			}
			List<String> lines = new ArrayList<>();
			for (JsonElement line : page.getAsJsonArray()) {
				lines.add(string(line, "bad_pages"));
			}
			out.add(lines);
		}
		return out;
	}

	/** The cont flags; a missing or misshapen entry counts as false, since the text is still readable. */
	private static List<List<Boolean>> cont(JsonObject data) {
		List<List<Boolean>> out = new ArrayList<>();
		JsonElement cont = data.get("cont");
		if (cont == null || !cont.isJsonArray()) {
			return out;
		}
		for (JsonElement page : cont.getAsJsonArray()) {
			List<Boolean> flags = new ArrayList<>();
			if (page.isJsonArray()) {
				for (JsonElement flag : page.getAsJsonArray()) {
					flags.add(flag.isJsonPrimitive() && flag.getAsJsonPrimitive().isBoolean() && flag.getAsBoolean());
				}
			}
			out.add(flags);
		}
		return out;
	}

	private static List<MenuModel.Item> items(JsonObject data, boolean required) throws Invalid {
		JsonArray items = array(data, "items", 256, required);
		List<MenuModel.Item> out = new ArrayList<>();
		for (JsonElement element : items) {
			if (!element.isJsonObject()) {
				throw new Invalid("bad_items");
			}
			JsonObject item = element.getAsJsonObject();
			for (String key : item.keySet()) {
				if (!key.equals("label") && !key.equals("right")) {
					throw new Invalid("bad_items");
				}
			}
			String label = text(item, "label", true);
			out.add(new MenuModel.Item(nonNull(label), text(item, "right", false)));
		}
		return out;
	}

	private static @Nullable List<List<List<String>>> grid(JsonObject data) throws Invalid {
		if (!data.has("grid")) {
			return null;
		}
		JsonArray pages = array(data, "grid", 4, true);
		List<List<List<String>>> out = new ArrayList<>();
		for (JsonElement page : pages) {
			if (!page.isJsonArray() || page.getAsJsonArray().size() > 12) {
				throw new Invalid("bad_grid");
			}
			List<List<String>> rows = new ArrayList<>();
			for (JsonElement row : page.getAsJsonArray()) {
				if (!row.isJsonArray() || row.getAsJsonArray().size() > 16) {
					throw new Invalid("bad_grid");
				}
				List<String> cells = new ArrayList<>();
				for (JsonElement cell : row.getAsJsonArray()) {
					String token = string(cell, "bad_grid");
					if (token.isEmpty() || token.length() > Gen1Text.MAX_TOKEN_CHARS) {
						throw new Invalid("bad_grid");
					}
					cells.add(token);
				}
				rows.add(cells);
			}
			out.add(rows);
		}
		return out;
	}

	private static List<String> strings(JsonObject data, String key, int min, int max, boolean required) throws Invalid {
		JsonArray array = array(data, key, max, required);
		if (array.size() < min) {
			throw new Invalid("bad_" + key);
		}
		List<String> out = new ArrayList<>();
		for (JsonElement element : array) {
			out.add(string(element, "bad_" + key));
		}
		return out;
	}

	private static JsonArray array(JsonObject data, String key, int max, boolean required) throws Invalid {
		JsonElement value = data.get(key);
		if (value == null) {
			if (required) {
				throw new Invalid("missing_" + key);
			}
			return new JsonArray();
		}
		if (!value.isJsonArray() || value.getAsJsonArray().size() > max) {
			throw new Invalid("bad_" + key);
		}
		return value.getAsJsonArray();
	}

	private static String string(JsonElement element, String code) throws Invalid {
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString() || element.getAsString().length() > MAX_TEXT) {
			throw new Invalid(code);
		}
		return element.getAsString();
	}

	private static @Nullable String text(JsonObject data, String key, boolean required) throws Invalid {
		JsonElement value = data.get(key);
		if (value == null) {
			if (required) {
				throw new Invalid("missing_" + key);
			}
			return null;
		}
		return string(value, "bad_" + key);
	}

	private static int integer(JsonObject data, String key, int min, int max, boolean required) throws Invalid {
		Integer value = optInteger(data, key, min, max);
		if (value == null) {
			if (required) {
				throw new Invalid("missing_" + key);
			}
			return min;
		}
		return value;
	}

	private static @Nullable Integer optInteger(JsonObject data, String key, int min, int max) throws Invalid {
		if (!data.has(key)) {
			return null;
		}
		long value = JsonFields.integer(data, key, Long.MIN_VALUE);
		if (value < min || value > max) {
			throw new Invalid("bad_" + key);
		}
		return (int) value;
	}

	private static boolean bool(JsonObject data, String key) throws Invalid {
		return Boolean.TRUE.equals(optBool(data, key));
	}

	private static @Nullable Boolean optBool(JsonObject data, String key) throws Invalid {
		JsonElement value = data.get(key);
		if (value == null) {
			return null;
		}
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
			throw new Invalid("bad_" + key);
		}
		return value.getAsBoolean();
	}

	private static String nonNull(@Nullable String value) {
		return value == null ? "" : value;
	}
}
