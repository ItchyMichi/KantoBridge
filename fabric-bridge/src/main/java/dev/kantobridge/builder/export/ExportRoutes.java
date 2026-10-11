package dev.kantobridge.builder.export;

import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The zones the hidden game runs (Wild Routes, WR2; protocol/export/routes.schema.json): each zone's route map and the
 * settings the builder generates it with (docs/specs/WILD_ROUTES_PLAN.md §4.1, §4.3). The town's clearing is already
 * in its map's blocks; the builder only places the zone itself.
 */
public record ExportRoutes(List<Zone> zones) {
	public static final String ID = "all";
	private static final Pattern ZONE_ID = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
	private static final Set<String> KINDS = Set.of("free_roam");
	private static final Set<String> THEMES = Set.of("forest");

	/**
	 * One zone.
	 *
	 * @param townMap        the map whose clearing leads into the zone
	 * @param routeMap       the zone's route map in the hidden game ({@code KB_ROUTE_<ID>}), 1×1 Gen 1 blocks
	 * @param areasMin       fewest areas on the main line
	 * @param areasMax       most areas on the main line
	 * @param sideAreaChance the chance of a side area
	 * @param trainersMin    fewest trainers per area
	 * @param trainersMax    most trainers per area
	 * @param classes        the Gen 1 trainer classes the zone's trainers use (the hidden game registered a copy of each)
	 * @param levelStep      levels added per area
	 * @param levelCap       the most levels added
	 * @param dungeonChance  the chance of a dungeon
	 */
	public record Zone(String id, String name, String kind, String theme, String townMap, String routeMap, int areasMin, int areasMax,
			double sideAreaChance, int trainersMin, int trainersMax, List<String> classes, int levelStep, int levelCap,
			double dungeonChance) {
		public Zone {
			classes = List.copyOf(classes);
		}
	}

	public ExportRoutes {
		zones = List.copyOf(zones);
	}

	public static ExportRoutes empty() {
		return new ExportRoutes(List.of());
	}

	static ExportRoutes parse(DataReader r) throws DataException {
		if (r.integer("version") != 1) {
			throw new DataException(r.path("version"), "unsupported version");
		}
		List<Zone> zones = new ArrayList<>();
		for (DataReader z : DataReader.objects(r.array("routes"), r.path("routes"))) {
			String kind = z.string("kind");
			if (!KINDS.contains(kind)) {
				throw new DataException(z.path("kind"), "unknown kind");
			}
			String theme = z.string("theme");
			if (!THEMES.contains(theme)) {
				throw new DataException(z.path("theme"), "unknown theme");
			}
			DataReader town = z.object("town");
			DataReader routeMap = z.object("routeMap");
			DataReader areas = z.object("areas");
			int[] main = DataReader.intArray(areas.array("main"), areas.path("main"), 1, 8);
			DataReader trainers = z.object("trainers");
			int[] perArea = DataReader.intArray(trainers.array("perArea"), trainers.path("perArea"), 0, 6);
			if (main.length != 2 || main[0] > main[1] || perArea.length != 2 || perArea[0] > perArea[1]) {
				throw new DataException(z.path(), "bad ranges");
			}
			List<String> classes = new ArrayList<>();
			for (var c : trainers.array("classes")) {
				classes.add(DataReader.asString(c, trainers.path("classes"), DataReader.SYMBOL));
			}
			DataReader levels = z.object("levels");
			DataReader dungeon = z.object("dungeon");
			zones.add(new Zone(z.string("id", ZONE_ID), z.string("name"), kind, theme, town.string("map", DataReader.MAP_ID),
					routeMap.string("id", DataReader.MAP_ID), main[0], main[1], chance(areas, "side"), perArea[0], perArea[1], classes,
					levels.integer("perArea", 0, 5), levels.integer("maxRise", 0, 20), chance(dungeon, "chance")));
		}
		return new ExportRoutes(zones);
	}

	private static double chance(DataReader r, String name) throws DataException {
		var e = r.element(name);
		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			throw new DataException(r.path(name), "not a number");
		}
		double v = e.getAsDouble();
		if (!(v >= 0 && v <= 1)) {
			throw new DataException(r.path(name), "out of range");
		}
		return v;
	}
}
