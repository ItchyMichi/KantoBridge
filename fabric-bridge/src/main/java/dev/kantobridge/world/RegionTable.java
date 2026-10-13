package dev.kantobridge.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * The region table, {@code <world>/kantobridge/regions.json} (docs/specs/MULTI_GAME_PLAN.md §4.6, MG3;
 * protocol/world/regions.schema.json): the regions of the one flat overworld, each built from one game's export at a
 * slot of the table. Slot {@code n} has its origin at {@code (pitch · n, 64, 0)} along +X (D8), {@link #PITCH} blocks
 * apart, so one row of slots holds as many regions as the world border allows. A world without this file is a legacy
 * world: one implicit region {@code kanto} at slot 0 (§4.14).
 *
 * <p>The JSON is canonical (sorted keys, compact); ids only, nothing ROM-derived.
 */
public final class RegionTable {
	public static final String FILE = "regions.json";
	public static final int VERSION = 1;
	/** Blocks between slot origins along X (D8). */
	public static final int PITCH = 8192;
	public static final int MIN_PITCH = 4096;
	public static final int MAX_PITCH = 65536;
	public static final int MAX_SLOT = 63;
	public static final int MAX_REGIONS = MAX_SLOT + 1;

	private static final Set<String> ROOT_FIELDS = Set.of("version", "pitch", "regions");
	private static final Set<String> ENTRY_FIELDS = Set.of("id", "game", "slot");

	/** One region of the table: its id, the game whose export built it, and its slot. */
	public record Entry(String id, String game, int slot) {
		public Entry {
			if (id == null || !Games.REGION_ID.matcher(id).matches()) {
				throw new IllegalArgumentException("invalid region id");
			}
			if (!Games.known(game)) {
				throw new IllegalArgumentException("unknown game");
			}
			if (slot < 0 || slot > MAX_SLOT) {
				throw new IllegalArgumentException("slot out of range");
			}
		}
	}

	private final int pitch;
	private final List<Entry> entries;
	private final String json;
	private final String hash;

	/** An empty table with the default pitch. */
	public RegionTable() {
		this(PITCH, List.of());
	}

	public RegionTable(List<Entry> entries) {
		this(PITCH, entries);
	}

	/**
	 * @throws IllegalArgumentException for a pitch outside {@link #MIN_PITCH}..{@link #MAX_PITCH}, two entries with one
	 *                                  id or one slot, or more than {@link #MAX_REGIONS} entries
	 */
	public RegionTable(int pitch, List<Entry> entries) {
		if (pitch < MIN_PITCH || pitch > MAX_PITCH) {
			throw new IllegalArgumentException("pitch out of range");
		}
		if (entries.size() > MAX_REGIONS) {
			throw new IllegalArgumentException("too many regions");
		}
		List<Entry> sorted = new ArrayList<>(entries);
		sorted.sort(Comparator.comparingInt(Entry::slot));
		Set<String> ids = new HashSet<>();
		for (int i = 0; i < sorted.size(); i++) {
			Entry entry = Objects.requireNonNull(sorted.get(i), "entry");
			if (!ids.add(entry.id())) {
				throw new IllegalArgumentException("duplicate region id");
			}
			if (i > 0 && sorted.get(i - 1).slot() == entry.slot()) {
				throw new IllegalArgumentException("two regions in one slot");
			}
		}
		this.pitch = pitch;
		this.entries = List.copyOf(sorted);
		this.json = ProtoJson.write(this.toJsonObject());
		this.hash = Hashes.sha256(this.json);
	}

	public static RegionTable empty() {
		return new RegionTable();
	}

	public int pitch() {
		return this.pitch;
	}

	/** The regions, by slot. */
	public List<Entry> entries() {
		return this.entries;
	}

	public boolean isEmpty() {
		return this.entries.isEmpty();
	}

	public int size() {
		return this.entries.size();
	}

	public @Nullable Entry byId(String id) {
		for (Entry entry : this.entries) {
			if (entry.id().equals(id)) {
				return entry;
			}
		}
		return null;
	}

	public @Nullable Entry bySlot(int slot) {
		for (Entry entry : this.entries) {
			if (entry.slot() == slot) {
				return entry;
			}
		}
		return null;
	}

	/** Block X of slot {@code slot}'s origin. */
	public int originX(int slot) {
		return this.pitch * slot;
	}

	/** Block Z of slot {@code slot}'s origin: every slot sits on Z 0 (one row along +X, D8). */
	public int originZ(int slot) {
		return 0;
	}

	/** The lowest slot no region holds. */
	public int nextFreeSlot() {
		for (int slot = 0; slot <= MAX_SLOT; slot++) {
			if (this.bySlot(slot) == null) {
				return slot;
			}
		}
		throw new IllegalStateException("the region table is full");
	}

	/**
	 * This table with {@code entry} in place of the entry of the same id (or added).
	 *
	 * @throws IllegalArgumentException when another id holds the entry's slot
	 */
	public RegionTable with(Entry entry) {
		Objects.requireNonNull(entry, "entry");
		List<Entry> out = new ArrayList<>();
		for (Entry existing : this.entries) {
			if (existing.id().equals(entry.id())) {
				continue;
			}
			if (existing.slot() == entry.slot()) {
				throw new IllegalArgumentException("slot held by another region");
			}
			out.add(existing);
		}
		out.add(entry);
		return new RegionTable(this.pitch, out);
	}

	/** This table without the region {@code id} (unchanged when it holds none). */
	public RegionTable without(String id) {
		List<Entry> out = new ArrayList<>();
		for (Entry existing : this.entries) {
			if (!existing.id().equals(id)) {
				out.add(existing);
			}
		}
		return new RegionTable(this.pitch, out);
	}

	/** Canonical JSON text (the contents of {@code regions.json}). */
	public String toJson() {
		return this.json;
	}

	/** SHA-256 of {@link #toJson()}. */
	public String hash() {
		return this.hash;
	}

	private JsonObject toJsonObject() {
		JsonObject root = new JsonObject();
		root.addProperty("version", VERSION);
		root.addProperty("pitch", this.pitch);
		JsonArray regions = new JsonArray();
		for (Entry entry : this.entries) {
			JsonObject item = new JsonObject();
			item.addProperty("id", entry.id());
			item.addProperty("game", entry.game());
			item.addProperty("slot", entry.slot());
			regions.add(item);
		}
		root.add("regions", regions);
		return root;
	}

	/** Reads a stored table strictly (protocol/world/regions.schema.json). */
	public static RegionTable fromJson(String text) throws DataException {
		DataReader root;
		try {
			root = DataReader.of(ProtoJson.parse(text), "");
		} catch (ProtocolException e) {
			throw new DataException("", "invalid JSON (" + e.code() + ")");
		}
		root.allowOnly(ROOT_FIELDS);
		if (root.integer("version") != VERSION) {
			throw new DataException(root.path("version"), "unsupported version");
		}
		int pitch = root.integer("pitch", MIN_PITCH, MAX_PITCH);
		List<Entry> entries = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		Set<Integer> slots = new HashSet<>();
		List<DataReader> items = DataReader.objects(root.array("regions"), "/regions");
		if (items.size() > MAX_REGIONS) {
			throw new DataException(root.path("regions"), "too many regions");
		}
		for (DataReader item : items) {
			item.allowOnly(ENTRY_FIELDS);
			String id = item.string("id", Games.REGION_ID);
			String game = item.string("game", Games.GAME_ID);
			if (!Games.known(game)) {
				throw new DataException(item.path("game"), "unknown game");
			}
			int slot = item.integer("slot", 0, MAX_SLOT);
			if (!ids.add(id)) {
				throw new DataException(item.path("id"), "duplicate region id");
			}
			if (!slots.add(slot)) {
				throw new DataException(item.path("slot"), "two regions in one slot");
			}
			entries.add(new Entry(id, game, slot));
		}
		return new RegionTable(pitch, entries);
	}

	/** The table's file inside a world's {@code kantobridge/} folder. */
	public static Path file(Path kantobridgeDir) {
		return kantobridgeDir.resolve(FILE);
	}
}
