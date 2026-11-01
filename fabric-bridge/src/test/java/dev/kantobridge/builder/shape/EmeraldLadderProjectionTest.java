package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.kantobridge.scene.CellTag;
import dev.kantobridge.scene.SceneAtlas;
import dev.kantobridge.scene.SceneMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Sootopolis B1F (11,21..22): projected cliff over a walkable warp ladder. */
class EmeraldLadderProjectionTest {
	@AfterEach
	void reset() {
		FittedShapes.useForTest(null);
		FittedShapes.useTablesForTest(null);
		FittedShapes.setEnabled(true);
	}

	@Test
	void cliffTopIsClearedAndExistingWalkEnabledWarpLadderKeepsOnlyDecorCarriers() {
		int[][] quarters = {{9419, 9420, 9425, 9426}, {9448, 9449, 9450, 9451}};
		String top = "g3q/9419-9420-9425-9426";
		String base = "g3q/9448-9449-9450-9451";
		SceneMap fixture = ShapeFixtures.interior("EM_SOOTOPOLIS_LADDER", "..bb..", "..bb..", "..bb..", "..bb..");
		int[] tiles = fixture.tiles().clone();
		int[] tags = fixture.tags().clone();
		tags[fixture.w() + 1] |= CellTag.WARP.bit();
		for (int row = 0; row < 2; row++) {
			for (int z = 0; z < 2; z++) {
				for (int x = 0; x < 2; x++) tiles[(row * 2 + z) * fixture.artCols() + 2 + x] = quarters[row][z * 2 + x];
			}
		}
		SceneMap map = new SceneMap(fixture.id(), 0, "EMERALD", "g3q", "BAKED", fixture.w(), fixture.h(), 2, false,
				fixture.env(), tiles, null, fixture.kinds(), tags.clone(), fixture.dirs(), null, fixture.ownBorder(), List.of(), List.of())
				.withHeadroom(9);
		byte[] occupied = new byte[FittedShapes.VOXEL_BYTES];
		occupied[0] = 1;
		occupied[480] = 1; // Two decor carriers, as the existing 16-high ladder.
		FittedShapes.Entry entry = new FittedShapes.Entry("voxels", List.of(), occupied);
		FittedShapes.useTablesForTest(Map.of("emerald", new FittedShapes.Table(Map.of(top, entry, base, entry), Map.of(),
				Map.of(top, base), Set.of(), Map.of(), List.of(), Set.of(base))));
		String previous = FittedShapes.enter("emerald");
		try {
			SceneAtlas atlas = new SceneAtlas("g3q", 32, 10000, 8, List.of(), Set.of(), Map.of());
			ShapePlanner.MapShapes result = ShapePlanner.planMap(map, atlas, 0, ShapeParams.v4());
			assertEquals(1, result.units().size());
			assertEquals(1, result.units().getFirst().cy());
			assertEquals("walk_voxels", result.units().getFirst().source());
			for (int z = 0; z < 2; z++) {
				for (int x = 0; x < 2; x++) {
					int topIndex = z * map.artCols() + 2 + x;
					int baseIndex = (2 + z) * map.artCols() + 2 + x;
					assertNull(result.open()[topIndex]);
					assertEquals(0, result.blocks()[topIndex]);
					assertEquals(0, result.decor()[topIndex]);
					assertNotNull(result.open()[baseIndex]);
					assertEquals(2, result.open()[baseIndex].length);
					assertEquals(0, result.blocks()[baseIndex]);
					assertEquals(0, result.shapeMask()[baseIndex]);
					assertEquals(3, result.decor()[baseIndex]);
					assertEquals(9448, result.open()[baseIndex][0].number(0));
				}
			}
			assertArrayEquals(fixture.kinds(), map.kinds());
			assertArrayEquals(tags, map.tags());
		} finally {
			FittedShapes.leave(previous);
		}
	}
}
