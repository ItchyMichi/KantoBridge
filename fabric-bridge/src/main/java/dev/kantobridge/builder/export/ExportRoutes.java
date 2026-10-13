package dev.kantobridge.builder.export;

import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * The zones the hidden game runs (Wild Routes, WR2; protocol/export/routes.schema.json): each zone's route map and the
 * settings the builder generates it with (docs/specs/WILD_ROUTES_PLAN.md §4.1, §4.3). The town's clearing is already
 * in its map's blocks; the builder only places the zone itself.
 *
 * <p>Version 2 (docs/specs/MULTI_GAME_PLAN.md §4.8, MG5): a {@code connecting} route has two ends in two regions; the
 * export lists one item per end the game registered, with both ends' regions and games ({@code ends}) and which end
 * this item is ({@code end}), so the builder plans this region's half and knows the far one.
 */
public record ExportRoutes(List<Zone> zones) {
	public static final String ID = "all";
	public static final String FREE_ROAM = "free_roam";
	public static final String CONNECTING = "connecting";
	/** A connecting route's near end and far end, as the route list names them. */
	public static final String END_FROM = "from";
	public static final String END_TO = "to";
	private static final Pattern ZONE_ID = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
	private static final Set<String> KINDS = Set.of(FREE_ROAM, CONNECTING);
	private static final Set<String> THEMES = Set.of("forest");
	private static final Set<String> ENDS = Set.of(END_FROM, END_TO);

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
	 * @param region         MG5, a connecting route: the region this half is built in (the end's region), else null
	 * @param toRegion       MG5, a connecting route: the region of the other half, else null
	 * @param end            MG5, a connecting route: {@code from} or {@code to}, the end this item's half is; else null
	 */
	public record Zone(String id, String name, String kind, String theme, String townMap, String routeMap, int areasMin, int areasMax,
			double sideAreaChance, int trainersMin, int trainersMax, List<String> classes, int levelStep, int levelCap,
			double dungeonChance, @Nullable String region, @Nullable String toRegion, @Nullable String end) {
		public Zone {
			classes = List.copyOf(classes);
			if (CONNECTING.equals(kind) != (end != null) || (end != null && !ENDS.contains(end)) || (end != null) != (toRegion != null)) {
				throw new IllegalArgumentException("a connecting zone names its end and the far region; a free-roam zone neither");
			}
		}

		/** A free-roam zone (every zone before MG5). */
		public Zone(String id, String name, String kind, String theme, String townMap, String routeMap, int areasMin, int areasMax,
				double sideAreaChance, int trainersMin, int trainersMax, List<String> classes, int levelStep, int levelCap,
				double dungeonChance) {
			this(id, name, kind, theme, townMap, routeMap, areasMin, areasMax, sideAreaChance, trainersMin, trainersMax, classes, levelStep,
					levelCap, dungeonChance, null, null, null);
		}

		/** True for a connecting route's half (MG5). */
		public boolean connecting() {
			return CONNECTING.equals(this.kind);
		}
	}

	public ExportRoutes {
		zones = List.copyOf(zones);
	}

	public static ExportRoutes empty() {
		return new ExportRoutes(List.of());
	}

	static ExportRoutes parse(DataReader r) throws DataException {
		int version = r.integer("version");
		if (version != 1 && version != 2) {
			throw new DataException(r.path("version"), "unsupported version");
		}
		List<Zone> zones = new ArrayList<>();
		for (DataReader z : DataReader.objects(r.array("routes"), r.path("routes"))) {
			String kind = z.string("kind");
			if (!KINDS.contains(kind)) {
				throw new DataException(z.path("kind"), "unknown kind");
			}
			// MG5: a connecting route names both ends and which one this half is; a free-roam zone neither
			String region = null;
			String toRegion = null;
			String end = null;
			if (CONNECTING.equals(kind)) {
				if (!z.has("ends") || !z.has("end")) {
					throw new DataException(z.path(), "a connecting route names its ends");
				}
				end = z.string("end");
				if (!ENDS.contains(end)) {
					throw new DataException(z.path("end"), "unknown end");
				}
				DataReader ends = z.object("ends");
				DataReader near = ends.object(end);
				DataReader far = ends.object(END_FROM.equals(end) ? END_TO : END_FROM);
				region = near.string("region", DataReader.REGION_ID);
				toRegion = far.string("region", DataReader.REGION_ID);
			} else if (z.has("ends") || z.has("end")) {
				throw new DataException(z.path(), "only a connecting route has ends");
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
					levels.integer("perArea", 0, 5), levels.integer("maxRise", 0, 20), chance(dungeon, "chance"), region, toRegion, end));
		}
		return new ExportRoutes(zones);
	}

	/** A chance the export carries in whole percent (canonical JSON carries no fractions), as the 0..1 the generator rolls. */
	private static double chance(DataReader r, String name) throws DataException {
		return r.integer(name, 0, 100) / 100.0;
	}
}
