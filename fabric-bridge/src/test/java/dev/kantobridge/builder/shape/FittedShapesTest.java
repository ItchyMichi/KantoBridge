package dev.kantobridge.builder.shape;

import static dev.kantobridge.builder.shape.ShapeFixtures.surface;
import static dev.kantobridge.builder.shape.ShapeFixtures.tile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.scene.SceneMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The fitted shapes table (docs/specs/FITTED_SHAPES.md): its file's entries (unknown classes left out, parameters
 * clamped), one table per game, and the planner's use of it: a fit re-picks a unit's class and parameters in its
 * key, {@code columns} keeps no unit, a building's fitted roof rows travel in its template entry, and a turned-off table
 * leaves the planner's own pick. Pixel-free.
 */
class FittedShapesTest {
	private static final String LONE = "......\n......\n..AB..\n..CD..\n......\n......";

	@AfterEach
	void reset() {
		FittedShapes.useForTest(null);
		FittedShapes.useTablesForTest(null);
		FittedShapes.setEnabled(true);
	}

	private static ShapePlanner.MapShapes plan(SceneMap map) {
		return ShapePlanner.planMap(map, ShapeFixtures.TIER_B, 0, ShapeParams.v4());
	}

	private static String lone() {
		return FittedShapes.key(ShapeFixtures.ATLAS, new int[] {tile('A'), tile('B'), tile('C'), tile('D')});
	}

	@Test
	void theFileKeepsKnownClassesAndClampsParameters() {
		Map<String, FittedShapes.Entry> table = new TreeMap<>();
		FittedShapes.parse("""
				{"kb_fitted_shapes": 1, "cells": {
				  "a/1-2-3-4": {"class": "can", "k": [3, 0, 120, -4, 2], "iou": 0.7},
				  "a/5-6-7-8": {"class": "castle"},
				  "a/9-9-9-9": {"class": "columns"},
				  "a/0-0-0-0": {"k": [1]}
				}}""", table);
		assertEquals(Map.of("a/1-2-3-4", new FittedShapes.Entry("can", List.of(3, 0, 99, 0, 2)), "a/9-9-9-9",
				new FittedShapes.Entry("columns", List.of())), table);
	}

	@Test
	void eachGameReadsItsOwnTable() {
		FittedShapes.Entry can = new FittedShapes.Entry("can", List.of(1, 2, 3, 4, 5));
		FittedShapes.useTablesForTest(Map.of("emerald", new FittedShapes.Table(Map.of("g3q/1-2-3-4", can), Map.of()),
				"blue", new FittedShapes.Table(Map.of("tilesets.x/1-2-3-4", can), Map.of())));
		int[] tiles = {1, 2, 3, 4};
		assertEquals(null, FittedShapes.get("g3q", tiles), "outside a scene: no table");
		String before = FittedShapes.enter("crystal");
		try {
			assertEquals(null, FittedShapes.get("g3q", tiles), "the same atlas name in another game: not its entry");
			FittedShapes.enter("emerald");
			assertEquals(can, FittedShapes.get("g3q", tiles));
			FittedShapes.enter("red");
			assertEquals(can, FittedShapes.get("tilesets.x", tiles), "Red reads Blue's");
		} finally {
			FittedShapes.leave(before);
		}
	}

	@Test
	void aFitRepicksTheUnitInItsKey() {
		SceneMap map = surface("LONE", LONE.split("\n"));
		assertEquals("g2", plan(map).units().get(0).key().variant(), "the planner's own pick");
		FittedShapes.useForTest(Map.of(lone(), new FittedShapes.Entry("can", List.of(3, 0, 8, 4, 2))));
		ShapePlanner.MapShapes shapes = plan(map);
		assertEquals("g2_can_k0300080402", shapes.units().get(0).key().variant());
		assertEquals("fit", shapes.units().get(0).source());
		FittedShapes.setEnabled(false);
		assertEquals("g2", plan(map).units().get(0).key().variant(), "turned off: the planner's own pick");
	}

	@Test
	void aBuildingsFittedRoofTravelsInItsEntry() {
		SceneMap town = surface("TOWN", TemplatesTest.TOWN);
		Templates.Placed own = TemplatesTest.plan(town, ShapeParams.v7()).templates().get(0);
		assertTrue(!own.entry().has("roofRows"), "no table: no roof rows");
		FittedShapes.useForTest(Map.of(), Map.of(ShapeFixtures.ATLAS + "/" + own.sig(), 3));
		Templates.Placed fitted = TemplatesTest.plan(town, ShapeParams.v7()).templates().get(0);
		assertEquals(3, fitted.entry().get("roofRows").getAsInt());
		JsonObject json = fitted.entry().deepCopy();
		json.addProperty("sig", fitted.sig());
		assertEquals(3, Templates.Entry.of(json).roofRows(), "read back by every client");
		assertTrue(!fitted.sig().equals(own.sig()), "another entry, another signature");
	}

	@Test
	void aColumnsFitKeepsNoUnit() {
		SceneMap map = surface("LONE", LONE.split("\n"));
		FittedShapes.useForTest(Map.of(lone(), new FittedShapes.Entry("columns", List.of())));
		assertTrue(plan(map).units().isEmpty());
	}
}
