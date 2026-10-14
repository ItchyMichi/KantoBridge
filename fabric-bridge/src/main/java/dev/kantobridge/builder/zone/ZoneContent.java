package dev.kantobridge.builder.zone;

import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportEncounters;
import dev.kantobridge.builder.export.ExportMap;
import dev.kantobridge.builder.export.G3Data;
import dev.kantobridge.builder.export.G3Map;
import dev.kantobridge.world.DataReader;
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

	/** Gen 3's species constants start so (the names resource keeps the prefix). */
	static final String SPECIES_PREFIX = "SPECIES_";
	/** And its items'. */
	static final String ITEM_PREFIX = "ITEM_";
	/** A trainer class's role: what follows this in Gen 3's class constants (TRAINER_CLASS_HIKER). */
	static final String CLASS_PREFIX = "TRAINER_CLASS_";
	/** The event-object graphics a role's trainer may wear (OBJ_EVENT_GFX_HIKER). */
	static final String GFX_PREFIX = "OBJ_EVENT_GFX_";

	/**
	 * The content for zones off Gen 3 towns (MG8, docs/specs/HOENN_PASS_PLAN.md K5-K6): each town's pool is the land
	 * slots of the maps its connections reach, by the engine's slot chances, each named by its constant in the names
	 * resource (its {@code SPECIES_} off; the first name for a dex several share) at its range's top level; a class's
	 * sprite is the graphic named like its role ({@code TRAINER_CLASS_HIKER} wears {@code OBJ_EVENT_GFX_HIKER}) when a
	 * map places one, else the one most trainers wear; the ball's sprite is the item balls' most used; the items are the
	 * names resource's, their {@code ITEM_} off.
	 *
	 * @param classes the zones' trainer classes
	 */
	public static ZoneContent ofGen3(ExportBundle bundle, Collection<String> towns, Collection<String> classes) throws BundleException {
		G3Data.Encounters encounters = bundle.g3encounters();
		G3Data.Names names = bundle.g3names();
		Map<Integer, String> speciesOf = new TreeMap<>();
		Set<String> items = new java.util.TreeSet<>();
		if (names != null) {
			names.species().forEach((name, dex) -> {
				String symbol = name.startsWith(SPECIES_PREFIX) ? name.substring(SPECIES_PREFIX.length()) : name;
				if (DataReader.SYMBOL.matcher(symbol).matches()) {
					speciesOf.merge(dex, symbol, (a, b) -> a.compareTo(b) <= 0 ? a : b);
				}
			});
			for (String name : names.items().keySet()) {
				String symbol = name.startsWith(ITEM_PREFIX) ? name.substring(ITEM_PREFIX.length()) : name;
				if (!symbol.equals("NONE") && DataReader.SYMBOL.matcher(symbol).matches()) {
					items.add(symbol);
				}
			}
		}
		Map<String, List<Slot>> pools = new TreeMap<>();
		for (String town : towns) {
			List<Slot> pool = new ArrayList<>();
			if (encounters != null && bundle.has("g3map", town)) {
				// connections in the export's order, so the pool's order (and every pick from it) is stable
				for (G3Map.Connection connection : bundle.g3map(town).connections()) {
					G3Data.MapTables tables = encounters.maps().get(connection.map());
					if (tables == null || tables.land() == null) {
						continue;
					}
					List<G3Data.Slot> slots = tables.land().slots();
					for (int i = 0; i < slots.size(); i++) {
						G3Data.Slot slot = slots.get(i);
						String species = speciesOf.get(slot.dex());
						if (species != null) {
							Integer weight = encounters.landWeight(i);
							pool.add(new Slot(species, slot.dex(), slot.max(), weight == null ? 1 : weight));
						}
					}
				}
			}
			pools.put(town, pool);
		}
		Map<String, Integer> trainerGfx = new TreeMap<>();
		Map<String, Integer> ballGfx = new TreeMap<>();
		Set<String> worn = new java.util.TreeSet<>();
		for (String id : bundle.ids("g3map")) {
			for (G3Map.MapObject object : bundle.g3map(id).objects()) {
				worn.add(object.gfx());
				if ("trainer".equals(object.kind())) {
					trainerGfx.merge(object.gfx(), 1, Integer::sum);
				} else if ("item".equals(object.kind())) {
					ballGfx.merge(object.gfx(), 1, Integer::sum);
				}
			}
		}
		String common = mostUsed(trainerGfx);
		Map<String, String> classSprites = new HashMap<>();
		for (String cls : classes) {
			String role = cls.startsWith(CLASS_PREFIX) ? cls.substring(CLASS_PREFIX.length()) : cls;
			String own = GFX_PREFIX + role;
			String sprite = worn.contains(own) ? own : common;
			if (sprite != null) {
				classSprites.put(cls, sprite);
			}
		}
		return new ZoneContent(pools, classSprites, ballGfx.isEmpty() ? null : mostUsed(ballGfx), items);
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
