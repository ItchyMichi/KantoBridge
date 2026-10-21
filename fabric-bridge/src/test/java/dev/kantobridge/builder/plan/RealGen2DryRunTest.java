package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.shape.ShapeKey;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.StoryCellTable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * MG9 (G2E): the Gen 2 planner's dry run on the real Crystal export named by {@code -Pkb.realGen2Bundle} (the pod's, from
 * the test install's own import): plans every map with shapes, as a build does, as Johto's region at slot 2 with its
 * family range (J7), and writes what the plan made of it to {@code reports/gen2_dry_run.json}: counts, reason codes and
 * map ids only, never a tile or a pixel. Skipped everywhere else.
 */
@EnabledIfSystemProperty(named = "kb.realGen2Bundle", matches = ".+")
class RealGen2DryRunTest {
	@Test
	void dryRun() throws Exception {
		ExportBundle bundle = ExportBundle.open(Path.of(System.getProperty("kb.realGen2Bundle")));
		long started = System.nanoTime();
		// Johto is a region build at slot 2 with Gen 2's families (J7)
		RegionBuild region = new RegionBuild("johto", 2, bundle.game(), new SlotMap.Range(0, SlotMap.familiesFor("gen2") - 1), null, "johto_");
		WorldPlan plan = WorldPlanner.plan(bundle, new WorldPlanner.Options(null, 0, 0, -256, true), region);
		JsonObject out = new JsonObject();
		out.addProperty("game", bundle.game());
		out.addProperty("plan_ms", (System.nanoTime() - started) / 1_000_000L);
		Runtime runtime = Runtime.getRuntime();
		out.addProperty("heap_used_mb_after_plan", (runtime.totalMemory() - runtime.freeMemory()) >> 20);
		out.addProperty("heap_max_mb", runtime.maxMemory() >> 20);
		out.addProperty("maps_requested", plan.mapsRequested());
		out.addProperty("maps", plan.maps().size());
		out.addProperty("maps_unplaced", plan.mapsUnplaced());
		out.addProperty("surface", plan.maps().stream().filter(m -> !m.interior()).count());
		out.addProperty("interiors", plan.maps().stream().filter(PlacedMap::interior).count());
		out.addProperty("max_layer", plan.maxLayer());
		out.addProperty("edge_mismatches", plan.edgeMismatches());
		out.addProperty("overlaps", plan.overlaps());
		out.add("warnings", counts(plan.warnings().warnings()));
		out.add("notes", counts(plan.warnings().notes()));
		// which maps each warning names (symbols only: map ids, object names)
		com.google.gson.JsonObject details = new com.google.gson.JsonObject();
		for (String code : plan.warnings().warnings().keySet()) {
			com.google.gson.JsonArray where = new com.google.gson.JsonArray();
			for (java.util.Map<String, String> detail : plan.warnings().details(code)) {
				where.add(String.join(" ", new java.util.TreeMap<>(detail).values()));
			}
			details.add(code, where);
		}
		out.add("warning_details", details);
		// the one shared Pokémon Center upstairs: which center it is placed under (an id)
		MapPlacement upstairs = plan.index().map("G2_POKECENTER_2F");
		out.addProperty("pokecenter_2f_parent", upstairs == null ? null : upstairs.parent());
		// the slot map: keys per shape and the families they take (J7: does Johto fit Gen 2's range?)
		Map<String, Integer> shapes = new TreeMap<>();
		int maxFamily = -1;
		for (Map.Entry<SlotMap.Key, SlotMap.Slot> entry : plan.slotMap().slots().entrySet()) {
			shapes.merge(entry.getKey().shape().id(), 1, Integer::sum);
			maxFamily = Math.max(maxFamily, entry.getValue().family());
		}
		out.add("slot_keys_by_shape", counts(shapes));
		out.addProperty("families_used", maxFamily + 1);
		out.addProperty("pages", plan.art().slotMap().slots().keySet().stream().map(SlotMap.Key::atlas).distinct().count());
		Map<String, Integer> story = new TreeMap<>();
		for (StoryCellTable.Entry entry : plan.storyCells().entries()) {
			story.merge(entry.type().id(), 1, Integer::sum);
		}
		out.add("story_cells_by_type", counts(story));
		out.addProperty("habitats", plan.habitats().size());
		out.addProperty("pools", plan.pools().size());
		int edges = 0;
		Map<String, Integer> biomes = new TreeMap<>();
		for (PlacedMap map : plan.maps()) {
			edges += map.edges().edges();
			MapPlacement p = map.placement();
			biomes.merge(p.biome(), 1, Integer::sum);
		}
		out.addProperty("one_way_edges", edges);
		// the outdoor maps placed under a door (ids only)
		com.google.gson.JsonArray offSurface = new com.google.gson.JsonArray();
		for (PlacedMap map : plan.maps()) {
			if (map.interior() && bundle.g2map(map.id()).outdoor()) {
				offSurface.add(map.id());
			}
		}
		out.add("outdoor_maps_off_the_surface", offSurface);
		out.add("maps_by_biome", counts(biomes));
		if (plan.shapes() != null) {
			JsonObject shaped = new JsonObject();
			shaped.addProperty("shaped_blocks", plan.shapes().shapedBlocks());
			shaped.addProperty("index_size", plan.shapes().index().size());
			Map<String, Integer> kinds = new TreeMap<>();
			for (Map.Entry<ShapeKey.Kind, Integer> entry : plan.shapes().keysByKind().entrySet()) {
				kinds.put(entry.getKey().name(), entry.getValue());
			}
			shaped.add("keys_by_kind", counts(kinds));
			shaped.addProperty("plan_ms", plan.shapes().planMs());
			out.add("shapes", shaped);
		}
		// the heights it built (HEIGHTS_ALL_GAMES_PLAN W4, W5): counts only; and the world index beside it (layout data,
		// as a build writes it), for the pod's checks of single maps
		out.add("heights", HeightsReport.of(plan));
		Files.createDirectories(Path.of("reports"));
		Files.writeString(Path.of("reports", "gen2_world_index.json"), plan.index().toJson(), StandardCharsets.UTF_8);
		Path report = Path.of("reports", "gen2_dry_run.json");
		Files.createDirectories(report.getParent());
		Files.writeString(report, ProtoJson.write(out), StandardCharsets.UTF_8);
		assertTrue(plan.maps().size() > 0);
	}

	private static JsonObject counts(Map<String, Integer> counts) {
		JsonObject out = new JsonObject();
		new TreeMap<>(counts).forEach(out::addProperty);
		return out;
	}
}
