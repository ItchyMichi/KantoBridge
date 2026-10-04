package dev.kantobridge.builder.shape;

import static dev.kantobridge.builder.shape.ShapeFixtures.surface;
import static dev.kantobridge.builder.shape.ShapeFixtures.tile;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.ShapeIndex;
import dev.kantobridge.world.ShapeMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Closed story art as overlays of the shape map (docs/specs/VOXEL_BLOCKS_PLAN.md §4.3, §5.9, V5): each tile story cell
 * gets its closed columns bottom-up from feet level (a unit's blocks, explicit cubes where the closed column is plain, a
 * slab 1/16 high for walkable art), keyed by the story cell; its keys come from the whole export.
 */
class ClosedOverlayTest {
	/** Open, a walkable town; closed: a cut tree (cell 1,0), a two-cell gate (2,0 and 3,0) and walkable art (0,1). */
	static SceneMap town() {
		SceneMap map = surface("TOWN",
				"........",
				"........",
				"........",
				"........");
		map = ShapeFixtures.withSite(map, "cut", 1, 0, "TT", "TT");
		map = ShapeFixtures.withSite(map, "gate", 2, 0, "AA", "AA");
		map = ShapeFixtures.withSite(map, "gate", 3, 0, "AA", "AA");
		return ShapeFixtures.withSite(map, "script", 0, 1, "bb", "bb");
	}

	static final List<Occupancy.TileCell> CELLS = List.of(new Occupancy.TileCell("TOWN:1:0", "TOWN", 1, 0),
			new Occupancy.TileCell("TOWN:2:0", "TOWN", 2, 0), new Occupancy.TileCell("TOWN:3:0", "TOWN", 3, 0),
			new Occupancy.TileCell("TOWN:0:1", "TOWN", 0, 1));

	private static ShapePlan plan(List<Occupancy.TileCell> cells) {
		SceneMap map = town();
		MapPlacement placement = ShapeFixtures.surfaceAt(map, 10, 20);
		return ShapePlanner.plan(ShapeFixtures.scene(ShapeFixtures.TIER_B, map), List.of(placement), ShapeParams.v5(),
				new Occupancy(Map.of(), null, cells));
	}

	private static ShapeMap.Overlay overlay(ShapePlan plan, String key) {
		return plan.map().overlays().stream().filter(o -> o.key().equals(key)).findFirst().orElseThrow();
	}

	@Test
	void everyTileCellGetsItsClosedColumns() {
		ShapePlan plan = plan(CELLS);
		ShapeIndex index = plan.index();
		assertEquals(List.of("TOWN:0:1", "TOWN:1:0", "TOWN:2:0", "TOWN:3:0"), plan.map().overlays().stream().map(ShapeMap.Overlay::key).toList(),
				"sorted by key");
		ShapeMap.Overlay tree = overlay(plan, "TOWN:1:0");
		assertEquals(10 + 2, tree.x(), "the cell's north-west column");
		assertEquals(ShapeFixtures.SURFACE_FEET, tree.y(), "feet level");
		assertEquals(20, tree.z());
		for (int[] column : tree.columns()) {
			assertEquals(2, column.length, "a unit stands two blocks high");
			for (int id : column) {
				ShapeKey key = index.key(id);
				assertEquals(ShapeKey.Kind.CELL, key.kind());
				assertTrue(key.variant().endsWith("t"), "foliage");
			}
		}
		assertArrayEquals(new int[] {2, 2, 2, 2}, plan.closed("TOWN:1:0").heights());
		assertTrue(plan.closed("TOWN:1:0").leaves(), "a cut tree's carriers above feet level are decor_leaves");
	}

	@Test
	void aPlainClosedColumnIsMadeExplicit() {
		ShapePlan plan = plan(CELLS);
		int a = tile('A');
		for (int[] column : overlay(plan, "TOWN:2:0").columns()) {
			assertEquals(2, column.length);
			for (int id : column) {
				assertEquals(ShapeKey.cube(0, a, a, a, a, a, a), plan.index().key(id), "its carriers have no cube of their own to fall back to");
			}
		}
		assertFalse(plan.closed("TOWN:2:0").leaves());
	}

	@Test
	void walkableClosedArtIsAThinSlab() {
		ShapePlan plan = plan(CELLS);
		for (int[] column : overlay(plan, "TOWN:0:1").columns()) {
			assertArrayEquals(new int[] {plan.index().shapeId(ShapeKey.slab(0, tile('b'), 1), ShapeFixtures.PALETTE)}, column);
		}
		assertArrayEquals(new int[] {1, 1, 1, 1}, plan.closed("TOWN:0:1").heights());
	}

	@Test
	void plannersBeforeV5HaveNoOverlays() {
		SceneMap map = town();
		ShapePlan v4 = ShapePlanner.plan(ShapeFixtures.scene(ShapeFixtures.TIER_B, map), List.of(ShapeFixtures.surfaceAt(map, 10, 20)),
				ShapeParams.v4(), new Occupancy(Map.of(), null, CELLS));
		assertEquals(List.of(), v4.map().overlays(), "a planner version keeps planning what it always did");
		assertFalse(v4.index().toJson().contains("storyArt"));
		assertTrue(plan(CELLS).index().toJson().contains("\"storyArt\":true"), "the parameter is in the hashed index");
	}

	@Test
	void overlayKeysComeFromTheWholeExport() {
		ShapePlan with = plan(CELLS);
		ShapePlan without = plan(List.of());
		assertEquals(with.index().hash(), without.index().hash(), "which cells are story cells changes positions, never keys");
		assertEquals(List.of(), without.map().overlays());
		assertEquals(Map.of(), without.closedColumns());
	}
}
