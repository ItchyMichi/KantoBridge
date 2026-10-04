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
				"world_index", "blocks_placed", "chunks", "layers", "elapsed_ms", "export_hash", "slot_map_hash", "status", "error")) {
			assertTrue(json.has(key), "missing " + key);
		}
		assertEquals(5, json.get("maps_built").getAsInt());
		assertTrue(json.get("world_index").getAsBoolean());
		assertEquals(1, json.getAsJsonObject("warning_codes").get("layout_overlap").getAsInt());
		assertTrue(json.get("error").isJsonNull());
		// Values are numbers, booleans, codes, ids and hashes only: no nested content beyond count maps.
		Set<String> objects = Set.of("warning_codes", "notes", "story_cells_by_type", "throughput", "viewpoints", "blocks_by_kind", "cells_by_kind",
				"solid_cells_by_height", "shape_keys_by_kind", "selfcheck");
		for (String key : json.keySet()) {
			assertTrue(!json.get(key).isJsonObject() || objects.contains(key), key + " is an unexpected object");
			assertTrue(!json.get(key).isJsonArray(), key + " is an array");
		}
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
