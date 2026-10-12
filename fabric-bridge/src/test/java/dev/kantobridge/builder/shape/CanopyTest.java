package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.shape.dressing.Canopy;
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
 * A wood's canopy and vines (docs/specs/CAVES_AND_WOODS_PLAN.md §5.2-§5.3, CW2): every cell's columns hold leaves from
 * its underside (40-62 px; a light well's 74-80) up to the canopy's top, all foliage; strands hang from the underside,
 * never below head height over a path nor into a tree, never in a well; nothing outside a canopy wood or before
 * kb-shapes/10. From kb-shapes/11 the wood's apron (CW3) replaces the ring's curtain.
 */
class CanopyTest {
	static SceneMap wood(String id, boolean canopy, boolean openAir, String... rows) {
		SceneMap m = ShapeFixtures.interior(id, rows);
		return new SceneMap(m.id(), m.index(), m.tileset(), m.atlas(), m.palette(), m.w(), m.h(), m.perCell(), m.surface(),
				new Env(false, openAir, false, Env.DARK_NEVER, canopy), m.tiles(), m.closedTiles(), m.kinds(), m.tags(), m.dirs(), m.border(),
				m.ownBorder(), m.objects(), m.swapSites());
	}

	/** A wood: tree cells ('T', solid, two blocks) standing in a checkerboard on a path. */
	static String[] wood(int w, int h) {
		String[] rows = new String[h * 2];
		for (int cy = 0; cy < h; cy++) {
			StringBuilder row = new StringBuilder();
			for (int cx = 0; cx < w; cx++) {
				row.append((cx + cy) % 3 == 0 ? "TT" : "..");
			}
			rows[cy * 2] = row.toString();
			rows[cy * 2 + 1] = row.toString();
		}
		return rows;
	}

	static ShapePlanner.MapShapes plan(SceneMap map, ShapeParams params, Map<String, Integer> tally) {
		return ShapePlanner.planMap(map, ShapeFixtures.TIER_B, 0, params, null, null, tally);
	}

	/** The lowest level a canopy piece (a hang box or a cube) takes in column i, or -1. */
	static int canopyBottom(ShapePlanner.MapShapes shapes, int i) {
		ShapeKey[] column = shapes.hung()[i];
		for (int k = 0; column != null && k < column.length; k++) {
			if (column[k] != null && (column[k].kind() == ShapeKey.Kind.HANG || column[k].kind() == ShapeKey.Kind.CUBE)) {
				return k;
			}
		}
		return -1;
	}

	@Test
	void everyCellHoldsLeavesUpToTheTop() {
		SceneMap map = wood("WOOD", true, true, wood(16, 14));
		Map<String, Integer> tally = new TreeMap<>();
		ShapePlanner.MapShapes shapes = plan(map, ShapeParams.cw2(), tally);
		int cols = map.artCols();
		int wells = 0;
		for (int cy = 0; cy < map.h(); cy++) {
			for (int cx = 0; cx < map.w(); cx++) {
				boolean well = CellHash.oneIn(cx, cy, 5, 9);
				wells += well ? 1 : 0;
				for (int d = 0; d < 4; d++) {
					int i = (cy * 2 + d / 2) * cols + cx * 2 + d % 2;
					int bottom = canopyBottom(shapes, i);
					ShapeKey[] column = shapes.hung()[i];
					for (int k = Math.max(bottom, 0); k < Canopy.TOP_LEVEL; k++) {
						assertNotNull(column[k], "leaves up to the top at " + cx + "," + cy + " level " + k);
						assertTrue((shapes.hungLeaves()[i] >> k & 1) != 0, "foliage carriers");
					}
					assertEquals(Canopy.TOP_LEVEL, column.length, "nothing above the top");
					if (well) {
						assertEquals(Canopy.TOP_LEVEL - 1, bottom, "a well shows the upper layer, 74-80 px");
					} else {
						int low = (int) Math.round(40 + 22 * CellHash.hash01(cx, cy, 2)) / 8;
						assertTrue(low >= 5 && low <= 7, "the underside at 40-62 px");
						boolean hollow = column[low] != null && column[low].kind() != ShapeKey.Kind.HANG && column[low].kind() != ShapeKey.Kind.CUBE;
						assertEquals(hollow ? low + 1 : low, bottom, "leaves from the underside, or above a strand's hollow");
					}
				}
			}
		}
		assertTrue(wells > 0);
		assertEquals(wells, tally.get("dress_canopy_well"));
		assertEquals(map.w() * map.h() - wells, tally.get("dress_canopy_cell"));
		int t = ShapeFixtures.tile('T');
		assertEquals(ShapeKey.cube(0, t, t, t, t, t, t).withTint(2), shapes.curtain(), "the commonest tall tile, darkened");
	}

	@Test
	void strandsHangFromTheUndersideAndKeepPathsClear() {
		SceneMap map = wood("WOOD", true, true, wood(24, 24));
		Map<String, Integer> tally = new TreeMap<>();
		ShapePlanner.MapShapes shapes = plan(map, ShapeParams.cw2(), tally);
		int cols = map.artCols();
		int vines = 0;
		int strands = 0;
		for (int i = 0; i < shapes.hung().length; i++) {
			ShapeKey[] column = shapes.hung()[i];
			if (column == null) {
				continue;
			}
			int tx = i % cols;
			int ty = i / cols;
			int lowest = Integer.MAX_VALUE;
			int strandTop = -1;
			for (int k = 0; k < column.length; k++) {
				ShapeKey key = column[k];
				if (key == null || (key.kind() != ShapeKey.Kind.VINE && key.kind() != ShapeKey.Kind.STRAND)) {
					continue;
				}
				boolean vine = key.kind() == ShapeKey.Kind.VINE;
				int top = key.number(vine ? 5 : 3);
				int len = key.number(vine ? 6 : 4);
				int b = key.number(vine ? 7 : 5);
				// The strand's tail, in 1/16 block from the floor: this level's bottom plus its tail in this block.
				int tail = 16 * k + top - len + 16 * b;
				lowest = Math.min(lowest, tail);
				strandTop = Math.max(strandTop, k);
				if (b == 0) {
					vines += vine ? 1 : 0;
					strands += vine ? 0 : 1;
				}
			}
			if (strandTop < 0) {
				continue;
			}
			CellKind kind = map.kindAtTile(tx, ty);
			int clear = kind == CellKind.WALK ? 2 * CaveRock.CLEAR : 16 * shapes.blocks()[i];
			assertTrue(lowest >= clear, "a strand's tail at head height over a path, at a tree's top: " + tx + "," + ty);
			assertTrue(!CellHash.oneIn(tx / 2, ty / 2, 5, 9), "no strand in a well");
			int bottom = canopyBottom(shapes, i);
			assertEquals(strandTop + 1, bottom, "a strand hangs right under the leaves, or from their hollow");
		}
		assertTrue(vines > 0 && strands > 0, vines + " vines, " + strands + " strands");
		assertEquals(vines, tally.get("dress_vine"));
		assertEquals(strands, tally.get("dress_strand"));
	}

	@Test
	void nothingOutsideACanopyWoodOrBeforeIt() {
		for (SceneMap map : new SceneMap[] {wood("PARK", false, true, wood(8, 8)), wood("ROOM", true, false, wood(8, 8))}) {
			ShapePlanner.MapShapes shapes = plan(map, ShapeParams.cw2(), new TreeMap<>());
			assertTrue(Arrays.stream(shapes.hungMask()).allMatch(m -> m == 0), map.id());
			assertNull(shapes.curtain());
		}
		ShapePlanner.MapShapes before = plan(wood("WOOD", true, true, wood(8, 8)), ShapeParams.cw1(), new TreeMap<>());
		assertTrue(Arrays.stream(before.hungMask()).allMatch(m -> m == 0), "kb-shapes/9 plans no canopy");
	}

	@Test
	void cw3GivesTheWoodItsApronInsteadOfTheCurtain() {
		SceneMap map = wood("WOOD", true, true, wood(10, 9));
		Map<String, Integer> tally = new TreeMap<>();
		ShapePlanner.MapShapes shapes = plan(map, ShapeParams.cw3(), tally);
		ShapePlanner.MapShapes before = plan(map, ShapeParams.cw2(), new TreeMap<>());
		assertNull(shapes.curtain(), "the apron's canopy and moss wall replace the ring's curtain");
		assertNotNull(shapes.apron());
		assertEquals(ShapeFixtures.tile('.'), shapes.apron().floorTile(), "no grass shown: the map's ground");
		assertTrue(Arrays.deepEquals(before.hung(), shapes.hung()), "the canopy itself is CW2's");
		assertTrue(tally.get("dress_apron_cell") > 0);
		assertNull(before.apron(), "kb-shapes/10 plans no apron");
		assertNotNull(before.curtain());
		for (SceneMap other : new SceneMap[] {wood("PARK", false, true, wood(8, 8)), wood("ROOM", true, false, wood(8, 8))}) {
			assertNull(plan(other, ShapeParams.cw3(), new TreeMap<>()).apron(), other.id());
		}
	}

	@Test
	void theSameWoodPlansTheSameCanopy() {
		SceneMap map = wood("WOOD", true, true, wood(10, 9));
		ShapePlanner.MapShapes a = plan(map, ShapeParams.cw2(), new TreeMap<>());
		ShapePlanner.MapShapes b = plan(map, ShapeParams.cw2(), new TreeMap<>());
		assertTrue(Arrays.deepEquals(a.hung(), b.hung()));
		assertTrue(Arrays.equals(a.hungLeaves(), b.hungLeaves()));
	}
}
