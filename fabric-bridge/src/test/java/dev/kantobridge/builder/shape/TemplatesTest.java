package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.builder.plan.HeightRules;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.world.ShapeIndex;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tier C planning (docs/specs/VOXEL_BLOCKS_PLAN.md §5.4 stage 4, V7): a bounded building a door folds into becomes a
 * template over its Tier A columns: every block of that volume a {@code tmpl} key on a {@code shape_full} carrier, its
 * entry (tile grid, door cells, ground, outdoor flag, and the Tier A keys as its fallback) in the shape index under the
 * hash of its content. Identical buildings share one; anything else stays Tier A.
 */
class TemplatesTest {
	/** A house: two roof rows over two facade rows, its door cell south of the middle. */
	static final String[] TOWN = {
			"..ABBBBC........",
			"..DEEEEF........",
			"..GHIJKL........",
			"..MNOPQR........",
			"....++..........",
			"....++..........",
			"................",
			"................"};

	static ShapePlanner.MapShapes plan(SceneMap map, ShapeParams params) {
		return ShapePlanner.planMap(map, ShapeFixtures.TIER_B, 0, params);
	}

	@Test
	void aBuildingWithADoorBecomesATemplateOverItsTierAColumns() {
		SceneMap town = ShapeFixtures.surface("TOWN", TOWN);
		ShapePlanner.MapShapes tierA = plan(town, ShapeParams.v5());
		ShapePlanner.MapShapes planned = plan(town, ShapeParams.v7());
		assertEquals(1, planned.templates().size());
		Templates.Placed placed = planned.templates().get(0);
		assertEquals(2, placed.x0());
		assertEquals(0, placed.y0());
		JsonObject json = placed.entry().deepCopy();
		json.addProperty("sig", placed.sig());
		Templates.Entry entry = Templates.Entry.of(json);
		assertEquals(6, entry.w(), "its cell-aligned box");
		assertEquals(6, entry.d(), "one cell row more for the door");
		assertTrue(entry.door(1, 2));
		assertEquals(ShapeFixtures.tile('+'), entry.tile(2, 4), "the door's art is part of the drawing");
		assertEquals(-1, entry.tile(0, 4), "beside the door: not the structure");
		int cols = town.artCols();
		for (int ty = 0; ty < 4; ty++) {
			for (int tx = 2; tx < 8; tx++) {
				int i = ty * cols + tx;
				assertEquals(tierA.blocks()[i], planned.blocks()[i], "the volume stays Tier A's");
				assertEquals((1 << planned.blocks()[i]) - 1, planned.shapeMask()[i], "every block a shape_full carrier");
				ShapeKey[] column = planned.open()[i];
				assertEquals(planned.blocks()[i], column.length);
				for (int k = 0; k < column.length; k++) {
					assertEquals(ShapeKey.tmpl(0, placed.sig(), tx - 2, k, ty), column[k]);
					ShapeKey fallback = entry.columns()[ty * entry.w() + tx - 2][k];
					ShapeKey before = tierA.open()[i] == null ? null : tierA.open()[i][k];
					assertEquals(before != null ? before : ShapeKey.cube(0, town.tile(tx, ty), town.tile(tx, ty), town.tile(tx, ty), town.tile(tx, ty),
							town.tile(tx, ty), town.tile(tx, ty)), fallback, "the fallback is the Tier A key (a plain one made explicit)");
				}
			}
		}
		assertEquals(3, planned.blocks()[4], "two bands and the roof");
		assertEquals(ShapeKey.Kind.ROOF, entry.columns()[0][2].kind());
	}

	@Test
	void identicalBuildingsShareOneTemplate() {
		SceneMap town = ShapeFixtures.surface("TOWN",
				"ABBBBC..ABBBBC..",
				"DEEEEF..DEEEEF..",
				"GHIJKL..GHIJKL..",
				"MNOPQR..MNOPQR..",
				"..++......++....",
				"..++......++....");
		ShapePlanner.MapShapes planned = plan(town, ShapeParams.v7());
		assertEquals(2, planned.templates().size());
		assertEquals(planned.templates().get(0).sig(), planned.templates().get(1).sig());
		ShapePlan plan = ShapePlanner.plan(ShapeFixtures.scene(ShapeFixtures.TIER_B, town), List.of(), ShapeParams.v7());
		assertEquals(1, plan.index().templates().size(), "one entry in the index");
		ShapeIndex index = plan.index();
		assertEquals(Templates.signature(index.templates().get(0).getAsJsonObject()), index.templates().get(0).getAsJsonObject().get("sig")
				.getAsString(), "the signature is the hash of the entry");
	}

	@Test
	void whatIsNoBuildingStaysTierA() {
		// No door: a structure, not a building.
		SceneMap noDoor = ShapeFixtures.surface("TOWN",
				"..ABBBBC........",
				"..DEEEEF........",
				"..GHIJKL........",
				"..MNOPQR........");
		assertTrue(plan(noDoor, ShapeParams.v7()).templates().isEmpty());
		// Too big for the template size.
		ShapeParams small = new ShapeParams("kb-shapes/5", ShapeParams.MEASURED, HeightRules.MAX_UNIT, HeightRules.MIN_REPEAT_ROWS, 2,
				HeightRules.INTERIOR_WALL, true, true, 16, 4, true, true, 4);
		assertTrue(plan(ShapeFixtures.surface("TOWN", TOWN), small).templates().isEmpty());
		// Touching a swap site (a gate in the facade).
		SceneMap gated = ShapeFixtures.withSite(ShapeFixtures.surface("TOWN", TOWN), "script", 1, 1, "..", "..");
		assertTrue(plan(gated, ShapeParams.v7()).templates().isEmpty());
		// Indoors.
		assertTrue(plan(ShapeFixtures.interior("ROOM", TOWN), ShapeParams.v7()).templates().isEmpty());
		// Before V7.
		assertTrue(plan(ShapeFixtures.surface("TOWN", TOWN), ShapeParams.v5()).templates().isEmpty());
		for (ShapeKey[] column : plan(noDoor, ShapeParams.v7()).open()) {
			for (int k = 0; column != null && k < column.length; k++) {
				assertTrue(column[k] == null || column[k].kind() != ShapeKey.Kind.TMPL);
			}
		}
	}

	@Test
	void anEntryMustMatchItsSignature() {
		Templates.Placed placed = plan(ShapeFixtures.surface("TOWN", TOWN), ShapeParams.v7()).templates().get(0);
		JsonObject json = placed.entry().deepCopy();
		json.addProperty("sig", placed.sig());
		assertEquals(placed.sig(), Templates.Entry.of(json).sig());
		JsonObject tampered = json.deepCopy();
		tampered.addProperty("ground", 7);
		assertThrows(IllegalArgumentException.class, () -> Templates.Entry.of(tampered));
		assertArrayEquals(Templates.Entry.of(json).tiles(), Templates.Entry.of(json.deepCopy()).tiles());
	}
}
