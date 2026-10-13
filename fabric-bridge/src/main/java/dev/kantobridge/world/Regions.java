package dev.kantobridge.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * The regions of one world (docs/specs/MULTI_GAME_PLAN.md §4.6, MG3): the {@link RegionTable} and a
 * {@link RegionRecord} per entry, with "which region am I in". Pure data; the server's {@code Regions.of(server)} and
 * the client's {@code ClientRegions} wrap it with their documents. A legacy world is one implicit region
 * ({@link #legacy(RegionRecord)}; §4.14).
 */
public final class Regions {
	private final RegionTable table;
	private final Map<String, RegionRecord> records;
	private final List<String> ids;

	/**
	 * @param table   the region table
	 * @param records a record per table entry, by id (an entry without a record, or a record the table lacks or that
	 *                disagrees with it on game or slot, is refused)
	 */
	public Regions(RegionTable table, Map<String, RegionRecord> records) {
		this.table = Objects.requireNonNull(table, "table");
		Map<String, RegionRecord> byId = new LinkedHashMap<>();
		List<String> ids = new ArrayList<>();
		for (RegionTable.Entry entry : table.entries()) {
			RegionRecord record = records.get(entry.id());
			if (record == null) {
				throw new IllegalArgumentException("a region of the table has no record");
			}
			if (!record.id().equals(entry.id()) || !record.game().equals(entry.game()) || record.slot() != entry.slot()) {
				throw new IllegalArgumentException("a record disagrees with the table");
			}
			byId.put(entry.id(), record);
			ids.add(entry.id());
		}
		if (records.size() != byId.size()) {
			throw new IllegalArgumentException("a record names a region the table lacks");
		}
		this.records = Collections.unmodifiableMap(byId);
		this.ids = List.copyOf(ids);
	}

	/** A legacy world: the one implicit record ({@link RegionRecord#legacy}) in a table of its own. */
	public static Regions legacy(RegionRecord record) {
		RegionTable table = new RegionTable(List.of(new RegionTable.Entry(record.id(), record.game(), record.slot())));
		return new Regions(table, Map.of(record.id(), record));
	}

	/** Parses a table's text and the records' texts (by id), strictly. */
	public static Regions parse(String tableJson, Map<String, String> recordJsons) throws DataException {
		RegionTable table = RegionTable.fromJson(tableJson);
		Map<String, RegionRecord> records = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : recordJsons.entrySet()) {
			records.put(entry.getKey(), RegionRecord.fromJson(entry.getValue()));
		}
		try {
			return new Regions(table, records);
		} catch (IllegalArgumentException e) {
			throw new DataException("/regions", e.getMessage());
		}
	}

	public RegionTable table() {
		return this.table;
	}

	/** The records, by id, in table (slot) order. */
	public Map<String, RegionRecord> records() {
		return this.records;
	}

	/** The region ids in table (slot) order. */
	public List<String> ids() {
		return this.ids;
	}

	public int size() {
		return this.records.size();
	}

	public @Nullable RegionRecord byId(String id) {
		return this.records.get(id);
	}

	/**
	 * The region block column (x, z) lies in: the one whose bounds hold it, else the one whose areas do; null when
	 * none (between regions).
	 */
	public @Nullable RegionRecord at(int x, int z) {
		for (RegionRecord record : this.records.values()) {
			if (record.bounds().contains(x, z)) {
				return record;
			}
		}
		for (RegionRecord record : this.records.values()) {
			if (record.areas().contains(x, z)) {
				return record;
			}
		}
		return null;
	}

	/** The legacy region: the one at slot 0, else the only one, else null. */
	public @Nullable RegionRecord legacy() {
		RegionTable.Entry slot0 = this.table.bySlot(0);
		if (slot0 != null) {
			return this.records.get(slot0.id());
		}
		return this.records.size() == 1 ? this.records.values().iterator().next() : null;
	}

	/** True for a world of one region whose documents sit at the root of {@code kantobridge/} (no regions.json). */
	public boolean isLegacyWorld() {
		RegionRecord only = this.records.size() == 1 ? this.records.values().iterator().next() : null;
		return only != null && only.isLegacy();
	}
}
