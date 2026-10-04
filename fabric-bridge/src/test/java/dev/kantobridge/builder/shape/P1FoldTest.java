package dev.kantobridge.builder.shape;

import static dev.kantobridge.builder.shape.ShapeFixtures.interior;
import static dev.kantobridge.builder.shape.ShapeFixtures.scene;
import static dev.kantobridge.builder.shape.ShapeFixtures.surface;
import static dev.kantobridge.builder.shape.ShapeFixtures.surfaceAt;
import static dev.kantobridge.builder.shape.ShapeFixtures.tile;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.shape.geom.ShapeTable;
import dev.kantobridge.scene.ArtSource;
import dev.kantobridge.scene.SceneMap;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Two things the P1 planner changes besides units (docs/specs/PROFILE_PLAN.md, its deviations): a counter folds as a
 * run one block high (its panel on the front, the row above on its top), and the self-check lets a ledge lip lift its
 * art by its own height. Made-up maps.
 */
class P1FoldTest {
	@Test
	void aCounterFoldsItsPanelToTheFrontAndTheRowAboveOnTop() {
		SceneMap room = interior("SHOP",
				"......",
				"......",
				"..ab..",
				"..==..",
				"......",
				"......");
		ShapePlanner.MapShapes p1 = ShapePlanner.planMap(room, ShapeFixtures.TIER_B, 0, ShapeParams.p1(), null, null, new TreeMap<>());
		int cols = room.artCols();
		ShapeKey front = p1.open()[3 * cols + 2][0];
		assertEquals(ShapeKey.cube(0, tile('a'), tile('a'), tile('='), tile('='), tile('='), tile('=')), front,
				"the panel ('=') stands on the front and the sides; its top shows the counter drawn above it ('a')");
		assertArrayEquals(new ShapeKey[] {ShapeKey.cube(0, tile('a'), tile('a'), tile('='), tile('='), tile('='), tile('='))}, p1.open()[2 * cols + 2],
				"the back row's top is its own tile; its sides fold the panel");
		assertEquals(1, p1.blocks()[3 * cols + 2], "one block high, as before");
		ShapePlanner.MapShapes v7 = ShapePlanner.planMap(room, ShapeFixtures.TIER_B, 0, ShapeParams.v7());
		assertNull(v7.open()[3 * cols + 2], "before P1 a plain cube of its own tile");
	}

	@Test
	void aLedgeLipMayShowItsArtLiftedByItsOwnHeight() {
		SceneMap map = surface("LEDGES",
				"ab..",
				">>..",
				"....",
				"....");
		ShapePlan plan = ShapePlanner.plan(scene(map), List.of(surfaceAt(map, 0, 0)), ShapeParams.p1());
		ShapeTable table = ShapeTable.build(plan.index(), ArtSource.NONE);
		ShapePlanner.MapShapes shapes = plan.shapes(map.id());
		PlannedGeometry geometry = new PlannedGeometry(map, shapes, plan.index(), table);
		SelfCheck.Scores strict = SelfCheck.score(map.id(), new Reference(map, ShapeFixtures.ART_PX, shapes, table), map.atlas(), geometry,
				ShapeFixtures.ART_PX);
		int lift = ShapeParams.p1().ledgeLip() * ShapeFixtures.ART_PX / 16;
		SelfCheck.Scores lifted = SelfCheck.score(map.id(), new Reference(map, ShapeFixtures.ART_PX, shapes, table, lift), map.atlas(), geometry,
				ShapeFixtures.ART_PX);
		assertEquals(2, lift, "four sixteenths of a block: two Game Boy pixels");
		assertTrue(strict.structureMatch() < 0.5, "texel for texel, a lifted lip mostly misses: " + strict.structureMatch());
		assertEquals(1.0, lifted.structureMatch(), "allowing the lip its own lift, every ledge pixel matches");
		assertEquals(strict.holes(), lifted.holes());
	}
}
