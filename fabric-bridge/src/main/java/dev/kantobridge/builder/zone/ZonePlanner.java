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
		List<ExportRoutes.Zone> zones = new ArrayList<>(routes.zones());
		zones.sort(Comparator.comparing(ExportRoutes.Zone::id));
		List<ZonePlan> out = new ArrayList<>();
		for (int i = 0; i < zones.size(); i++) {
			ExportRoutes.Zone zone = zones.get(i);
			RouteLayout layout = RouteGenerator.generate(specOf(zone), RouteSeeds.forRoute(worldSeed, zone.id()));
			int[] slot = regions.slotOrigin(i);
			out.add(new ZonePlan(zone.id(), zone.routeMap(), paletteOf.apply(zone.townMap()), layout, slot[0], slot[1], terrain));
		}
		return out;
	}

	/** The generator's spec for an export zone (only free-roam forest zones exist so far). */
	static RouteSpec specOf(ExportRoutes.Zone zone) {
		return new RouteSpec(RouteSpec.Kind.FREE_ROAM, zone.areasMin(), zone.areasMax(), zone.sideAreaChance(), zone.dungeonChance(),
				zone.trainersMin(), zone.trainersMax(), ITEMS_MIN, ITEMS_MAX, zone.levelStep(), zone.levelCap(), RouteSpec.DEFAULT_BOUND,
				ForestTheme.INSTANCE);
	}
}
