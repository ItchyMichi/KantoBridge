package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapLayout;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The levels pilot's switch and its landscape models on raised ground (HEIGHTS_ALL_GAMES_PLAN.md D4). */
class OutdoorLevelsTest {
	@Test
	void allCoversEveryMap() {
		assertTrue(OutdoorLevels.covers(OutdoorLevels.parse("all"), "EM_ROUTE119"));
		assertTrue(OutdoorLevels.covers(OutdoorLevels.parse("ROUTE_23"), "ROUTE_23"));
		assertFalse(OutdoorLevels.covers(OutdoorLevels.parse("ROUTE_23"), "ROUTE_1"));
		assertFalse(OutdoorLevels.covers(OutdoorLevels.parse(null), "ROUTE_1"));
	}

	@Test
	void thePropertyNamesTheMaps() {
		assertEquals(Set.of("EM_ROUTE119", "ROUTE_23"), OutdoorLevels.parse(" EM_ROUTE119,,ROUTE_23 "));
		assertEquals(Set.of(), OutdoorLevels.parse(null));
	}

	@Test
	void aModelsRockStandsOnItsLowestLevel() {
		MapModel model = RaisedRegionsTest.model(
				"....",
				"##..",
				"##..",
				"....");
		int[] levels = {
				6, 6, 6, 6,
				6, 3, 6, 6,
				0, 0, 3, 3,
				0, 0, 3, 3};
		MapLayout layout = MapLayout.flat(4, 4, levels, List.of(new MapLayout.Ramp(2, 2, Dir.UP, 3)));
		MapLayout rigid = OutdoorLevels.rigid(layout, model, List.of(Set.of(4, 5, 8, 9, 0)));
		assertEquals(0, rigid.level(0, 1), "the rock's cells on its lowest");
		assertEquals(0, rigid.level(1, 1));
		assertEquals(6, rigid.level(0, 0), "a walkable cell of the model keeps its own");
		assertEquals(6, rigid.level(2, 1), "cells of no model keep theirs");
		assertEquals(new MapLayout.Ramp(2, 2, Dir.UP, 3), rigid.ramp(2, 2), "the ramps stay");
		assertSame(layout, OutdoorLevels.rigid(layout, model, List.of()), "no models: the same layout");
		assertNull(OutdoorLevels.rigid(null, model, List.of(Set.of(4))));
	}

	@Test
	void anOutdoorStepIsThreeStagesOfAStepCollider() {
		int low = BlockCode.step(Dir.UP, 1);
		int high = BlockCode.step(Dir.UP, 2);
		assertEquals(BlockCode.KIND_STEP, BlockCode.kind(low));
		assertEquals(BlockCode.KIND_STEP, BlockCode.kind(high));
		assertEquals(1, BlockCode.value(low) >> 2 & 3, "the low block: three half steps to a block and a half");
		assertEquals(2, BlockCode.value(high) >> 2 & 3, "the high block's two half steps over a flat third");
		assertEquals(BlockCode.step(Dir.UP), BlockCode.step(Dir.UP, 0), "a cave's two half steps unchanged");
	}

	@Test
	void theDrawnStairsAndTheMarksMeet() {
		Dir[] drawn = {null, Dir.UP, null, null};
		Dir[] marked = {Dir.LEFT, Dir.DOWN, null, null};
		assertEquals(List.of(Dir.LEFT, Dir.DOWN), java.util.Arrays.asList(OutdoorLevels.slopes(marked, drawn)).subList(0, 2), "a mark wins");
		assertEquals(Dir.UP, OutdoorLevels.slopes(null, drawn)[1], "the drawn stairs alone");
		assertSame(marked, OutdoorLevels.slopes(marked, new Dir[4]), "no drawn stairs: the marks as they are");
		assertNull(OutdoorLevels.slopes(null, new Dir[4]), "neither: the data decides");
		assertNull(OutdoorLevels.slopes(null, null));
	}

	@Test
	void emeraldsTableNamesTheGreyStairs() {
		assertEquals(Dir.UP, FloorKinds.table("emerald").get("general/175"));
		assertEquals(Dir.UP, FloorKinds.table("emerald").get("general/207"));
	}

	@Test
	void aPinMovesTheGroundLevelWithItsCell() {
		// two strips of ground at 0 and 3 a row of rock apart; a review pins the upper strip at 6
		MapModel model = RaisedRegionsTest.model(
				"....",
				"####",
				"....",
				"####");
		MapLayout solved = MapLayout.flat(4, 4, new int[] {
				3, 3, 3, 3,
				0, 0, 0, 0,
				0, 0, 0, 0,
				0, 0, 0, 0}, List.of());
		MapLayout pinned = OutdoorLevels.pinned(solved, model, List.of(new dev.kantobridge.builder.shape.LandscapeModels.Pin(2, 0, 6)));
		assertEquals(6, pinned.level(0, 0), "the whole strip level with the pinned cell");
		assertEquals(6, pinned.level(3, 0));
		assertEquals(0, pinned.level(1, 2), "the other strip stays");
		assertEquals(0, pinned.level(1, 1), "the rock between them on the nearer, lower ground");
		assertSame(solved, OutdoorLevels.pinned(solved, model, List.of()), "no pins: the solve as it was");
		MapLayout fromFlat = OutdoorLevels.pinned(null, model, List.of(new dev.kantobridge.builder.shape.LandscapeModels.Pin(2, 0, 6)));
		assertEquals(6, fromFlat.level(3, 0), "a map the solver left flat stands on 0 for its pins");
		assertEquals(0, fromFlat.level(1, 2));
		assertNull(OutdoorLevels.pinned(null, model, List.of(new dev.kantobridge.builder.shape.LandscapeModels.Pin(2, 0, 0))),
				"pins that lift nothing: still flat");
	}

	@Test
	void aStairGoesWithTheGroundAtItsEnds() {
		// a stair at (1, 1) climbing north from the strip at 0 to the strip at 3
		MapModel model = RaisedRegionsTest.model(
				"....",
				"#.##",
				"....",
				"####");
		MapLayout solved = MapLayout.flat(4, 4, new int[] {
				3, 3, 3, 3,
				0, 0, 0, 0,
				0, 0, 0, 0,
				0, 0, 0, 0}, List.of(new MapLayout.Ramp(1, 1, Dir.UP, 3)));
		MapLayout both = OutdoorLevels.pinned(solved, model, List.of(pin(0, 0, 9), pin(0, 2, 6)));
		assertEquals(6, both.level(1, 1), "both ends 6 up: the stair with them (Mt. Pyre's summit)");
		assertEquals(Dir.UP, both.ramp(1, 1).dir());
		MapLayout level = OutdoorLevels.pinned(solved, model, List.of(pin(0, 0, 0)));
		assertNull(level.ramp(1, 1), "its ends level: no stair (Route 109's beach chairs)");
		assertEquals(0, level.level(1, 1));
		MapLayout one = OutdoorLevels.pinned(solved, model, List.of(pin(0, 0, 6)));
		assertEquals(0, one.level(1, 1), "one end moved: the stair stays, its top a break");
		assertEquals(Dir.UP, one.ramp(1, 1).dir());
	}

	private static dev.kantobridge.builder.shape.LandscapeModels.Pin pin(int x, int y, int level) {
		return new dev.kantobridge.builder.shape.LandscapeModels.Pin(x, y, level);
	}

	@Test
	void aPinStopsAtTheGamesWalls() {
		// two strips the game walls apart along their whole edge (Crystal's one-way walls): a pin moves only its own
		MapModel model = RaisedRegionsTest.model(
				"....",
				"....");
		PairEdges walls = PairEdges.walls(model, List.of(new int[] {0, 0, 0, 1}, new int[] {1, 0, 1, 1}, new int[] {2, 0, 2, 1},
				new int[] {3, 0, 3, 1}));
		MapLayout pinned = OutdoorLevels.pinned(MapLayout.flat(4, 2, new int[8], List.of()), model,
				List.of(new dev.kantobridge.builder.shape.LandscapeModels.Pin(1, 0, 3)), walls);
		assertEquals(3, pinned.level(3, 0), "the pinned strip");
		assertEquals(0, pinned.level(1, 1), "the strip beyond the wall stays");
		MapLayout open = OutdoorLevels.pinned(MapLayout.flat(4, 2, new int[8], List.of()), model,
				List.of(new dev.kantobridge.builder.shape.LandscapeModels.Pin(1, 0, 3)), null);
		assertEquals(3, open.level(1, 1), "no walls: one ground");
	}
}
