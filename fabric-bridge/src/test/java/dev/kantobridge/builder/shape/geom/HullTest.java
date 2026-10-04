package dev.kantobridge.builder.shape.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Round cells (docs/specs/VOXEL_BLOCKS_PLAN.md §5.5, Tier B): the outline-plus-enclosure mask, the round test (a crown,
 * never a board on a post or a lopsided blob), and the hull: one disc per mask row, symmetric, its runs in half-block
 * steps, standing on the ground, its cap showing the crown.
 */
class HullTest {
	/** Outdoors, walkable ground to the south; foliage. */
	private static final CellUnits.Context TREE = new CellUnits.Context(Background.SOUTH, false, false, false, true);

	@Test
	void aRoundCrownIsAHull() {
		CellUnits.Unit unit = CellUnits.build(ArtFixtures.tiles(ArtFixtures.crown(7.0)), TREE, 2, ArtFixtures.ART_PX);
		assertEquals(CellUnits.Kind.HULL, unit.kind());
		assertTrue(unit.cover() >= CellUnits.HULL_COVER, "cover " + unit.cover());
		assertTrue(unit.maxQuads() <= CellUnits.MAX_QUADS, unit.maxQuads() + " quads in a block");
		for (int b = 0; b < 8; b++) {
			assertFalse(unit.blocks().get(b).isEmpty(), "every block of the unit holds part of the crown");
		}
		// Its background (outside the crown) shows the floor; the crown is the foreground.
		assertFalse(unit.foreground()[0]);
		assertTrue(unit.foreground()[6 * 16 + 7]);
	}

	@Test
	void theMaskIsTheOutlineAndWhatItEncloses() {
		int[] ring = ArtFixtures.shades(
				"........",
				".333333.",
				".311113.",
				".311113.",
				".311113.",
				".311113.",
				".333333.",
				"........");
		boolean[] mask = Hulls.mask(ring, new boolean[64], 8, 8);
		assertEquals(36, Background.count(mask), "the rim and what it encloses");
		assertFalse(mask[0]);
		assertTrue(mask[3 * 8 + 3]);
	}

	@Test
	void aDitheredDrawingIsFloodedThroughItsLightShadesOnly() {
		// No outline at all: the first flood encloses nothing, so the light shades alone mark the outside.
		int[] blob = ArtFixtures.shades(
				"........",
				"..2222..",
				".222222.",
				".222222.",
				".222222.",
				".222222.",
				"..2222..",
				"........");
		boolean[] mask = Hulls.mask(blob, new boolean[64], 8, 8);
		assertEquals(32, Background.count(mask), "the blob's dark pixels");
	}

	@Test
	void theHullIsSymmetricAndStandsOnTheGround() {
		int[] crown = ArtFixtures.crown(7.0);
		boolean[] mask = Hulls.mask(crown, new boolean[256], 16, 16);
		assertTrue(Hulls.round(mask, 16, 16));
		VoxelGrid grid = Hulls.hull(mask, ArtFixtures.texels(), 16, 16, 16, ArtFixtures.ART_PX);
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					assertEquals(grid.solid(x, y, z), grid.solid(15 - x, y, z), "left/right at " + x + "," + y + "," + z);
					assertEquals(grid.solid(x, y, z), grid.solid(x, y, 15 - z), "front/back at " + x + "," + y + "," + z);
				}
			}
		}
		// The crown's lowest row (13) repeats down to the ground.
		assertTrue(grid.solid(7, 0, 8) && grid.solid(7, 1, 8) && grid.solid(7, 2, 8));
		assertEquals(grid.at(7, 2, 9), grid.at(7, 0, 9), "the repeated rows wear the lowest row's texels");
		// The widest row's run is the deepest: at the crown's middle, 12 of the cell's 16 pixels.
		assertTrue(grid.solid(7, 15 - 6, 2) && grid.solid(7, 15 - 6, 13));
		assertFalse(grid.solid(7, 15 - 6, 1) || grid.solid(7, 15 - 6, 14));
		assertFalse(grid.solid(7, 15, 1), "the top row is narrow");
		// Every run is a multiple of half a block deep.
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				int depth = 0;
				for (int z = 0; z < 16; z++) {
					depth += grid.solid(x, y, z) ? 1 : 0;
				}
				assertTrue(depth == 0 || depth == 2 || depth % Hulls.DEPTH_STEP == 0, "run depth " + depth + " at " + x + "," + y);
			}
		}
	}

	@Test
	void theCapShowsTheCrownNotItsOutline() {
		int[] crown = ArtFixtures.crown(7.0);
		int[] texels = ArtFixtures.texels();
		VoxelGrid grid = Hulls.hull(Hulls.mask(crown, new boolean[256], 16, 16), texels, 16, 16, 16, ArtFixtures.ART_PX);
		// The top row (0) at x = 7: a run of four voxels, z 6..9, its top wearing the row below; its other faces its own.
		for (int z = 6; z < 10; z++) {
			assertEquals(texels[16 + 7], grid.cap(7, 15, z));
			assertEquals(texels[7], grid.at(7, 15, z));
		}
		assertFalse(grid.solid(7, 15, 5) || grid.solid(7, 15, 10));
	}

	@Test
	void aBoardOnAPostIsNotRound() {
		int[] sign = ArtFixtures.sign();
		assertFalse(Hulls.round(Hulls.mask(sign, new boolean[256], 16, 16), 16, 16));
	}

	@Test
	void aLopsidedBlobIsNotRound() {
		int[] bitten = ArtFixtures.crown(7.0);
		for (int y = 0; y < 7; y++) {
			for (int x = 10; x < 16; x++) {
				bitten[y * 16 + x] = 0;
			}
		}
		assertFalse(Hulls.round(Hulls.mask(bitten, new boolean[256], 16, 16), 16, 16));
	}

	@Test
	void aSignIsNeverAHull() {
		CellUnits.Context sign = new CellUnits.Context(Background.SOUTH, false, false, true, true);
		CellUnits.Unit unit = CellUnits.build(ArtFixtures.tiles(ArtFixtures.crown(7.0)), sign, 2, ArtFixtures.ART_PX);
		assertNotEquals(CellUnits.Kind.HULL, unit.kind());
		assertEquals(CellUnits.Kind.OBJECT, unit.kind(), "a round sign stands as a slab");
	}

	@Test
	void voidTilesNeverStandUp() {
		// The north-west tile is void (every pixel the darkest): it lies as a plate 1/16 high; the rest is a sign.
		int[] drawing = ArtFixtures.sign();
		for (int y = 0; y < 8; y++) {
			for (int x = 0; x < 8; x++) {
				drawing[y * 16 + x] = 3;
			}
		}
		CellUnits.Unit unit = CellUnits.build(ArtFixtures.tiles(drawing), new CellUnits.Context(Background.SOUTH, false, false, true, false), 2,
				ArtFixtures.ART_PX);
		boolean plate = false;
		for (ShapeQuad quad : unit.blocks().get(CellUnits.block(2, 0, 0, 0))) {
			plate |= quad.face() == ShapeQuad.UP && quad.y(0) == 1 && quad.texture() == 0;
		}
		assertTrue(plate, "the void tile's plate");
		int[][] dark = new int[4][64];
		for (int[] tile : dark) {
			java.util.Arrays.fill(tile, 3);
		}
		CellUnits.Unit flat = CellUnits.build(dark, TREE, 2, ArtFixtures.ART_PX);
		assertEquals(CellUnits.Kind.FLAT, flat.kind(), "all void: plates only");
		for (int b = 0; b < 8; b++) {
			for (ShapeQuad quad : flat.blocks().get(b)) {
				assertEquals(ShapeQuad.UP, quad.face());
				assertEquals(1, quad.y(0));
			}
		}
	}
}
