package dev.kantobridge.builder.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.MapLayout;
import dev.kantobridge.world.SlotMap;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The levels pilot's levels (HEIGHTS_ALL_GAMES_PLAN.md D4, LANDSCAPE_SECTIONS_PLAN.md), pod only: a game's world planned
 * with {@code kantobridge.outdoorLevels} naming outdoor maps (-Pkb.outdoorLevels), and for each named map its level per
 * cell (blocks over the map's floor) and its ramps, with the planner's outdoor notes. Map ids, cells and numbers only.
 * -Pkb.outdoorDump="&lt;game&gt;;&lt;bundle&gt;;&lt;out dir&gt;[;all]": with "all", every outdoor map (on the surface or under
 * the open sky) is dumped, those not named with the levels the solver gives them, and placements_&lt;game&gt;.json says where
 * each stands in the world (the seams between maps, LEVELS_PIPELINE.md).
 */
@EnabledIfSystemProperty(named = "kb.outdoorDump", matches = ".+")
class OutdoorLevelsDumpTest {
	@Test
	void dump() throws Exception {
		String[] p = System.getProperty("kb.outdoorDump").split(";");
		String game = p[0];
		ExportBundle bundle = ExportBundle.open(Path.of(p[1]));
		Path out = Path.of(p[2]);
		boolean all = p.length > 3 && "all".equals(p[3]);
		Files.createDirectories(out);
		WorldPlanner.Options options = new WorldPlanner.Options(null, 0, 0, dev.kantobridge.builder.TestData.MIN_BUILD_HEIGHT, true);
		WorldPlan plan = switch (game) {
			case "crystal" -> WorldPlanner.plan(bundle, options,
					new RegionBuild("johto", 2, bundle.game(), new SlotMap.Range(0, SlotMap.familiesFor("gen2") - 1), null, "johto_"));
			case "emerald" -> WorldPlanner.plan(bundle, options,
					new RegionBuild("hoenn", 1, bundle.game(), new SlotMap.Range(0, SlotMap.familiesFor("gen3") - 1), null, "hoenn_"));
			default -> WorldPlanner.plan(bundle, options);
		};
		Set<String> named = OutdoorLevels.fromProperty();
		Map<String, dev.kantobridge.builder.export.G3Map> g3maps = "emerald".equals(game) ? G3Built.maps(bundle) : Map.of();
		if ("emerald".equals(game)) {
			// the elevations of the named maps (hex digits; '#' solid, '~' water): what the solver reads
			Map<String, dev.kantobridge.builder.export.G3Map> maps = G3Built.maps(bundle);
			for (PlacedMap map : plan.maps()) {
				dev.kantobridge.builder.export.G3Map g3 = maps.get(map.id());
				if (!named.contains(map.id()) || g3 == null) {
					continue;
				}
				StringBuilder text = new StringBuilder();
				for (int cy = 0; cy < g3.h(); cy++) {
					for (int cx = 0; cx < g3.w(); cx++) {
						MapModel.Kind kind = map.model().kind(cx, cy);
						text.append(kind == MapModel.Kind.SOLID || kind == MapModel.Kind.COUNTER ? '#'
								: kind == MapModel.Kind.WATER ? '~' : Character.forDigit(g3.elev(cx, cy), 16));
					}
					text.append('\n');
				}
				Files.writeString(out.resolve(map.id() + "_elev.txt"), text.toString(), StandardCharsets.UTF_8);
				// each cell's metatile id (and its tileset pair): what a table of drawn stairs is keyed by
				JsonObject mids = new JsonObject();
				mids.addProperty("pair", g3.pair());
				JsonArray ids = new JsonArray();
				for (int cy = 0; cy < g3.h(); cy++) {
					for (int cx = 0; cx < g3.w(); cx++) {
						ids.add(g3.inGrid(cx, cy) ? g3.mid(cx, cy) : -1);
					}
				}
				mids.add("mids", ids);
				Files.writeString(out.resolve(map.id() + "_mids.json"), ProtoJson.write(mids), StandardCharsets.UTF_8);
			}
		}
		JsonObject notes = new JsonObject();
		for (Map.Entry<String, Integer> e : plan.warnings().notes().entrySet()) {
			if (e.getKey().startsWith("outdoor_") || e.getKey().startsWith("chain_")) {
				notes.addProperty(e.getKey(), e.getValue());
			}
		}
		Files.writeString(out.resolve("notes_" + game + ".json"), ProtoJson.write(notes), StandardCharsets.UTF_8);
		// every chunk of the named maps rendered as the build renders it (a gap in the raised ground's art fails here)
		ChunkCodesView view = new ChunkCodesView(plan);
		for (PlacedMap map : plan.maps()) {
			if (!named.contains(map.id())) {
				continue;
			}
			var pl = map.placement();
			int bw = map.model().cellWidth() * dev.kantobridge.world.Scale.CELL_BLOCKS;
			int bh = map.model().cellHeight() * dev.kantobridge.world.Scale.CELL_BLOCKS;
			for (int bz = 0; bz < bh; bz += 16) {
				for (int bx = 0; bx < bw; bx += 16) {
					view.code(pl.x() + bx, pl.y(), pl.z() + bz);
				}
			}
		}
		JsonArray placements = new JsonArray();
		for (PlacedMap map : plan.maps()) {
			var pl = map.placement();
			boolean outdoor = pl.kind() == dev.kantobridge.world.MapPlacement.Kind.SURFACE || WorldPlan.openSky(pl);
			if (!OutdoorLevels.covers(named, map.id()) && !(all && outdoor)) {
				continue;
			}
			JsonObject o = new JsonObject();
			o.addProperty("map", map.id());
			int w = map.model().cellWidth();
			int h = map.model().cellHeight();
			o.addProperty("w", w);
			o.addProperty("h", h);
			// the solver's own account (as the planner ran it): its outcome, the rules it skipped and the cells at the edges they
			// leave unwalkable
			dev.kantobridge.builder.export.G3Map g3 = g3maps.get(map.id());
			dev.kantobridge.builder.export.G3Tiles g3tiles = g3 == null ? null : bundle.g3tiles(g3.pair());
			dev.kantobridge.world.Dir[] slopes = OutdoorLevels.slopes(dev.kantobridge.builder.shape.LandscapeModels.slopes(map.id(), w, h),
					g3 == null ? RaisedRegions.tableStairs(map.model(), bundle.game()) : RaisedRegions.gen3Stairs(map.model(), g3, g3tiles));
			RaisedRegions.Result result = g3 != null
					? RaisedRegions.gen3Outdoor(map.model(), g3, slopes, RaisedRegions.gen3Falls(map.model(), g3, g3tiles))
					: RaisedRegions.outdoor(map.model(), map.edges(), slopes, RaisedRegions.tableFaces(map.model(), bundle.game()));
			// a named map's levels as the planner placed them; another's as the solver gives them
			MapLayout layout = OutdoorLevels.covers(named, map.id()) ? map.layout() : result.layout();
			o.addProperty("raised", layout != null);
			JsonObject at = new JsonObject();
			at.addProperty("map", map.id());
			at.addProperty("x", pl.x());
			at.addProperty("y", pl.y());
			at.addProperty("z", pl.z());
			at.addProperty("bw", pl.blockWidth());
			at.addProperty("bd", pl.blockDepth());
			at.addProperty("w", w);
			at.addProperty("h", h);
			at.addProperty("sky", pl.kind() != dev.kantobridge.world.MapPlacement.Kind.SURFACE);
			placements.add(at);
			o.addProperty("outcome", result.outcome());
			o.addProperty("marked", slopes != null);
			o.addProperty("skipped", result.skipped());
			JsonArray breaks = new JsonArray();
			for (int c : result.breaks()) {
				JsonArray a = new JsonArray();
				a.add(c % w);
				a.add(c / w);
				breaks.add(a);
			}
			o.add("breaks", breaks);
			// each cell's kind ('.' ground, '~' water, 'L' ledge, '#' rock or counter): what a marking page's fill stops at
			StringBuilder kinds = new StringBuilder();
			for (int cy = 0; cy < h; cy++) {
				for (int cx = 0; cx < w; cx++) {
					kinds.append(switch (map.model().kind(cx, cy)) {
						case GROUND -> '.';
						case WATER -> '~';
						case LEDGE -> 'L';
						default -> '#';
					});
				}
			}
			o.addProperty("kinds", kinds.toString());
			if (g3 == null) {
				// each cell's walled sides a pin's ground stops at (1 north, 2 east, 4 south, 8 west: Crystal's one-way walls, a
				// door cut into a face), when the map has any: tools/landscape_sections/rules_check.py's pins stop there too
				PairEdges walls = RaisedRegions.doorsInFaces(map.model(), map.edges());
				if (walls != null) {
					JsonArray sides = new JsonArray();
					boolean any = false;
					for (int cy = 0; cy < h; cy++) {
						for (int cx = 0; cx < w; cx++) {
							sides.add(walls.sides(cx, cy));
							any |= walls.sides(cx, cy) != 0;
						}
					}
					if (any) {
						o.add("walls", sides);
					}
				}
				// each cell's drawing, as a table of drawn stairs keys it (Crystal's and Blue's: tools/landscape_sections/stair_tiles.py)
				JsonArray keys = new JsonArray();
				String atlas = CaveMap.atlas(map.model());
				for (int cy = 0; cy < h; cy++) {
					for (int cx = 0; cx < w; cx++) {
						keys.add(dev.kantobridge.builder.shape.FittedShapes.key(atlas, CaveMap.cellTiles(map.model(), cx, cy)));
					}
				}
				JsonObject keyed = new JsonObject();
				keyed.add("keys", keys);
				Files.writeString(out.resolve(map.id() + "_keys.json"), ProtoJson.write(keyed), StandardCharsets.UTF_8);
			}
			JsonArray levels = new JsonArray();
			int max = 0;
			for (int cy = 0; cy < h; cy++) {
				for (int cx = 0; cx < w; cx++) {
					int level = layout == null ? 0 : layout.level(cx, cy);
					levels.add(level);
					max = Math.max(max, level);
				}
			}
			o.addProperty("max_level", max);
			o.add("levels", levels);
			JsonArray ramps = new JsonArray();
			if (layout != null) {
				for (MapLayout.Ramp r : layout.ramps()) {
					JsonArray a = new JsonArray();
					a.add(r.x());
					a.add(r.y());
					a.add(r.dir().id());
					a.add(r.rise());
					ramps.add(a);
				}
			}
			o.add("ramps", ramps);
			Files.writeString(out.resolve(map.id() + ".json"), ProtoJson.write(o), StandardCharsets.UTF_8);
		}
		if (all) {
			Files.writeString(out.resolve("placements_" + game + ".json"), ProtoJson.write(placements), StandardCharsets.UTF_8);
		}
	}
}
