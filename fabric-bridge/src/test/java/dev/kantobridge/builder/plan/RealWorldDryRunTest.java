package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportProfile;
import dev.kantobridge.builder.shape.QuadCounter;
import dev.kantobridge.builder.shape.geom.ShapeTable;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.scene.gen1.BundleArtSource;
import dev.kantobridge.scene.profile.Profile;
import dev.kantobridge.scene.profile.ProfileSource;
import dev.kantobridge.world.MapLayout;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.WorldIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The caves in 3D plan's dry run on the real world (docs/specs/CAVES_3D_PLAN.md §0, H3–H5): plans the world from the
 * export bundle named by {@code -Pkb.realBundle} (the pod's, from the player's own game), every cave built in 3D, and
 * writes what the plan made of it to {@code build/reports/caves3d_dry_run.json}: counts, reason codes and map ids only,
 * never a tile or a pixel (docs/specs/VOXEL_BLOCKS_PLAN.md §0.2). Skipped everywhere else.
 */
@EnabledIfSystemProperty(named = "kb.realBundle", matches = ".+")
class RealWorldDryRunTest {
	@Test
	void dryRun() throws Exception {
		ExportBundle bundle = ExportBundle.open(Path.of(System.getProperty("kb.realBundle")));
		// every cave in 3D (the build's default since H7), or with -Pkb.caves3d=pilot the pilot's only (H6's world)
		boolean pilot = "pilot".equals(System.getProperty("kb.caves3d"));
		long started = System.nanoTime();
		WorldPlan plan = WorldPlanner.plan(bundle, new WorldPlanner.Options(null, 0, 0, -256, true, pilot ? Caves3d.PILOT : Caves3d.EVERY));
		JsonObject out = new JsonObject();
		out.addProperty("caves3d", pilot ? "pilot" : Caves3d.ALL);
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
		// H4: each cave's raised ground, or why it stays flat
		ExportProfile carried = bundle.profile();
		Profile profile = carried == null ? null : ProfileSource.read(carried.text(), carried.version(), carried.enabled()).profile();
		out.addProperty("profile", profile != null);
		JsonArray levels = new JsonArray();
		List<Box3> caveVolumes = new ArrayList<>();
		for (PlacedMap map : plan.maps()) {
			if (!WorldPlan.BIOME_CAVE_3D.equals(map.placement().biome())) {
				continue;
			}
			caveVolumes.add(WorldPlan.interiorVolume(map.placement(), WorldPlan.maxCeiling(map.layout())));
			CaveLevels.Result result = CaveLevels.plan(map.model(), bundle.field().tilePairs(),
					profile == null ? null : profile.tileset(map.model().tileset().id()));
			JsonObject row = new JsonObject();
			row.addProperty("map", map.id());
			row.addProperty("outcome", result.outcome());
			row.addProperty("high", result.high());
			row.addProperty("regions", result.regions());
			row.addProperty("raised_cells", result.raisedCells());
			row.addProperty("ramps", result.stairs());
			row.addProperty("stairs_profile_confirmed", result.stairsConfirmed());
			row.addProperty("stairs_profile_other", result.stairsOther());
			row.addProperty("faces", result.faces());
			row.addProperty("faces_other", result.facesOther());
			MapLayout layout = map.layout();
			// H5: its rooms, drawn apart
			row.addProperty("rooms", layout == null ? 1 : layout.pieces().size());
			if (layout != null) {
				Map<String, Integer> dirs = new TreeMap<>();
				for (int i = 0; i < layout.w() * layout.h(); i++) {
					MapLayout.Ramp ramp = layout.ramp(i % layout.w(), i / layout.w());
					if (ramp != null) {
						dirs.merge(ramp.dir().id(), 1, Integer::sum);
					}
				}
				row.add("ramps_by_dir", counts(dirs));
				row.addProperty("max_ceiling", WorldPlan.maxCeiling(layout));
			}
			levels.add(row);
		}
		out.add("cave_levels", levels);
		// H5: the shafts lined up (counts and map ids only)
		JsonArray shafts = new JsonArray();
		for (WorldIndex.Shaft shaft : plan.index().shafts()) {
			JsonObject row = new JsonObject();
			row.addProperty("kind", shaft.kind());
			row.addProperty("top", shaft.top().map());
			row.addProperty("bottom", shaft.bottom().map());
			row.addProperty("up", shaft.up());
			row.addProperty("length", shaft.y1() - shaft.y0());
			shafts.add(row);
		}
		out.add("shafts", shafts);
		// H5: per complex (by its cave maps' prefix: counts only), the links of each kind and what became of them
		Map<String, Integer> links = new TreeMap<>();
		for (PlacedMap map : plan.maps()) {
			if (!WorldPlan.BIOME_CAVE_3D.equals(map.placement().biome())) {
				continue;
			}
			for (dev.kantobridge.builder.export.ExportMap.Warp warp : map.model().map().warps()) {
				int flags = map.model().map().flags(warp.x(), warp.y());
				String kind = dev.kantobridge.builder.export.CellFlags.has(flags, dev.kantobridge.builder.export.CellFlags.HOLE) ? "hole"
						: dev.kantobridge.builder.export.CellFlags.has(flags, dev.kantobridge.builder.export.CellFlags.DOOR) ? "door"
						: dev.kantobridge.builder.export.CellFlags.has(flags, dev.kantobridge.builder.export.CellFlags.WARP_TILE) ? "warp_tile" : "other";
				PlacedMap to = plan.map(warp.to());
				String where = to == null ? "out" : WorldPlan.BIOME_CAVE_3D.equals(to.placement().biome()) ? "cave" : "out";
				boolean shaft = plan.index().shaftEnd(new dev.kantobridge.world.CellPos(map.id(), warp.x(), warp.y())) != null;
				links.merge(map.id() + ":" + kind + ":" + where + (shaft ? ":shaft" : ""), 1, Integer::sum);
			}
		}
		out.add("cave_warps", counts(links));
		// H4: story cells on ramps, by map and type (their blocks stand on the steps; a swap site's can't)
		Map<String, Integer> rampStories = new TreeMap<>();
		for (dev.kantobridge.world.StoryCellTable.Entry entry : plan.storyCells().entries()) {
			MapLayout layout = plan.index().layout(entry.cell().map());
			if (layout != null && layout.ramp(entry.cell().x(), entry.cell().y()) != null) {
				rampStories.merge(entry.cell().map() + ":" + entry.type().id(), 1, Integer::sum);
			}
		}
		out.add("ramp_story_cells", counts(rampStories));
		// H2 and H4: every block along a pair edge has its wall, at its cell's feet or the block above
		ChunkCodesView view = new ChunkCodesView(plan);
		Map<String, Integer> unwalled = new TreeMap<>();
		int walled = 0;
		for (PlacedMap map : plan.maps()) {
			if (map.edges().edges() == 0) {
				continue;
			}
			MapPlacement p = map.placement();
			for (int cy = 0; cy < p.h(); cy++) {
				for (int cx = 0; cx < p.w(); cx++) {
					BlockPos origin = plan.index().cellOrigin(map.id(), cx, cy);
					for (int b = 0; b < 4; b++) {
						if (map.edges().blockSides(cx, cy, b % 2, b / 2) == 0) {
							continue;
						}
						int x = origin.getX() + b % 2;
						int z = origin.getZ() + b / 2;
						int feet = view.code(x, origin.getY(), z);
						int above = view.code(x, origin.getY() + 1, z);
						if (BlockCode.kind(feet) == BlockCode.KIND_EDGE || BlockCode.kind(above) == BlockCode.KIND_EDGE
								|| BlockCode.isStory(feet) && BlockCode.storySides(feet) != 0) {
							walled++;
						} else {
							unwalled.merge(map.id() + ":" + BlockCode.kind(feet) + "/" + BlockCode.kind(above), 1, Integer::sum);
						}
					}
				}
			}
		}
		out.addProperty("edge_wall_blocks", walled);
		out.add("edge_wall_blocks_missing_by_map", counts(unwalled));
		// H4: what the caves draw, every one raised where it can be
		if (plan.shapes() != null && !caveVolumes.isEmpty()) {
			long t0 = System.nanoTime();
			ShapeTable table = ShapeTable.build(plan.shapes().index(), BundleArtSource.of(bundle));
			QuadCounter.Stats quads = QuadCounter.count(plan, plan.shapes().map(), table, caveVolumes);
			JsonObject cave = new JsonObject();
			cave.addProperty("shaped_blocks", quads.shapedBlocks());
			cave.addProperty("shape_quads", quads.shapeQuads());
			cave.addProperty("max_block", quads.maxBlock());
			cave.addProperty("sections", quads.sections());
			cave.addProperty("section_p50", quads.sectionP50());
			cave.addProperty("section_p95", quads.sectionP95());
			cave.addProperty("section_max", quads.sectionMax());
			cave.addProperty("ms", (System.nanoTime() - t0) / 1_000_000L);
			out.add("cave_quads", cave);
		}
		// the heights it built (HEIGHTS_ALL_GAMES_PLAN W4, W5): counts only
		out.add("heights", HeightsReport.of(plan));
		Path report = Path.of("reports", pilot ? "caves3d_dry_run_pilot.json" : "caves3d_dry_run.json");
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
