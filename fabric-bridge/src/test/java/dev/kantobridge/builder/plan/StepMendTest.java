package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapLayout;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.Scale;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The steps inside a map (docs/specs/FIELD_MECHANICS_AUDIT_PLAN.md §14, SC4), on made-up maps of 4 x 4 cells. */
class StepMendTest {
	private static final MapLayout TERRACE = MapLayout.flat(4, 4, new int[] {
			3, 3, 3, 3,
			0, 0, 0, 0,
			0, 0, 0, 0,
			0, 0, 0, 0}, List.of());

	private static PlacedMap map(String... rows) {
		MapPlacement p = new MapPlacement("M", 0, Scale.SURFACE_Y, 0, 4, 4, 0, MapPlacement.Kind.SURFACE, "PAL", "kantobridge:kanto_outdoor", false, null);
		return new PlacedMap(p, RaisedRegionsTest.model(rows), PairEdges.NONE, TERRACE);
	}

	@Test
	void aGapThroughAFaceClimbsToTheTop() {
		// row 1 is the terrace's face: a gap at (1, 1) between rock, and (3, 1) between rock and the map's edge
		List<PlacedMap> placed = new ArrayList<>(List.of(map("....", "#.#.", "....", "....")));
		Warnings warnings = new Warnings();
		StepMend.apply(placed, warnings);
		MapLayout now = placed.get(0).layout();
		assertEquals(Dir.UP, now.ramp(1, 1).dir(), "the gap climbs to the top");
		assertEquals(3, now.ramp(1, 1).rise());
		assertEquals(0, now.level(1, 1), "at its own level");
		assertEquals(Dir.UP, now.ramp(3, 1).dir(), "the map's edge closes a side as rock does");
		assertEquals(2, warnings.noteCount("step_mend_ramps"));
	}

	@Test
	void aWideOpeningIsNoStair() {
		// two cells side by side under the terrace: a wider opening than a drawn stair, left for the levels to answer
		List<PlacedMap> placed = new ArrayList<>(List.of(map("....", "#..#", "....", "....")));
		StepMend.apply(placed, new Warnings());
		assertNull(placed.get(0).layout().ramp(1, 1));
		assertNull(placed.get(0).layout().ramp(2, 1));
	}

	@Test
	void aStairOverAHopCellComesDownToIt() {
		// row 2 a ledge row (its hop cells 1 block under the yard behind them, which the levels gave the stair cell (1, 1)
		// too); rock beside the stair; the yard on row 0
		MapLayout yard = MapLayout.flat(4, 4, new int[] {
				1, 1, 1, 1,
				1, 1, 1, 1,
				0, 0, 0, 0,
				0, 0, 0, 0}, List.of());
		MapPlacement p = new MapPlacement("M", 0, Scale.SURFACE_Y, 0, 4, 4, 0, MapPlacement.Kind.SURFACE, "PAL", "kantobridge:kanto_outdoor", false, null);
		List<PlacedMap> placed = new ArrayList<>(List.of(new PlacedMap(p, RaisedRegionsTest.model("....", "#.##", "vvvv", "...."), PairEdges.NONE, yard)));
		StepMend.apply(placed, new Warnings());
		MapLayout now = placed.get(0).layout();
		assertEquals(Dir.UP, now.ramp(1, 1).dir());
		assertEquals(1, now.ramp(1, 1).rise());
		assertEquals(0, now.level(1, 1), "down to the hop cell's level");
	}
}
