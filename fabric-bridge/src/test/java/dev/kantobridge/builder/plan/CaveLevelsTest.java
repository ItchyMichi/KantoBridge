package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.export.ExportField;
import dev.kantobridge.builder.export.ExportMap;
import dev.kantobridge.builder.export.ExportTileset;
import dev.kantobridge.scene.profile.Profile;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Raised ground in a cave (docs/specs/CAVES_3D_PLAN.md §3.4, H4) on made-up caves, one character a cell: {@code .} floor,
 * {@code ^} raised floor (the two form a tile pair), {@code s} a tile in no pair (stairs where it joins the two),
 * {@code #} rock, {@code ~} water.
 */
class CaveLevelsTest {
	private static final int FLOOR = 0;
	private static final int RAISED = 1;
	private static final int ROCK = 2;
	private static final int WATER = 3;
	private static final int STAIRS = 4;
	private static final ExportField.TilePairs PAIRS = new ExportField.TilePairs(Map.of("CAVERN", Set.of(ExportField.TilePairs.key(RAISED, FLOOR))),
			Map.of());

	/** A cave map from its cells' rows (an even number of each, as blocks hold 2 × 2 cells). */
	static MapModel cave(String... rows) {
		int h = rows.length;
		int w = rows[0].length();
		List<int[]> blocks = new ArrayList<>();
		int[] map = new int[(w / 2) * (h / 2)];
		int[][] cells = new int[h][w];
		for (int by = 0; by < h / 2; by++) {
			for (int bx = 0; bx < w / 2; bx++) {
				int[] block = new int[16];
				for (int ty = 0; ty < 4; ty++) {
					for (int tx = 0; tx < 4; tx++) {
						block[ty * 4 + tx] = tile(rows[by * 2 + ty / 2].charAt(bx * 2 + tx / 2));
					}
				}
				map[by * (w / 2) + bx] = blocks.size();
				blocks.add(block);
			}
		}
		for (int cy = 0; cy < h; cy++) {
			for (int cx = 0; cx < w; cx++) {
				char c = rows[cy].charAt(cx);
				cells[cy][cx] = c == '#' ? 0 : c == '~' ? CellFlags.WATER : CellFlags.WALKABLE;
			}
		}
		ExportTileset tileset = new ExportTileset("CAVERN", "tilesets.cavern", 16, 96, blocks.toArray(new int[0][]), "TILEANIM_NONE",
				Set.of(FLOOR, RAISED, STAIRS), Set.of(), null, Set.of(), Set.of(), Set.of(WATER), Set.of(), Set.of());
		ExportMap export = new ExportMap("CAVE", 60, "CAVERN", w / 2, h / 2, map, 0, false, false, Map.of(), List.of(), List.of(), List.of(), cells,
				null, null, false, "PAL");
		return MapModel.build(export, tileset, List.of(), true, new Warnings());
	}

	private static int tile(char c) {
		return switch (c) {
			case '.' -> FLOOR;
			case '^' -> RAISED;
			case 's' -> STAIRS;
			case '~' -> WATER;
			default -> ROCK;
		};
	}

	private static Profile.Tileset profile(String classes) throws Exception {
		return Profile.parse("return { version = 1, tilesets = { CAVERN = { " + classes + " } } }").tileset("CAVERN");
	}

	/** A plateau over a floor, its south face a cliff, stairs two cells wide climbing it from the floor. */
	private static final String[] PLATEAU = {
			"########",
			"#^^^^^^#",
			"#^^^^^^#",
			"#^^ss^^#",
			"#......#",
			"########",
	};

	@Test
	void theCliffFaceRaisesThePlateauAndTheStairsClimbIt() {
		CaveLevels.Result result = CaveLevels.plan(cave(PLATEAU), PAIRS);
		assertEquals("raised", result.outcome());
		assertEquals(CaveLevels.HIGH_CLIFF_FACE, result.high(), "the face runs along the plateau's south side");
		MapLayout layout = result.layout();
		assertNotNull(layout);
		assertEquals(2, layout.level(1, 1));
		assertEquals(2, layout.level(6, 3), "beside the stairs, still the plateau");
		assertEquals(0, layout.level(1, 4));
		assertEquals(0, layout.level(3, 3), "a ramp sits at its low end");
		assertEquals(new MapLayout.Ramp(3, 3, Dir.UP, CaveLevels.RISE), layout.ramp(3, 3));
		assertEquals(new MapLayout.Ramp(4, 3, Dir.UP, CaveLevels.RISE), layout.ramp(4, 3), "side by side: a wide stair");
		assertNull(layout.ramp(2, 3));
		assertEquals(2, result.stairs());
		assertEquals(16, result.raisedCells());
		assertEquals(2, result.regions());
		// Rock stands as high as the highest cell around it.
		assertEquals(2, layout.level(0, 0));
		assertEquals(2, layout.level(0, 4), "diagonal to the plateau");
		assertEquals(0, layout.level(1, 5));
	}

	@Test
	void theProfileSaysWhichSideIsHighFirst() throws Exception {
		// Pinned the other way round, the floor is raised: the profile wins over the cliff face, and the stairs turn.
		CaveLevels.Result flipped = CaveLevels.plan(cave(PLATEAU), PAIRS, profile("ledge = { " + FLOOR + " }"));
		assertEquals("raised", flipped.outcome());
		assertEquals(CaveLevels.HIGH_PROFILE, flipped.high());
		assertEquals(0, flipped.layout().level(1, 1));
		assertEquals(2, flipped.layout().level(1, 4));
		assertEquals(Dir.DOWN, flipped.layout().ramp(3, 3).dir());
		// Pinned as drawn, with its stairs a stair class: the same levels as the cliff face's, the stairs confirmed.
		CaveLevels.Result pinned = CaveLevels.plan(cave(PLATEAU), PAIRS, profile("ledge = { " + RAISED + " }, stair_n = { " + STAIRS + " }"));
		assertEquals(CaveLevels.HIGH_PROFILE, pinned.high());
		assertEquals(2, pinned.layout().level(1, 1));
		assertEquals(2, pinned.stairsConfirmed());
		assertEquals(0, pinned.stairsOther());
		// A wall pin says nothing (the profile's fallback for solid tiles).
		assertEquals(CaveLevels.HIGH_CLIFF_FACE, CaveLevels.plan(cave(PLATEAU), PAIRS, profile("wall = { " + FLOOR + " }")).high());
	}

	@Test
	void withoutAFaceOrAPinTheMapStaysFlat() {
		// The pair edge runs north to south: nothing says which side is high.
		CaveLevels.Result result = CaveLevels.plan(cave(
				"####",
				"#.^#",
				"#.^#",
				"####"), PAIRS);
		assertEquals("flat_no_high_side", result.outcome());
		assertNull(result.layout());
	}

	@Test
	void stairsClimbEastAndWest() {
		CaveLevels.Result result = CaveLevels.plan(cave(
				"########",
				"#^^^^^^#",
				"#^^^^^^#",
				"#.s^^s.#",
				"#.#..#.#",
				"########"), PAIRS);
		assertEquals("raised", result.outcome());
		assertEquals(Dir.RIGHT, result.layout().ramp(2, 3).dir());
		assertEquals(Dir.LEFT, result.layout().ramp(5, 3).dir());
	}

	@Test
	void groundBothSidesReachWithoutStairsStaysFlat() {
		// A tile in no pair joins the plateau and the floor round a corner: the game walks it freely, Minecraft can't.
		CaveLevels.Result result = CaveLevels.plan(cave(
				"######",
				"#^^^^#",
				"#^^^^#",
				"##s..#",
				"###..#",
				"######"), PAIRS);
		assertEquals("flat_mixed_region", result.outcome());
	}

	@Test
	void rampsMayNotMeetClimbingDifferentWays() throws Exception {
		CaveLevels.Result result = CaveLevels.plan(cave(
				"######",
				"#.s^##",
				"#^s.##",
				"######"), PAIRS, profile("ledge = { " + RAISED + " }"));
		assertEquals("flat_check_ramps_meet", result.outcome());
	}

	@Test
	void aMapOnOneSideOrWithoutPairsStaysFlat() {
		String[] floor = {
				"####",
				"#..#",
				"#.s#",
				"####"};
		assertEquals("flat_one_side", CaveLevels.plan(cave(floor), PAIRS).outcome());
		assertEquals("no_pairs", CaveLevels.plan(cave(floor), ExportField.TilePairs.NONE).outcome());
	}

	@Test
	void aTilesetsPairsMustSplitInTwo() {
		assertEquals(Map.of(1, 0, 2, 1, 3, 1), CaveLevels.sides(Set.of(ExportField.TilePairs.key(1, 2), ExportField.TilePairs.key(1, 3))));
		assertNull(CaveLevels.sides(Set.of(ExportField.TilePairs.key(1, 2), ExportField.TilePairs.key(2, 3), ExportField.TilePairs.key(1, 3))),
				"a triangle: some pair would join one side to itself");
	}
}
