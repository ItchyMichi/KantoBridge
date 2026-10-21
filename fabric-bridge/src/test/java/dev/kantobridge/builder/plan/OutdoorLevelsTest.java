package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapLayout;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The levels pilot's switch and its landscape models on raised ground (HEIGHTS_ALL_GAMES_PLAN.md D4). */
class OutdoorLevelsTest {
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
}
