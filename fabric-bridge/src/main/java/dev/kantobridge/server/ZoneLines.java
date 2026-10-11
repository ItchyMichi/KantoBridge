package dev.kantobridge.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What Wild Routes trainers say before a battle (docs/specs/WILD_ROUTES_PLAN.md §4.6): our own text, a list by trainer
 * class in data/kantobridge/wild_routes/lines.json, with {@code default} for any other class. Each trainer always says
 * the same one (picked by its id). A line fits one line of a Gen 1 text box ({@link #MAX_LENGTH} characters) until the
 * engine's wrapping of literal text is known (check C6).
 */
public final class ZoneLines {
	public static final String RESOURCE = "/data/kantobridge/wild_routes/lines.json";
	public static final int MAX_LENGTH = 18;

	private final Map<String, List<String>> byClass;

	ZoneLines(Map<String, List<String>> byClass) {
		Map<String, List<String>> copy = new TreeMap<>();
		byClass.forEach((cls, lines) -> {
			if (lines.isEmpty()) {
				throw new IllegalArgumentException("no lines for " + cls);
			}
			for (String line : lines) {
				if (line.isEmpty() || line.length() > MAX_LENGTH || !line.chars().allMatch(c -> c >= 0x20 && c < 0x7F)) {
					throw new IllegalArgumentException("a line of " + cls + " doesn't fit a text box line");
				}
			}
			copy.put(cls, List.copyOf(lines));
		});
		if (!copy.containsKey("default")) {
			throw new IllegalArgumentException("no default lines");
		}
		this.byClass = Collections.unmodifiableMap(copy);
	}

	/** The line trainer {@code trainerId} of class {@code cls} says. */
	public String lineFor(String cls, String trainerId) {
		List<String> lines = this.byClass.getOrDefault(cls, this.byClass.get("default"));
		return lines.get(Math.floorMod(trainerId.hashCode(), lines.size()));
	}

	public Map<String, List<String>> byClass() {
		return this.byClass;
	}

	/** The mod's own lines. */
	public static ZoneLines bundled() {
		try (InputStream in = ZoneLines.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("missing " + RESOURCE);
			}
			JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			if (root.get("version").getAsInt() != 1) {
				throw new IllegalArgumentException("unsupported version");
			}
			Map<String, List<String>> byClass = new TreeMap<>();
			for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("byClass").entrySet()) {
				List<String> lines = new ArrayList<>();
				for (JsonElement line : e.getValue().getAsJsonArray()) {
					lines.add(line.getAsString());
				}
				byClass.put(e.getKey(), lines);
			}
			return new ZoneLines(byClass);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
