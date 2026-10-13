package dev.kantobridge.builder.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.export.ExportEncounters;
import dev.kantobridge.builder.export.ExportField;
import dev.kantobridge.builder.plan.HabitatPlanner;
import dev.kantobridge.pokemon.GenOneIds;
import dev.kantobridge.proto.ProtoJson;
import java.util.Map;
import java.util.TreeMap;

/**
 * Cobblemon habitat pools from the Gen 1 grass tables (docs/PLAN.md §4.5 "Datapack writer", DESIGN §5 "one custom pool
 * per route"): {@code data/kantobridge/habitat_pools/<map>.json} for every map whose wild Pokémon come from a habitat
 * pool ({@link HabitatPlanner#wild}), whatever the build's filter, like the slot map.
 *
 * <ul>
 * <li>One entry per Gen 1 slot, in slot order, so a species in several slots keeps each slot's level.</li>
 * <li>{@code bucket} "common", {@code weight} the slot's percentage (its share of the 256-step roll), so Cobblemon's
 * weighted pick reproduces Gen 1's slot odds.</li>
 * <li>{@code levelRange} the slot's single level; a single level also stays exact under Cobblemon's player-level
 * influence.</li>
 * <li>{@code species} Cobblemon's id by national dex number; position type "grounded"; no phases, so the pool has one
 * phase and a route is the same every day.</li>
 * </ul>
 * The pools are ROM-derived and live only in the world's own datapack under {@code local/}. Pure Java.
 */
public final class HabitatPoolWriter {
	public static final String FOLDER = "data/" + HabitatPlanner.POOL_NAMESPACE + "/habitat_pools/";
	public static final String BUCKET = "common";
	public static final String POSITION = "grounded";

	private HabitatPoolWriter() {
	}

	/** Every pool of the export, canonical JSON text by pool id ({@code kantobridge:route_1}). */
	public static Map<String, String> pools(ExportEncounters encounters, ExportField field) {
		return pools(encounters, field, "");
	}

	/** {@link #pools} with the pool ids of a region ({@code kantobridge:<prefix>route_1}, MULTI_GAME_PLAN §4.6, MG3). */
	public static Map<String, String> pools(ExportEncounters encounters, ExportField field, String prefix) {
		Map<String, String> out = new TreeMap<>();
		for (String map : HabitatPlanner.wildMaps(encounters, field)) {
			out.put(HabitatPlanner.poolId(prefix, map), ProtoJson.write(pool(map, encounters.grass().get(map), encounters)));
		}
		return out;
	}

	/** One map's pool. */
	public static JsonObject pool(String map, ExportEncounters.Table table, ExportEncounters encounters) {
		JsonArray spawns = new JsonArray();
		for (int slot = 0; slot < table.slots().size(); slot++) {
			ExportEncounters.Slot entry = table.slots().get(slot);
			JsonObject spawn = new JsonObject();
			spawn.addProperty("species", GenOneIds.NAMESPACE + ":" + GenOneIds.speciesId(entry.dex()));
			spawn.addProperty("spawnablePositionType", POSITION);
			spawn.addProperty("bucket", BUCKET);
			spawn.addProperty("weight", encounters.percent(slot));
			spawn.addProperty("levelRange", Integer.toString(entry.level()));
			spawns.add(spawn);
		}
		JsonObject pool = new JsonObject();
		// The habitat editor shows this name; a map symbol, not game text.
		pool.addProperty("name", "Kanto Bridge " + map);
		pool.add("spawns", spawns);
		return pool;
	}

	/** The pool's file inside the datapack. */
	public static String path(String poolId) {
		return FOLDER + poolId.substring(poolId.indexOf(':') + 1) + ".json";
	}
}
