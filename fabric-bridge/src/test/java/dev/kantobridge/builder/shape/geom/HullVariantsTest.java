package dev.kantobridge.builder.shape.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * P3's pinned units (docs/specs/PROFILE_PLAN.md §5.6): stairs, a relief, a stump's cut face, a can's well, base and taper;
 * and their parameters in a unit's variant. Made-up 16 × 16 drawings.
 */
class HullVariantsTest {
	static final int[] TEXELS = ArtFixtures.texels();

	@Test
	void stairsRiseAQuarterOfTheirHeightPerStep() {
		VoxelGrid east = HullVariants.stairs(TEXELS, 16, 8, true, 4);
		assertTrue(east.solid(0, 3, 0) && !east.solid(0, 4, 0), "the first step at the west edge, four high");
		assertTrue(east.solid(11, 11, 15) && !east.solid(11, 12, 15), "the third, twelve");
		assertTrue(east.solid(15, 15, 7), "the last, sixteen, as deep as the cell");
		assertEquals(TEXELS[(15 - 6) * 16 + 5], east.at(5, 6, 3), "every voxel the drawing standing up");
		VoxelGrid west = HullVariants.stairs(TEXELS, 16, 8, false, 4);
		assertTrue(west.solid(15, 3, 0) && !west.solid(15, 4, 0), "rising west: the first step at the east edge");
		assertTrue(west.solid(0, 15, 0));
	}

	@Test
	void aReliefRaisesWhatItsOutlineEncloses() {
		int[] shades = ArtFixtures.shades(
				"2222222222222222",
				"2222222222222222",
				"2233333333332222",
				"2231111111132222",
				"2231111111132222",
				"2233333333332222",
				"2222222222222222",
				"2222222222222222",
				"2222222222222222",
				"2222222222222222",
				"2222222222222222",
				"2222222222222222",
				"2222222222222222",
				"2222222222222222",
				"2222222222222222",
				"2222222222222222");
		VoxelGrid g = HullVariants.relief(shades, TEXELS, new boolean[256], 16, 8, 3);
		assertTrue(g.solid(2, 0, 2), "the outline");
		assertTrue(g.solid(5, 2, 3) && !g.solid(5, 3, 3), "what it encloses, three high");
		assertFalse(g.solid(0, 0, 0), "the border's shade is background");
		assertEquals(TEXELS[3 * 16 + 5], g.at(5, 1, 3), "the drawing lying flat: its rows north to south");
	}

	private static boolean[] disc() {
		return Hulls.mask(ArtFixtures.crown(7), new boolean[256], 16, 16);
	}

	private static int topLayer(VoxelGrid g, int x, int z) {
		for (int y = g.height() - 1; y >= 0; y--) {
			if (g.solid(x, y, z)) {
				return y;
			}
		}
		return -1;
	}

	@Test
	void aStumpLaysItsCutFaceOnItsTop() {
		boolean[] mask = disc();
		VoxelGrid plain = Hulls.hull(mask, TEXELS, 16, 16, 16, 8);
		VoxelGrid stump = HullVariants.stump(mask, TEXELS, 16, 8, 4);
		assertEquals(15, topLayer(plain, 7, 8));
		assertEquals(11, topLayer(stump, 7, 8), "four rows of cut face stripped from the body");
		// The body's top row spans depths 2..13: the face's first row at the north end, its fourth at the south.
		assertEquals(TEXELS[7], stump.cap(7, 11, 2));
		assertEquals(TEXELS[3 * 16 + 7], stump.cap(7, 11, 13));
	}

	@Test
	void aCanIsHollowAtItsMouth() {
		VoxelGrid can = HullVariants.can(disc(), TEXELS, 16, 8, 4, 2, 9, 3, 2);
		assertEquals(11, topLayer(can, 7, 2), "the rim");
		assertFalse(can.solid(7, 11, 8), "the well's inside");
		assertTrue(can.solid(7, 11, 2) && can.solid(7, 11, 13), "walls two voxels thick");
		assertTrue(can.solid(7, 8, 8), "the well's floor");
		assertEquals(TEXELS[2 * 16 + 7], can.cap(7, 8, 8), "the mouth laid on it by depth");
		int front = -1;
		for (int z = 0; z < 16; z++) {
			front = can.solid(7, 3, z) ? z : front;
		}
		assertEquals(TEXELS[12 * 16 + 7], can.at(7, 3, front), "the drawn base on the foot's front");
		VoxelGrid straight = HullVariants.can(disc(), TEXELS, 16, 8, 4, 2, 9, 3, 0);
		assertEquals(widest(straight, 1) - 2, widest(can, 1), "the foot a pixel narrower each side than without a taper");
	}

	private static int widest(VoxelGrid g, int y) {
		int n = 0;
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				n += g.solid(x, y, z) && z == 8 ? 1 : 0;
			}
		}
		return n;
	}

	@Test
	void aUnitsParametersTravelInItsVariant() {
		CellUnits.Context stair = new CellUnits.Context(0, true, false, false, false, "stair_down_e", 0, List.of(16));
		assertEquals("g0i_stair_down_e_k16", stair.variant());
		assertEquals(stair, CellUnits.Context.parse(stair.variant()));
		CellUnits.Context can = new CellUnits.Context(1, false, false, false, false, "can", 5, List.of(9, 4, 9, 5, 4));
		assertEquals("g1_can_k0904090504_bg5", can.variant());
		assertEquals(can, CellUnits.Context.parse(can.variant()));
		assertEquals(List.of(), CellUnits.Context.parse("g0_prop_bg3").params(), "a P1 variant has none");
	}

	@Test
	void pinnedStairsAndReliefsBuildAsTheirOwnKinds() {
		int[][] tiles = ArtFixtures.tiles(ArtFixtures.crown(7));
		CellUnits.Unit stairs = CellUnits.build(tiles, CellUnits.Context.parse("g0_stair_e_k16"), 2, 8);
		assertEquals(CellUnits.Kind.STAIR, stairs.kind());
		assertFalse(stairs.blocks().get(CellUnits.block(2, 1, 1, 0)).isEmpty(), "the upper steps reach the second block");
		CellUnits.Unit stump = CellUnits.build(tiles, CellUnits.Context.parse("g0_stump_k04"), 2, 8);
		assertEquals(CellUnits.Kind.HULL, stump.kind());
		assertEquals(CellUnits.Kind.STAIR, CellUnits.build(tiles, CellUnits.Context.parse("g0_stair_e_k16"), 2, 8, CellUnits.MAX_QUADS, "columns", 0)
				.kind(), "a pack's prop style doesn't turn stairs into columns");
		assertEquals(CellUnits.Kind.COLUMNS, CellUnits.build(tiles, CellUnits.Context.parse("g0_stump_k04"), 2, 8, CellUnits.MAX_QUADS, "columns", 0)
				.kind(), "a stump follows it, as a pinned cylinder does");
	}
}
