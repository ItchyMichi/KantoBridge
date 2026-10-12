package dev.kantobridge.builder.shape;

import static dev.kantobridge.builder.shape.ShapeFixtures.interior;
import static dev.kantobridge.builder.shape.ShapeFixtures.surface;
import static dev.kantobridge.builder.shape.ShapeFixtures.tile;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.scene.profile.Profile;
import dev.kantobridge.world.MapLayout;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The profile's class boxes (docs/specs/PROFILE_PLAN.md §5.6, P3): pinned uprights, tops and bookcases at their class
 * heights, the fold and the top rule, the culling, a bookcase's ranks, the ceiling, a counter cell's one block. Made-up
 * maps and profiles; pixel-free.
 */
class ClassBoxesTest {
	private static ShapePlanner.MapShapes plan(SceneMap map, String pins, Map<String, Integer> tally) throws Exception {
		Profile.Tileset tileset = ProfileUnitsTest.tileset(pins);
		return ShapePlanner.planMap(map, ShapeFixtures.TIER_B, 0, ShapeParams.p3(), tileset, null, tally);
	}

	private static ShapeKey[] column(SceneMap map, ShapePlanner.MapShapes shapes, int tx, int ty) {
		return shapes.open()[ty * map.artCols() + tx];
	}

	private static ShapeKey box(int up, char north, char south, char west, char east, int h) {
		return ShapeKey.box(0, up, t(north), t(south), t(west), t(east), h);
	}

	/** A face's tile, '-' for none. */
	private static int t(char c) {
		return c == '-' ? -1 : tile(c);
	}

	@Test
	void aDeskRunFoldsItsRowsUpEveryFace() throws Exception {
		SceneMap map = surface("DESK",
				"......",
				"......",
				"..AA..",
				"..CC..",
				"......",
				"......");
		Map<String, Integer> tally = new TreeMap<>();
		ShapePlanner.MapShapes shapes = plan(map, "desk = { `A`, `C` }", tally);
		assertEquals(4, tally.get("box_desk"));
		// The front tile (row 3): 24 pixels, three blocks; band 0 its own row, band 1 the row north (same class), band 2
		// its own again; the whole run is folded (two rows under a three-band face), so the top wears the run's north row.
		assertArrayEquals(new ShapeKey[] {box(-1, '-', 'C', 'C', '-', 16), box(-1, '-', 'A', 'A', '-', 16), box(tile('A'), '-', 'C', 'C', '-', 16)},
				column(map, shapes, 2, 3), "north and east sides stand against boxes as tall: left out");
		assertArrayEquals(new ShapeKey[] {box(-1, 'C', '-', 'C', '-', 16), box(-1, 'A', '-', 'A', '-', 16), box(tile('A'), 'A', '-', 'A', '-', 16)},
				column(map, shapes, 2, 2), "the back tile: the same stack (its own tile where the run ends)");
		assertEquals(3, shapes.blocks()[3 * map.artCols() + 2]);
		assertEquals(0b111, shapes.shapeMask()[3 * map.artCols() + 2], "every block a carrier");
	}

	@Test
	void aRaisedNeighbourCoversOnlyAsHighAsItReallyStands() throws Exception {
		// Raised ground (docs/specs/CAVES_3D_PLAN.md §3.4): the cell west of the desk stands 2 blocks up, rock under it.
		SceneMap map = surface("DESK",
				"......",
				"......",
				"..AA..",
				"..CC..",
				"......",
				"......");
		MapLayout layout = MapLayout.flat(3, 3, new int[] {0, 0, 0, 2, 0, 0, 0, 0, 0}, List.of());
		ShapePlanner.MapShapes shapes = ShapePlanner.planMap(map, ShapeFixtures.TIER_B, 0, ShapeParams.p3(),
				ProfileUnitsTest.tileset("desk = { `A`, `C` }"), null, new TreeMap<>(), layout);
		assertArrayEquals(new ShapeKey[] {box(-1, '-', 'C', '-', '-', 16), box(-1, '-', 'A', '-', '-', 16), box(tile('A'), '-', 'C', 'C', '-', 16)},
				column(map, shapes, 2, 3), "its west face only over the raised floor's 16 pixels");
	}

	@Test
	void aOneRowCounterWearsTheUprightPinNorthOfItOnTop() throws Exception {
		SceneMap map = interior("SHOP",
				"......",
				"..WW..",
				"..KK..",
				"..LL..",
				"......",
				"......");
		ShapePlanner.MapShapes shapes = plan(map, "counter = { `K` }, wall = { `W` }", new TreeMap<>());
		assertArrayEquals(new ShapeKey[] {box(tile('W'), '-', '-', 'K', '-', 16), ShapeKey.empty(0)}, column(map, shapes, 2, 2),
				"one block of counter under the wall pin's tile; the wall and the solid row south cover its north and south; its second "
						+ "block of collision draws nothing");
	}

	@Test
	void aTopClassIsItsOwnTileAllRound() throws Exception {
		SceneMap map = surface("LEDGE",
				"......",
				"......",
				"..GG..",
				"..GG..",
				"......",
				"......");
		ShapePlanner.MapShapes shapes = plan(map, "ledge = { `G` }", new TreeMap<>());
		assertArrayEquals(new ShapeKey[] {box(tile('G'), '-', 'G', 'G', '-', 12), ShapeKey.empty(0)}, column(map, shapes, 2, 3),
				"six pixels: twelve sixteenths");
	}

	@Test
	void aBookcaseRankStandsOnItsTwoFrontRowsUnderTheCeiling() throws Exception {
		SceneMap map = interior("LIBRARY",
				"......",
				"..XX..",
				"..CC..",
				"..DD..",
				"..EE..",
				"..FF..",
				"......",
				"......");
		Map<String, Integer> tally = new TreeMap<>();
		ShapePlanner.MapShapes shapes = plan(map, "bookcase = { `C`, `D`, `E`, `F` }", tally);
		// Four rows and the cap (X, unpinned and solid): five bands, forty pixels; the ceiling leaves three.
		assertArrayEquals(new ShapeKey[] {box(-1, '-', 'F', 'F', '-', 16).withVariant("p"), box(-1, '-', 'E', 'E', '-', 16).withVariant("p"),
				box(tile('X'), '-', 'D', 'D', '-', 16).withVariant("p")}, column(map, shapes, 2, 5),
				"the front row: band b the rank's row b north of its front, the cap on top; no north face inside; its panes sunk");
		assertArrayEquals(new ShapeKey[] {box(-1, 'F', '-', 'F', '-', 16), box(-1, 'E', '-', 'E', '-', 16), box(tile('X'), 'D', '-', 'D', '-', 16)},
				column(map, shapes, 2, 4), "the back row: the same bands, its north face out, no south face inside");
		assertArrayEquals(new ShapeKey[] {ShapeKey.empty(0), ShapeKey.empty(0)}, column(map, shapes, 2, 3), "the rows behind: empty carriers");
		assertEquals(tile('.'), shapes.floor()[3 * map.artCols() + 2], "on the ground's floor");
		assertEquals(Map.of("bookcase_rank", 2, "bookcase_vacated", 4, "box_bookcase", 4), tally);
		ShapePlanner.MapShapes flat = plan(map, "bookcase = { `C`, `D`, `E`, `F` }, bookcase_relief = false", new TreeMap<>());
		assertNull(column(map, flat, 2, 5)[0].variant(), "a tileset without the relief keeps flat faces");
	}

	@Test
	void aCounterCellKeepsItsOneBlock() throws Exception {
		SceneMap map = interior("MART",
				"......",
				"......",
				"..==..",
				"..==..",
				"......",
				"......");
		ShapePlanner.MapShapes shapes = plan(map, "table = { `=` }", new TreeMap<>());
		ShapeKey[] column = column(map, shapes, 2, 3);
		assertEquals(1, column.length, "a counter's collision is one block; the table stands no higher");
		assertEquals(16, column[0].number(5));
	}

	@Test
	void aWalkablePinChangesNothing() throws Exception {
		SceneMap map = surface("FLOOR",
				"......",
				"..gg..",
				"......",
				"......");
		Map<String, Integer> tally = new TreeMap<>();
		ShapePlanner.MapShapes shapes = plan(map, "ledge = { `g` }", tally);
		assertNull(column(map, shapes, 2, 1));
		assertEquals(2, tally.get("box_walkable"));
	}

	@Test
	void withoutTheFlagTheBoxesStayTierAs() throws Exception {
		SceneMap map = surface("DESK",
				"......",
				"......",
				"..AA..",
				"..CC..",
				"......",
				"......");
		Profile.Tileset tileset = ProfileUnitsTest.tileset("desk = { `A`, `C` }");
		ShapePlanner.MapShapes shapes = ShapePlanner.planMap(map, ShapeFixtures.TIER_B, 0, ShapeParams.p2(), tileset, null, new TreeMap<>());
		assertEquals(ShapeKey.Kind.CUBE, column(map, shapes, 2, 3)[0].kind(), "P2's planner folds them as Tier A");
	}
}
