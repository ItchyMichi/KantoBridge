package dev.kantobridge.builder.shape.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class PartsBuildingTest {
	private static final int W = 32;
	private static final int D = 32;

	/** Every shape once, and a carve: the spec tools/shape_fit/emerald/parts_model.py's numbers below come from. */
	private static final String SPEC = """
			{"height": 48, "parts": [
			 {"shape": "box", "x": [2, 30], "z": [8, 28], "y": [0, 16], "paint": {"front": [0, 16, 32, 32], "top": [0, 0, 32, 16]}},
			 {"shape": "gable", "x": [2, 16], "z": [6, 30], "y": [16, 26], "ridge": "x", "paint": {"top": [0, 0, 16, 16]}},
			 {"shape": "barrel", "x": [16, 30], "z": [8, 28], "y": [16, 24], "along": "z",
			  "paint": {"top": [16, 0, 32, 16], "front": [16, 16, 32, 24]}},
			 {"shape": "dome", "center": [9, 18], "radius": 5, "y": [26, 32]},
			 {"shape": "cylinder", "center": [24, 4], "radius": [3, 2], "y": [0, 20], "paint": {"front": [20, 20, 28, 32]}},
			 {"shape": "cone", "center": [24, 4], "radius": 3, "y": [20, 30]},
			 {"shape": "hip", "x": [0, 8], "z": [0, 6], "y": [0, 6]},
			 {"shape": "profile", "x": [10, 20], "z": [0, 8], "y": [0, 2], "along": "x", "profile": [0, 4, 6, 4, 0], "cross": [2, 6, 2]},
			 {"shape": "box", "x": [12, 20], "z": [24, 28], "y": [0, 10], "carve": true}
			]}""";

	/** A drawing whose every pixel is its own texel: slot 0, u = x, v = z. */
	private static VoxelGrid model() {
		int[] texel = new int[W * D];
		for (int z = 0; z < D; z++) {
			for (int x = 0; x < W; x++) {
				texel[z * W + x] = VoxelGrid.texel(0, x, z);
			}
		}
		return PartsBuilding.voxels(JsonParser.parseString(SPEC).getAsJsonObject(), texel, W, D, 8);
	}

	@Test
	void theSameVoxelsAsTheRendererTheAgentsCheckWith() {
		VoxelGrid grid = model();
		assertEquals(48, grid.height());
		assertEquals(13244, grid.count(), "parts_model.py builds 13,244 voxels from this spec");
	}

	@Test
	void eachVoxelShowsTheSameDrawingPixelAsTheRenderer() {
		VoxelGrid grid = model();
		// x, y, z, then the drawing pixel parts_model.py paints there (column, row)
		int[][] cases = {
			{5, 3, 27, 4, 28}, // the box's front
			{20, 5, 27, 21, 26}, // the box's front by the carved doorway
			{9, 30, 18, 24, 8}, // the dome, unpainted: the whole drawing
			{24, 10, 5, 24, 25}, // the oval cylinder's front, wrapped round it
			{25, 22, 4, 28, 24}, // the cone
			{3, 2, 2, 14, 18}, // the hip
			{15, 3, 4, 17, 0}, // the profile roof
			{22, 20, 18, 23, 19}, // the barrel's top, laid along its curve
			{29, 8, 20, 31, 23}, // the box's east side: the front's outer column
			{8, 15, 9, 7, 16}, // the box's top under the gable's eave
		};
		for (int[] c : cases) {
			int t = grid.at(c[0], c[1], c[2]);
			assertTrue(grid.solid(c[0], c[1], c[2]), "solid at " + c[0] + "," + c[1] + "," + c[2]);
			assertEquals(c[3], VoxelGrid.u(t), "column at " + c[0] + "," + c[1] + "," + c[2]);
			assertEquals(c[4], VoxelGrid.v(t), "row at " + c[0] + "," + c[1] + "," + c[2]);
		}
		assertFalse(grid.solid(4, 20, 7), "above the gable's slope");
		assertFalse(grid.solid(15, 3, 26), "the carved doorway");
	}

	@Test
	void aSpecWithoutPartsBuildsNothing() {
		assertNull(PartsBuilding.voxels(new JsonObject(), new int[W * D], W, D, 8));
	}
}
