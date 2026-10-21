package dev.kantobridge.builder.shape.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.shape.ShapeKey;
import dev.kantobridge.builder.shape.Templates;
import dev.kantobridge.scene.ArtSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Tier C geometry (docs/specs/VOXEL_BLOCKS_PLAN.md §5.5, V7): the bands of a template from its own drawing. The roof
 * rows are those above the highest pane (or a pack's rule); the facade folds upright at each run's front, a folded
 * door's columns showing the door; panes sink one pixel; the roof lies on the facade showing what the game's view shows
 * there; no pane and no rule: the Tier A columns stay, not as a failure. What it builds stays inside the template's
 * volume and is seen from the game's view as drawn.
 */
class BandsTest {
	static final String ATLAS = "tilesets.fix";
	static final int ROOF = 2;
	static final int WALL = 2;
	static final int DOOR = 3;

	/** A 4 × 4-tile house (two roof rows, two facade rows) over a door cell under its east half, 3 blocks high. */
	static Templates.Entry house() {
		int w = 4;
		int d = 6;
		int[] tiles = new int[w * d];
		ShapeKey[][] columns = new ShapeKey[w * d][];
		for (int y = 0; y < d; y++) {
			for (int x = 0; x < w; x++) {
				int i = y * w + x;
				if (y < 4) {
					int t = 1 + y * w + x;
					tiles[i] = t;
					columns[i] = new ShapeKey[] {ShapeKey.cube(0, t, t, t, t, t, t), ShapeKey.cube(0, t, t, t, t, t, t),
							ShapeKey.roof(0, t, t, t, 16, 16, x == 0, x == w - 1)};
				} else {
					tiles[i] = x >= 2 ? 17 + (y - 4) * 2 + (x - 2) : -1;
				}
			}
		}
		return new Templates.Entry("0123456789abcdef", 0, w, d, 2, tiles, new int[][] {{1, 2}}, 0, true, columns);
	}

	/** The house's art: the roof and wall in mid shades, the door dark; with a pane in the facade's upper west tile. */
	static ArtSource art(boolean pane) {
		Map<Integer, int[]> tiles = new HashMap<>();
		for (int t = 1; t <= 8; t++) {
			tiles.put(t, filled(ROOF));
		}
		for (int t = 9; t <= 16; t++) {
			tiles.put(t, filled(WALL));
		}
		for (int t = 17; t <= 20; t++) {
			tiles.put(t, filled(DOOR));
		}
		if (pane) {
			int[] window = filled(WALL);
			for (int v = 1; v <= 5; v++) {
				for (int u = 1; u <= 5; u++) {
					window[v * 8 + u] = u >= 2 && u <= 4 && v >= 2 && v <= 4 ? 0 : 3;
				}
			}
			tiles.put(9, window);
		}
		return ArtFixtures.art(ATLAS, tiles, Map.of());
	}

	static int[] filled(int shade) {
		int[] out = new int[64];
		java.util.Arrays.fill(out, shade);
		return out;
	}

	static ShapeOverrides rule(int roofRows) {
		return new ShapeOverrides() {
			@Override
			public int roofRows(String atlas, String sig) {
				return roofRows;
			}
		};
	}

	@Test
	void theRoofRowsLieAboveTheHighestPaneAndTheFacadeFolds() {
		TemplateRule.Template t = TemplateRule.build(house(), ATLAS, art(true), ShapeOverrides.NONE);
		assertEquals(TemplateRule.Kind.BUILDING, t.kind(), "match " + t.match() + ", quads " + t.maxQuads());
		assertEquals(2, t.roofRows(), "the pane lies in row 2: rows 0 and 1 are roof");
		assertTrue(t.match() >= TemplateRule.MATCH);
		assertTrue(t.maxQuads() <= CellUnits.BUDGET_QUADS);
		VoxelGrid v = t.voxels();
		assertNotNull(v);
		// The west run (no door): the facade up to 2 blocks (rows 2..3 folded), the roof layer on top at 16/8 px.
		int front = 3 * 8 + 7;
		assertTrue(v.solid(0, 0, front), "the facade stands at the run's front");
		assertTrue(v.solid(0, 15, front));
		assertTrue(v.solid(0, 16, front), "the roof's front edge");
		assertFalse(v.solid(0, 17, front), "nothing above the roof");
		assertEquals(7, VoxelGrid.v(v.at(0, 0, front)), "band 0 shows the front row's lowest pixels");
		// The pane: the upper west facade tile (row 2), pixels u 2..4, v 2..4: at heights 31 - (16 + v).
		for (int y = 11; y <= 13; y++) {
			for (int x = 2; x <= 4; x++) {
				assertFalse(v.solid(x, y, front), "the pane sinks: " + x + "," + y);
				assertTrue(v.solid(x, y, front - 1));
			}
		}
		assertTrue(v.solid(1, 12, front), "its frame stays at the front");
		// The east runs (a door folds into them): their facade shows the door's rows.
		assertEquals(19, southTile(t, 2, 0, 3), "band 0 of a folded run: the door's lowest row");
		assertEquals(13, southTile(t, 0, 0, 3), "band 0 of the west run: its front row");
		// Everything inside the volume: three blocks high, the template's width and depth.
		for (int key : t.blocks().keySet()) {
			assertTrue(QuadMerger.blockY(key) < 3 && QuadMerger.blockX(key) < 4 && QuadMerger.blockZ(key) < 4, "block " + key);
		}
	}

	@Test
	void withoutAPaneTheTierAColumnsStay() {
		Templates.Entry house = house();
		TemplateRule.Template t = TemplateRule.build(house, ATLAS, art(false), ShapeOverrides.NONE);
		assertEquals(TemplateRule.Kind.COLUMNS, t.kind());
		assertNull(t.failed(), "not a failure: the generic volume");
		assertEquals(-1, t.roofRows());
		assertEquals(KeyGeometry.direct(house.columns()[0][0], tile -> tile).size(), t.block(0, 0, 0).size(), "the Tier A cube");
		assertEquals(KeyGeometry.direct(house.columns()[0][2], tile -> tile).size(), t.block(0, 2, 0).size(), "the Tier A roof");
	}

	@Test
	void aPacksRuleSetsTheBands() {
		TemplateRule.Template t = TemplateRule.build(house(), ATLAS, art(false), rule(2));
		assertEquals(TemplateRule.Kind.BUILDING, t.kind(), "the rule replaces the pane heuristic");
		assertEquals(2, t.roofRows());
	}

	@Test
	void aPacksModelReplacesItWhenItFits() {
		Map<Integer, List<ShapeQuad>> blocks = new HashMap<>();
		blocks.put(QuadMerger.blockKey(1, 1, 1), List.of(ShapeQuad.fullFace(ShapeQuad.UP, 3)));
		int[] colours = new int[256];
		colours[3] = 0xFF00FF00;
		ShapeOverrides model = new ShapeOverrides() {
			@Override
			public @Nullable StructModel templateModel(String atlas, String sig) {
				return new StructModel(blocks, colours);
			}
		};
		TemplateRule.Template t = TemplateRule.build(house(), ATLAS, art(true), model);
		assertEquals(TemplateRule.Kind.MODEL, t.kind());
		assertEquals(0xFF00FF00, t.colours()[3]);
		Map<Integer, List<ShapeQuad>> outside = new HashMap<>();
		outside.put(QuadMerger.blockKey(1, 3, 1), List.of(ShapeQuad.fullFace(ShapeQuad.UP, 3)));
		ShapeOverrides tooTall = new ShapeOverrides() {
			@Override
			public @Nullable StructModel templateModel(String atlas, String sig) {
				return new StructModel(outside, colours);
			}
		};
		assertEquals(TemplateRule.Kind.BUILDING, TemplateRule.build(house(), ATLAS, art(true), tooTall).kind(), "a model out of the volume: not used");
	}

	@Test
	void artThisClientLacksKeepsTheColumns() {
		ArtSource none = ArtFixtures.art(ATLAS, Map.of(), Map.of());
		assertEquals(TemplateRule.Kind.COLUMNS, TemplateRule.build(house(), ATLAS, none, ShapeOverrides.NONE).kind());
	}

	@Test
	void theRoofCyclesItsMiddleRowsNorthOfTheDrawing() {
		assertEquals(5, TemplateRule.roofRow(5, 0, 2, 8), "inside the drawing: itself");
		assertEquals(8 * 1 + 7, TemplateRule.roofRow(-1, 0, 2, 8), "two roof rows: both cycled");
		assertEquals(8 + 7, TemplateRule.roofRow(-1, 0, 3, 8), "three roof rows: the middle one cycled");
		assertEquals(8, TemplateRule.roofRow(-8, 0, 3, 8));
	}

	/** The tile the south face of a block shows (the building's quads carry tile ids). */
	private static int southTile(TemplateRule.Template t, int bx, int by, int bz) {
		for (ShapeQuad quad : t.block(bx, by, bz)) {
			if (quad.face() == ShapeQuad.SOUTH && quad.z(0) == 16) {
				return quad.texture();
			}
		}
		return -1;
	}
}
