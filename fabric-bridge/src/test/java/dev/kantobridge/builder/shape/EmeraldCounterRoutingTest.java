package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.SceneAtlas;
import dev.kantobridge.scene.SceneMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Read-only proof of the existing raw144/COUNTER dispatch for Center counters. */
class EmeraldCounterRoutingTest {
	@AfterEach
	void reset() {
		FittedShapes.useForTest(null);
		FittedShapes.useTablesForTest(null);
		FittedShapes.setEnabled(true);
	}

	private static ShapePlanner.MapShapes plan(SceneMap map, String key, boolean fitted) {
		byte[] occupied = new byte[FittedShapes.VOXEL_BYTES];
		java.util.Arrays.fill(occupied, (byte) 255);
		FittedShapes.Entry entry = new FittedShapes.Entry("voxels", List.of(), occupied);
		FittedShapes.useTablesForTest(Map.of("emerald", new FittedShapes.Table(fitted ? Map.of(key, entry) : Map.of(), Map.of(),
				Map.of(), Set.of(), Map.of(), List.of(), fitted ? Set.of(key) : Set.of())));
		String previous = FittedShapes.enter("emerald");
		try {
			return ShapePlanner.planMap(map, new SceneAtlas("g3q", 32, 10000, 8, List.of(), Set.of(), Map.of()), 0, ShapeParams.current(true));
		} finally {
			FittedShapes.leave(previous);
		}
	}

	@Test
	void allFiveSourceCounterPatternsUseFoldedOneBlockCubesEvenWithFittedWalkGeometry() {
		for (int[] q : new int[][] {{7989, 7990, 7991, 7992}, {8318, 8319, 8239, 8320},
				{8319, 8319, 8149, 8149}, {8319, 8321, 8322, 8323}, {8319, 8319, 8330, 8320}}) {
			String key = "g3q/" + q[0] + "-" + q[1] + "-" + q[2] + "-" + q[3];
			SceneMap fixture = ShapeFixtures.interior("EM_CENTER_COUNTER", "..==..", "..==..");
			int[] tiles = fixture.tiles().clone();
			for (int z = 0; z < 2; z++) {
				for (int x = 0; x < 2; x++) tiles[z * fixture.artCols() + 2 + x] = q[z * 2 + x];
			}
			SceneMap map = new SceneMap(fixture.id(), 0, "EMERALD", "g3q", "BAKED", fixture.w(), fixture.h(), 2, false,
					fixture.env(), tiles, null, fixture.kinds(), fixture.tags(), fixture.dirs(), null, fixture.ownBorder(), List.of(), List.of());
			ShapePlanner.MapShapes before = plan(map, key, false), after = plan(map, key, true);
			assertTrue(after.units().isEmpty());
			assertArrayEquals(before.blocks(), after.blocks());
			assertArrayEquals(before.shapeMask(), after.shapeMask());
			assertArrayEquals(before.decor(), after.decor());
			assertArrayEquals(before.floor(), after.floor());
			assertArrayEquals(before.open(), after.open());
			assertArrayEquals(fixture.kinds(), map.kinds());
			assertArrayEquals(fixture.tags(), map.tags());
			assertEquals(CellKind.COUNTER, map.kind(1, 0));
			for (int z = 0; z < 2; z++) {
				for (int x = 0; x < 2; x++) {
					int at = z * map.artCols() + 2 + x;
					assertEquals(1, after.blocks()[at]);
					assertEquals(0, after.decor()[at]);
					assertEquals(ShapeKey.cube(0, q[x], q[x], q[x + 2], q[x + 2], q[x + 2], q[x + 2]), after.open()[at][0]);
				}
			}
		}
	}
}
