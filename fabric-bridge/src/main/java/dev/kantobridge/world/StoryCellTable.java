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
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Every cell that carries a story-cell block, with what decides whether it is closed for a player (WorldIndex
 * {@code storyCells}; docs/PROTOCOL.md "Story rules (M8)"). Entries are kept sorted by map, then row, then column, so
 * the JSON is deterministic.
 *
 * <p>Besides its type, an entry carries what a client needs to decide its state from the hidden game's reports:
 * {@code object} entries name their map object and whether it starts hidden; {@code tile} entries name their swap site,
 * the block the map data has there ({@code start}, what maps other than the hidden player's current one show), whether
 * their open (built) state is water ({@code water}: it then closes unless the player surfs, like a water cell) and their
 * closed state ({@link Closed}: the block that closes the cell, how it lets players through, and its art).
 */
public final class StoryCellTable {
	/** Swap kinds, as in protocol/export/swaps.schema.json. */
	public static final Set<String> SWAP_KINDS = Set.of("cut", "card_key", "script", "boulder");
	/** Closed art covers the cell's 2×2 blocks: north-west, north-east, south-west, south-east. */
	public static final int ART_BLOCKS = 4;
	/** The tallest closed art: a roof column (4) plus room for measured heights. */
	public static final int MAX_CLOSED_HEIGHT = 8;

	private static final Set<String> ENTRY_FIELDS = Set.of("key", "type", "obj", "swap", "hidden", "start", "closed", "water");
	private static final Set<String> SWAP_FIELDS = Set.of("bx", "by", "kind", "before", "after");
	private static final Set<String> CLOSED_FIELDS = Set.of("block", "pass", "height", "art");
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

	/** How a tile cell's closed state lets a player through. */
	public enum Pass {
		/** Never: a tree, a shut door, a barrier, a ship's hull. */
		SOLID,
		/** Water: only a surfing player. */
		WATER,
		/** Always: only the art differs. */
		GROUND;

		public String id() {
			return this.name().toLowerCase(java.util.Locale.ROOT);
		}

		public static @Nullable Pass byId(String id) {
			for (Pass pass : values()) {
				if (pass.id().equals(id)) {
					return pass;
				}
			}
			return null;
		}
	}

	/**
	 * A {@code tile} cell's closed state.
	 *
	 * @param block  the cell is closed while the live block at the site is this one (the site's {@code before}, or its
	 *               {@code after} for a cell whose {@code after} state is the barred one)
	 * @param pass   how the closed cell lets players through
	 * @param height closed art drawn above the floor block, in blocks; 0 draws it flat on the floor
	 * @param art    full-shape slot indices of the closed art, {@link #ART_BLOCKS} of them (north-west, north-east,
	 *               south-west, south-east)
	 */
	public record Closed(int block, Pass pass, int height, List<Integer> art) {
		public Closed {
			Objects.requireNonNull(pass, "pass");
			art = List.copyOf(art);
			if (block < 0 || block > 255 || height < 0 || height > MAX_CLOSED_HEIGHT || art.size() != ART_BLOCKS) {
				throw new IllegalArgumentException("invalid closed state");
			}
			for (int slot : art) {
				if (slot < 0 || slot >= SlotMap.CAPACITY) {
					throw new IllegalArgumentException("invalid art slot");
				}
			}
		}

		/** A closed state that either bars everyone ({@code solid}) or only shows other art. */
		public Closed(int block, boolean solid, int height, List<Integer> art) {
			this(block, solid ? Pass.SOLID : Pass.GROUND, height, art);
		}

		/** The art slot of the block at ({@code dx}, {@code dz}) within the cell (each 0 or 1). */
		public int art(int dx, int dz) {
			return this.art.get((dz & 1) * 2 + (dx & 1));
		}

		JsonObject toJson() {
			JsonObject out = new JsonObject();
			out.addProperty("block", this.block);
			out.addProperty("pass", this.pass.id());
			out.addProperty("height", this.height);
			JsonArray slots = new JsonArray();
			this.art.forEach(slots::add);
			out.add("art", slots);
			return out;
		}
	}

	/**
	 * @param obj    {@code object} cells: the map object (toggle name) standing here
	 * @param swap   {@code tile} cells: the swap site behind them
	 * @param hidden {@code object} cells: the object starts hidden (its map record's default)
	 * @param start  {@code tile} cells: the map data's block at the site
	 * @param closed {@code tile} cells: the closed state
	 * @param water  {@code tile} cells: the open (built) state is water, closed unless the player surfs
	 */
	public record Entry(CellPos cell, StoryCellType type, @Nullable String obj, @Nullable Swap swap, boolean hidden, @Nullable Integer start,
			@Nullable Closed closed, boolean water) {
		public Entry {
			Objects.requireNonNull(cell, "cell");
			Objects.requireNonNull(type, "type");
			if (start != null && (start < 0 || start > 255)) {
				throw new IllegalArgumentException("invalid start block");
			}
		}

		/** An entry without M8's state fields (an object that starts visible; a tile cell with no closed state known). */
		public Entry(CellPos cell, StoryCellType type, @Nullable String obj, @Nullable Swap swap) {
			this(cell, type, obj, swap, false, null, null, false);
		}

		/** An entry whose open state (if it is a tile cell) isn't water. */
		public Entry(CellPos cell, StoryCellType type, @Nullable String obj, @Nullable Swap swap, boolean hidden, @Nullable Integer start,
				@Nullable Closed closed) {
			this(cell, type, obj, swap, hidden, start, closed, false);
		}

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
			if (this.hidden) {
				out.addProperty("hidden", true);
			}
			if (this.start != null) {
				out.addProperty("start", this.start);
			}
			if (this.closed != null) {
				out.add("closed", this.closed.toJson());
			}
			if (this.water) {
				out.addProperty("water", true);
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
			// a zone ball's obj is its id in the zone (a2.i1), every other obj a map object's name
			String obj = item.has("obj") ? item.string("obj", type == StoryCellType.ROUTE ? WorldIndex.ZONE_OBJECT_ID : DataReader.SYMBOL) : null;
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
			boolean hidden = item.has("hidden") && item.bool("hidden");
			Integer start = item.has("start") ? item.integer("start", 0, 255) : null;
			Closed closed = null;
			if (item.has("closed")) {
				DataReader c = item.object("closed").allowOnly(CLOSED_FIELDS);
				int[] art = DataReader.intArray(c.array("art"), c.path("art"), 0, SlotMap.CAPACITY - 1);
				if (art.length != ART_BLOCKS) {
					throw new DataException(c.path("art"), "closed art needs " + ART_BLOCKS + " slots");
				}
				List<Integer> slots = new ArrayList<>(ART_BLOCKS);
				for (int slot : art) {
					slots.add(slot);
				}
				Pass pass = Pass.byId(c.string("pass"));
				if (pass == null) {
					throw new DataException(c.path("pass"), "unknown pass");
				}
				closed = new Closed(c.integer("block", 0, 255), pass, c.integer("height", 0, MAX_CLOSED_HEIGHT), slots);
			}
			boolean water = item.has("water") && item.bool("water");
			CellPos cell;
			try {
				cell = CellPos.parse(key);
			} catch (IllegalArgumentException e) {
				throw new DataException(item.path("key"), "bad cell key");
			}
			entries.add(new Entry(cell, type, obj, swap, hidden, start, closed, water));
		}
		try {
			return of(entries);
		} catch (IllegalArgumentException e) {
			throw new DataException(path, "duplicate story cell");
		}
	}
}
