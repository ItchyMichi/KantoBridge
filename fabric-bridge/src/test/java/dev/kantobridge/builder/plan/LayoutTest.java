package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportLayout;
import dev.kantobridge.builder.export.ExportMap;
import dev.kantobridge.builder.export.ExportMap.Connection;
import dev.kantobridge.builder.export.ExportMap.Direction;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** The surface layout over a made-up connection graph: positions, edge agreement, export cross-check, overlaps. */
class LayoutTest {
	private static Map<Direction, Connection> links(Object... pairs) {
		Map<Direction, Connection> out = new EnumMap<>(Direction.class);
		for (int i = 0; i < pairs.length; i += 3) {
			out.put((Direction) pairs[i], new Connection((String) pairs[i + 1], (Integer) pairs[i + 2]));
		}
		return out;
	}

	/**
	 * ROOT 10×9 at (0,0); NORTH 10×18 above it; WEST 5×9 left of it, 2 blocks lower; EAST 10×9 right of it, whose
	 * connection back is off by one; SOUTH 10×9 below it; SOUTH2 right of SOUTH and 5 blocks up, overlapping EAST.
	 */
	private static Map<String, ExportMap> graph() {
		Map<String, ExportMap> maps = new LinkedHashMap<>();
		maps.put("ROOT", TestData.map("ROOT", 10, 9, true, links(Direction.NORTH, "NORTH", 0, Direction.EAST, "EAST", 0,
				Direction.WEST, "WEST", 2, Direction.SOUTH, "SOUTH", 0)));
		maps.put("NORTH", TestData.map("NORTH", 10, 18, true, links(Direction.SOUTH, "ROOT", 0)));
		maps.put("EAST", TestData.map("EAST", 10, 9, true, links(Direction.WEST, "ROOT", 1)));
		maps.put("WEST", TestData.map("WEST", 5, 9, true, links(Direction.EAST, "ROOT", -2)));
		maps.put("SOUTH", TestData.map("SOUTH", 10, 9, true, links(Direction.NORTH, "ROOT", 0, Direction.EAST, "SOUTH2", -5)));
		maps.put("SOUTH2", TestData.map("SOUTH2", 10, 9, true, links(Direction.WEST, "SOUTH", 5)));
		maps.put("ISLAND", TestData.map("ISLAND", 4, 4, true, links()));
		return maps;
	}

	private static ExportLayout exported(boolean wrongNorth) {
		Map<String, ExportLayout.Rect> rects = new TreeMap<>();
		rects.put("ROOT", new ExportLayout.Rect(0, 0, 10, 9));
		rects.put("NORTH", new ExportLayout.Rect(0, wrongNorth ? -17 : -18, 10, 18));
		rects.put("EAST", new ExportLayout.Rect(10, 0, 10, 9));
		rects.put("WEST", new ExportLayout.Rect(-5, 2, 5, 9));
		rects.put("SOUTH", new ExportLayout.Rect(0, 9, 10, 9));
		rects.put("SOUTH2", new ExportLayout.Rect(10, 4, 10, 9));
		rects.put("ISLAND", new ExportLayout.Rect(50, 50, 4, 4));
		return new ExportLayout("ROOT", rects);
	}

	@Test
	void breadthFirstPositionsFollowTheEngineArithmetic() {
		Warnings warnings = new Warnings();
		Layout.Result result = Layout.compute(graph(), exported(false), "ROOT", Set.of("ROOT", "NORTH", "WEST", "SOUTH"), warnings);
		assertEquals(new Layout.Origin(0, 0), result.origins().get("ROOT"));
		assertEquals(new Layout.Origin(0, -18), result.origins().get("NORTH"));
		assertEquals(new Layout.Origin(10, 0), result.origins().get("EAST"));
		assertEquals(new Layout.Origin(-5, 2), result.origins().get("WEST"));
		assertEquals(new Layout.Origin(0, 9), result.origins().get("SOUTH"));
		assertEquals(new Layout.Origin(10, 4), result.origins().get("SOUTH2"));
		assertFalse(result.origins().containsKey("ISLAND"), "an unconnected map is not reached");
		// Only built maps are checked: EAST's bad edge and SOUTH2's overlap don't count here.
		assertEquals(0, result.edgeMismatches());
		assertEquals(0, result.overlaps());
		assertEquals(0, result.exportMismatches());
		assertEquals(0, warnings.total(), warnings.warnings().toString());
	}

	@Test
	void mismatchedEdgesAndOverlapsAreCounted() {
		Warnings warnings = new Warnings();
		Map<String, ExportMap> maps = graph();
		maps.remove("ISLAND");
		Layout.Result result = Layout.compute(maps, exported(false), "ROOT", maps.keySet(), warnings);
		assertEquals(1, result.edgeMismatches(), "EAST's way back to ROOT is off by one block");
		assertEquals(1, result.overlaps(), "SOUTH2 overlaps EAST");
		assertEquals(1, warnings.count("layout_edge_mismatch"));
		assertEquals(1, warnings.count("layout_overlap"));
	}

	@Test
	void exportedLayoutIsCrossChecked() {
		Warnings warnings = new Warnings();
		Layout.Result result = Layout.compute(graph(), exported(true), "ROOT", Set.of("ROOT", "NORTH"), warnings);
		assertEquals(1, result.exportMismatches());
		assertEquals(1, warnings.count("layout_export_mismatch"));
		// The computed position wins over the exported one.
		assertEquals(new Layout.Origin(0, -18), result.origins().get("NORTH"));
	}

	@Test
	void unreachedMapsFallBackToTheExportedLayout() {
		Warnings warnings = new Warnings();
		Layout.Result result = Layout.compute(graph(), exported(false), "ROOT", Set.of("ROOT", "ISLAND"), warnings);
		assertEquals(new Layout.Origin(50, 50), result.origins().get("ISLAND"));
		assertEquals(1, warnings.count("layout_unreached"));

		Warnings none = new Warnings();
		Layout.Result unplaced = Layout.compute(graph(), null, "ROOT", Set.of("ISLAND"), none);
		assertFalse(unplaced.origins().containsKey("ISLAND"));
		assertEquals(1, none.count("layout_unplaced"));
		assertEquals(1, none.count("layout_export_missing"));
	}

	@Test
	void aMissingRootIsReported() {
		Warnings warnings = new Warnings();
		Layout.Result result = Layout.compute(graph(), null, "NOWHERE", Set.of("ROOT"), warnings);
		assertTrue(result.origins().isEmpty());
		assertEquals(1, warnings.count("layout_root_missing"));
	}
}
