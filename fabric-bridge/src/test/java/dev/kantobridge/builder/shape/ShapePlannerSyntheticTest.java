package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.builder.plan.WorldPlanner;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.ShapeMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The planner on the synthetic bundle (docs/specs/VOXEL_BLOCKS_PLAN.md §6.1): a golden shape index and shape map hash;
 * the map bound to the build's WorldIndex; both swap states planned; equal results across two runs, a reversed map order
 * and a filtered build's index (I7).
 */
class ShapePlannerSyntheticTest {
	private static ExportBundle bundle;
	private static WorldPlan plan;

	@BeforeAll
	static void plan() throws Exception {
		bundle = ExportBundle.open(TestData.syntheticBundle());
		plan = WorldPlanner.plan(bundle, new WorldPlanner.Options(null, TestData.ORIGIN_X, TestData.ORIGIN_Z, TestData.MIN_BUILD_HEIGHT));
	}

	@Test
	void theShapesAreTheGolden() throws Exception {
		ShapePlan shapes = plan.shapes();
		assertNotNull(shapes);
		JsonObject golden = new JsonObject();
		golden.add("index", ProtoJson.parse(shapes.index().toJson()));
		golden.addProperty("indexHash", shapes.index().hash());
		golden.addProperty("mapHash", shapes.map().hash());
		golden.addProperty("shapedBlocks", shapes.shapedBlocks());
		TestData.assertGolden(TestData.goldenShapes(), ProtoJson.write(golden), "shapes");
		assertEquals("kb-shapes/3", shapes.index().planner());
		assertEquals(2, shapes.keysByKind().get(ShapeKey.Kind.TUFT), "the grass and the flower");
		assertEquals(5 * 8, shapes.keysByKind().get(ShapeKey.Kind.CELL),
				"five units of eight blocks: the tree wall (with and without walkable ground south), the town's post, the cut tree, the hill's sign");
		assertTrue(shapes.index().size() > 0);
		assertTrue(shapes.keysByKind().get(ShapeKey.Kind.ROOF) > 0, "the hill's house has its roof");
		assertEquals(1, shapes.keysByKind().get(ShapeKey.Kind.SLAB), "the town's ledge lip, one tile");
		assertEquals(shapes.index().size(), shapes.index().geoms().size(), "one palette: one shape per key");
	}

	@Test
	void theMapIsBoundToThisBuildsIndex() {
		ShapeMap map = plan.shapes().map();
		assertEquals(dev.kantobridge.world.Hashes.sha256(plan.index().toJson()), map.indexHash());
		assertEquals(plan.shapes().index().hash(), map.shapeHash());
		assertEquals(3, map.grids().size(), "the surface and the two interiors");
		assertEquals(plan.shapes().shapedBlocks(), map.shapedBlocks());
	}

	@Test
	void bothSwapStatesArePlanned() {
		ShapePlanner.MapShapes town = plan.shapes().shapes("TEST_TOWN");
		assertNotNull(town);
		assertEquals(town.open().length, town.closed().length);
		// Open, the cut tree's cell is ground: no shape. Closed, its tree is a unit (V4): a cut tree is foliage.
		assertEquals(null, town.open()[28 * 40 + 20]);
		ShapeKey[] closed = town.closed()[28 * 40 + 20];
		assertNotNull(closed);
		assertEquals(2, closed.length, "two blocks high");
		assertEquals(ShapeKey.Kind.CELL, closed[0].kind());
		assertTrue(closed[0].variant().endsWith("t"), "foliage: " + closed[0].variant());
		for (String id : plan.shapes().maps().keySet()) {
			assertNotNull(plan.shapes().shapes(id), id);
		}
	}

	@Test
	void runsFilteredBuildsAndMapOrderGiveTheSameShapes() throws Exception {
		WorldPlan again = WorldPlanner.plan(ExportBundle.open(TestData.syntheticBundle()),
				new WorldPlanner.Options(null, TestData.ORIGIN_X, TestData.ORIGIN_Z, TestData.MIN_BUILD_HEIGHT));
		assertEquals(plan.shapes().index().hash(), again.shapes().index().hash());
		assertEquals(plan.shapes().map().hash(), again.shapes().map().hash());

		WorldPlan filtered = WorldPlanner.plan(bundle, new WorldPlanner.Options(List.of("TEST_TOWN"), TestData.ORIGIN_X, TestData.ORIGIN_Z,
				TestData.MIN_BUILD_HEIGHT));
		assertEquals(plan.shapes().index().hash(), filtered.shapes().index().hash(), "keys come from the whole export");
		assertNotEquals(plan.shapes().map().hash(), filtered.shapes().map().hash());

		List<MapPlacement> placements = new ArrayList<>(plan.index().maps().values());
		ShapePlan forward = ShapePlanner.plan(plan.scene(), placements, ShapeParams.v2());
		Collections.reverse(placements);
		ShapePlan backward = ShapePlanner.plan(plan.scene(), placements, ShapeParams.v2());
		assertEquals(forward.index().hash(), backward.index().hash());
		assertEquals(forward.map().hash(), backward.map().hash());
		assertEquals(plan.shapes().map().grids().size(), backward.map().grids().size());
		ShapePlan tierB = ShapePlanner.plan(plan.scene(), placements, ShapeParams.v4());
		Collections.reverse(placements);
		ShapePlan tierBForward = ShapePlanner.plan(plan.scene(), placements, ShapeParams.v4());
		assertEquals(tierB.index().hash(), tierBForward.index().hash(), "Tier B keys don't depend on the map order");
		assertEquals(tierB.map().hash(), tierBForward.map().hash());
	}
}
