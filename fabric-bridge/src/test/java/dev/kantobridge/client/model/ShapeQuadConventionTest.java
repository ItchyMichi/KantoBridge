package dev.kantobridge.client.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.kantobridge.builder.shape.geom.ShapeQuad;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/**
 * A shape's full face is V1's cube face exactly (docs/specs/VOXEL_BLOCKS_PLAN.md §5.1 "The same cubes, exactly", §5.5):
 * the same corners in the same vertex order and the same UVs, so a {@code cube} key whose faces show the block's own
 * tile draws what the plain cube draws. Partial quads take the matching part of the texture by the same conventions.
 */
class ShapeQuadConventionTest {
	@Test
	void fullFacesAreTheCubesFaces() {
		for (Direction face : Direction.values()) {
			ShapeQuad quad = ShapeQuad.fullFace(face.get3DDataValue(), 0);
			float[] corners = new float[12];
			for (int i = 0; i < 4; i++) {
				corners[i * 3] = quad.x(i) / 16.0F;
				corners[i * 3 + 1] = quad.y(i) / 16.0F;
				corners[i * 3 + 2] = quad.z(i) / 16.0F;
			}
			assertArrayEquals(CubeEmitter.corners(CubeEmitter.FULL, face), corners, face + " corners");
			assertArrayEquals(CubeEmitter.uvs(CubeEmitter.FULL, face), quad.cornerUvs(), 1e-6F, face + " uvs");
			assertEquals(face.get3DDataValue(), quad.cull(), "a full face is culled by its own side");
		}
	}

	@Test
	void partialQuadsTakeTheirPartOfTheTexture() {
		// The lower half of a south face shows the tile's lower half, upright.
		ShapeQuad south = ShapeQuad.face(ShapeQuad.SOUTH, 0, 0, 0, 16, 8, 16, 0, 0, 8, 16, 16, ShapeQuad.SOUTH, 0);
		assertArrayEquals(new float[] {0, 8, 0, 16, 16, 16, 16, 8}, south.cornerUvs(), 1e-6F);
		// A top face 4 units wide on the west side shows the tile's west quarter, north at the top.
		ShapeQuad up = ShapeQuad.face(ShapeQuad.UP, 0, 0, 0, 4, 16, 16, 0, 0, 0, 4, 16, -1, 0);
		assertArrayEquals(new float[] {0, 0, 0, 16, 4, 16, 4, 0}, up.cornerUvs(), 1e-6F);
		// A reversed rectangle mirrors.
		ShapeQuad mirrored = ShapeQuad.face(ShapeQuad.SOUTH, 0, 0, 0, 16, 16, 16, 0, 16, 0, 0, 16, ShapeQuad.SOUTH, 0);
		assertArrayEquals(new float[] {16, 0, 16, 16, 0, 16, 0, 0}, mirrored.cornerUvs(), 1e-6F);
		// Per corner u and v agree with the array form.
		for (int i = 0; i < 4; i++) {
			assertEquals(south.cornerUvs()[i * 2], south.cornerU(i));
			assertEquals(south.cornerUvs()[i * 2 + 1], south.cornerV(i));
		}
	}
}
