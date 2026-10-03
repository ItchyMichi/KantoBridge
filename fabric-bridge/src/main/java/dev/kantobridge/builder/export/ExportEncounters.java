package dev.kantobridge.builder.export;

import dev.kantobridge.pokemon.GenOneIds;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The wild encounter tables (protocol/export/encounters.schema.json): the ten slot thresholds out of 256 and each map's
 * grass table, which Gen 1 rolls on grass cells and on every floor cell of indoor maps. Only grass tables become habitat
 * pools: water, fishing and Safari encounters stay in the hidden game (docs/PLAN.md P8, P9). ROM-derived: nothing here
 * is ever logged beyond counts and map ids.
 *
 * @param buckets the cumulative slot thresholds out of 256 (10 values, never falling; Gen 1's end at 256)
 * @param grass   grass tables by map id
 */
public record ExportEncounters(List<Integer> buckets, Map<String, Table> grass) {
	public static final int SLOTS = 10;
	public static final int ROLL = 256;

	/** One slot of a table: a species by national dex number at one level. */
	public record Slot(int dex, int level) {
	}

	/** A table: the encounter rate out of 256 per step, and its slots in slot order. */
	public record Table(int rate, List<Slot> slots) {
		public Table {
			slots = List.copyOf(slots);
		}
	}

	public ExportEncounters {
		buckets = List.copyOf(buckets);
		grass = Collections.unmodifiableMap(new TreeMap<>(grass));
	}

	/**
	 * Slot {@code slot}'s share of the encounters in percent: its step of the cumulative thresholds out of 256 (Gen 1's
	 * 51, 51, 39, 25, … out of 256 are 19.9, 19.9, 15.2, 9.8, … percent). Exact in binary: {@code 25·step / 64}.
	 */
	public double percent(int slot) {
		int low = slot == 0 ? 0 : this.buckets.get(slot - 1);
		return (this.buckets.get(slot) - low) * 100.0 / ROLL;
	}

	static ExportEncounters parse(DataReader r) throws DataException {
		int[] thresholds = DataReader.intArray(r.array("buckets"), r.path("buckets"), 1, ROLL);
		if (thresholds.length != SLOTS) {
			throw new DataException(r.path("buckets"), "needs 10 thresholds");
		}
		List<Integer> buckets = new ArrayList<>(SLOTS);
		for (int i = 0; i < SLOTS; i++) {
			if (i > 0 && thresholds[i] < thresholds[i - 1]) {
				throw new DataException(r.path("buckets") + "/" + i, "thresholds must not fall");
			}
			buckets.add(thresholds[i]);
		}
		Map<String, Table> grass = new TreeMap<>();
		DataReader maps = r.object("maps");
		for (String id : maps.json().keySet()) {
			if (!DataReader.MAP_ID.matcher(id).matches()) {
				throw new DataException(maps.path(), "bad map id");
			}
			DataReader entry = maps.object(id);
			DataReader table = entry.optionalObject("grass");
			if (table == null) {
				continue;
			}
			List<Slot> slots = new ArrayList<>();
			for (DataReader slot : DataReader.objects(table.array("slots"), table.path("slots"))) {
				slots.add(new Slot(slot.integer("dex", 1, GenOneIds.MAX_DEX), slot.integer("level", 1, 100)));
			}
			if (slots.isEmpty() || slots.size() > SLOTS) {
				throw new DataException(table.path("slots"), "needs 1-10 slots");
			}
			grass.put(id, new Table(table.integer("rate", 0, 255), slots));
		}
		return new ExportEncounters(buckets, grass);
	}
}
