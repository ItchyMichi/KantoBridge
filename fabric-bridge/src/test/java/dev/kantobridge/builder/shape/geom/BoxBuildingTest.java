package dev.kantobridge.builder.shape.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoxBuildingTest {
	private static final int W = 16;
	private static final int D = 32;

	/** A one-cell building: 16 rows of roof outlined black, 16 of facade with black walls at columns 1 and 14. */
	private static VoxelGrid house(String roof) {
		int[] shade = new int[W * D];
		int[] texel = new int[W * D];
		for (int y = 0; y < D; y++) {
			for (int x = 0; x < W; x++) {
				int i = y * W + x;
				boolean roofRow = y < 16;
				shade[i] = roofRow ? (y == 0 || x == 0 || x == W - 1 ? 3 : 1) : (x == 1 || x == W - 2 ? 3 : 1);
				texel[i] = VoxelGrid.texel(0, x % 8, y % 8);
			}
		}
		JsonObject box = new JsonObject();
		box.addProperty("roofRows", 16);
		box.addProperty("roof", roof);
		box.add("bands", new JsonArray());
		return BoxBuilding.voxels(shade, texel, W, D, 8, box, List.of());
	}

	@Test
	void theCubeEndsAtTheWallsOutlineAndTheEaveReachesTheSouthEdge() {
		VoxelGrid grid = house("flat");
		int front = D - 1 - BoxBuilding.EAVE;
		assertTrue(grid.solid(1, 0, 0) && grid.solid(14, 0, front), "the cube spans the outlined walls");
		assertFalse(grid.solid(0, 0, 0) || grid.solid(15, 0, 0), "nothing outside the walls' outline");
		assertFalse(grid.solid(7, 0, front + 1), "the wall's front face");
		int wall = BoxBuilding.wall(16);
		assertTrue(grid.solid(0, wall, D - 1) && grid.solid(15, wall, D - 1), "the flat roof overhangs to the grid's edges");
		assertTrue(grid.solid(0, wall + BoxBuilding.FLAT, 7) && !grid.solid(7, wall + BoxBuilding.FLAT, 7), "a parapet rim, open inside");
	}

	@Test
	void anOutlinedWindowStandsOutButBricksDoNot() {
		int[] shade = new int[W * D];
		int[] texel = new int[W * D];
		for (int y = 0; y < D; y++) {
			for (int x = 0; x < W; x++) {
				int i = y * W + x;
				// walls black at 1 and 14; a facade of shade 1 with bricks of shade 2 (rows 18-19, no outline) and a
				// window (rows 24-27, columns 5-9: a black frame round shade 0)
				boolean frame = y >= 24 && y <= 27 && x >= 5 && x <= 9;
				boolean pane = y >= 25 && y <= 26 && x >= 6 && x <= 8;
				shade[i] = y < 16 ? (y == 0 || x == 0 || x == W - 1 ? 3 : 1)
						: x == 1 || x == W - 2 ? 3 : pane ? 0 : frame ? 3 : (y == 18 || y == 19) && x >= 3 && x <= 11 ? 2 : 1;
				texel[i] = VoxelGrid.texel(0, x % 8, y % 8);
			}
		}
		JsonObject box = new JsonObject();
		box.addProperty("roofRows", 16);
		box.addProperty("roof", "flat");
		box.add("bands", new JsonArray());
		VoxelGrid grid = BoxBuilding.voxels(shade, texel, W, D, 8, box, List.of());
		int front = D - 1 - BoxBuilding.EAVE;
		int wall = BoxBuilding.wall(16);
		int windowY = (int) Math.round((D - 1 - 25) * wall / 16.0);
		int brickY = (int) Math.round((D - 1 - 18.5) * wall / 16.0);
		assertTrue(grid.solid(7, windowY, front + 1), "the window stands out");
		assertFalse(grid.solid(7, brickY, front + 1), "the bricks don't");
	}

	@Test
	void aHipRoofStepsInFromEverySide() {
		VoxelGrid grid = house("hip");
		int wall = BoxBuilding.wall(16);
		assertTrue(grid.solid(0, wall, 0), "the first step covers the roof's outline");
		assertFalse(grid.solid(0, wall + BoxBuilding.STEP, 0), "the next step is in from the edge");
		assertTrue(grid.solid(7, wall + BoxBuilding.STEP, 7), "and still covers the middle");
		assertEquals(BoxBuilding.height(D, 16, true), wall + BoxBuilding.RH);
	}
}
