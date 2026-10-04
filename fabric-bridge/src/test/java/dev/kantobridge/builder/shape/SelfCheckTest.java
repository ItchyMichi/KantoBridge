package dev.kantobridge.builder.shape;

import static dev.kantobridge.builder.shape.ShapeFixtures.scene;
import static dev.kantobridge.builder.shape.ShapeFixtures.surface;
import static dev.kantobridge.builder.shape.ShapeFixtures.surfaceAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import dev.kantobridge.builder.shape.geom.ShapeTable;
import dev.kantobridge.scene.ArtSource;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.world.ShapeIndex;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The self-check (docs/specs/VOXEL_BLOCKS_PLAN.md §5.6) on fixtures, with exact numbers: flat ground is the 2D map;
 * V2's folded wall shows every drawn row where the game draws it, while today's plain cubes show only their own tile
 * (half its rows wrong); a tall structure hides the ground behind it, which is reported, not scored.
 */
class SelfCheckTest {
	private static SelfCheck.Scores score(SceneMap map, boolean shaped) {
		return score(map, shaped, ShapeParams.v2());
	}

	private static SelfCheck.Scores score(SceneMap map, boolean shaped, ShapeParams params) {
		ShapePlan plan = ShapePlanner.plan(scene(map), List.of(surfaceAt(map, 0, 0)), params);
		ShapeIndex index = plan.index();
		if (!shaped) {
			// No shapes at all: every block draws the plain cube of its own tile, as before V2.
			ShapeParams p = ShapeParams.v2();
			index = ShapeIndex.of(p.planner(), p.toJson(), List.of(ShapeFixtures.ATLAS), List.of(ShapeFixtures.PALETTE), List.of(),
					new JsonArray());
		}
		ShapeTable table = ShapeTable.build(index, ArtSource.NONE);
		return SelfCheck.score(map.id(), new Reference(map, ShapeFixtures.ART_PX), map.atlas(),
				new PlannedGeometry(map, plan.shapes(map.id()), index, table), ShapeFixtures.ART_PX);
	}

	@Test
	void flatGroundIsTheMap() {
		SelfCheck.Scores s = score(surface("FLAT", "ab..", "cd..", "....", "~~~~"), true);
		assertEquals(0, s.holes());
		assertEquals(1.0, s.groundMatch());
		assertEquals(1.0, s.structureMatch(), "nothing to score");
		assertEquals(0.0, s.occludedGround());
		assertEquals(0, s.backFaces());
		assertEquals(16 * 64, s.groundPixels());
		assertEquals(0, s.structurePixels());
	}

	@Test
	void theFoldedWallShowsEveryDrawnRow() {
		SelfCheck.Scores shaped = score(ShapePlannerTest.WALL, true);
		assertEquals(0, shaped.holes());
		assertEquals(1.0, shaped.groundMatch(), "the ground that stays visible shows its own tiles");
		assertEquals(1.0, shaped.structureMatch(), "B at the bottom band, A above it: as drawn");
		// The wall's tops cover the two ground rows north of it: 8 of the 40 ground tiles.
		assertEquals(8 / 40.0, shaped.occludedGround(), 1e-9);
		assertEquals(8, shaped.backFaces(), "the back column's north faces, 4 columns x 2 bands");
		assertEquals(8 * 64, shaped.structurePixels());

		SelfCheck.Scores plain = score(ShapePlannerTest.WALL, false);
		assertEquals(0, plain.holes());
		assertEquals(0.5, plain.structureMatch(), "plain cubes show B on both bands: the A row is wrong");
	}

	@Test
	void aTallStructureMatchesToo() {
		SceneMap tall = surface("TALL",
				"........",
				"........",
				"AAAA....",
				"BBBB....",
				"CCCC....",
				"DDDD....",
				"........",
				"........");
		SelfCheck.Scores s = score(tall, true);
		assertEquals(0, s.holes());
		assertEquals(1.0, s.structureMatch());
		assertEquals(1.0, s.groundMatch());
		// Four blocks high: the tops cover the four rows north of the structure, here the two ground rows and the map's
		// edge above them; the ground rows 0 and 1 are hidden (8 of 48 ground tiles).
		assertEquals(8 / 48.0, s.occludedGround(), 1e-9);
	}

	@Test
	void measuredWallsStayExact() {
		// kb-shapes/2: the same two-row wall is a two-high run without a roof; still exactly the 2D map.
		SelfCheck.Scores s = score(ShapePlannerTest.WALL, true, ShapeParams.v3());
		assertEquals(0, s.holes());
		assertEquals(1.0, s.structureMatch());
		assertEquals(1.0, s.groundMatch());
	}

	@Test
	void aRoofedHouseHasNoHoles() {
		SceneMap house = surface("HOUSE",
				"........",
				"........",
				"RRRR....",
				"SSSS....",
				"FFFF....",
				"GGGG....",
				"........",
				"........");
		SelfCheck.Scores s = score(house, true, ShapeParams.v3());
		assertEquals(0, s.holes(), "the slopes close the roof");
		assertEquals(1.0, s.groundMatch());
		// The facade's bands show its rows exactly; the roof rows are laid on a one-block slope, so they match only roughly.
		assertTrue(s.structureMatch() >= 0.5 && s.structureMatch() < 1.0, "structure match " + s.structureMatch());
	}

	@Test
	void aHippedEndOverAFoldedDoorHasNoHoles() {
		// Found by the real build: the hipped end's south face is a triangle whose first two corners coincide.
		SceneMap house = surface("DOOR",
				"........",
				"........",
				"..DD....",
				"..FF....",
				"..GG....",
				"..KM....",
				"..++....",
				"..++....",
				"........",
				"........");
		SelfCheck.Scores s = score(house, true, ShapeParams.v3());
		assertEquals(0, s.holes());
	}

	@Test
	void tierANeverLeavesAHole() {
		// Random layouts of structures, uniform and drawn rows, doors below them: every pixel of the map is covered.
		java.util.Random random = new java.util.Random(7);
		String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
		for (int trial = 0; trial < 2000; trial++) {
			int w = 3 + random.nextInt(5);
			int h = 3 + random.nextInt(6);
			char[][] art = new char[h * 2][w * 2];
			for (char[] row : art) {
				java.util.Arrays.fill(row, '.');
			}
			for (int structures = 1 + random.nextInt(4); structures > 0; structures--) {
				int cx = random.nextInt(w);
				int cy = random.nextInt(h);
				int cw = 1 + random.nextInt(w - cx);
				int ch = 1 + random.nextInt(h - cy);
				for (int y = cy * 2; y < (cy + ch) * 2; y++) {
					char c = letters.charAt(random.nextInt(26));
					boolean uniform = random.nextInt(3) == 0;
					for (int x = cx * 2; x < (cx + cw) * 2; x++) {
						art[y][x] = uniform || random.nextInt(4) != 0 ? c : letters.charAt(random.nextInt(26));
					}
				}
				if (random.nextBoolean() && cy + ch < h) {
					int dx = cx + random.nextInt(cw);
					for (int k = 0; k < 4; k++) {
						art[(cy + ch) * 2 + k / 2][dx * 2 + k % 2] = '+';
					}
				}
			}
			String[] rows = new String[art.length];
			for (int i = 0; i < art.length; i++) {
				rows[i] = new String(art[i]);
			}
			SelfCheck.Scores s = score(surface("FUZZ", rows), true, ShapeParams.v3());
			assertEquals(0, s.holes(), "trial " + trial + ": " + String.join("/", rows));
		}
	}
}
