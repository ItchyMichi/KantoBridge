package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.kantobridge.scene.SceneAtlas;
import dev.kantobridge.scene.SceneMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A source floor under a two-row solid object is not a third vertical layer. */
class EmeraldDecorFootStackTest {

	@AfterEach
	void reset() {
		FittedShapes.useForTest(null);
		FittedShapes.useTablesForTest(null);
		FittedShapes.setEnabled(true);
	}

	private static String key(int[] quarters) {
		return "g3q/" + quarters[0] + "-" + quarters[1] + "-" + quarters[2] + "-" + quarters[3];
	}

	private static SceneMap column(int[][] quarters) {
		SceneMap fixture = ShapeFixtures.interior("EM_SOLID_OBJECT_WALK_FOOT", "..BB..", "..BB..", "..BB..", "..BB..", "..bb..", "..bb..");
		int[] tiles = fixture.tiles().clone();
		for (int row = 0; row < 3; row++) {
			for (int z = 0; z < 2; z++) {
				for (int x = 0; x < 2; x++) tiles[(row * 2 + z) * fixture.artCols() + 2 + x] = quarters[row][z * 2 + x];
			}
		}
		return new SceneMap(fixture.id(), 0, "EMERALD", "g3q", "BAKED", fixture.w(), fixture.h(), 2, false,
				fixture.env(), tiles, null, fixture.kinds(), fixture.tags(), fixture.dirs(), null, fixture.ownBorder(), List.of(), List.of())
				.withHeadroom(9);
	}

	private static ShapePlanner.MapShapes plan(SceneMap map, int[][] quarters, boolean footWalk, boolean falseLink) {
		byte[] occupied = new byte[FittedShapes.VOXEL_BYTES];
		occupied[0] = 1; // Deliberately one-voxel foot: one noncolliding decor carrier.
		FittedShapes.Entry entry = new FittedShapes.Entry("voxels", List.of(), occupied);
		String top = key(quarters[0]), body = key(quarters[1]), foot = key(quarters[2]);
		Map<String, String> links = falseLink ? Map.of(top, body, body, foot) : Map.of(top, body);
		FittedShapes.useTablesForTest(Map.of("emerald", new FittedShapes.Table(Map.of(top, entry, body, entry, foot, entry), Map.of(),
				links, Set.of(), Map.of(), List.of(), footWalk ? Set.of(foot) : Set.of())));
		String previous = FittedShapes.enter("emerald");
		try {
			return ShapePlanner.planMap(map, new SceneAtlas("g3q", 32, 10000, 8, List.of(), Set.of(), Map.of()), 0, ShapeParams.v4());
		} finally {
			FittedShapes.leave(previous);
		}
	}

	private static void assertTopology(int[][] quarters) {
		SceneMap map = column(quarters);
		byte[] kinds = map.kinds().clone();
		int[] tags = map.tags().clone();
		ShapePlanner.MapShapes baseline = plan(map, quarters, false, true);
		ShapePlanner.MapShapes unsafeOverlay = plan(map, quarters, true, true);
		ShapePlanner.MapShapes corrected = plan(map, quarters, true, false);
		assertEquals(1, baseline.units().size());
		assertEquals(3, unsafeOverlay.units().size());
		assertEquals(2, corrected.units().size());
		for (int z = 0; z < 2; z++) {
			for (int x = 0; x < 2; x++) {
				int top = z * map.artCols() + 2 + x;
				int body = (2 + z) * map.artCols() + 2 + x;
				int foot = (4 + z) * map.artCols() + 2 + x;
				// No baseline WALK unit exists, so the old link stops at the solid body.
				assertNull(baseline.open()[top]);
				assertEquals(4, baseline.blocks()[body]);
				assertNull(baseline.open()[foot]);
				// Adding a WALK unit while keeping body.on makes an invalid three-row chain.
				assertNotNull(unsafeOverlay.open()[top]);
				assertEquals(2, unsafeOverlay.blocks()[top]);
				assertEquals(2, unsafeOverlay.blocks()[body]);
				// Removing only that false floor link preserves the valid solid pair.
				assertNull(corrected.open()[top]);
				assertEquals(4, corrected.blocks()[body]);
				assertEquals(quarters[1][0], corrected.open()[body][0].number(0));
				assertEquals(quarters[0][0], corrected.open()[body][2].number(0));
				assertEquals(0, corrected.blocks()[foot]);
				assertEquals(0, corrected.shapeMask()[foot]);
				assertEquals(1, corrected.decor()[foot]);
				assertEquals(1, corrected.open()[foot].length);
			}
		}
		assertArrayEquals(kinds, map.kinds());
		assertArrayEquals(tags, map.tags());
		assertArrayEquals(unsafeOverlay.floor(), corrected.floor());
		assertArrayEquals(unsafeOverlay.decor(), corrected.decor());
	}

	@Test
	void bedroomPcKeepsItsCapPairWhenTheCarpetFootBecomesWalkDecor() {
		assertTopology(new int[][] {{7999, 8000, 8009, 8010}, {9, 10, 11, 12}, {8039, 8040, 7993, 7993}});
	}

	@Test
	void centerPlantKeepsItsCrownPairWhenTheFloorFootBecomesWalkDecor() {
		assertTopology(new int[][] {{7999, 8000, 8001, 8002}, {8019, 8020, 8021, 8022}, {8036, 8037, 8038, 7982}});
	}
}
