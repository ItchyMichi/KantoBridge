package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.plan.BlockCode;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.builder.plan.WorldPlanner;
import dev.kantobridge.builder.shape.geom.ShapeTable;
import dev.kantobridge.scene.ArtSource;
import dev.kantobridge.world.ShapeIndex;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Counting quads as the client draws them (docs/specs/VOXEL_BLOCKS_PLAN.md §3.8): opaque neighbours hide faces, shaped
 * neighbours hide what their face masks cover, carriers without a shape draw their cube or nothing; the synthetic world
 * stays far inside the budgets.
 */
class QuadCounterTest {
	/** A few planned blocks and shape ids; air elsewhere. */
	static final class Blocks implements QuadCounter.World {
		final Map<Long, Integer> codes = new HashMap<>();
		final Map<Long, Integer> shapes = new HashMap<>();

		static long key(int x, int y, int z) {
			return ((long) x & 0xFFFFF) << 40 | ((long) y & 0xFFFFF) << 20 | ((long) z & 0xFFFFF);
		}

		Blocks put(int x, int y, int z, int code, int shape) {
			this.codes.put(key(x, y, z), code);
			if (shape != 0) {
				this.shapes.put(key(x, y, z), shape);
			}
			return this;
		}

		@Override
		public int code(int x, int y, int z) {
			return this.codes.getOrDefault(key(x, y, z), BlockCode.AIR);
		}

		@Override
		public int shapeId(int x, int y, int z) {
			return this.shapes.getOrDefault(key(x, y, z), 0);
		}
	}

	private static ShapeTable table(ShapeIndex index) {
		return ShapeTable.build(index, ArtSource.NONE);
	}

	@Test
	void plainBlocksDrawTheFacesNothingHides() {
		ShapeTable none = table(ShapeIndex.of("kb-shapes/2", ShapeParams.v3().toJson(), List.of("a"), List.of("P"), List.of(), new JsonArray()));
		Blocks world = new Blocks().put(0, 0, 0, BlockCode.full(1), 0);
		assertEquals(6, QuadCounter.quadsAt(world, none, 0, 0, 0), "alone: six faces");
		world.put(0, -1, 0, BlockCode.full(2), 0).put(1, 0, 0, BlockCode.shape(3), 0);
		assertEquals(5, QuadCounter.quadsAt(world, none, 0, 0, 0), "the floor hides its bottom; a shape tile hides nothing");
		assertEquals(5, QuadCounter.quadsAt(world, none, 1, 0, 0), "a shape tile without a shape is a cube; the tile beside hides one face");
		world.put(0, 1, 0, BlockCode.half(4), 0);
		assertEquals(4, QuadCounter.quadsAt(world, none, 0, 0, 0), "water above hides the top");
		assertEquals(5, QuadCounter.quadsAt(world, none, 0, 1, 0), "the half block's own top is never culled; the tile below hides its bottom");
		assertEquals(0, QuadCounter.quadsAt(new Blocks().put(0, 0, 0, BlockCode.ledge(0), 0), none, 0, 0, 0), "a ledge without a lip");
		assertEquals(0, QuadCounter.quadsAt(new Blocks().put(0, 0, 0, BlockCode.story(0), 0), none, 0, 0, 0));
	}

	@Test
	void lipsSideBySideHideTheirTouchingSides() {
		ShapeKey lip = ShapeKey.slab(0, 5, 4);
		ShapeIndex index = ShapeIndex.of("kb-shapes/2", ShapeParams.v3().toJson(), List.of("a"), List.of("P"), List.of(new ShapeIndex.Use(lip, "P")),
				new JsonArray());
		ShapeTable table = table(index);
		int id = index.shapeId(lip, "P");
		Blocks world = new Blocks();
		for (int x = 0; x < 3; x++) {
			world.put(x, 0, 0, BlockCode.ledge(0), id).put(x, -1, 0, BlockCode.full(1), 0);
		}
		// The middle lip: top, north and south; its west and east sides touch the other lips; the bottom rests on the floor.
		assertEquals(3, QuadCounter.quadsAt(world, table, 1, 0, 0));
		assertEquals(4, QuadCounter.quadsAt(world, table, 0, 0, 0), "the end lip also shows its outer side");
		// What the model emits (§6.3's dev check): the face masks apply, Minecraft's culling against the floor doesn't yet.
		assertEquals(4, QuadCounter.emittedAt(world, table, 1, 0, 0), "top, north, south and the bottom on the floor");
		assertEquals(5, QuadCounter.emittedAt(world, table, 0, 0, 0));
		assertEquals(6, QuadCounter.emittedAt(world, table, 0, -1, 0), "a cube carrier without a shape emits its whole cube");
		assertEquals(6, QuadCounter.emittedAt(world, null, 0, -1, 0), "without a table too");
		assertEquals(0, QuadCounter.emittedAt(world, null, 1, 0, 0), "a ledge without its lip emits nothing");
		assertEquals(0, QuadCounter.emittedAt(new Blocks().put(0, 0, 0, BlockCode.decor(false), 0), null, 0, 0, 0), "nor a tuft's carrier");
	}

	@Test
	void theSyntheticWorldStaysInsideTheBudgets() throws Exception {
		WorldPlan plan = WorldPlanner.plan(ExportBundle.open(TestData.syntheticBundle()),
				new WorldPlanner.Options(null, TestData.ORIGIN_X, TestData.ORIGIN_Z, TestData.MIN_BUILD_HEIGHT));
		ShapeTable table = table(plan.shapes().index());
		QuadCounter.Stats stats = QuadCounter.count(plan, plan.shapes().map(), table);
		assertEquals(plan.shapes().map().shapedBlocks(), stats.shapedBlocks(), "every shaped position is counted once");
		assertTrue(stats.maxBlock() <= 96 && stats.meanBlock() <= 24, "quads per shaped block: mean " + stats.meanBlock() + ", max "
				+ stats.maxBlock());
		assertTrue(stats.sectionP95() <= 12_000 && stats.sectionMax() <= 30_000);
		assertTrue(stats.sections() > 0 && stats.sectionMax() > 0);
		assertNotNull(stats.densest());
		assertEquals(0, stats.densest()[0] % 16);
		QuadCounter.Stats again = QuadCounter.count(plan, plan.shapes().map(), table);
		assertEquals(stats.shapeQuads(), again.shapeQuads(), "deterministic");
		assertEquals(stats.sectionP95(), again.sectionP95());
		assertEquals(stats.sectionMax(), again.sectionMax());
		assertEquals(java.util.Arrays.toString(stats.densest()), java.util.Arrays.toString(again.densest()));
	}
}
