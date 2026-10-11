package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.builder.shape.dressing.CaveRock;
import dev.kantobridge.builder.shape.dressing.CellHash;
import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.Env;
import dev.kantobridge.scene.SceneMap;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Cave rock (docs/specs/CAVES_AND_WOODS_PLAN.md §5.1, CW1): every roofed cell of a cave gets a box hanging under the
 * ceiling at its hashed depth, one cell in five a spike; walls up to the ceiling and void cells get none; over a path
 * nothing comes below head height and no stalagmite stands; nothing at all outside caves or before kb-shapes/9.
 */
class CaveRockTest {
	/** Rock along the north edge (a wall up to the ceiling), floor, a rock cell, water, a void cell (the border's tiles). */
	static final String[] CAVE = {
			"RRRRRRRRRRRRRRRR",
			"RRRRRRRRRRRRRRRR",
			"................",
			"................",
			"..SS......~~....",
			"..SS......~~....",
			"......##........",
			"......##........"};

	static SceneMap cave(String id, boolean cave, boolean openAir, String... rows) {
		SceneMap m = ShapeFixtures.interior(id, rows);
		return new SceneMap(m.id(), m.index(), m.tileset(), m.atlas(), m.palette(), m.w(), m.h(), m.perCell(), m.surface(),
				new Env(false, openAir, cave, Env.DARK_NEVER), m.tiles(), m.closedTiles(), m.kinds(), m.tags(), m.dirs(), m.border(), m.ownBorder(),
				m.objects(), m.swapSites());
	}

	static ShapePlanner.MapShapes plan(SceneMap map, ShapeParams params, Map<String, Integer> tally) {
		return ShapePlanner.planMap(map, ShapeFixtures.TIER_B, 0, params, null, null, tally);
	}

	/** A cave floor with rock cells standing free in a checkerboard, away from the edges. */
	static String[] field(int w, int h) {
		String[] rows = new String[h * 2];
		for (int cy = 0; cy < h; cy++) {
			StringBuilder row = new StringBuilder();
			for (int cx = 0; cx < w; cx++) {
				boolean rock = cy > 0 && cx > 0 && cx < w - 1 && (cx + cy) % 2 == 0;
				row.append(rock ? "SS" : "..");
			}
			rows[cy * 2] = row.toString();
			rows[cy * 2 + 1] = row.toString();
		}
		return rows;
	}

	@Test
	void roofedCellsHangUnderTheCeiling() {
		SceneMap map = cave("CAVE", true, false, CAVE);
		Map<String, Integer> tally = new TreeMap<>();
		ShapePlanner.MapShapes shapes = plan(map, ShapeParams.cw1(), tally);
		int cols = map.artCols();
		int roofed = 0;
		for (int cy = 0; cy < map.h(); cy++) {
			for (int cx = 0; cx < map.w(); cx++) {
				boolean wall = cy == 0;
				boolean voidCell = cx == 3 && cy == 3;
				int y0 = -1;
				for (int dy = 0; dy < 2; dy++) {
					for (int dx = 0; dx < 2; dx++) {
						int i = (cy * 2 + dy) * cols + cx * 2 + dx;
						if (wall || voidCell) {
							assertEquals(0, shapes.hungMask()[i], "no rock over a wall up to the ceiling or a void cell");
							continue;
						}
						assertEquals(1 << CaveRock.LEVEL, shapes.hungMask()[i], "the block under the ceiling");
						ShapeKey key = shapes.hung()[i][CaveRock.LEVEL];
						assertEquals(ShapeKey.Kind.HANG, key.kind());
						assertEquals(shapes.wallTile(), key.number(0), "the room's wall tile");
						assertTrue(key.number(1) % 2 == 0 && key.number(1) <= 12, "whole pixels, 16..22 from the floor: " + key);
						assertEquals(2 * (CaveRock.rockAt(cx, cy) - 16), key.number(1));
						if (y0 >= 0) {
							assertEquals(y0, key.number(1), "a cell's blocks share one depth");
						}
						y0 = key.number(1);
						assertNull(shapes.open()[i] == null ? null : shapes.open()[i].length > CaveRock.LEVEL ? shapes.open()[i][CaveRock.LEVEL] : null,
								"hangings stay out of the columns' keys (the self-check never sees them)");
					}
				}
				roofed += wall || voidCell ? 0 : 1;
			}
		}
		assertEquals(roofed, tally.get("dress_rock_roof"));
		assertTrue(shapes.wallTile() >= 0);
		assertEquals(3, shapes.blocks()[0], "the north wall rises to the ceiling");
	}

	@Test
	void nothingOutsideCavesOrBeforeTheCavePlan() {
		for (SceneMap map : new SceneMap[] {cave("ROOM", false, false, CAVE), cave("WOOD", true, true, CAVE)}) {
			ShapePlanner.MapShapes shapes = plan(map, ShapeParams.cw1(), new TreeMap<>());
			assertTrue(Arrays.stream(shapes.hungMask()).allMatch(m -> m == 0), map.id());
		}
		ShapePlanner.MapShapes before = plan(cave("CAVE", true, false, CAVE), ShapeParams.p3(), new TreeMap<>());
		assertTrue(Arrays.stream(before.hungMask()).allMatch(m -> m == 0), "kb-shapes/8 plans no rock");
	}

	@Test
	void spikesFollowTheModsCellsAndKeepPathsClear() {
		SceneMap map = cave("FIELD", true, false, field(24, 24));
		Map<String, Integer> tally = new TreeMap<>();
		ShapePlanner.MapShapes shapes = plan(map, ShapeParams.cw1(), tally);
		int cols = map.artCols();
		int stalagmites = 0;
		int stalactites = 0;
		for (int cy = 0; cy < map.h(); cy++) {
			for (int cx = 0; cx < map.w(); cx++) {
				ShapeKey spike = null;
				int spikes = 0;
				for (int dy = 0; dy < 2; dy++) {
					for (int dx = 0; dx < 2; dx++) {
						ShapeKey key = shapes.hung()[(cy * 2 + dy) * cols + cx * 2 + dx][CaveRock.LEVEL];
						if (key.number(2) != ShapeKey.SPIKE_NONE) {
							spike = key;
							spikes++;
						}
					}
				}
				assertTrue(spikes <= 1, "one spike a cell at most");
				CellKind kind = map.kind(cx, cy);
				int rock = CaveRock.rockAt(cx, cy);
				boolean picked = CellHash.oneIn(cx, cy, CaveRock.SALT_SPIKE, CaveRock.SPIKE_EVERY);
				if (kind == CellKind.WALK) {
					// Over a path: only stalactites, their tips at or above head height; dropped when no room is left.
					assertEquals(picked && rock - CaveRock.CLEAR >= 2, spike != null, "cell " + cx + "," + cy);
				}
				if (spike == null) {
					continue;
				}
				assertTrue(picked, "a spike only where the mod has one");
				int base = spike.number(3);
				int apex = spike.number(4);
				if (spike.number(2) == ShapeKey.SPIKE_UP) {
					stalagmites++;
					assertEquals(CellKind.SOLID, kind, "a stalagmite only on rock");
					assertEquals(0, base, "rising from the rock's top");
					assertTrue(apex <= spike.number(1) - 4, "short of the rock above by two pixels");
				} else {
					stalactites++;
					assertEquals(spike.number(1), base, "hanging from the rock");
					assertTrue(apex >= (kind == CellKind.WALK ? 0 : 2), "tips at head height over a path, a pixel above rock");
					assertTrue(base - apex >= 4, "at least two pixels long");
				}
			}
		}
		assertTrue(stalagmites > 0 && stalactites > 0, stalagmites + " up, " + stalactites + " down");
		assertEquals(stalagmites, tally.get("dress_rock_stalagmite"));
		assertEquals(stalactites, tally.get("dress_rock_stalactite"));
		assertNotNull(tally.get("dress_rock_spike_dropped"));
	}

	@Test
	void theSameMapPlansTheSameRock() {
		SceneMap map = cave("FIELD", true, false, field(12, 10));
		ShapePlanner.MapShapes a = plan(map, ShapeParams.cw1(), new TreeMap<>());
		ShapePlanner.MapShapes b = plan(map, ShapeParams.cw1(), new TreeMap<>());
		assertTrue(Arrays.deepEquals(a.hung(), b.hung()));
		assertEquals(WorldPlan.CEILING_ABOVE_FEET - 1, CaveRock.LEVEL);
	}
}
