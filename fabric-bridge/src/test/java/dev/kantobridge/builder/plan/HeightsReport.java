package dev.kantobridge.builder.plan;

import com.google.gson.JsonObject;
import dev.kantobridge.world.MapLayout;
import dev.kantobridge.world.MapPlacement;
import java.util.Map;
import java.util.TreeMap;

/**
 * The heights a plan built (docs/specs/HEIGHTS_ALL_GAMES_PLAN.md W4, W5, W6), for the real dry runs' reports: counts only.
 * Rooms on the interior layers by their ceiling, the 3D area's maps by their room over the floor, the deepest layer and
 * floors, the maps raised, and the visible-layers check's findings (D9) with the shape planner's units.
 */
final class HeightsReport {
	private HeightsReport() {
	}

	static JsonObject of(WorldPlan plan) {
		Map<Integer, Integer> rooms = new TreeMap<>();
		Map<Integer, Integer> area = new TreeMap<>();
		Map<Integer, Integer> raisedLevels = new TreeMap<>();
		int deepestLayer = 0;
		int deepestRoom = Integer.MAX_VALUE;
		int deepestArea = Integer.MAX_VALUE;
		int openSky = 0;
		Map<String, Integer> tallest = new TreeMap<>();
		for (PlacedMap map : plan.maps()) {
			MapPlacement p = map.placement();
			if (p.kind() != MapPlacement.Kind.INTERIOR) {
				continue;
			}
			if (WorldPlan.openSky(p)) {
				openSky++;
				continue;
			}
			MapLayout layout = map.layout();
			boolean inArea = WorldPlan.BIOME_CAVE_3D.equals(p.biome()) || layout != null && layout.pieces().size() > 1;
			int top = 0;
			for (int cy = 0; layout != null && cy < layout.h(); cy++) {
				for (int cx = 0; cx < layout.w(); cx++) {
					top = Math.max(top, layout.level(cx, cy));
				}
			}
			if (top > 0) {
				raisedLevels.merge(top, 1, Integer::sum);
				tallest.put(p.id(), top);
			}
			if (inArea) {
				area.merge(layout == null ? -1 : layout.headroom(), 1, Integer::sum);
				deepestArea = Math.min(deepestArea, p.y());
			} else {
				rooms.merge(WorldPlan.maxCeiling(layout), 1, Integer::sum);
				deepestLayer = Math.max(deepestLayer, p.layer());
				deepestRoom = Math.min(deepestRoom, p.y());
			}
		}
		JsonObject out = new JsonObject();
		out.add("rooms_by_ceiling", counts(rooms));
		out.add("area_maps_by_room", counts(area));
		out.add("maps_by_highest_level", counts(raisedLevels));
		out.addProperty("deepest_layer", deepestLayer);
		out.addProperty("deepest_room_feet", deepestRoom == Integer.MAX_VALUE ? 0 : deepestRoom);
		out.addProperty("deepest_area_feet", deepestArea == Integer.MAX_VALUE ? 0 : deepestArea);
		out.addProperty("open_sky_interiors", openSky);
		Integer capped = plan.warnings().notes().get("room_ceiling_capped");
		out.addProperty("room_ceiling_capped", capped == null ? 0 : capped);
		// each outcome of the raised ground and the maps it names (ids only)
		JsonObject levels = new JsonObject();
		for (String code : new java.util.TreeSet<>(plan.warnings().notes().keySet())) {
			if (!code.startsWith("cave_levels_")) {
				continue;
			}
			com.google.gson.JsonArray maps = new com.google.gson.JsonArray();
			for (Map<String, String> detail : plan.warnings().details(code)) {
				if (detail.containsKey("map")) {
					maps.add(detail.get("map"));
				}
			}
			levels.add(code.substring("cave_levels_".length()), maps);
		}
		out.add("cave_levels", levels);
		// the maps raised highest (ids and their highest level)
		JsonObject high = new JsonObject();
		tallest.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(12).forEach(e -> high.addProperty(e.getKey(), e.getValue()));
		out.add("raised_highest", high);
		// the visible-layers check (D9): outdoor maps whose faces are short, the columns and rows they lack, every such map
		if (plan.shapes() != null) {
			int maps = 0;
			int columns = 0;
			int rows = 0;
			JsonObject shortMaps = new JsonObject();
			Map<String, Integer> kinds = new TreeMap<>();
			for (var e : plan.shapes().visibleRowsMissing().entrySet()) {
				maps++;
				columns += e.getValue().columns();
				rows += e.getValue().rows();
				e.getValue().kinds().forEach((k, v) -> kinds.merge(k, v, Integer::sum));
			}
			plan.shapes().visibleRowsMissing().entrySet().stream().sorted((x, y) -> y.getValue().columns() - x.getValue().columns())
					.forEach(e -> shortMaps.addProperty(e.getKey(), e.getValue().columns() + " columns, " + e.getValue().rows() + " rows, worst "
							+ e.getValue().worst() + ", " + e.getValue().kinds() + ", first at " + e.getValue().first()));
			JsonObject visible = new JsonObject();
			visible.addProperty("maps_short", maps);
			visible.addProperty("columns_short", columns);
			visible.addProperty("rows_short", rows);
			JsonObject byKind = new JsonObject();
			kinds.forEach(byKind::addProperty);
			visible.add("columns_short_by_kind", byKind);
			visible.add("short_maps", shortMaps);
			out.add("visible_rows", visible);
			// the shape planner's units by why each is one (a fitted model, a hint, a probe...): what D9 must leave in use
			JsonObject units = new JsonObject();
			new TreeMap<>(plan.shapes().unitTally()).forEach(units::addProperty);
			out.add("unit_tally", units);
		}
		return out;
	}

	private static JsonObject counts(Map<Integer, Integer> counts) {
		JsonObject out = new JsonObject();
		counts.forEach((k, v) -> out.addProperty(Integer.toString(k), v));
		return out;
	}
}
