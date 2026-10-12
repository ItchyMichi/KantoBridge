package dev.kantobridge.builder.shape.dressing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.shape.ShapeFixtures;
import dev.kantobridge.builder.zone.ZoneBlock;
import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.Env;
import dev.kantobridge.scene.SceneMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The forest's apron (docs/specs/CAVES_AND_WOODS_PLAN.md §5.4, CW3): four cells of vanilla wood past a canopy wood's
 * edge, each mirroring the nearest map cell by the mod's odds; a thin canopy over it; vines on its leaves out of reach;
 * an invisible wall without a gap where the enclosure ring was; a moss wall around it all.
 */
class ApronTest {
	/** A wood: tree cells ('T', solid) in a pattern on a path, with a grass cell (',') at (1, 1). */
	static SceneMap wood(int w, int h) {
		String[] rows = new String[h * 2];
		for (int cy = 0; cy < h; cy++) {
			StringBuilder row = new StringBuilder();
			for (int cx = 0; cx < w; cx++) {
				row.append(cx == 1 && cy == 1 ? ",," : (cx + cy) % 3 == 0 ? "TT" : "..");
			}
			rows[cy * 2] = row.toString();
			rows[cy * 2 + 1] = row.toString();
		}
		SceneMap m = ShapeFixtures.interior("WOOD", rows);
		return new SceneMap(m.id(), m.index(), m.tileset(), m.atlas(), m.palette(), m.w(), m.h(), m.perCell(), m.surface(),
				new Env(false, true, false, Env.DARK_NEVER, true), m.tiles(), m.closedTiles(), m.kinds(), m.tags(), m.dirs(), m.border(),
				m.ownBorder(), m.objects(), m.swapSites());
	}

	static final SceneMap WOOD = wood(12, 10);
	static final int FLOOR = ShapeFixtures.tile(',');

	static boolean inMap(SceneMap map, int cx, int cy) {
		return cx >= 0 && cy >= 0 && cx < map.w() && cy < map.h();
	}

	static ZoneBlock leavesOf(int cx, int cy) {
		return CellHash.hash01(cx, cy, Canopy.SALT_LEAF) < 0.5 ? ZoneBlock.OAK_LEAVES : ZoneBlock.DARK_OAK_LEAVES;
	}

	@Test
	void theBoxHoldsTheWoodAndItsWall() {
		Apron.Plan plan = Apron.plan(WOOD, FLOOR, new TreeMap<>());
		int cols = WOOD.artCols();
		int rows = WOOD.artRows();
		assertEquals(Apron.CELLS * 2 + 1, plan.margin(), "four cells and the moss wall");
		for (int ty = -plan.margin(); ty < rows + plan.margin(); ty++) {
			for (int tx = -plan.margin(); tx < cols + plan.margin(); tx++) {
				int out = Apron.outside(tx, ty, cols, rows);
				byte expected = out == 0 ? Apron.NONE : out <= Apron.CELLS * 2 ? Apron.WOOD : Apron.WALL;
				assertEquals(expected, plan.kind(tx, ty), tx + "," + ty);
				assertEquals(expected == Apron.WOOD, plan.column(tx, ty) != null);
			}
		}
		assertEquals(Apron.NONE, plan.kind(-plan.margin() - 1, 0), "nothing past the wall");
	}

	@Test
	void eachCellMirrorsTheNearestMapCell() {
		Map<String, Integer> tally = new TreeMap<>();
		Apron.Plan plan = Apron.plan(WOOD, FLOOR, tally);
		int bushes = 0;
		int trunks = 0;
		int talls = 0;
		for (int cy = -Apron.CELLS; cy < WOOD.h() + Apron.CELLS; cy++) {
			for (int cx = -Apron.CELLS; cx < WOOD.w() + Apron.CELLS; cx++) {
				if (inMap(WOOD, cx, cy)) {
					continue;
				}
				ZoneBlock leaves = leavesOf(cx, cy);
				int low = Canopy.bottom(cx, cy) / 8;
				boolean mirror = CellHash.hash01(cx, cy, Apron.SALT_RING) <= Apron.DENSITY;
				CellKind source = WOOD.kind(Math.max(0, Math.min(WOOD.w() - 1, cx)), Math.max(0, Math.min(WOOD.h() - 1, cy)));
				boolean solid = source != CellKind.WALK && source != CellKind.LEDGE;
				int logs = 0;
				for (int d = 0; d < 4; d++) {
					int tx = cx * 2 + d % 2;
					int ty = cy * 2 + d / 2;
					boolean ring = tx >= -1 && ty >= -1 && tx <= WOOD.artCols() && ty <= WOOD.artRows();
					if (mirror && solid) {
						assertEquals(leaves, plan.at(tx, ty, 0), "a bush at " + cx + "," + cy);
						assertEquals(leaves, plan.at(tx, ty, Apron.TREE_LEVELS - 1));
					} else {
						for (int k = 0; k < Apron.TREE_LEVELS; k++) {
							ZoneBlock block = plan.at(tx, ty, k);
							boolean log = block == ZoneBlock.OAK_LOG || block == ZoneBlock.DARK_OAK_LOG;
							logs += log && k == 0 ? 1 : 0;
							assertTrue(log || block == (ring ? ZoneBlock.BARRIER : null), "open ground at " + cx + "," + cy + ": " + block);
						}
					}
				}
				boolean trunk = mirror && !solid && CellHash.hash01(cx, cy, Apron.SALT_TRUNK) < Apron.TRUNK_ODDS;
				assertEquals(trunk ? 1 : 0, logs, "one trunk in 30% of the mirrored open cells at " + cx + "," + cy);
				if (trunk) {
					int corner = (int) Math.floor(4 * CellHash.hash01(cy, cx, Apron.SALT_TRUNK));
					int tx = cx * 2 + (corner & 1);
					int ty = cy * 2 + (corner >> 1);
					for (int k = 0; k < low; k++) {
						assertTrue(plan.at(tx, ty, k) == ZoneBlock.OAK_LOG || plan.at(tx, ty, k) == ZoneBlock.DARK_OAK_LOG, "logs up into the canopy");
					}
					assertTrue(Apron.leaves(plan.at(tx, ty, low)), "the canopy over the trunk");
					trunks++;
				}
				if (mirror && solid) {
					bushes++;
					if (CellHash.hash01(cx, cy, Apron.SALT_TALL) < Apron.TALL_ODDS) {
						int top = (int) Math.round((24 + 20 * CellHash.hash01(cx, cy, Apron.SALT_TALL_HEIGHT)) / 8);
						assertTrue(top >= 3 && top <= 6);
						assertEquals(leaves, plan.at(cx * 2, cy * 2, top - 1), "carried up taller");
						talls++;
					}
				}
			}
		}
		assertTrue(bushes > 0 && trunks > 0 && talls > 0, bushes + " bushes, " + trunks + " trunks, " + talls + " taller");
		assertEquals(bushes, tally.get("dress_apron_bush"));
		assertEquals(trunks, tally.get("dress_apron_trunk"));
		assertEquals(talls, tally.get("dress_apron_tall"));
	}

	@Test
	void theCanopyIsThin() {
		Map<String, Integer> tally = new TreeMap<>();
		Apron.Plan plan = Apron.plan(WOOD, FLOOR, tally);
		int cells = 0;
		int wells = 0;
		for (int cy = -Apron.CELLS; cy < WOOD.h() + Apron.CELLS; cy++) {
			for (int cx = -Apron.CELLS; cx < WOOD.w() + Apron.CELLS; cx++) {
				if (inMap(WOOD, cx, cy)) {
					continue;
				}
				boolean well = Canopy.well(cx, cy);
				cells += well ? 0 : 1;
				wells += well ? 1 : 0;
				int low = Canopy.bottom(cx, cy) / 8;
				assertTrue(well ? low == Canopy.TOP_LEVEL - 1 : low >= 5 && low <= 7, "the underside at 40-62 px, a well's at 74-79");
				int fill = low;
				for (int d = 0; d < 4; d++) {
					int nx = cx + Apron.DX[d];
					int ny = cy + Apron.DZ[d];
					if (nx >= -Apron.CELLS && ny >= -Apron.CELLS && nx < WOOD.w() + Apron.CELLS && ny < WOOD.h() + Apron.CELLS) {
						fill = Math.max(fill, Canopy.bottom(nx, ny) / 8 - 1);
					}
				}
				fill = Math.min(fill, Canopy.TOP_LEVEL - 2);
				ZoneBlock leaves = leavesOf(cx, cy);
				for (int d = 0; d < 4; d++) {
					int tx = cx * 2 + d % 2;
					int ty = cy * 2 + d / 2;
					for (int k = low; k <= fill; k++) {
						assertEquals(leaves, plan.at(tx, ty, k), "the underside and its steps at " + cx + "," + cy + " level " + k);
					}
					assertEquals(leaves, plan.at(tx, ty, Canopy.TOP_LEVEL - 1), "the upper layer");
					for (int k = Math.max(fill + 1, low); k < Canopy.TOP_LEVEL - 1; k++) {
						assertTrue(!Apron.leaves(plan.at(tx, ty, k)), "hollow between the layers at " + cx + "," + cy + " level " + k);
					}
				}
			}
		}
		assertTrue(wells > 0, "some wells in the apron");
		assertEquals(cells, tally.get("dress_apron_cell"));
		assertEquals(wells, tally.get("dress_apron_well"));
	}

	@Test
	void theInvisibleWallHasNoGap() {
		Apron.Plan plan = Apron.plan(WOOD, FLOOR, new TreeMap<>());
		Set<ZoneBlock> solid = Set.of(ZoneBlock.BARRIER, ZoneBlock.OAK_LEAVES, ZoneBlock.DARK_OAK_LEAVES, ZoneBlock.OAK_LOG, ZoneBlock.DARK_OAK_LOG);
		int barriers = 0;
		for (int ty = -1; ty <= WOOD.artRows(); ty++) {
			for (int tx = -1; tx <= WOOD.artCols(); tx++) {
				if (Apron.outside(tx, ty, WOOD.artCols(), WOOD.artRows()) != 1) {
					continue;
				}
				for (int k = 0; k <= Apron.BARRIER_TOP; k++) {
					ZoneBlock block = plan.at(tx, ty, k);
					assertTrue(solid.contains(block), "the ring keeps its collision at " + tx + "," + ty + " level " + k + ": " + block);
					barriers += block == ZoneBlock.BARRIER ? 1 : 0;
				}
			}
		}
		assertTrue(barriers > 0);
		// Past the ring the apron is open: no barrier anywhere else.
		for (int ty = -plan.margin(); ty < WOOD.artRows() + plan.margin(); ty++) {
			for (int tx = -plan.margin(); tx < WOOD.artCols() + plan.margin(); tx++) {
				if (Apron.outside(tx, ty, WOOD.artCols(), WOOD.artRows()) > 1) {
					for (int k = 0; k < Canopy.TOP_LEVEL; k++) {
						assertTrue(plan.at(tx, ty, k) != ZoneBlock.BARRIER);
					}
				}
			}
		}
	}

	@Test
	void vinesHangOnLeavesOutOfReach() {
		Map<String, Integer> tally = new TreeMap<>();
		Apron.Plan plan = Apron.plan(wood(20, 18), FLOOR, tally);
		int vines = 0;
		for (int ty = -plan.margin(); ty < plan.rows() + plan.margin(); ty++) {
			for (int tx = -plan.margin(); tx < plan.cols() + plan.margin(); tx++) {
				for (int k = 0; k < Canopy.TOP_LEVEL; k++) {
					ZoneBlock block = plan.at(tx, ty, k);
					int d = block == null ? -1 : java.util.List.of(ZoneBlock.VINE_NORTH, ZoneBlock.VINE_EAST, ZoneBlock.VINE_SOUTH, ZoneBlock.VINE_WEST)
							.indexOf(block);
					if (d < 0) {
						continue;
					}
					assertTrue(k >= Apron.VINE_LOW, "out of reach");
					if (plan.at(tx, ty, k + 1) != block) {
						// A vine's top block hangs on a leaf beside it, above the invisible wall.
						assertTrue(Apron.leaves(plan.at(tx + Apron.DX[d], ty + Apron.DZ[d], k)), "on a leaf at " + tx + "," + ty + " level " + k);
						assertTrue(k >= Apron.VINE_FROM && k < Canopy.TOP_LEVEL - 1);
						vines++;
					}
				}
			}
		}
		// A vine can start right under another on the same face: one longer vine to see, two in the tally.
		assertTrue(vines > 0 && vines <= tally.get("dress_apron_vine"), vines + " of " + tally.get("dress_apron_vine"));
	}

	@Test
	void theFloorIsTheForestsGrass() {
		Set<Integer> grass = Set.of(FLOOR);
		assertEquals(FLOOR, Apron.floorTile(WOOD, grass, ShapeFixtures.tile('.')));
		SceneMap bare = wood(3, 1);
		assertEquals(ShapeFixtures.tile('.'), Apron.floorTile(bare, grass, ShapeFixtures.tile('.')), "no grass shown: the map's ground");
		assertEquals(ShapeFixtures.tile('T'), Apron.floorTile(bare, grass, -1), "nor ground: the first tile it shows");
	}

	@Test
	void theSameWoodPlansTheSameApron() {
		Apron.Plan a = Apron.plan(WOOD, FLOOR, new TreeMap<>());
		Apron.Plan b = Apron.plan(wood(12, 10), FLOOR, new TreeMap<>());
		assertArrayEquals(a.kinds(), b.kinds());
		for (int i = 0; i < a.blocks().length; i++) {
			assertArrayEquals(a.blocks()[i], b.blocks()[i]);
		}
		assertNotNull(a.column(-1, -1));
		assertNull(a.column(0, 0), "the map's own columns are not the apron's");
	}
}
