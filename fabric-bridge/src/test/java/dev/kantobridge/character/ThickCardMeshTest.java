package dev.kantobridge.character;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import org.junit.jupiter.api.Test;

/**
 * Thick cards (docs/specs/VOXEL_BLOCKS_PLAN.md §5.11; report B5.2): the front and back over the whole frame, an edge strip
 * per run of opaque texels around the silhouette; within the frame and its depth; the worst mask falls back to a flat
 * card, as does an empty one.
 */
class ThickCardMeshTest {
	static BitSet mask(String[] art) {
		BitSet out = new BitSet();
		for (int y = 0; y < art.length; y++) {
			for (int x = 0; x < art[y].length(); x++) {
				if (art[y].charAt(x) == '#') {
					out.set(y * art[y].length() + x);
				}
			}
		}
		return out;
	}

	@Test
	void aBoxIsSixQuads() {
		ThickCardMesh.Mesh mesh = ThickCardMesh.build(mask(SpriteFixtures.BOX), 16, 16, ThickCardMesh.DEPTH);
		assertEquals(6, mesh.quads(), "front, back and one run per side");
		assertEquals(6 * 20, mesh.vertices().length);
		// Inside the frame and the depth; UVs inside the frame.
		for (int q = 0; q < mesh.quads(); q++) {
			for (int v = 0; v < 4; v++) {
				int i = q * 20 + v * 5;
				assertTrue(mesh.vertices()[i] >= 0 && mesh.vertices()[i] <= 16);
				assertTrue(mesh.vertices()[i + 1] >= 0 && mesh.vertices()[i + 1] <= 16);
				assertTrue(Math.abs(mesh.vertices()[i + 2]) <= ThickCardMesh.DEPTH / 2);
				assertTrue(mesh.vertices()[i + 3] >= 0 && mesh.vertices()[i + 3] <= 1 && mesh.vertices()[i + 4] >= 0 && mesh.vertices()[i + 4] <= 1);
			}
		}
		// The top edge of the box (rows 4..11): at y = 16 - 4, from x 4 to 12, wearing row 4's texels.
		boolean top = false;
		for (int q = 2; q < mesh.quads(); q++) {
			int i = q * 20;
			if (mesh.normals()[q * 3 + 1] == 1 && mesh.vertices()[i + 1] == 12 && mesh.vertices()[i] == 4 && mesh.vertices()[i + 5] == 12) {
				top = true;
				assertEquals(4.5F / 16, mesh.vertices()[i + 4], 1e-6, "the row's texels drawn across the depth");
			}
		}
		assertTrue(top);
	}

	@Test
	void aPersonCountsItsRuns() {
		ThickCardMesh.Mesh mesh = ThickCardMesh.build(mask(SpriteFixtures.PERSON), 16, 16, ThickCardMesh.DEPTH);
		assertTrue(mesh.quads() > 6 && mesh.quads() < 60, mesh.quads() + " quads");
	}

	@Test
	void theWorstMaskAndAnEmptyOneAreFlat() {
		BitSet checker = new BitSet();
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				if ((x + y) % 2 == 0) {
					checker.set(y * 16 + x);
				}
			}
		}
		assertNull(ThickCardMesh.build(checker, 16, 16, ThickCardMesh.DEPTH), "514 quads: a flat card");
		assertNull(ThickCardMesh.build(new BitSet(), 16, 16, ThickCardMesh.DEPTH));
	}
}
