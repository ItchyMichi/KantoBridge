package dev.kantobridge.builder.zone;

import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportEncounters;
import dev.kantobridge.builder.export.ExportMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * What the export gives Wild Routes' trainers and balls (docs/specs/WILD_ROUTES_PLAN.md §4.3, §4.6, WR5): each town's
 * neighbour pool (the grass slots of the maps connected to it), each trainer class's overworld sprite (the one most of
 * that class's map objects use), the item ball's sprite (likewise, over every object holding an item) and the game's
 * items. ROM-derived: nothing here is logged beyond counts.
 *
 * @param pools        grass slots by town map id
 * @param classSprites overworld sprite by trainer class
 * @param ballSprite   the item ball's sprite, or null when no map object holds an item
 * @param items        the game's items that aren't key items
 */
public record ZoneContent(Map<String, List<Slot>> pools, Map<String, String> classSprites, @Nullable String ballSprite, Set<String> items) {
	/** Nothing: zones get no trainers or balls. */
	public static final ZoneContent EMPTY = new ZoneContent(Map.of(), Map.of(), null, Set.of());

	/**
	 * One grass slot of a neighbour: the hidden game's species id, its national dex number (Cobblemon's species), the
	 * level, and the slot's share of encounters (percent).
	 */
	public record Slot(String species, int dex, int level, double weight) {
	}

	public ZoneContent {
		Map<String, List<Slot>> copy = new TreeMap<>();
		pools.forEach((town, slots) -> copy.put(town, List.copyOf(slots)));
		pools = Collections.unmodifiableMap(copy);
		classSprites = Collections.unmodifiableMap(new TreeMap<>(classSprites));
		items = Set.copyOf(items);
	}

	/** The pool of {@code town}: empty when it has no neighbour with grass. */
	public List<Slot> pool(String town) {
		return this.pools.getOrDefault(town, List.of());
	}

	/** The content for zones off {@code towns}, from the export. */
	public static ZoneContent of(ExportBundle bundle, Collection<String> towns) throws BundleException {
		ExportEncounters encounters = bundle.encounters();
		Map<String, List<Slot>> pools = new TreeMap<>();
		for (String town : towns) {
			List<Slot> pool = new ArrayList<>();
			if (encounters != null && bundle.has("map", town)) {
				// connections in direction order, so the pool's order (and every pick from it) is stable
				for (ExportMap.Connection connection : bundle.map(town).connections().values()) {
					ExportEncounters.Table table = encounters.grass().get(connection.map());
					if (table == null) {
						continue;
					}
					for (int i = 0; i < table.slots().size(); i++) {
						ExportEncounters.Slot slot = table.slots().get(i);
						if (slot.species() != null) {
							pool.add(new Slot(slot.species(), slot.dex(), slot.level(), encounters.percent(i)));
						}
					}
				}
			}
			pools.put(town, pool);
		}
		Map<String, Map<String, Integer>> byClass = new TreeMap<>();
		Map<String, Integer> balls = new TreeMap<>();
		for (String id : bundle.ids("map")) {
			for (ExportMap.MapObject object : bundle.map(id).objects()) {
				if (object.trainerClass() != null) {
					byClass.computeIfAbsent(object.trainerClass(), c -> new TreeMap<>()).merge(object.sprite(), 1, Integer::sum);
				}
				if (object.item() != null) {
					balls.merge(object.sprite(), 1, Integer::sum);
				}
			}
		}
		Map<String, String> classSprites = new HashMap<>();
		byClass.forEach((cls, sprites) -> classSprites.put(cls, mostUsed(sprites)));
		return new ZoneContent(pools, classSprites, balls.isEmpty() ? null : mostUsed(balls), bundle.itemIds());
	}

	/** The key counted most often; ties go to the first in order (the maps are sorted, so the choice is stable). */
	private static String mostUsed(Map<String, Integer> counts) {
		String best = null;
		int most = 0;
		for (Map.Entry<String, Integer> e : counts.entrySet()) {
			if (e.getValue() > most) {
				best = e.getKey();
				most = e.getValue();
			}
		}
		return best;
	}
}
