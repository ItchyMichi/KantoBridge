package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportMap;
import dev.kantobridge.builder.export.G2Map;
import dev.kantobridge.builder.export.G2Tiles;
import dev.kantobridge.builder.export.G3Map;
import dev.kantobridge.builder.export.G3Tiles;
import dev.kantobridge.builder.shape.Structures;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.scene.ArtSource;
import dev.kantobridge.scene.FrontEnds;
import dev.kantobridge.scene.GameFrontEnd;
import dev.kantobridge.scene.Scene;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.SlotMap;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The 3D heights plan's survey (docs/specs/HEIGHTS_ALL_GAMES_PLAN.md, W0): plans each real world named by
 * {@code -Pkb.realBundle} (Blue), {@code -Pkb.realGen2Bundle} (Crystal) and {@code -Pkb.realGen3Bundle} (Emerald), and
 * writes per map what the heights work needs to judge it: placement, cell kinds and flags, walls, Gen 1 cave levels,
 * Emerald's elevations and behaviours, Crystal's warp kinds and one-way sides, warps with their arrival cells, and the
 * drawn faces (Structures' measured runs, today's 6-row cap and uncapped). Layout data only, never a tile or a pixel; the
 * reports go to {@code reports/heights_survey_<game>.json} on the pod and are read there or in the user's kb-hub work
 * folder, never committed. Runs only with {@code -Pkb.heightsSurvey=true}.
 */
class HeightsSurveyTest {
	@Test
	void survey() throws Exception {
		Map<String, String> bundles = new TreeMap<>();
		put(bundles, "gen1", System.getProperty("kb.realBundle"));
		put(bundles, "gen2", System.getProperty("kb.realGen2Bundle"));
		put(bundles, "gen3", System.getProperty("kb.realGen3Bundle"));
		org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getProperty("kb.heightsSurvey")) && !bundles.isEmpty(),
				"no survey asked for, or no real bundle");
		for (Map.Entry<String, String> e : bundles.entrySet()) {
			survey(e.getKey(), ExportBundle.open(Path.of(e.getValue())));
		}
	}

	private static void put(Map<String, String> out, String family, String value) {
		if (value != null && !value.isBlank()) {
			out.put(family, value);
		}
	}

	private static void survey(String family, ExportBundle bundle) throws Exception {
		long started = System.nanoTime();
		WorldPlan plan;
		if (family.equals("gen1")) {
			plan = WorldPlanner.plan(bundle, new WorldPlanner.Options(null, 0, 0, -256, false, Caves3d.EVERY));
		} else if (family.equals("gen2")) {
			RegionBuild region = new RegionBuild("johto", 2, bundle.game(), new SlotMap.Range(0, SlotMap.familiesFor("gen2") - 1), null, "johto_");
			plan = WorldPlanner.plan(bundle, new WorldPlanner.Options(null, 0, 0, -256, false), region);
		} else {
			RegionBuild region = new RegionBuild("hoenn", 1, bundle.game(), new SlotMap.Range(0, SlotMap.familiesFor("gen3") - 1), null, "hoenn_");
			plan = WorldPlanner.plan(bundle, new WorldPlanner.Options(null, 0, 0, -256, false), region);
		}
		long planned = System.nanoTime();
		GameFrontEnd front = FrontEnds.forGame(bundle.game());
		Scene scene = front.describe(bundle);
		ArtSource art = FrontEnds.artSource(bundle);
		JsonObject out = new JsonObject();
		out.addProperty("game", bundle.game());
		out.addProperty("family", family);
		out.addProperty("plan_ms", (planned - started) / 1_000_000L);
		out.addProperty("max_layer", plan.maxLayer());
		out.addProperty("maps_placed", plan.maps().size());
		out.addProperty("cell_px", scene.cellPx());
		out.addProperty("art_px", scene.artPx());
		JsonObject errors = new JsonObject();
		JsonArray maps = new JsonArray();
		for (PlacedMap placed : plan.maps()) {
			try {
				maps.add(map(family, bundle, scene.maps().get(placed.id()), placed, art));
			} catch (RuntimeException | dev.kantobridge.builder.export.BundleException ex) {
				errors.addProperty(placed.id(), ex.getClass().getSimpleName() + ": " + ex.getMessage());
			}
		}
		out.add("errors", errors);
		out.add("maps", maps);
		out.addProperty("survey_ms", (System.nanoTime() - planned) / 1_000_000L);
		Path report = Path.of("reports", "heights_survey_" + bundle.game() + ".json");
		Files.createDirectories(report.getParent());
		Files.writeString(report, ProtoJson.write(out), StandardCharsets.UTF_8);
		assertTrue(maps.size() > 0);
	}

	private static JsonObject map(String family, ExportBundle bundle, SceneMap scene, PlacedMap placed, ArtSource art)
			throws dev.kantobridge.builder.export.BundleException {
		MapPlacement p = placed.placement();
		MapModel model = placed.model();
		int w = model.cellWidth();
		int h = model.cellHeight();
		JsonObject row = new JsonObject();
		row.addProperty("id", p.id());
		row.addProperty("kind", p.kind().id());
		row.addProperty("x", p.x());
		row.addProperty("y", p.y());
		row.addProperty("z", p.z());
		row.addProperty("w", w);
		row.addProperty("h", h);
		row.addProperty("layer", p.layer());
		row.addProperty("biome", p.biome());
		row.addProperty("dark", p.dark());
		if (p.parent() != null) {
			row.addProperty("parent", p.parent());
		}
		row.addProperty("open_sky", WorldPlan.openSky(p));
		if (scene != null) {
			row.addProperty("scene_surface", scene.surface());
			row.addProperty("outdoor", scene.env().outdoor());
			row.addProperty("open_air", scene.env().openAir());
			row.addProperty("cave", scene.env().cave());
			row.addProperty("canopy", scene.env().canopy());
			row.addProperty("tileset", scene.tileset());
		}
		// cells: kinds, flags, walls, levels (a Gen 1 cave's layout)
		StringBuilder kinds = new StringBuilder(w * h);
		JsonArray flags = new JsonArray();
		JsonArray sides = new JsonArray();
		JsonArray levels = new JsonArray();
		boolean anySide = false;
		boolean anyLevel = false;
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				kinds.append(switch (model.kind(cx, cy)) {
					case GROUND -> 'G';
					case WATER -> 'A';
					case LEDGE -> 'L';
					case COUNTER -> 'C';
					case SOLID -> 'S';
				});
				flags.add(model.flags(cx, cy));
				int s = placed.edges().edges() > 0 ? placed.edges().sides(cx, cy) : 0;
				anySide |= s != 0;
				sides.add(s);
				int level = placed.level(cx, cy);
				anyLevel |= level != 0;
				levels.add(level);
			}
		}
		row.addProperty("kinds", kinds.toString());
		row.add("flags", flags);
		if (anySide) {
			row.add("sides", sides);
		}
		if (anyLevel) {
			row.add("levels", levels);
		}
		if (placed.layout() != null) {
			row.addProperty("pieces", placed.layout().pieces().size());
		}
		// warps, with the arrival cell where the export says it
		JsonArray warps = new JsonArray();
		if (family.equals("gen1")) {
			ExportMap m = bundle.map(p.id());
			for (ExportMap.Warp warp : m.warps()) {
				JsonArray wr = new JsonArray();
				wr.add(warp.x());
				wr.add(warp.y());
				wr.add(warp.to());
				ExportMap.Warp arrival = null;
				if (!"LAST_MAP".equals(warp.to()) && bundle.has("map", warp.to())) {
					arrival = bundle.map(warp.to()).warp(warp.warp());
				}
				wr.add(arrival == null ? -1 : arrival.x());
				wr.add(arrival == null ? -1 : arrival.y());
				wr.add("LAST_MAP".equals(warp.to()));
				warps.add(wr);
			}
			if (m.parent() != null) {
				JsonArray door = new JsonArray();
				door.add(m.parent().map());
				door.add(m.parent().x());
				door.add(m.parent().y());
				row.add("door", door);
			}
			if (m.lastMap() != null) {
				row.addProperty("last_map", m.lastMap());
			}
		} else if (family.equals("gen2")) {
			G2Map m = bundle.g2map(p.id());
			for (G2Map.Warp warp : m.warps()) {
				warps.add(warpRow(warp.x(), warp.y(), warp.to(), warp.dynamic(), warp.to() == null || !bundle.has("g2map", warp.to()) ? null
						: arrival2(bundle.g2map(warp.to()), warp.warp())));
			}
			// Crystal: each cell's warp kind, ledge facings and one-way sides, where it has any
			G2Tiles tiles = bundle.g2tiles(m.variant());
			JsonObject meanings = new JsonObject();
			for (int cy = 0; cy < Math.min(h, m.cellHeight()); cy++) {
				for (int cx = 0; cx < Math.min(w, m.cellWidth()); cx++) {
					int block = m.wallBlock(cx / 2, cy / 2) ? -1 : m.drawnBlock(cx / 2, cy / 2);
					if (block < 0) {
						continue;
					}
					G2Tiles.Meaning mean = tiles.meaning(tiles.coll(block, cx % 2, cy % 2));
					if (mean.warp() == null && mean.ledge().isEmpty() && mean.sides().isEmpty() && mean.neighbor().isEmpty()) {
						continue;
					}
					JsonObject c = new JsonObject();
					if (mean.warp() != null) {
						c.addProperty("warp", mean.warp());
					}
					if (!mean.ledge().isEmpty()) {
						c.addProperty("ledge", String.join(",", mean.ledge()));
					}
					if (!mean.sides().isEmpty()) {
						c.addProperty("sides", String.join(",", mean.sides()));
					}
					if (!mean.neighbor().isEmpty()) {
						c.addProperty("neighbor", mean.neighbor().toString());
					}
					meanings.add(Integer.toString(cy * w + cx), c);
				}
			}
			row.add("g2", meanings);
			row.addProperty("env", m.env());
		} else {
			G3Map m = bundle.g3map(p.id());
			for (G3Map.Warp warp : m.warps()) {
				warps.add(warpRow(warp.x(), warp.y(), warp.to(), warp.dynamic(), warp.to() == null || !bundle.has("g3map", warp.to()) ? null
						: arrival3(bundle.g3map(warp.to()), warp.warp())));
			}
			// Emerald: elevation and behaviour per cell (behaviour numbers, with the names present)
			G3Tiles tiles = bundle.g3tiles(m.pair());
			JsonArray elev = new JsonArray();
			JsonArray beh = new JsonArray();
			JsonObject names = new JsonObject();
			for (int cy = 0; cy < h; cy++) {
				for (int cx = 0; cx < w; cx++) {
					boolean in = m.inGrid(cx, cy);
					elev.add(in ? m.elev()[m.index(cx, cy)] : 0);
					int b = in ? tiles.behavior(m.mid(cx, cy)) : 0;
					beh.add(b);
					String name = in ? tiles.behaviorName(m.mid(cx, cy)) : null;
					if (name != null && !names.has(Integer.toString(b))) {
						names.addProperty(Integer.toString(b), name);
					}
				}
			}
			row.add("elev", elev);
			row.add("beh", beh);
			row.add("beh_names", names);
			row.addProperty("true_w", m.trueW());
			row.addProperty("true_h", m.trueH());
			row.addProperty("map_type", m.mapType());
			row.addProperty("g3kind", m.kind());
		}
		row.add("warps", warps);
		// the drawn faces: every measured run, today's cap (6 rows) and uncapped
		if (scene != null) {
			boolean[] solid = Structures.solidTiles(scene);
			row.add("runs", runs(Structures.measured(scene, solid, HeightRules.MAX_UNIT, HeightRules.MIN_REPEAT_ROWS, HeightRules.INTERIOR_WALL)));
			row.add("runs64", runs(Structures.measured(scene, solid, 64, HeightRules.MIN_REPEAT_ROWS, HeightRules.INTERIOR_WALL)));
			row.addProperty("flat", flat(scene, art));
			row.addProperty("art_cols", scene.artCols());
			row.addProperty("art_rows", scene.artRows());
			row.addProperty("per_cell", scene.perCell());
		}
		return row;
	}

	/**
	 * Per cell, '0'..'3' when its whole drawing is one shade (black void is '3'), else '.': the only thing read from the
	 * art, so filler the game draws as one flat colour isn't taken for a drawn wall.
	 */
	private static String flat(SceneMap map, ArtSource art) {
		int per = map.perCell();
		StringBuilder out = new StringBuilder(map.w() * map.h());
		for (int cy = 0; cy < map.h(); cy++) {
			for (int cx = 0; cx < map.w(); cx++) {
				int shade = -2;
				boolean one = true;
				for (int k = 0; k < per * per && one; k++) {
					int tile = map.tile(cx * per + k % per, cy * per + k / per);
					int[] shades = tile < 0 ? null : art.tileShades(map.atlas(), tile);
					if (shades == null) {
						one = false;
						break;
					}
					for (int v : shades) {
						if (shade == -2) {
							shade = v;
						} else if (v != shade) {
							one = false;
							break;
						}
					}
				}
				out.append(one && shade >= 0 ? (char) ('0' + shade) : '.');
			}
		}
		return out.toString();
	}

	private static JsonArray warpRow(int x, int y, String to, boolean dynamic, int[] arrival) {
		JsonArray wr = new JsonArray();
		wr.add(x);
		wr.add(y);
		wr.add(to);
		wr.add(arrival == null ? -1 : arrival[0]);
		wr.add(arrival == null ? -1 : arrival[1]);
		wr.add(dynamic);
		return wr;
	}

	private static int[] arrival2(G2Map to, int warp) {
		return warp >= 0 && warp < to.warps().size() ? new int[] {to.warps().get(warp).x(), to.warps().get(warp).y()} : null;
	}

	private static int[] arrival3(G3Map to, int warp) {
		return warp >= 0 && warp < to.warps().size() ? new int[] {to.warps().get(warp).x(), to.warps().get(warp).y()} : null;
	}

	private static JsonArray runs(List<Structures.MeasuredRun> runs) {
		JsonArray out = new JsonArray();
		for (Structures.MeasuredRun r : runs) {
			JsonArray a = new JsonArray();
			a.add(r.tx());
			a.add(r.n());
			a.add(r.f());
			a.add(r.pureRepeat() ? 1 : 0);
			a.add(r.repeatRead() ? 1 : 0);
			a.add(r.unit());
			a.add(r.h());
			a.add(r.r());
			a.add(r.doorFold() ? 1 : 0);
			a.add(r.origin());
			out.add(a);
		}
		return out;
	}
}
