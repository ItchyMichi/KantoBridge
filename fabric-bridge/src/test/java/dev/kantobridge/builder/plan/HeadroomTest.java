package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.export.ExportMap;
import dev.kantobridge.builder.export.ExportTileset;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapLayout;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Interiors' ceilings (docs/specs/HEIGHTS_ALL_GAMES_PLAN.md D5, D8), on made-up rooms drawn as rows of cells: {@code #}
 * drawing (a wall, furniture), {@code ~} water, {@code -} the black filler round a room, any other a floor cell.
 */
class HeadroomTest {
	private static final ExportTileset ROOMS = new ExportTileset("ROOM", "tilesets.room", 16, 96, new int[][] {new int[16]}, "TILEANIM_NONE",
			Set.of(0), Set.of(), null, Set.of(), Set.of(), Set.of(), Set.of(), Set.of());

	private static MapModel model(String... rows) {
		int w = rows[0].length();
		int h = rows.length;
		int[][] cells = new int[h][w];
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				cells[cy][cx] = switch (rows[cy].charAt(cx)) {
					case '#', '-' -> 0;
					case '~' -> CellFlags.WATER;
					default -> CellFlags.WALKABLE;
				};
			}
		}
		ExportMap map = new ExportMap("ROOM", 60, "ROOM", w / 2, h / 2, new int[(w / 2) * (h / 2)], 0, false, false, Map.of(), List.of(),
				List.of(), List.of(), cells, null, null, false, "PAL");
		return MapModel.build(map, ROOMS, List.of(), true, new Warnings());
	}

	/** The filler the rows draw ({@code -}). */
	private static Headroom.Filler filler(String... rows) {
		return (cx, cy) -> rows[cy].charAt(cx) == '-';
	}

	private static int[] ceilings(String[] rows, boolean cave) {
		return Headroom.ceilings(model(rows), null, filler(rows), Headroom.SCREEN_ROWS, cave);
	}

	@Test
	void aRoomIsAsTallAsItsDeepestWall() {
		// the back wall three cells deep over the floor: six rows, six blocks; one flat ceiling
		String[] rows = {
				"######",
				"######",
				"######",
				"#....#",
				"#....#",
				"######"};
		int[] ceilings = ceilings(rows, false);
		assertEquals(6, ceilings[3 * 6 + 2]);
		assertEquals(6, ceilings[0], "one ceiling over the whole room");
	}

	@Test
	void neverUnderFourBlocks() {
		// one cell of wall: two rows, but four blocks of air at least
		String[] rows = {
				"######",
				"#....#",
				"#....#",
				"######"};
		assertEquals(Headroom.MIN, ceilings(rows, false)[6 + 2]);
	}

	@Test
	void theFillerAndTheScreenEndAChain() {
		// two rows of filler over two cells of wall: the wall's four rows; a wall deeper than the screen: its eight rows
		String[] filled = {
				"------",
				"------",
				"######",
				"######",
				"#....#",
				"######"};
		assertEquals(4, ceilings(filled, false)[4 * 6 + 1]);
		String[] deep = {
				"######",
				"######",
				"######",
				"######",
				"######",
				"######",
				"#....#",
				"######"};
		assertEquals(Headroom.SCREEN_ROWS, ceilings(deep, false)[6 * 6 + 1]);
		assertEquals(Headroom.SCREEN_ROWS_GEN3, Headroom.ceilings(model(deep), null, filler(deep), Headroom.screenRows(true), false)[6 * 6 + 1]);
	}

	@Test
	void aRaisedFloorKeepsFourBlocksOverIt() {
		// a stage two blocks up on the west half: its floor + 4 over the room's chain of 4
		String[] rows = {
				"######",
				"######",
				"#....#",
				"#....#",
				"######",
				"######"};
		int[] levels = new int[36];
		for (int cy = 2; cy <= 3; cy++) {
			levels[cy * 6 + 1] = 2;
			levels[cy * 6 + 2] = 2;
		}
		MapLayout layout = MapLayout.flat(6, 6, levels, List.of());
		assertEquals(6, Headroom.ceilings(model(rows), layout, filler(rows), Headroom.SCREEN_ROWS, false)[0]);
	}

	@Test
	void aCaveFollowsItsFloor() {
		// the tallest chain over the cave (6 rows) over every cell's own floor; a ramp's top counts as its floor
		String[] rows = {
				"######",
				"######",
				"######",
				"#....#",
				"#....#",
				"######"};
		int[] levels = new int[36];
		levels[4 * 6 + 1] = 2;
		MapLayout layout = MapLayout.flat(6, 6, levels, List.of(new MapLayout.Ramp(2, 4, Dir.LEFT, 2)));
		int[] ceilings = Headroom.ceilings(model(rows), layout, filler(rows), Headroom.SCREEN_ROWS, true);
		assertEquals(6, ceilings[3 * 6 + 1]);
		assertEquals(8, ceilings[4 * 6 + 1], "two blocks up, six over it");
		assertEquals(8, ceilings[4 * 6 + 2], "a ramp's top, six over it");
		MapLayout built = layout.withCeilings(ceilings);
		assertEquals(6, built.headroom(), "the least room over any cell");
		assertEquals(8, WorldPlan.maxCeiling(built));
		assertEquals(6, WorldPlan.room(built, 2, 4));
	}
}
