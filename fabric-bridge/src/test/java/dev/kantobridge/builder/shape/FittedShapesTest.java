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
	void aShapeInTheTableMakesAUnitWhereNoRuleWould() {
		SceneMap walls = surface("WALLS", "AAAA..", "AAAA..", "......", "......");
		assertTrue(plan(walls).units().isEmpty(), "a repeating wall: no unit by the rules");
		int a = tile('A');
		FittedShapes.useForTest(Map.of(FittedShapes.key(ShapeFixtures.ATLAS, new int[] {a, a, a, a}), new FittedShapes.Entry("can", List.of(3, 0, 8, 0, 0))));
		ShapePlanner.MapShapes shapes = plan(walls);
		assertEquals(2, shapes.units().size(), "a row of jars: every cell the table holds a shape for is a unit");
		assertEquals("fit", shapes.units().get(0).source());
	}

	@Test
	void aVoxelShapeInTheTableTakesARaisedCell() {
		String row = "AAAAAAAAAAAA";
		SceneMap block = surface("BLOCK", row, row, row, row, row, row, row, row, "............", "............");
		int before = plan(block).units().size();
		int a = tile('A');
		byte[] bits = new byte[FittedShapes.VOXEL_BYTES];
		bits[0] = 1;
		FittedShapes.useForTest(Map.of(FittedShapes.key(ShapeFixtures.ATLAS, new int[] {a, a, a, a}), new FittedShapes.Entry("voxels", List.of(), bits)));
		ShapePlanner.MapShapes shapes = plan(block);
		assertTrue(before < 24, "the rules leave part of a 6 x 4 cell block as Tier A columns (" + before + " units)");
		assertEquals(24, shapes.units().size(), "a reviewed voxel shape takes every cell of the block, raised ones too");
	}

	@Test
	void aStackStandsOnTheCellBelow() {
		// a tall object: tile A over tile B, both with voxel shapes, A standing on B
		SceneMap map = surface("STACK", "......", "......", "..AA..", "..AA..", "..BB..", "..BB..", "......", "......");
		int a = tile('A');
		int b = tile('B');
		byte[] bits = new byte[FittedShapes.VOXEL_BYTES];
		bits[0] = 1;
		String top = FittedShapes.key(ShapeFixtures.ATLAS, new int[] {a, a, a, a});
		String bottom = FittedShapes.key(ShapeFixtures.ATLAS, new int[] {b, b, b, b});
		FittedShapes.useStacksForTest(Map.of(top, new FittedShapes.Entry("voxels", List.of(), bits), bottom,
				new FittedShapes.Entry("voxels", List.of(), bits)), Map.of(top, bottom));
		ShapePlanner.MapShapes shapes = plan(map);
		assertEquals(1, shapes.units().size(), "the top unit moved onto the bottom one");
		Units.Cell cell = shapes.units().get(0);
		int per = map.perCell();
		int i = (cell.cy() * per) * map.artCols() + cell.cx() * per;
		assertEquals(2 * per, shapes.blocks()[i], "the bottom cell is two cells tall");
	}

	@Test
	void aSeatStandsOnAWalkableCell() {
		// a stool the hidden game draws on a walkable cell (lower case: walkable), its voxel entry marked a seat
		SceneMap map = surface("SEAT", "......", "......", "..ss..", "..ss..", "......", "......");
		int s = tile('s');
		byte[] bits = new byte[FittedShapes.VOXEL_BYTES];
		bits[0] = 1;
		String seat = FittedShapes.key(ShapeFixtures.ATLAS, new int[] {s, s, s, s});
		FittedShapes.useSeatsForTest(Map.of(seat, new FittedShapes.Entry("voxels", List.of(), bits)), java.util.Set.of(seat));
		ShapePlanner.MapShapes shapes = plan(map);
		assertEquals(1, shapes.units().size(), "a unit on the walkable cell");
		Units.Cell cell = shapes.units().get(0);
		assertEquals("seat", cell.source());
		int per = map.perCell();
		int i = (cell.cy() * per) * map.artCols() + cell.cx() * per;
		assertEquals(ShapePlanner.SEAT | 1, shapes.decor()[i], "a seat collider at feet level, no other decor");
		assertEquals(0, shapes.blocks()[i], "no solid blocks");
	}

	@Test
	void aColumnsFitKeepsNoUnit() {
		SceneMap map = surface("LONE", LONE.split("\n"));
		FittedShapes.useForTest(Map.of(lone(), new FittedShapes.Entry("columns", List.of())));
		assertTrue(plan(map).units().isEmpty());
	}
}
