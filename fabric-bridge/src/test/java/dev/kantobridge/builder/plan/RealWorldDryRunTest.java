package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.WorldIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The caves in 3D plan's dry run on the real world (docs/specs/CAVES_3D_PLAN.md §0, H3–H5): plans the world from the
 * export bundle named by {@code -Pkb.realBundle} (the pod's, from the player's own game) and writes what the plan made
 * of it to {@code build/reports/caves3d_dry_run.json}: counts, reason codes and map ids only, never a tile or a pixel
 * (docs/specs/VOXEL_BLOCKS_PLAN.md §0.2). Skipped everywhere else.
 */
@EnabledIfSystemProperty(named = "kb.realBundle", matches = ".+")
class RealWorldDryRunTest {
	@Test
	void dryRun() throws Exception {
		ExportBundle bundle = ExportBundle.open(Path.of(System.getProperty("kb.realBundle")));
		long started = System.nanoTime();
		WorldPlan plan = WorldPlanner.plan(bundle, new WorldPlanner.Options(null, 0, 0, -256));
		JsonObject out = new JsonObject();
		out.addProperty("plan_ms", (System.nanoTime() - started) / 1_000_000L);
		out.addProperty("maps", plan.maps().size());
		out.addProperty("interiors", plan.maps().stream().filter(PlacedMap::interior).count());
		out.addProperty("max_layer", plan.maxLayer());
		out.add("warnings", counts(plan.warnings().warnings()));
		out.add("notes", counts(plan.warnings().notes()));
		out.addProperty("tile_pairs", bundle.field().tilePairs().size());
		// H2: the pair edges each map has
		JsonObject edges = new JsonObject();
		for (PlacedMap map : plan.maps()) {
			if (map.edges().edges() > 0) {
				edges.addProperty(map.id(), map.edges().edges());
			}
		}
		out.add("pair_edges_by_map", edges);
		// H3: the cave area
		JsonArray caves = new JsonArray();
		for (PlacedMap map : plan.maps()) {
			MapPlacement p = map.placement();
			if (WorldPlan.BIOME_CAVE_3D.equals(p.biome())) {
				JsonObject cave = new JsonObject();
				cave.addProperty("map", p.id());
				cave.addProperty("layer", p.layer());
				cave.addProperty("feet", p.y());
				cave.addProperty("cells", p.w() * p.h());
				caves.add(cave);
			}
		}
		out.add("caves", caves);
		Map<String, Integer> habitats = new TreeMap<>();
		for (WorldIndex.Habitat habitat : plan.habitats()) {
			habitats.merge("range_" + habitat.range(), 1, Integer::sum);
		}
		out.add("habitats_by_range", counts(habitats));
		if (plan.shapes() != null) {
			out.addProperty("shape_map_wire_chars", plan.shapes().map().wire().length());
		}
		Path report = Path.of("reports", "caves3d_dry_run.json");
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
