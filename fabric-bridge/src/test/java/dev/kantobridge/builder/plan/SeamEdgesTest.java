package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.world.MapLayout;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.Scale;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The seam walls (docs/specs/FIELD_MECHANICS_AUDIT_PLAN.md §14, SC1), on made-up maps of 4 x 4 cells. */
class SeamEdgesTest {
	private static PlacedMap map(String id, int x, int y, int z, int h, MapLayout layout, String... rows) {
		MapPlacement p = new MapPlacement(id, x, y, z, 4, h, 0, MapPlacement.Kind.SURFACE, "PAL", "kantobridge:kanto_outdoor", false, null);
		return new PlacedMap(p, RaisedRegionsTest.model(rows), PairEdges.NONE, layout);
	}

	@Test
	void aSidePastItsNeighboursEndIsWalled() {
		// A (4 x 4) west of B (4 x 2, its rows meeting A's rows 0-1): A's east edge cells on rows 2-3 meet nothing
		List<PlacedMap> placed = new ArrayList<>(List.of(
				map("A", 0, Scale.SURFACE_Y, 0, 4, null, "....", "....", "....", "...."),
				map("B", 8, Scale.SURFACE_Y, 0, 2, null, "....", "....")));
		Warnings warnings = new Warnings();
		SeamEdges.apply(placed, SeamEdges.Refusal.NONE, warnings);
		PairEdges a = placed.get(0).edges();
		assertFalse(a.closed(3, 0, 4, 0), "B is there");
		assertFalse(a.closed(3, 1, 4, 1));
		assertTrue(a.closed(3, 2, 4, 2), "nothing beyond");
		assertTrue(a.closed(3, 3, 4, 3));
		assertTrue(a.closed(0, 0, -1, 0), "the west side meets nothing either");
		assertTrue(placed.get(1).edges().closed(0, 1, 0, 2), "B's south side too");
		assertEquals(warnings.noteCount("seam_walls"), warnings.noteCount("seam_walls_unmet"));
	}

	@Test
	void aWalkerCantStepOntoTheNeighboursWaterBelow() {
		// A's ground 3 blocks over B's water (B lower), and A's water beside B's water
		MapLayout up = MapLayout.flat(4, 4, new int[] {3, 3, 3, 3, 3, 3, 3, 3, 0, 0, 0, 0, 0, 0, 0, 0}, List.of());
		List<PlacedMap> placed = new ArrayList<>(List.of(
				map("A", 0, Scale.SURFACE_Y, 0, 4, up, "....", "....", "~~~~", "~~~~"),
				map("B", 8, Scale.SURFACE_Y, 0, 4, null, "~~~~", "~~~~", "~~~~", "~~~~")));
		SeamEdges.apply(placed, SeamEdges.Refusal.NONE, new Warnings());
		PairEdges a = placed.get(0).edges();
		assertTrue(a.closed(3, 0, 4, 0), "ground over the neighbour's water");
		assertFalse(a.closed(3, 2, 4, 2), "water to water stays open");
		assertFalse(placed.get(1).edges().closed(0, 0, -1, 0), "a surfer meeting a bank is the game's own move");
	}

	@Test
	void theGamesOwnRefusalWallsBothSides() {
		List<PlacedMap> placed = new ArrayList<>(List.of(
				map("A", 0, Scale.SURFACE_Y, 0, 4, null, "....", "....", "....", "...."),
				map("B", 8, Scale.SURFACE_Y, 0, 4, null, "....", "....", "....", "....")));
		SeamEdges.apply(placed, (a, ax, ay, b, bx, by, d) -> ay == 1 && by == 1, new Warnings());
		assertTrue(placed.get(0).edges().closed(3, 1, 4, 1));
		assertTrue(placed.get(1).edges().closed(0, 1, -1, 1));
		assertFalse(placed.get(0).edges().closed(3, 0, 4, 0));
	}
}
