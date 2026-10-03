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
		Set<String> objects = Set.of("warning_codes", "notes", "story_cells_by_type");
		for (String key : json.keySet()) {
			assertTrue(!json.get(key).isJsonObject() || objects.contains(key), key + " is an unexpected object");
			assertTrue(!json.get(key).isJsonArray(), key + " is an array");
		}
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
