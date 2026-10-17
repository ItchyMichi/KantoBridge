package dev.kantobridge.builder.shape;

import static dev.kantobridge.builder.shape.ShapeFixtures.surface;
import static dev.kantobridge.builder.shape.ShapeFixtures.tile;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.shape.geom.CellUnits;
import dev.kantobridge.builder.shape.geom.VoxelGrid;
import dev.kantobridge.scene.SceneMap;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Fitted voxel shapes (docs/specs/FITTED_SHAPES.md section 10): a table entry's bits and the hash its key carries, the
 * planner's key, the lookup any client does by cell and hash, and the builder that paints the voxels from the drawing in
 * front of them. Made-up drawings; no game art.
 */
class VoxelShapeTest {
	private static final int W = 16;

	@AfterEach
	void reset() {
		FittedShapes.useForTest(null);
		FittedShapes.setEnabled(true);
	}

	/** A cube from (x0, y0, z0) of edge n, as occupancy bits. */
	private static byte[] cube(int x0, int y0, int z0, int n) {
		byte[] out = new byte[W * W * W / 8];
		for (int y = y0; y < y0 + n; y++) {
			for (int z = z0; z < z0 + n; z++) {
				for (int x = x0; x < x0 + n; x++) {
					int i = (y * W + z) * W + x;
					out[i >> 3] |= (byte) (1 << (i & 7));
				}
			}
		}
		return out;
	}

	private static boolean[] unbits(byte[] bits) {
		boolean[] out = new boolean[bits.length * 8];
		for (int i = 0; i < out.length; i++) {
			out[i] = (bits[i >> 3] >> (i & 7) & 1) != 0;
		}
		return out;
	}

	private static int[][] tiles(int shade) {
		int[][] out = new int[4][64];
		for (int[] t : out) {
			Arrays.fill(t, shade);
		}
		return out;
	}

	@Test
	void anEntryKeepsItsBitsAndItsHashAsParameters() {
		byte[] v = cube(4, 0, 6, 4);
		Map<String, FittedShapes.Entry> table = new TreeMap<>();
		FittedShapes.parse("{\"cells\": {\"a/1-2-3-4\": {\"class\": \"voxels\", \"v\": \"" + Base64.getEncoder().encodeToString(v)
				+ "\"}, \"a/5-6-7-8\": {\"class\": \"voxels\", \"v\": \"AAAA\"}}}", table);
		assertEquals(1, table.size(), "a shape of the wrong size is left out");
		FittedShapes.Entry e = table.get("a/1-2-3-4");
		assertEquals(FittedShapes.VOXELS, e.cls());
		assertEquals(FittedShapes.hashParams(v), e.params());
		assertEquals(FittedShapes.HASH_PARAMS, e.params().size());
		assertArrayEquals(v, e.voxels());
	}

	@Test
	void thePlannerPutsTheHashInTheKeyAndAnyClientFindsTheShape() {
		byte[] v = cube(4, 0, 6, 4);
		int[] ids = {tile('A'), tile('B'), tile('C'), tile('D')};
		String key = FittedShapes.key(ShapeFixtures.ATLAS, ids);
		FittedShapes.useForTest(Map.of(key, new FittedShapes.Entry(FittedShapes.VOXELS, FittedShapes.hashParams(v), v)));
		SceneMap map = surface("LONE", "......", "......", "..AB..", "..CD..", "......", "......");
		ShapeKey unitKey = ShapePlanner.planMap(map, ShapeFixtures.TIER_B, 0, ShapeParams.v4()).units().get(0).key();
		CellUnits.Context context = CellUnits.Context.parse(unitKey.variant());
		assertEquals(FittedShapes.VOXELS, context.pinned());
		assertEquals(FittedShapes.hashParams(v), context.params());
		assertArrayEquals(unbits(v), FittedShapes.voxels(ShapeFixtures.ATLAS, ids, context.params()));
		assertNull(FittedShapes.voxels(ShapeFixtures.ATLAS, ids, List.of(1, 2, 3, 4, 5, 6)), "another hash: another shape");
		FittedShapes.setEnabled(false);
		assertNotNull(FittedShapes.voxels(ShapeFixtures.ATLAS, ids, context.params()), "clients look up even with the planner's table off");
	}

	@Test
	void theBuilderPaintsEachVoxelFromTheDrawingInFrontOfIt() {
		int[][] tiles = tiles(1);
		// The drawing's column 5, rows 12..15 (the bottom left tile's column 5) in shade 3; the rest shade 1.
		for (int r = 4; r < 8; r++) {
			tiles[2][r * 8 + 5] = 3;
		}
		CellUnits.Unit unit = CellUnits.voxels(tiles, unbits(cube(4, 0, 6, 4)), 2, 8, CellUnits.MAX_QUADS);
		assertNotNull(unit);
		assertEquals(CellUnits.Kind.VOXELS, unit.kind());
		VoxelGrid grid = unit.voxels();
		int solid = 0;
		for (int y = 0; y < W; y++) {
			for (int z = 0; z < W; z++) {
				for (int x = 0; x < W; x++) {
					solid += grid.solid(x, y, z) ? 1 : 0;
				}
			}
		}
		assertEquals(64, solid);
		int texel = grid.at(5, 0, 8);
		assertEquals(2, VoxelGrid.slot(texel), "the bottom left tile");
		assertEquals(5, VoxelGrid.u(texel));
		assertEquals(7, VoxelGrid.v(texel), "row 15 of the drawing is voxel row 0");
		assertTrue(unit.foreground()[15 * W + 5]);
	}

	@Test
	void aMissingTileOrABusyBlockFallsBackToTheOtherBuilders() {
		boolean[] occupancy = unbits(cube(0, 0, 0, 16));
		int[][] missing = tiles(1);
		missing[1] = null;
		assertNull(CellUnits.voxels(missing, occupancy, 2, 8, CellUnits.MAX_QUADS));
		assertNull(CellUnits.voxels(tiles(1), new boolean[W * W * W], 2, 8, CellUnits.MAX_QUADS), "nothing stands");
		assertNull(CellUnits.voxels(tiles(1), occupancy, 2, 8, 2), "over the quad budget");
	}
}
