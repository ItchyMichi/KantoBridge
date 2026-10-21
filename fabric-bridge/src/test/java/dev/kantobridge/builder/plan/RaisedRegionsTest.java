package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.export.ExportMap;
import dev.kantobridge.builder.export.ExportTileset;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapLayout;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Raised ground from regions (docs/specs/HEIGHTS_ALL_GAMES_PLAN.md D3, W4), on made-up maps: each drawn as rows of
 * cells ({@code #} rock, {@code ~} water, {@code v} a ledge hopping south, any other a floor cell) with its keys as
 * rows of the same size (a digit is a floor cell's key, {@code 0} a cell joining any level, {@code B} a bridge).
 */
class RaisedRegionsTest {
	/** One tile throughout: the cells' kinds come from their flags. */
	private static final ExportTileset ROCKS = new ExportTileset("CAVERN", "tilesets.cavern", 16, 96, new int[][] {new int[16]}, "TILEANIM_NONE",
			Set.of(0), Set.of(), null, Set.of(), Set.of(), Set.of(), Set.of(), Set.of());

	private static MapModel model(String... rows) {
		int w = rows[0].length();
		int h = rows.length;
		int[][] cells = new int[h][w];
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				cells[cy][cx] = switch (rows[cy].charAt(cx)) {
					case '#' -> 0;
					case '~' -> CellFlags.WATER;
					case 'v' -> CellFlags.WALKABLE | CellFlags.LEDGE;
					default -> CellFlags.WALKABLE;
				};
			}
		}
		ExportMap map = new ExportMap("CAVE", 60, "CAVERN", w / 2, h / 2, new int[(w / 2) * (h / 2)], 0, false, false, Map.of(), List.of(),
				List.of(), List.of(), cells, null, null, false, "PAL");
		return MapModel.build(map, ROCKS, List.of(), true, new Warnings());
	}

	private static RaisedRegions.Result solve(String[] rows, String[] keys) {
		MapModel model = model(rows);
		int w = rows[0].length();
		int n = w * rows.length;
		int[] key = new int[n];
		boolean[] joins = new boolean[n];
		boolean[] bridge = new boolean[n];
		Arrays.fill(key, -1);
		for (int i = 0; i < n; i++) {
			char c = keys[i / w].charAt(i % w);
			if (c == '0') {
				joins[i] = true;
			} else if (c == 'B') {
				bridge[i] = true;
			} else if (Character.isDigit(c)) {
				key[i] = c - '0';
			}
		}
		return RaisedRegions.solve(new RaisedRegions.Input(model, key, joins, bridge, 3));
	}

	@Test
	void aPlatformStandsAsHighAsItsStairsClimb() {
		String[] rows = {
				"######",
				"#....#",
				"#....#",
				"##.###",
				"#....#",
				"#....#"};
		String[] keys = {
				"######",
				"#4444#",
				"#4444#",
				"##0###",
				"#3333#",
				"#3333#"};
		RaisedRegions.Result result = solve(rows, keys);
		assertEquals("raised", result.outcome());
		MapLayout layout = result.layout();
		assertNotNull(layout);
		assertEquals(2, layout.level(1, 1), "a stair cell climbs 2 blocks");
		assertEquals(0, layout.level(1, 4));
		assertEquals(new MapLayout.Ramp(2, 3, Dir.UP, 2), layout.ramp(2, 3));
		assertEquals(0, layout.level(2, 3), "a ramp sits at its low end");
		assertEquals(0, layout.level(1, 3), "the face beside the stairs rises from the floor it faces");
		assertEquals(2, layout.level(1, 0), "rock north of the platform stands on it");
		assertEquals(8, result.raisedCells());
		assertEquals(1, result.stairs());
		assertEquals(2, result.maxLevel());
	}

	@Test
	void eachStairCellIsACellOfFace() {
		// Victory Road's tall tiers: three cells of stairs, six blocks; each cell a ramp on the one below
		String[] rows = {
				"######",
				"#....#",
				"##.###",
				"##.###",
				"##.###",
				"#....#"};
		String[] keys = {
				"######",
				"#4444#",
				"##0###",
				"##0###",
				"##0###",
				"#3333#"};
		MapLayout layout = solve(rows, keys).layout();
		assertNotNull(layout);
		assertEquals(6, layout.level(1, 1));
		assertEquals(List.of(4, 2, 0), List.of(layout.level(2, 2), layout.level(2, 3), layout.level(2, 4)));
		assertEquals(Dir.UP, layout.ramp(2, 2).dir());
	}

	@Test
	void wideStairsClimbSideBySide() {
		String[] rows = {
				"######",
				"#....#",
				"#....#",
				"##..##",
				"#....#",
				"#....#"};
		String[] keys = {
				"######",
				"#4444#",
				"#4444#",
				"##00##",
				"#3333#",
				"#3333#"};
		MapLayout layout = solve(rows, keys).layout();
		assertNotNull(layout);
		assertEquals(new MapLayout.Ramp(2, 3, Dir.UP, 2), layout.ramp(2, 3));
		assertEquals(new MapLayout.Ramp(3, 3, Dir.UP, 2), layout.ramp(3, 3));
	}

	@Test
	void keysAreNotHeights() {
		// Two platforms of one key over one floor: one cell of stairs up to the west one, two to the east one (Victory Road)
		String[] rows = {
				"########",
				"#..##..#",
				"##.##.##",
				"#...#.##",
				"#......#",
				"########"};
		String[] keys = {
				"########",
				"#44##44#",
				"##0##0##",
				"#333#0##",
				"#333333#",
				"########"};
		MapLayout layout = solve(rows, keys).layout();
		assertNotNull(layout);
		assertEquals(List.of(2, 4, 0), List.of(layout.level(1, 1), layout.level(5, 1), layout.level(1, 4)));
		assertEquals(List.of(2, 0), List.of(layout.level(5, 2), layout.level(5, 3)));
	}

	@Test
	void regionsNoStairsJoinTakeTheirFacesThenTheirKeys() {
		// A face of rock two cells tall between the 2s and the 3s: the 2s (smaller) four blocks up by the face; the second
		// region of 3s, which nothing joins, as high as the first
		String[] rows = {
				"########",
				"#..#####",
				"########",
				"########",
				"#....#.#",
				"######.#"};
		String[] keys = {
				"########",
				"#22#####",
				"########",
				"########",
				"#3333#3#",
				"######3#"};
		RaisedRegions.Result result = solve(rows, keys);
		assertEquals("raised", result.outcome());
		assertEquals(4, result.layout().level(1, 1));
		assertEquals(0, result.layout().level(6, 5));
	}

	@Test
	void waterStandsWithItsShore() {
		String[] rows = {
				"######",
				"#....#",
				"##.###",
				"#....#",
				"#~~~~#",
				"######"};
		String[] keys = {
				"######",
				"#4444#",
				"##0###",
				"#3333#",
				"######",
				"######"};
		MapLayout layout = solve(rows, keys).layout();
		assertNotNull(layout);
		assertEquals(0, layout.level(1, 4), "level with the 3s it touches");
		assertEquals(2, layout.level(1, 1));
	}

	@Test
	void aLedgeHopsWithinOneHeight() {
		String[] rows = {
				"######",
				"#....#",
				"##.###",
				"#....#",
				"#vvvv#",
				"#....#"};
		String[] keys = {
				"######",
				"#4444#",
				"##0###",
				"#3333#",
				"#3333#",
				"#3333#"};
		assertEquals("raised", solve(rows, keys).outcome());
	}

	@Test
	void crystalsCavesStandByTheirWallsAndDrawnStairs() {
		// One key for every floor cell; drawn stairs (3, 2) climbing north through the face up to the north plateau; a
		// one-way wall along the trough's south edge, the plateau beyond it two blocks up (Kanto's rule for tile pairs)
		String[] rows = {
				"########",
				"#......#",
				"###.####",
				"#......#",
				"#......#",
				"#......#"};
		MapModel model = model(rows);
		int n = 48;
		int[] key = new int[n];
		Dir[] climb = new Dir[n];
		for (int i = 0; i < n; i++) {
			key[i] = rows[i / 8].charAt(i % 8) == '.' ? 0 : -1;
		}
		key[2 * 8 + 3] = -1;
		climb[2 * 8 + 3] = Dir.UP;
		List<int[]> walls = new java.util.ArrayList<>();
		for (int x = 1; x <= 6; x++) {
			walls.add(new int[] {x, 4, x, 5});
		}
		RaisedRegions.Result result = RaisedRegions.solve(new RaisedRegions.Input(model, key, new boolean[n], new boolean[n], -1, climb,
				PairEdges.walls(model, walls), true, 1, false, false));
		assertEquals("raised", result.outcome());
		MapLayout layout = result.layout();
		assertEquals(List.of(2, 0, 2), List.of(layout.level(1, 1), layout.level(1, 3), layout.level(1, 5)));
		assertEquals(new MapLayout.Ramp(3, 2, Dir.UP, 2), layout.ramp(3, 2));
		assertEquals(0, layout.level(3, 2));
		// the same walls without the rise (a building's): they keep the areas apart, nothing stands on them
		RaisedRegions.Result building = RaisedRegions.solve(new RaisedRegions.Input(model, key, new boolean[n], new boolean[n], -1, climb,
				PairEdges.walls(model, walls), false, 1, false, false));
		assertEquals(0, building.layout().level(1, 5));
	}

	/** The same as {@link #solve} with a room's rules (D5: a room on a layer). */
	private static RaisedRegions.Result room(String[] rows, String[] keys) {
		RaisedRegions.Result asCave = solve(rows, keys);
		MapModel model = model(rows);
		int w = rows[0].length();
		int n = w * rows.length;
		int[] key = new int[n];
		boolean[] joins = new boolean[n];
		java.util.Arrays.fill(key, -1);
		for (int i = 0; i < n; i++) {
			char c = keys[i / w].charAt(i % w);
			if (c == '0') {
				joins[i] = true;
			} else if (Character.isDigit(c)) {
				key[i] = c - '0';
			}
		}
		assertNotNull(asCave);
		return RaisedRegions.solve(new RaisedRegions.Input(model, key, joins, new boolean[n], 3, null, null, false, 0, true, true));
	}

	@Test
	void aRoomsSeatIsNoPlatformButAStageIs() {
		// A seat (elevation 4) a cell joining any level leads to across open floor: the engine's drawing order, not a step
		String[] floor = {
				"######",
				"#....#",
				"#....#",
				"######"};
		assertEquals("flat_one_height", room(floor, new String[] {"######", "#3043#", "#3333#", "######"}).outcome());
		// a stage up a stair cut through its front: raised
		String[] stage = {
				"######",
				"#....#",
				"##.###",
				"#....#"};
		RaisedRegions.Result raised = room(stage, new String[] {"######", "#4444#", "##0###", "#3333#"});
		assertEquals("raised", raised.outcome());
		assertEquals(2, raised.layout().level(1, 1));
	}

	@Test
	void whatTheSolverLeavesFlat() {
		String[] rows = {
				"######",
				"#....#",
				"##.###",
				"#....#"};
		assertEquals("flat_one_level", solve(rows, new String[] {"######", "#3333#", "##0###", "#3333#"}).outcome());
		assertEquals("flat_bridge", solve(rows, new String[] {"######", "#4444#", "##B###", "#3333#"}).outcome());
		RaisedRegions.Result flat = solve(new String[] {"######", "#....#", "#....#", "######"}, new String[] {"######", "#3344#", "#3344#", "######"});
		assertEquals("flat_one_height", flat.outcome(), "different keys and no stairs or face: one height, walls between");
		assertNull(flat.layout());
		// stairs a region meets from the side, halfway up
		assertEquals("flat_stairs_shape", solve(new String[] {
				"######",
				"#....#",
				"##.###",
				"#..###",
				"##.###",
				"#....#"}, new String[] {
				"######",
				"#4444#",
				"##0###",
				"#30###",
				"##0###",
				"#3333#"}).outcome());
		// two stairs between the same two regions, one cell and two cells long
		assertEquals("flat_stairs_disagree", solve(new String[] {
				"########",
				"#......#",
				"##.##.##",
				"##.##.##",
				"#......#",
				"########"}, new String[] {
				"########",
				"#444444#",
				"##0##4##",
				"##0##0##",
				"#333333#",
				"########"}).outcome());
	}
}
