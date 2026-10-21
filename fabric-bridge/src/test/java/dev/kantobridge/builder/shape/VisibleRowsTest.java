package dev.kantobridge.builder.shape;

import static dev.kantobridge.builder.shape.ShapeFixtures.surface;
import static dev.kantobridge.builder.shape.ShapeFixtures.tile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.scene.SceneAtlas;
import dev.kantobridge.scene.SceneMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Every visible layer built (docs/specs/HEIGHTS_ALL_GAMES_PLAN.md D9), on made-up faces: the visible-layers check finds
 * today's lost rows (a face over a door folded to its building's 2 blocks, a face cut at 6 rows) and none under H9's
 * rules; a cliff body under its rim stands as tall as drawn, a tree line stays rows of 2-row trees.
 */
class VisibleRowsTest {
	private static final int SCREEN = 8;

	/** The columns {@code params} plan for {@code map}, the tiles {@code leaves} draw foliage. */
	private static ShapePlanner.Columns columns(SceneMap map, ShapeParams params, char... leaves) {
		Map<Integer, String> hints = new java.util.TreeMap<>();
		for (char c : leaves) {
			hints.put(tile(c), SceneAtlas.HINT_FOLIAGE);
		}
		SceneAtlas atlas = new SceneAtlas(ShapeFixtures.ATLAS, 16, ShapeFixtures.LEGEND.length(), ShapeFixtures.ART_PX, List.of(), Set.of(), hints);
		return ShapePlanner.measured(map, 0, params, Structures.solidTiles(map), Structures.foliage(map, atlas), null);
	}

	/** The rows the column over tile ({@code tx}, {@code ty}) shows. */
	private static int shown(ShapePlanner.Columns columns, SceneMap map, int tx, int ty) {
		for (Structures.MeasuredRun run : columns.runs()) {
			if (run.tx() == tx && run.n() <= ty && ty <= run.f()) {
				return VisibleRows.shown(run, columns.blocks()[ty * map.artCols() + tx]);
			}
		}
		throw new AssertionError("no run at " + tx + "," + ty);
	}

	@Test
	void aFaceOverADoorKeepsItsRows() {
		// A wall one cell high with a door in it, and a cell of rock over the door: the door's face shows 4 rows over the
		// door's own 2. Today the door folds to the wall's 2 blocks and the rows over it vanish.
		SceneMap map = surface("DOOR",
				"....AA......",
				"....BB......",
				"CCCCCCCCCCCC",
				"DDDDDDDDDDDD",
				"....++......",
				"....++......",
				"............",
				"............");
		VisibleRows.Missing today = VisibleRows.check(map, columns(map, ShapeParams.cw3()), SCREEN);
		assertEquals(2, today.columns(), "the door's two columns, seen from the cell in front of it (the door's cell takes you in)");
		assertEquals(2 * 4, today.rows(), "the door's 2 blocks show the door: all 4 rows over it are lost");
		assertEquals(Map.of("door", 2), today.kinds());
		assertEquals(Map.of("door", "2,3"), today.first());
		ShapePlanner.Columns h9 = columns(map, ShapeParams.h9(SCREEN));
		assertEquals(6, shown(h9, map, 4, 3), "the door's 2 rows and the 4 over it");
		assertEquals(2, shown(h9, map, 0, 3), "the wall beside it stays 2");
		assertEquals(0, VisibleRows.check(map, h9, SCREEN).columns());
	}

	@Test
	void aMouthsModelSitsInAFaceThatRisesPastIt() {
		// A cave mouth: its arch drawn on the cell over the door holds a fitted model, a cell of rock over the arch. Today the
		// door folds the face to the wall's 2 blocks, the model in use, so the rock over the arch is lost (the user's Battle
		// Frontier mouth). With H9 the model stays in use as the doorway, and the rock behind it rises past it.
		SceneMap map = surface("MOUTH",
				"....RR......",
				"....RR......",
				"CCCCAACCCCCC",
				"DDDDBBDDDDDD",
				"....++......",
				"....++......",
				"............",
				"............");
		boolean[] arch = new boolean[map.artCols() * map.artRows()];
		for (int ty = 2; ty < 4; ty++) {
			arch[ty * 12 + 4] = true;
			arch[ty * 12 + 5] = true;
		}
		ShapePlanner.Columns today = withModel(map, ShapePlanner.measured(map, 0, ShapeParams.cw3(), Structures.solidTiles(map), null, arch), 2, 1);
		VisibleRows.Missing lost = VisibleRows.check(map, today, SCREEN);
		assertEquals(2, lost.columns());
		assertEquals(2 * 4, lost.rows(), "the arch's and the rock's rows over the door");
		ShapePlanner.Columns h9 = ShapePlanner.measured(map, 0, ShapeParams.h9(SCREEN), Structures.solidTiles(map), null, arch);
		Structures.MeasuredRun run = h9.runs().stream().filter(x -> x.tx() == 4).findFirst().orElseThrow();
		assertTrue(run.models());
		assertEquals(0, run.r(), "no roof: the arch's model stays in use");
		assertEquals(3, run.origin(), "no fold: the model is the doorway");
		assertEquals(4, run.h(), "the arch's rows and the rock's");
		assertEquals(0, VisibleRows.check(map, withModel(map, h9, 2, 1), SCREEN).columns());
	}

	/** {@code columns} with a fitted model's unit, a cell (2 blocks) tall, on cell ({@code cx}, {@code cy}). */
	private static ShapePlanner.Columns withModel(SceneMap map, ShapePlanner.Columns columns, int cx, int cy) {
		int[] blocks = columns.blocks().clone();
		for (int ty = 2 * cy; ty < 2 * cy + 2; ty++) {
			blocks[ty * map.artCols() + 2 * cx] = 2;
			blocks[ty * map.artCols() + 2 * cx + 1] = 2;
		}
		return new ShapePlanner.Columns(columns.keys(), blocks, columns.shapeMask(), columns.floor(), columns.decor(),
				List.of(new Units.Cell(cx, cy, ShapeKey.cube(0, 1, 1, 1, 1, 1, 1), false)), columns.runs(), columns.templates(), columns.wallTile(),
				columns.ceiling(), columns.hung(), columns.hungMask(), columns.hungLeaves(), columns.curtain(), columns.modelled());
	}

	@Test
	void aTallFaceStandsToTheScreensRows() {
		// Ten different rows: today 6 stand; the screen shows 8 over the floor in front.
		SceneMap map = surface("TALL",
				"AAAA",
				"BBBB",
				"CCCC",
				"DDDD",
				"EEEE",
				"FFFF",
				"GGGG",
				"HHHH",
				"IIII",
				"JJJJ",
				"....",
				"....");
		VisibleRows.Missing today = VisibleRows.check(map, columns(map, ShapeParams.cw3()), SCREEN);
		assertEquals(4, today.columns());
		assertEquals(2, today.worst());
		ShapePlanner.Columns h9 = columns(map, ShapeParams.h9(SCREEN));
		assertEquals(SCREEN, shown(h9, map, 0, 9));
		assertEquals(0, VisibleRows.check(map, h9, SCREEN).columns());
	}

	@Test
	void aCliffUnderItsRimStandsAsTallAsDrawnATreeLineStaysLow() {
		// A rim over a repeating body: today read as a repeat, 2 blocks; with H9 a face, all 8 rows. The same drawing in
		// foliage is a tree line under its crowns: rows of separate trees, 2 blocks each.
		String[] art = {
				"RRRR",
				"QQQQ",
				"SSSS",
				"TTTT",
				"SSSS",
				"TTTT",
				"SSSS",
				"TTTT",
				"....",
				"...."};
		SceneMap cliff = surface("CLIFF", art);
		assertEquals(2, shown(columns(cliff, ShapeParams.cw3()), cliff, 0, 7));
		ShapePlanner.Columns h9 = columns(cliff, ShapeParams.h9(SCREEN));
		assertEquals(8, shown(h9, cliff, 0, 7));
		assertEquals(0, VisibleRows.check(cliff, h9, SCREEN).columns());
		ShapePlanner.Columns trees = columns(cliff, ShapeParams.h9(SCREEN), 'R', 'Q', 'S', 'T');
		assertEquals(2, shown(trees, cliff, 0, 7));
		assertTrue(trees.runs().get(0).separate());
		assertEquals(0, VisibleRows.check(cliff, trees, SCREEN).columns(), "two rows of a tree line are enough");
		// A fitted model in use at the cliff's foot: the run stands as it stood (the model stays in use), and the check names
		// the rows the face lacks.
		ShapePlanner.Columns foot = withModels(cliff, ShapePlanner.measured(cliff, 0, ShapeParams.h9(SCREEN), Structures.solidTiles(cliff), null,
				fitted(cliff, 3)), 3);
		Structures.MeasuredRun run = foot.runs().get(0);
		assertTrue(run.models());
		assertEquals(2, run.h(), "as it stood");
		assertEquals(0, run.r());
		VisibleRows.Missing model = VisibleRows.check(cliff, foot, SCREEN);
		assertEquals(Map.of("model", 2), model.kinds());
		assertEquals(2 * 6, model.rows());
	}

	@Test
	void aFaceOfFittedModelsRisesModelByModel() {
		// The user's choice (2026-10-21): every cell of the cliff holds a fitted model; each is lifted onto the face below its
		// own rows, so the cliff shows all 8 rows and keeps all four models.
		SceneMap cliff = surface("STACK",
				"RRRR",
				"QQQQ",
				"SSSS",
				"TTTT",
				"SSSS",
				"TTTT",
				"SSSS",
				"TTTT",
				"....",
				"....");
		ShapePlanner.Columns all = withModels(cliff, ShapePlanner.measured(cliff, 0, ShapeParams.h9(SCREEN), Structures.solidTiles(cliff), null,
				fitted(cliff, 0, 1, 2, 3)), 0, 1, 2, 3);
		ShapeKey model = all.keys()[0][0];
		ShapePlanner.stackFaces(cliff, 0, SCREEN, all.runs(), all.keys(), all.blocks(), all.shapeMask(), all.units());
		int[] lift = {6, 6, 4, 4, 2, 2, 0, 0};
		for (int ty = 0; ty < 8; ty++) {
			assertEquals(lift[ty] + 2, all.blocks()[ty * 4], "row " + ty);
			assertEquals(3 << lift[ty], all.shapeMask()[ty * 4], "the model's blocks over the face's own");
		}
		assertEquals(model, all.keys()[0][6], "the back cell's model on top");
		assertEquals(0, VisibleRows.check(cliff, all, SCREEN).columns());
		// A model at the foot alone: it stays, and the face's other cells stand to its 8 rows.
		ShapePlanner.Columns foot = withModels(cliff, ShapePlanner.measured(cliff, 0, ShapeParams.h9(SCREEN), Structures.solidTiles(cliff), null,
				fitted(cliff, 3)), 3);
		ShapePlanner.stackFaces(cliff, 0, SCREEN, foot.runs(), foot.keys(), foot.blocks(), foot.shapeMask(), foot.units());
		assertEquals(2, foot.blocks()[7 * 4]);
		assertEquals(8, foot.blocks()[0]);
		assertEquals(0, foot.shapeMask()[0], "roofless: the model stays in use");
		assertEquals(0, VisibleRows.check(cliff, foot, SCREEN).columns());
	}

	/** The tiles of cells (0, cy) of {@code map}, for each {@code cy}: cells holding fitted models. */
	private static boolean[] fitted(SceneMap map, int... cys) {
		boolean[] out = new boolean[map.artCols() * map.artRows()];
		for (int cy : cys) {
			for (int ty = 2 * cy; ty < 2 * cy + 2; ty++) {
				out[ty * map.artCols()] = true;
				out[ty * map.artCols() + 1] = true;
			}
		}
		return out;
	}

	/** {@code columns} with a fitted model's unit, a cell (2 blocks) tall, on cells (0, cy) for each {@code cy}. */
	private static ShapePlanner.Columns withModels(SceneMap map, ShapePlanner.Columns columns, int... cys) {
		int[] blocks = columns.blocks().clone();
		int[] mask = columns.shapeMask().clone();
		List<Units.Cell> units = new java.util.ArrayList<>();
		for (int cy : cys) {
			for (int ty = 2 * cy; ty < 2 * cy + 2; ty++) {
				for (int tx = 0; tx < 2; tx++) {
					blocks[ty * map.artCols() + tx] = 2;
					mask[ty * map.artCols() + tx] = 3;
				}
			}
			units.add(new Units.Cell(0, cy, ShapeKey.cube(0, 1, 1, 1, 1, 1, 1), false));
		}
		return new ShapePlanner.Columns(columns.keys(), blocks, mask, columns.floor(), columns.decor(), units, columns.runs(),
				columns.templates(), columns.wallTile(), columns.ceiling(), columns.hung(), columns.hungMask(), columns.hungLeaves(),
				columns.curtain(), columns.modelled());
	}

	@Test
	void whatStandsBehindShowsItsOwnRows() {
		// A tree line behind a house: the house's 4 rows, then the trees' rows the screen has left. Built to nothing, the
		// trees lack their 2 rows; a model of their own (a fitted building) shows its rows.
		SceneMap map = surface("BEHIND",
				"TTTT",
				"UUUU",
				"TTTT",
				"UUUU",
				"AAAA",
				"BBBB",
				"CCCC",
				"DDDD",
				"....",
				"....");
		ShapePlanner.Columns h9 = columns(map, ShapeParams.h9(SCREEN), 'T', 'U');
		assertEquals(0, VisibleRows.check(map, h9, SCREEN).columns());
		int[] blocks = h9.blocks().clone();
		for (int i = 0; i < 4 * 4; i++) {
			blocks[i] = 0;
		}
		ShapePlanner.Columns bare = new ShapePlanner.Columns(h9.keys(), blocks, h9.shapeMask(), h9.runs());
		VisibleRows.Missing lacks = VisibleRows.check(map, bare, SCREEN);
		assertEquals(4, lacks.columns());
		assertEquals(2, lacks.worst());
		assertEquals(Map.of("repeat/behind", 4), lacks.kinds());
		boolean[] modelled = new boolean[blocks.length];
		for (int i = 0; i < 4 * 4; i++) {
			modelled[i] = true;
		}
		ShapePlanner.Columns fitted = new ShapePlanner.Columns(h9.keys(), blocks, h9.shapeMask(), h9.floor(), h9.decor(), h9.units(), h9.runs(),
				h9.templates(), h9.wallTile(), h9.ceiling(), h9.hung(), h9.hungMask(), h9.hungLeaves(), h9.curtain(), modelled);
		assertEquals(0, VisibleRows.check(map, fitted, SCREEN).columns());
	}
}
