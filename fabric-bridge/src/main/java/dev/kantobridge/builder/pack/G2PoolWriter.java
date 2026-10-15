package dev.kantobridge.builder.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.export.G2Data;
import dev.kantobridge.builder.plan.HabitatPlanner;
import dev.kantobridge.builder.plan.Warnings;
import dev.kantobridge.pokemon.GenOneIds;
import dev.kantobridge.pokemon.NationalDexIds;
import dev.kantobridge.proto.ProtoJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Cobblemon habitat pools from a Gen 2 export's grass tables (MG9, docs/specs/GEN2_BRIDGE_PLAN.md §7 "Habitats"), as
 * {@link G3PoolWriter} writes Gen 3's: one pool per wild map, whatever the build's filter.
 *
 * <ul>
 * <li>The three times of day merged into one pool (J6: Minecraft stays at noon, the hidden game keeps the clock): one
 * entry per species and level, in the order first seen (morning, day, night; slots in order).</li>
 * <li>{@code weight} the sum of its slots' chances over the three tables, in percent as the engine's roll uses them (the
 * export's {@code grassChances}); an export without them weighs every slot alike ({@code g2_grass_chances_missing}).</li>
 * <li>{@code species} Cobblemon's id by national dex number ({@link NationalDexIds}); {@code levelRange} the one level;
 * {@code bucket} "common", position type "grounded", no phases.</li>
 * </ul>
 * ROM-derived: the pools live only in the world's own datapack under {@code local/}. Pure Java.
 */
public final class G2PoolWriter {
	private G2PoolWriter() {
	}

	/** Every pool, canonical JSON text by pool id ({@code kantobridge:<prefix><map in lower case>}). */
	public static Map<String, String> pools(G2Data.Encounters encounters, Set<String> wildMaps, String prefix, Warnings warnings) {
		Map<String, String> out = new TreeMap<>();
		if (encounters.grassChances() == null && !wildMaps.isEmpty()) {
			warnings.warn("g2_grass_chances_missing");
		}
		for (String map : wildMaps) {
			G2Data.Grass grass = encounters.grass().get(map);
			if (grass == null) {
				continue;
			}
			out.put(HabitatPlanner.poolId(prefix, map), ProtoJson.write(pool(map, grass, encounters, warnings)));
		}
		return out;
	}

	/** One map's pool. */
	public static JsonObject pool(String map, G2Data.Grass grass, G2Data.Encounters encounters, Warnings warnings) {
		Map<String, int[]> merged = new LinkedHashMap<>();
		for (String daytime : G2Data.DAYTIMES) {
			if (grass.rates().getOrDefault(daytime, 0) <= 0) {
				continue;
			}
			List<G2Data.Slot> slots = grass.slots().getOrDefault(daytime, List.of());
			for (int slot = 0; slot < slots.size(); slot++) {
				G2Data.Slot entry = slots.get(slot);
				if (!NationalDexIds.isDex(entry.dex())) {
					warnings.warn("g2_species_unknown", Warnings.symbols("map", map));
					continue;
				}
				Integer chance = encounters.grassChance(slot);
				int[] row = merged.computeIfAbsent(entry.dex() + ":" + entry.level(), k -> new int[] {entry.dex(), entry.level(), 0});
				row[2] += chance == null ? 1 : chance;
			}
		}
		JsonArray spawns = new JsonArray();
		for (int[] row : merged.values()) {
			JsonObject spawn = new JsonObject();
			spawn.addProperty("species", GenOneIds.NAMESPACE + ":" + NationalDexIds.speciesId(row[0]));
			spawn.addProperty("spawnablePositionType", HabitatPoolWriter.POSITION);
			spawn.addProperty("bucket", HabitatPoolWriter.BUCKET);
			spawn.addProperty("weight", Math.max(1, row[2]));
			spawn.addProperty("levelRange", Integer.toString(row[1]));
			spawns.add(spawn);
		}
		JsonObject pool = new JsonObject();
		// The habitat editor shows this name; a map symbol, not game text.
		pool.addProperty("name", "Kanto Bridge " + map);
		pool.add("spawns", spawns);
		return pool;
	}
}
