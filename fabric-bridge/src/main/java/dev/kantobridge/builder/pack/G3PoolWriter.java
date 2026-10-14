package dev.kantobridge.builder.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.export.G3Data;
import dev.kantobridge.builder.plan.HabitatPlanner;
import dev.kantobridge.builder.plan.Warnings;
import dev.kantobridge.pokemon.GenOneIds;
import dev.kantobridge.pokemon.NationalDexIds;
import dev.kantobridge.proto.ProtoJson;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Cobblemon habitat pools from a Gen 3 export's land tables (MG7, docs/specs/GEN3_BRIDGE_PLAN.md §6 "Habitats"), as
 * {@link HabitatPoolWriter} writes Gen 1's: one pool per wild map ({@link HabitatPlanner#wildMapsGen3}), whatever the
 * build's filter.
 *
 * <ul>
 * <li>One entry per land slot, in slot order; {@code species} Cobblemon's id by national dex number (up to 386,
 * {@link NationalDexIds}); a slot past 386 is left out ({@code g3_species_unknown}).</li>
 * <li>{@code weight} the slot's chance in percent as the engine's roll uses it (the export's {@code landWeights}); an
 * export without them weighs every slot alike ({@code g3_land_weights_missing}).</li>
 * <li>{@code levelRange} the slot's levels, {@code "min-max"}, or the one level.</li>
 * <li>{@code bucket} "common", position type "grounded", no phases.</li>
 * </ul>
 * ROM-derived: the pools live only in the world's own datapack under {@code local/}. Pure Java.
 */
public final class G3PoolWriter {
	private G3PoolWriter() {
	}

	/** Every pool, canonical JSON text by pool id ({@code kantobridge:<prefix><map in lower case>}). */
	public static Map<String, String> pools(G3Data.Encounters encounters, Set<String> wildMaps, String prefix, Warnings warnings) {
		Map<String, String> out = new TreeMap<>();
		if (encounters.landWeights() == null && !wildMaps.isEmpty()) {
			warnings.warn("g3_land_weights_missing");
		}
		for (String map : wildMaps) {
			G3Data.MapTables tables = encounters.maps().get(map);
			if (tables == null || tables.land() == null) {
				continue;
			}
			out.put(HabitatPlanner.poolId(prefix, map), ProtoJson.write(pool(map, tables.land(), encounters, warnings)));
		}
		return out;
	}

	/** One map's pool. */
	public static JsonObject pool(String map, G3Data.Table land, G3Data.Encounters encounters, Warnings warnings) {
		JsonArray spawns = new JsonArray();
		for (int slot = 0; slot < land.slots().size(); slot++) {
			G3Data.Slot entry = land.slots().get(slot);
			if (!NationalDexIds.isDex(entry.dex())) {
				warnings.warn("g3_species_unknown", Warnings.symbols("map", map));
				continue;
			}
			Integer weight = encounters.landWeight(slot);
			JsonObject spawn = new JsonObject();
			spawn.addProperty("species", GenOneIds.NAMESPACE + ":" + NationalDexIds.speciesId(entry.dex()));
			spawn.addProperty("spawnablePositionType", HabitatPoolWriter.POSITION);
			spawn.addProperty("bucket", HabitatPoolWriter.BUCKET);
			spawn.addProperty("weight", weight == null ? 1 : weight);
			spawn.addProperty("levelRange", entry.min() == entry.max() ? Integer.toString(entry.min()) : entry.min() + "-" + entry.max());
			spawns.add(spawn);
		}
		JsonObject pool = new JsonObject();
		// The habitat editor shows this name; a map symbol, not game text.
		pool.addProperty("name", "Kanto Bridge " + map);
		pool.add("spawns", spawns);
		return pool;
	}
}
