package dev.kantobridge.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The build report carries the fields the e2e gate reads, and only counts, codes, ids and hashes. */
class BuildReportTest {
	@TempDir
	Path temp;

	@Test
	void reportHasTheGateFields() throws Exception {
		BuildReport report = new BuildReport();
		report.status = "ok";
		report.mapsRequested = 5;
		report.mapsBuilt = 5;
		report.storyCells = 12;
		report.warningCodes.put("layout_overlap", 1);
		report.worldIndex = true;
		Path file = this.temp.resolve("reports/build_report.json");
		report.writeTo(file);
		JsonObject json = ProtoJson.parseObject(Files.readString(file).trim());
		for (String key : List.of("maps_requested", "maps_built", "overlaps", "story_cells", "warnings", "warning_codes", "pack_textures",
				"world_index", "blocks_placed", "chunks", "layers", "elapsed_ms", "export_hash", "slot_map_hash", "status", "error",
				// The voxel plan's Tier B groups (V4): carriers and structures.
				"shape_decor_skipped", "void_hull_cells", "void_ring_units", "structures", "structures_by_kind", "shape_templates")) {
			assertTrue(json.has(key), "missing " + key);
		}
		assertEquals(5, json.get("maps_built").getAsInt());
		assertTrue(json.get("world_index").getAsBoolean());
		assertEquals(1, json.getAsJsonObject("warning_codes").get("layout_overlap").getAsInt());
		assertTrue(json.get("error").isJsonNull());
		// Values are numbers, booleans, codes, ids and hashes only: no nested content beyond count maps.
		Set<String> objects = Set.of("warning_codes", "notes", "story_cells_by_type", "throughput", "viewpoints", "blocks_by_kind", "cells_by_kind",
				"solid_cells_by_height", "shape_keys_by_kind", "selfcheck", "densest_section", "structures_by_kind", "sprites_by_frames",
				"character_fallbacks", "profile", "units_by_source", "buildings", "classes", "dressing", "region");
		for (String key : json.keySet()) {
			assertTrue(!json.get(key).isJsonObject() || objects.contains(key), key + " is an unexpected object");
			assertTrue(!json.get(key).isJsonArray(), key + " is an array");
		}
	}

	@Test
	void theRegionObjectNamesTheRegionBuilt() {
		// MG3: null for a legacy build; the region's slot, origin, bounds, areas, families and shape-map chars otherwise
		BuildReport report = new BuildReport();
		assertTrue(report.toJson().get("region").isJsonNull(), "a legacy build has no region");
		report.region = new BuildReport.RegionReport("hoenn", 1, 8192, 64, 0, new dev.kantobridge.world.RegionRecord.Box(8000, -9000, 13000, 4000, -256, 319),
				new dev.kantobridge.world.RegionRecord.Areas(new dev.kantobridge.world.RegionRecord.Area(8180, -12, 700, 744),
						dev.kantobridge.world.RegionRecord.Area.NONE, dev.kantobridge.world.RegionRecord.Area.NONE, dev.kantobridge.world.RegionRecord.Area.NONE),
				16, 31, 12345L, 6789L, 4321L);
		JsonObject region = report.toJson().getAsJsonObject("region");
		assertEquals(Set.of("region", "slot", "origin", "bounds", "areas", "families", "shape_map_chars", "shape_index_chars", "index_chars"),
				region.keySet());
		assertEquals("hoenn", region.get("region").getAsString());
		assertEquals(1, region.get("slot").getAsInt());
		assertEquals(8192, region.getAsJsonObject("origin").get("x").getAsInt());
		assertEquals(64, region.getAsJsonObject("origin").get("y").getAsInt());
		assertEquals(-9000, region.getAsJsonObject("bounds").get("minZ").getAsInt());
		assertEquals(319, region.getAsJsonObject("bounds").get("maxY").getAsInt());
		assertEquals(Set.of("surface", "zones", "dungeons", "caves"), region.getAsJsonObject("areas").keySet());
		assertEquals(700, region.getAsJsonObject("areas").getAsJsonObject("surface").get("w").getAsInt());
		assertEquals(0, region.getAsJsonObject("areas").getAsJsonObject("zones").get("w").getAsInt(), "zero size: not built");
		assertEquals(16, region.getAsJsonArray("families").get(0).getAsInt());
		assertEquals(31, region.getAsJsonArray("families").get(1).getAsInt());
		assertEquals(12345, region.get("shape_map_chars").getAsLong());
		assertEquals(6789, region.get("shape_index_chars").getAsLong(), "the shape index on the wire, deflated (item 4)");
		assertEquals(4321, region.get("index_chars").getAsLong(), "the WorldIndex on the wire, deflated (G2G)");
		report.region = new BuildReport.RegionReport("hoenn", 1, 8192, 64, 0, null, dev.kantobridge.world.RegionRecord.Areas.NONE, 16, 31, 0);
		assertTrue(report.toJson().getAsJsonObject("region").get("bounds").isJsonNull(), "nothing placed: no bounds");
	}

	@Test
	void viewpointsAreBlockCoordinatesOnly() {
		BuildReport report = new BuildReport();
		assertEquals(0, report.toJson().getAsJsonObject("viewpoints").size(), "nothing without a spawn or a surface");
		report.spawnBlock = new int[] {10, 64, -20};
		report.surfaceBox = new int[] {-12, 63, -40, 52, 71, 36};
		JsonObject viewpoints = report.toJson().getAsJsonObject("viewpoints");
		assertEquals(9, viewpoints.size());
		assertEquals(10, viewpoints.get("spawn_x").getAsInt());
		assertEquals(64, viewpoints.get("spawn_feet_y").getAsInt());
		assertEquals(-20, viewpoints.get("spawn_z").getAsInt());
		assertEquals(52, viewpoints.get("surface_max_x").getAsInt());
		for (String key : viewpoints.keySet()) {
			assertTrue(viewpoints.get(key).getAsJsonPrimitive().isNumber(), key + " is a number");
		}
	}

	@Test
	void throughputIsRecorded() throws Exception {
		BuildReport report = new BuildReport();
		report.status = "ok";
		report.mapsBuilt = 220;
		report.blocksPlaced = 2_485_154;
		report.chunks = 2_126;
		report.placeMs = 53_000;
		report.elapsedMs = 56_500;
		JsonObject throughput = report.toJson().getAsJsonObject("throughput");
		assertEquals(46_889.7, throughput.get("blocks_per_s").getAsDouble(), 1e-9, "blocks placed per second of placement");
		assertEquals(40.1, throughput.get("chunks_per_s").getAsDouble(), 1e-9);
		assertEquals(3.9, throughput.get("maps_per_s").getAsDouble(), 1e-9, "maps per second of the whole build");
		assertEquals(3, throughput.size());
		JsonObject empty = new BuildReport().toJson().getAsJsonObject("throughput");
		assertEquals(0.0, empty.get("blocks_per_s").getAsDouble(), "nothing measured: 0, never a division by zero");
		assertEquals(0.0, BuildReport.rate(5, 0));
		assertEquals(0.0, BuildReport.rate(0, 100));
	}

	@Test
	void mapFilters() {
		assertNull(BuildOptions.parseMaps(null));
		assertNull(BuildOptions.parseMaps(" "));
		assertEquals(List.of("PALLET_TOWN", "REDS_HOUSE_1F"), BuildOptions.parseMaps("PALLET_TOWN, reds_house_1f,PALLET_TOWN,,"));
		BuildOptions options = BuildOptions.defaults().withOrigin(16, -32).withWorldSpawn(false);
		assertEquals(16, options.originX());
		assertEquals(-32, options.originZ());
		assertTrue(!options.setWorldSpawn() && options.writePack() && options.publishIndex());
	}
}
