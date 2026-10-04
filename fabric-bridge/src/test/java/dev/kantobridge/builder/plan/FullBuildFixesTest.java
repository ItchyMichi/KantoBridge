package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.BuildDiagnostics;
import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.Scale;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two warning kinds of the early full-Kanto build, on the synthetic bundle (docs/PROGRESS.md, M8 item 1):
 * {@code swap_block_mismatch} (sites the engine stamps closed on map load, whose map data shows them open) and
 * {@code interior_unplaced} (a floor chain deeper than the world), plus the diagnostics that name what a warning is
 * about with symbols only.
 */
class FullBuildFixesTest {
	/** Synthetic block ids (tools/fake_gen1/synth.py): floor, interior wall, counter, rug over a wall row, stairs. */
	private static final int B_FLOOR = 9;
	private static final int B_IWALL = 10;
	private static final int B_COUNTER = 11;
	private static final int B_RUG = 13;
	private static final int B_STAIRS = 16;

	@TempDir
	Path temp;

	private static JsonObject site(String map, int bx, int by, String kind, int before, int after, String source) {
		JsonObject site = new JsonObject();
		site.addProperty("map", map);
		site.addProperty("bx", bx);
		site.addProperty("by", by);
		site.addProperty("kind", kind);
		site.addProperty("before", before);
		site.addProperty("after", after);
		if (source != null) {
			site.addProperty("source", source);
		}
		return site;
	}

	@Test
	void stampedSitesAreAcceptedAndMismatchesNameTheirMaps() throws Exception {
		ExportBundle synthetic = ExportBundle.open(TestData.syntheticBundle());
		JsonObject swaps = synthetic.json("swaps", "all").deepCopy();
		JsonArray sites = swaps.getAsJsonArray("sites");
		// A card-key door the engine stamps shut on map load: the house's data has the open floor there.
		sites.add(site("TEST_HOUSE", 2, 2, "card_key", B_IWALL, B_FLOOR, "EVENT_TEST_DOOR"));
		// A door whose data shows neither of its blocks: the export and the engine disagree.
		sites.add(site("TEST_HOUSE", 1, 2, "card_key", B_COUNTER, B_STAIRS, "EVENT_TEST_GATE"));
		// A scripted seal its script writes on every entry (Agatha's exit, wave 2): the data's own floor never shows.
		sites.add(site("TEST_HOUSE", 3, 3, "script", B_IWALL, B_RUG, "EVENT_TEST_SEAL"));
		ExportBundle bundle = ExportBundle.open(TestData.syntheticCopyWith(this.temp, Map.of("swaps/all", swaps)));
		WorldPlan plan = WorldPlanner.plan(bundle, new WorldPlanner.Options(null, TestData.ORIGIN_X, TestData.ORIGIN_Z, TestData.MIN_BUILD_HEIGHT));

		Warnings warnings = plan.warnings();
		assertEquals(Map.of("swap_block_mismatch", 1), warnings.warnings(), "only the true mismatch is a warning");
		assertEquals(List.of(Map.of("kind", "card_key", "map", "TEST_HOUSE", "site", "2", "source", "EVENT_TEST_GATE")),
				warnings.details("swap_block_mismatch"));
		assertEquals(1, warnings.noteCount("swap_site_stamped"));
		assertEquals("TEST_HOUSE", warnings.details("swap_site_stamped").get(0).get("map"));
		assertEquals(List.of(Map.of("kind", "script", "map", "TEST_HOUSE", "site", "3", "source", "EVENT_TEST_SEAL")),
				warnings.details("swap_site_overwritten"), "noted with its symbols, never a warning");

		// The seal: its rug row built open with tile cells the wall closes; the wall row it never changes is built as the
		// site shows it (a wall), not as the data's floor says.
		for (String key : new String[] {"TEST_HOUSE:6:6", "TEST_HOUSE:7:6"}) {
			StoryCellTable.Entry entry = plan.storyCells().get(key);
			assertNotNull(entry, key);
			assertEquals(B_FLOOR, entry.start(), "other maps show the data's own block");
			assertEquals(B_IWALL, entry.closed().block());
			assertEquals(StoryCellTable.Pass.SOLID, entry.closed().pass());
		}
		MapModel house = plan.map("TEST_HOUSE").model();
		assertEquals(MapModel.Kind.GROUND, house.kind(6, 6));
		assertEquals(MapModel.Kind.SOLID, house.kind(6, 7), "the unchanged wall row isn't the data's walkable floor");
		assertEquals(null, plan.storyCells().get("TEST_HOUSE:6:7"));

		// The stamped door: built open, four tile cells closed by the wall block, which other maps' data would show open.
		for (String key : new String[] {"TEST_HOUSE:4:4", "TEST_HOUSE:5:4", "TEST_HOUSE:4:5", "TEST_HOUSE:5:5"}) {
			StoryCellTable.Entry entry = plan.storyCells().get(key);
			assertNotNull(entry, key);
			assertEquals(StoryCellType.TILE, entry.type());
			assertEquals(B_FLOOR, entry.start());
			assertEquals(B_IWALL, entry.closed().block());
			assertEquals(StoryCellTable.Pass.SOLID, entry.closed().pass());
			assertEquals(HeightRules.INTERIOR_WALL, entry.closed().height());
		}
		assertEquals(MapModel.Kind.GROUND, plan.map("TEST_HOUSE").model().kind(4, 4));
		// The cut tree keeps its closed art: the tree tile, two blocks high like the tree line beside it.
		StoryCellTable.Entry tree = plan.storyCells().get("TEST_TOWN:10:14");
		assertEquals(14, tree.start());
		assertEquals(new StoryCellTable.Closed(14, true, HeightRules.WALL, tree.closed().art()), tree.closed());

		// The diagnostics carry the symbols; the report's counts point at them.
		JsonObject diagnostics = ProtoJson.parseObject(BuildDiagnostics.toText(warnings, plan.mapsUnplaced()).trim());
		assertEquals(1, diagnostics.getAsJsonObject("warnings").get("swap_block_mismatch").getAsInt());
		JsonObject row = diagnostics.getAsJsonObject("details").getAsJsonArray("swap_block_mismatch").get(0).getAsJsonObject();
		assertEquals(List.of("kind", "map", "site", "source"), row.keySet().stream().sorted().toList(), "no coordinates or block ids");
		assertEquals(0, diagnostics.get("maps_unplaced").getAsInt());
	}

	@Test
	void aChainDeeperThanTheWorldSpillsUnderTheVoidFill() throws Exception {
		ExportBundle synthetic = ExportBundle.open(TestData.syntheticBundle());
		// A third floor under the second, in a world with room for two layers only (min Y 0).
		JsonObject third = synthetic.json("map", "TEST_HOUSE_2F").deepCopy();
		third.addProperty("id", "TEST_HOUSE_3F");
		third.addProperty("index", 39);
		JsonObject parent = new JsonObject();
		parent.addProperty("map", "TEST_HOUSE_2F");
		parent.addProperty("warp", 1);
		parent.addProperty("x", 5);
		parent.addProperty("y", 2);
		third.add("parent", parent);
		ExportBundle bundle = ExportBundle.open(TestData.syntheticCopyWith(this.temp, Map.of("map/TEST_HOUSE_3F", third)));
		WorldPlan plan = WorldPlanner.plan(bundle, new WorldPlanner.Options(null, TestData.ORIGIN_X, TestData.ORIGIN_Z, 0));

		assertEquals(2, Scale.maxLayer(0));
		assertEquals(5, plan.maps().size(), "every map placed");
		assertEquals(0, plan.mapsUnplaced());
		assertEquals(0, plan.warnings().count("interior_unplaced"));
		assertEquals(1, plan.warnings().noteCount("interior_deep_spill"));
		assertEquals(Map.of("map", "TEST_HOUSE_3F", "parent", "TEST_HOUSE_2F", "parent_layer", "2"),
				plan.warnings().details("interior_deep_spill").get(0));
		MapPlacement placed = plan.index().map("TEST_HOUSE_3F");
		assertEquals(2, placed.layer(), "the deepest layer");
		MapPlacement town = plan.index().map("TEST_TOWN");
		InteriorPacker.Box box = InteriorPacker.footprint(placed.x(), placed.z(), placed.blockWidth(), placed.blockDepth());
		assertTrue(box.separated(new InteriorPacker.Box(town.x(), town.z(), town.x() + town.blockWidth(), town.z() + town.blockDepth()), 0),
				"not under the town: under the void fill");
		assertEquals(0, plan.overlaps());
	}

	@Test
	void unplacedMapsAreCountedAndNamed() throws Exception {
		ExportBundle bundle = ExportBundle.open(TestData.syntheticBundle());
		WorldPlan houseOnly = WorldPlanner.plan(bundle,
				new WorldPlanner.Options(List.of("TEST_HOUSE"), TestData.ORIGIN_X, TestData.ORIGIN_Z, TestData.MIN_BUILD_HEIGHT));
		assertEquals(1, houseOnly.mapsUnplaced());
		assertEquals(List.of(Map.of("map", "TEST_HOUSE")), houseOnly.warnings().details("maps_unplaced"));
		assertEquals(List.of(Map.of("map", "TEST_HOUSE", "parent", "TEST_TOWN")), houseOnly.warnings().details("interior_parent_not_built"));
		assertFalse(houseOnly.warnings().details().containsKey("swap_block_mismatch"));
	}
}
