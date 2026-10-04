package dev.kantobridge.builder.shape.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A bookcase's sunk panes (docs/specs/PROFILE_PLAN.md §5.6, P3) on made-up tiles. */
class PaneBoxTest {
	/** A black-framed 2 × 2 light window in a dark tile: rows 3-4, columns 3-4. */
	static final int[] WINDOW = ArtFixtures.shades(
			"22222222",
			"22222222",
			"22333322",
			"22311322",
			"22311322",
			"22333322",
			"22222222",
			"22222222");

	private static long count(List<ShapeQuad> quads, int face) {
		return quads.stream().filter(q -> q.face() == face).count();
	}

	@Test
	void aWindowSinksOnePixelWithItsReveals() {
		boolean[] panes = KeyGeometry.panes(WINDOW, 8, 0);
		assertTrue(panes[3 * 8 + 3] && panes[4 * 8 + 4], "the window touches no edge of the face");
		assertTrue(!panes[0] && !panes[2 * 8 + 2], "the dark field touches the edges; black is never a pane");
		List<ShapeQuad> quads = KeyGeometry.paneBox(new int[] {7, -1, 9, 9, -1, 16}, WINDOW, 8, t -> t);
		// The face: the proud field around the window (rows 0-2, the two sides of rows 3-4, rows 5-7) and the sunk window.
		assertEquals(5, count(quads, ShapeQuad.SOUTH));
		// The reveals: the window's west and east walls (each over both rows), its sill and its lintel (each over both columns).
		assertEquals(1, count(quads, ShapeQuad.EAST), "the window's west wall, facing east (the box has no east side)");
		assertEquals(1 + 1, count(quads, ShapeQuad.WEST), "the box's west side and the window's east wall");
		assertEquals(1 + 1, count(quads, ShapeQuad.UP), "the top and the sill");
		assertEquals(1, count(quads, ShapeQuad.DOWN), "the lintel");
	}

	@Test
	void aFaceWithoutPanesStaysFlat() {
		int[] plain = ArtFixtures.shades(
				"22222222",
				"22222222",
				"22222222",
				"22222222",
				"22222222",
				"22222222",
				"22222222",
				"22222222");
		List<ShapeQuad> flat = KeyGeometry.box(new int[] {7, -1, 9, 9, -1, 16}, t -> t);
		List<ShapeQuad> quads = KeyGeometry.paneBox(new int[] {7, -1, 9, 9, -1, 16}, plain, 8, t -> t);
		assertEquals(flat.stream().map(ShapeQuad::face).toList(), quads.stream().map(ShapeQuad::face).toList());
	}
}
