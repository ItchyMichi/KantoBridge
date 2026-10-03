package dev.kantobridge.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Every cell that carries a story-cell block, with what decides whether it is closed for a player (WorldIndex
 * {@code storyCells}). Entries are kept sorted by map, then row, then column, so the JSON is deterministic.
 */
public final class StoryCellTable {
	/** Swap kinds, as in protocol/export/swaps.schema.json. */
	public static final Set<String> SWAP_KINDS = Set.of("cut", "card_key", "script", "boulder");

	private static final Set<String> ENTRY_FIELDS = Set.of("key", "type", "obj", "swap");
	private static final Set<String> SWAP_FIELDS = Set.of("bx", "by", "kind", "before", "after");
	private static final Pattern CELL_KEY = Pattern.compile("^[A-Z0-9_]+:-?[0-9]+:-?[0-9]+$");
	private static final Comparator<Entry> ORDER = Comparator.<Entry, String>comparing(e -> e.cell().map())
			.thenComparingInt(e -> e.cell().y())
			.thenComparingInt(e -> e.cell().x());

	/** The swap site (from the swaps export) a {@code tile} cell belongs to. */
	public record Swap(int bx, int by, String kind, int before, int after) {
		public Swap {
			if (!SWAP_KINDS.contains(kind)) {
				throw new IllegalArgumentException("unknown swap kind");
			}
		}

		JsonObject toJson() {
			JsonObject out = new JsonObject();
			out.addProperty("bx", this.bx);
			out.addProperty("by", this.by);
			out.addProperty("kind", this.kind);
			out.addProperty("before", this.before);
			out.addProperty("after", this.after);
			return out;
		}
	}

	/**
	 * @param obj  {@code object} cells: the map object (toggle name) standing here
	 * @param swap {@code tile} cells: the swap site behind them
	 */
	public record Entry(CellPos cell, StoryCellType type, @Nullable String obj, @Nullable Swap swap) {
		public String key() {
			return this.cell.key();
		}

		JsonObject toJson() {
			JsonObject out = new JsonObject();
			out.addProperty("key", this.key());
			out.addProperty("type", this.type.id());
			if (this.obj != null) {
				out.addProperty("obj", this.obj);
			}
			if (this.swap != null) {
				out.add("swap", this.swap.toJson());
			}
			return out;
		}
	}

	private final List<Entry> entries;
	private final Map<String, Entry> byKey;

	private StoryCellTable(List<Entry> sorted) {
		this.entries = Collections.unmodifiableList(sorted);
		Map<String, Entry> map = new LinkedHashMap<>();
		for (Entry entry : sorted) {
			if (map.put(entry.key(), entry) != null) {
				throw new IllegalArgumentException("duplicate story cell");
			}
		}
		this.byKey = Collections.unmodifiableMap(map);
	}

	public static StoryCellTable empty() {
		return new StoryCellTable(List.of());
	}

	/** A table of {@code entries} in canonical order; keys must be unique. */
	public static StoryCellTable of(Collection<Entry> entries) {
		List<Entry> sorted = new ArrayList<>(entries);
		sorted.sort(ORDER);
		return new StoryCellTable(sorted);
	}

	public List<Entry> entries() {
		return this.entries;
	}

	public @Nullable Entry get(String key) {
		return this.byKey.get(key);
	}

	public int size() {
		return this.entries.size();
	}

	/** Entry counts per type, every type present (zero included). */
	public Map<StoryCellType, Integer> countByType() {
		Map<StoryCellType, Integer> out = new EnumMap<>(StoryCellType.class);
		for (StoryCellType type : StoryCellType.values()) {
			out.put(type, 0);
		}
		for (Entry entry : this.entries) {
			out.merge(entry.type(), 1, Integer::sum);
		}
		return out;
	}

	public JsonArray toJson() {
		JsonArray out = new JsonArray();
		for (Entry entry : this.entries) {
			out.add(entry.toJson());
		}
		return out;
	}

	public static StoryCellTable fromJson(JsonArray array, String path) throws DataException {
		List<Entry> entries = new ArrayList<>(array.size());
		for (DataReader item : DataReader.objects(array, path)) {
			item.allowOnly(ENTRY_FIELDS);
			String key = item.string("key", CELL_KEY);
			StoryCellType type = StoryCellType.byId(item.string("type"));
			if (type == null) {
				throw new DataException(item.path("type"), "unknown story cell type");
			}
			String obj = item.has("obj") ? item.string("obj", DataReader.SYMBOL) : null;
			Swap swap = null;
			if (item.has("swap")) {
				DataReader s = item.object("swap").allowOnly(SWAP_FIELDS);
				String kind = s.string("kind");
				if (!SWAP_KINDS.contains(kind)) {
					throw new DataException(s.path("kind"), "unknown swap kind");
				}
				swap = new Swap(s.integer("bx", 0, 255), s.integer("by", 0, 255), kind, s.integer("before", 0, 255),
						s.integer("after", 0, 255));
			}
			CellPos cell;
			try {
				cell = CellPos.parse(key);
			} catch (IllegalArgumentException e) {
				throw new DataException(item.path("key"), "bad cell key");
			}
			entries.add(new Entry(cell, type, obj, swap));
		}
		try {
			return of(entries);
		} catch (IllegalArgumentException e) {
			throw new DataException(path, "duplicate story cell");
		}
	}
}
