package dev.kantobridge.builder.zone;

import dev.kantobridge.builder.export.ExportRoutes;
import dev.kantobridge.routes.gen.ForestTheme;
import dev.kantobridge.routes.gen.RouteGenerator;
import dev.kantobridge.routes.gen.RouteLayout;
import dev.kantobridge.routes.gen.RouteSeeds;
import dev.kantobridge.routes.gen.RouteSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Plans every zone the export lists (docs/specs/WILD_ROUTES_PLAN.md §4.3-4.4): each zone's layout from the world's seed
 * and its id (the same world always gets the same zone, R3), in its own slot (in id order, so adding a zone later never
 * moves the others), on the land the {@link TerrainSampler} describes.
 */
public final class ZonePlanner {
	/** Item balls per area before the dead-end bonus (WR1's Gen 1 numbers). */
	static final int ITEMS_MIN = 1;
	static final int ITEMS_MAX = 2;

	private ZonePlanner() {
	}

	/**
	 * @param routes    the export's zones
	 * @param worldSeed the world's seed
	 * @param regions   where the slots are
	 * @param terrain   the land's height
	 * @param paletteOf a town map's palette (the route map's placement carries it)
	 */
	public static List<ZonePlan> plan(ExportRoutes routes, long worldSeed, ZoneRegions regions, TerrainSampler terrain,
			Function<String, String> paletteOf) {
		return plan(routes, worldSeed, regions, terrain, paletteOf, ZoneContent.EMPTY);
	}

	/** {@link #plan} with trainers and balls made from {@code content} (WR5). */
	public static List<ZonePlan> plan(ExportRoutes routes, long worldSeed, ZoneRegions regions, TerrainSampler terrain,
			Function<String, String> paletteOf, ZoneContent content) {
		return plan(routes, worldSeed, regions, terrain, paletteOf, content, "");
	}

	/** {@link #plan} for the zones of a region: their pool ids start with {@code poolPrefix} (MULTI_GAME_PLAN §4.6, MG3). */
	public static List<ZonePlan> plan(ExportRoutes routes, long worldSeed, ZoneRegions regions, TerrainSampler terrain,
			Function<String, String> paletteOf, ZoneContent content, String poolPrefix) {
		ZoneObjects.ItemTiers tiers = ZoneObjects.ItemTiers.bundled();
		List<ExportRoutes.Zone> zones = new ArrayList<>(routes.zones());
		// MG5: a connecting route's two ends in one game (a test world) are two zones; the far end's gets its own id, after
		// the near end's in id order, so the slots stay stable
		zones.sort(Comparator.comparing(ExportRoutes.Zone::id).thenComparing(z -> z.end() == null ? "" : z.end()));
		List<ZonePlan> out = new ArrayList<>();
		for (int i = 0; i < zones.size(); i++) {
			ExportRoutes.Zone zone = zones.get(i);
			String zoneId = zoneId(zones, zone);
			// the layout's seed follows the zone's id, so the two halves of one route are different mazes
			RouteLayout layout = RouteGenerator.generate(specOf(zone), RouteSeeds.forRoute(worldSeed, zoneId));
			int[] slot = regions.slotOrigin(i);
			ZoneObjects objects = ZoneObjects.make(zone, layout, content, tiers, worldSeed);
			ZonePlan.Crossing crossing = zone.connecting() ? new ZonePlan.Crossing(zone.id(), zone.toRegion(), zone.end()) : null;
			out.add(new ZonePlan(zoneId, zone.routeMap(), paletteOf.apply(zone.townMap()), layout, slot[0], slot[1], terrain, objects,
					poolPrefix, crossing));
		}
		return out;
	}

	/**
	 * The zone's id: the route's, unless the export lists both ends of a connecting route (one game holding both halves, a
	 * test world): then the {@code to} end's zone is {@code <id>_to}, like its route map.
	 */
	static String zoneId(List<ExportRoutes.Zone> zones, ExportRoutes.Zone zone) {
		if (ExportRoutes.END_TO.equals(zone.end())) {
			for (ExportRoutes.Zone other : zones) {
				if (other != zone && other.id().equals(zone.id())) {
					return zone.id() + "_to";
				}
			}
		}
		return zone.id();
	}

	/** The generator's spec for an export zone: a free-roam zone, or (MG5) one half of a connecting route. */
	static RouteSpec specOf(ExportRoutes.Zone zone) {
		RouteSpec.Kind kind = zone.connecting() ? RouteSpec.Kind.CONNECTING : RouteSpec.Kind.FREE_ROAM;
		return new RouteSpec(kind, zone.areasMin(), zone.areasMax(), zone.sideAreaChance(), zone.dungeonChance(),
				zone.trainersMin(), zone.trainersMax(), ITEMS_MIN, ITEMS_MAX, zone.levelStep(), zone.levelCap(), RouteSpec.DEFAULT_BOUND,
				ForestTheme.INSTANCE);
	}
}
