package dev.kantobridge.story;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.DataReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * The relay's {@code story.state} message (protocol/messages/story.state.schema.json): a full state on join, then deltas.
 * The client applies each message to its own player's {@link StoryState} before relaying it, and the server applies the
 * same message to its copy, so both sides run the one {@link #apply} and agree (docs/PLAN.md §2.3).
 *
 * <p>A message is checked completely before anything changes. Problems are reported as short codes, never values.
 */
public final class StoryStateMessages {
	/** More than the 12,539 story cells of a full Kanto build. */
	public static final int MAX_CELLS = 65_536;
	/** Lit dark maps; Kanto has a handful. */
	public static final int MAX_LIT = 256;

	/** MG3: {@code region} is the client's link bookkeeping, read by the service, not applied here. */
	private static final Set<String> FIELDS = Set.of("full", "active", "add", "remove", "surf", "bike", "run", "forced", "lit", "region");

	/** What one message changed. */
	public record Applied(boolean full, boolean surfChanged, boolean bikeChanged, int cellsChanged, boolean runChanged) {
		public Applied(boolean full, boolean surfChanged, boolean bikeChanged, int cellsChanged) {
			this(full, surfChanged, bikeChanged, cellsChanged, false);
		}

	}

	/** A message that breaks the schema's rules; nothing was applied. */
	public static final class Invalid extends Exception {
		private final String code;

		public Invalid(String code) {
			super(code, null, false, false);
			this.code = code;
		}

		public String code() {
			return this.code;
		}
	}

	private StoryStateMessages() {
	}

	/** A full state: every active cell, the surf and bike flags, and the lit maps. */
	public static JsonObject full(Collection<String> active, boolean surf, boolean bike, Collection<String> lit) {
		return full(active, surf, bike, false, lit);
	}

	/** The same with the running flag (FA8 item 7; written only when running). */
	public static JsonObject full(Collection<String> active, boolean surf, boolean bike, boolean run, Collection<String> lit) {
		return full(active, surf, bike, run, false, lit);
	}

	/** The same with the forced-move flag (WATER_AND_BRIDGES_PLAN D4; written only during one). */
	public static JsonObject full(Collection<String> active, boolean surf, boolean bike, boolean run, boolean forced, Collection<String> lit) {
		JsonObject data = new JsonObject();
		data.addProperty("full", true);
		data.add("active", array(active));
		data.addProperty("surf", surf);
		data.addProperty("bike", bike);
		if (run) {
			data.addProperty("run", true);
		}
		if (forced) {
			data.addProperty("forced", true);
		}
		data.add("lit", array(lit));
		return data;
	}

	/** A delta; null or empty parts are left out. {@code lit}, when given, replaces the lit maps. */
	public static JsonObject delta(@Nullable Collection<String> add, @Nullable Collection<String> remove, @Nullable Boolean surf,
			@Nullable Boolean bike, @Nullable Collection<String> lit) {
		return delta(add, remove, surf, bike, null, lit);
	}

	/** The same with the running flag (FA8 item 7), null when it didn't change. */
	public static JsonObject delta(@Nullable Collection<String> add, @Nullable Collection<String> remove, @Nullable Boolean surf,
			@Nullable Boolean bike, @Nullable Boolean run, @Nullable Collection<String> lit) {
		return delta(add, remove, surf, bike, run, null, lit);
	}

	/** The same with the forced-move flag, null when it didn't change. */
	public static JsonObject delta(@Nullable Collection<String> add, @Nullable Collection<String> remove, @Nullable Boolean surf,
			@Nullable Boolean bike, @Nullable Boolean run, @Nullable Boolean forced, @Nullable Collection<String> lit) {
		JsonObject data = new JsonObject();
		data.addProperty("full", false);
		if (add != null && !add.isEmpty()) {
			data.add("add", array(add));
		}
		if (remove != null && !remove.isEmpty()) {
			data.add("remove", array(remove));
		}
		if (surf != null) {
			data.addProperty("surf", surf);
		}
		if (bike != null) {
			data.addProperty("bike", bike);
		}
		if (run != null) {
			data.addProperty("run", run);
		}
		if (forced != null) {
			data.addProperty("forced", forced);
		}
		if (lit != null) {
			data.add("lit", array(lit));
		}
		return data;
	}

	/** Checks a message and applies it to {@code state}; throws without changing anything if it is malformed. */
	public static Applied apply(StoryState state, JsonObject data) throws Invalid {
		for (String key : data.keySet()) {
			if (!FIELDS.contains(key)) {
				throw new Invalid("unknown_field");
			}
		}
		Boolean full = bool(data, "full");
		if (full == null) {
			throw new Invalid("full");
		}
		Boolean surf = bool(data, "surf");
		Boolean bike = bool(data, "bike");
		Boolean run = bool(data, "run");
		Boolean forced = bool(data, "forced");
		if ((data.has("surf") && surf == null) || (data.has("bike") && bike == null) || (data.has("run") && run == null)
				|| (data.has("forced") && forced == null)) {
			throw new Invalid("flag");
		}
		List<String> lit = data.has("lit") ? strings(data.get("lit"), MAX_LIT, false) : null;
		boolean surfBefore = state.surf();
		boolean bikeBefore = state.bike();
		boolean runBefore = state.run();
		int changed;
		if (full) {
			if (!data.has("active") || surf == null || bike == null || lit == null) {
				throw new Invalid("incomplete");
			}
			List<String> active = strings(data.get("active"), MAX_CELLS, true);
			Set<String> before = Set.copyOf(state.active());
			changed = 0;
			for (String key : active) {
				if (!before.contains(key)) {
					changed++;
				}
			}
			changed += before.size() - (active.size() - changed);
			state.setActive(active);
		} else {
			// "active" only means something in a full state; a delta's adds and removes say what changed.
			List<String> add = data.has("add") ? strings(data.get("add"), MAX_CELLS, true) : List.of();
			List<String> remove = data.has("remove") ? strings(data.get("remove"), MAX_CELLS, true) : List.of();
			changed = 0;
			for (String key : remove) {
				if (state.isActive(key)) {
					state.deactivate(key);
					changed++;
				}
			}
			for (String key : add) {
				if (!state.isActive(key)) {
					state.activate(key);
					changed++;
				}
			}
		}
		if (surf != null) {
			state.setSurf(surf);
		}
		if (bike != null) {
			state.setBike(bike);
		}
		if (run != null || full) {
			// a full state without it: not running
			state.setRun(Boolean.TRUE.equals(run));
		}
		if (forced != null || full) {
			state.setForced(Boolean.TRUE.equals(forced));
		}
		if (lit != null) {
			state.setLit(lit);
		}
		return new Applied(full, state.surf() != surfBefore, state.bike() != bikeBefore, changed, state.run() != runBefore);
	}

	private static @Nullable Boolean bool(JsonObject data, String key) {
		JsonElement value = data.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : null;
	}

	/** Unique cell keys ({@code cells}) or map ids. */
	private static List<String> strings(JsonElement element, int max, boolean cells) throws Invalid {
		if (!element.isJsonArray()) {
			throw new Invalid(cells ? "cells" : "lit");
		}
		JsonArray array = element.getAsJsonArray();
		if (array.size() > max) {
			throw new Invalid("too_many");
		}
		Set<String> seen = new LinkedHashSet<>();
		for (JsonElement item : array) {
			String value = item.isJsonPrimitive() && item.getAsJsonPrimitive().isString() ? item.getAsString() : null;
			boolean ok = value != null && (cells ? CellPos.isKey(value) : DataReader.MAP_ID.matcher(value).matches());
			if (!ok) {
				throw new Invalid(cells ? "cell_key" : "map_id");
			}
			if (!seen.add(value)) {
				throw new Invalid("duplicate");
			}
		}
		return new ArrayList<>(seen);
	}

	private static JsonArray array(Collection<String> values) {
		JsonArray array = new JsonArray();
		for (String value : new LinkedHashSet<>(values)) {
			array.add(value);
		}
		return array;
	}
}
