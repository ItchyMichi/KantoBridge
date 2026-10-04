package dev.kantobridge.builder.shape.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Objects (docs/specs/VOXEL_BLOCKS_PLAN.md §5.5, Tier B): a small thing drawn on a background, grounded on flat ground
 * and not repeating vertically, stands as slabs on its own drawing; a vertically repeating cluster, a cluster without
 * flat ground and a drawing without background are not objects.
 */
class ObjectTest {
	/** Outdoors, walkable ground to the south; a sign. */
	private static final CellUnits.Context OUTDOOR_SOUTH = new CellUnits.Context(Background.SOUTH, false, false, true, false);

	@Test
	void aSignOnLightGroundIsAnObjectStandingOnItsOwnDrawing() {
		CellUnits.Unit unit = CellUnits.build(ArtFixtures.tiles(ArtFixtures.sign()), OUTDOOR_SOUTH, 2, ArtFixtures.ART_PX);
		assertEquals(CellUnits.Kind.OBJECT, unit.kind());
		assertEquals(1.0, unit.match(), "the slabs show exactly the drawing in the game's view");
		assertEquals(1.0, unit.cover());
		assertTrue(unit.maxQuads() <= CellUnits.MAX_QUADS);
		// The background isn't drawn: it shows the floor (the unit's ground tile).
		assertFalse(unit.foreground()[0]);
		assertTrue(unit.foreground()[16 + 1], "the board's rim");
		assertTrue(unit.foreground()[15 * 16 + 7], "the post's foot");
		// One component, its lowest pixel in the cell's bottom row: it stands at the cell's south edge, in the southern blocks.
		for (int by = 0; by < 2; by++) {
			for (int bx = 0; bx < 2; bx++) {
				assertTrue(unit.blocks().get(CellUnits.block(2, bx, by, 0)).isEmpty(), "nothing in the northern blocks");
			}
		}
		boolean front = false;
		for (ShapeQuad quad : unit.blocks().get(CellUnits.block(2, 0, 0, 1))) {
			if (quad.face() == ShapeQuad.SOUTH) {
				front = true;
				assertEquals(16, quad.z(0), "its front lies on the block's south face");
				assertEquals(ShapeQuad.SOUTH, quad.cull());
			}
		}
		assertTrue(front);
	}

	@Test
	void slabsAreDepthPixelsDeepPerComponent() {
		// Two components: a short post on the ground and a dot higher up, whose lowest pixel is its own row.
		int[] drawing = ArtFixtures.shades(
				"................",
				"................",
				"..33............",
				"..33............",
				"................",
				"................",
				"................",
				"................",
				"................",
				"................",
				"................",
				"................",
				"..........3.....",
				"..........3.....",
				"..........3.....",
				"..........3.....");
		boolean[] background = Background.of(drawing, 16, 16, Background.SOUTH);
		boolean[] skip = new boolean[256];
		VoxelGrid grid = ObjectRule.slabs(ObjectRule.foreground(background, skip), ArtFixtures.texels(), 16, 16, 16, ArtFixtures.ART_PX);
		assertEquals((4 + 4) * ObjectRule.DEPTH, grid.count(), "every pixel DEPTH voxels deep");
		// The dot (rows 2..3) stands on the ground at its own row's depth: its front at z = 4, its feet at y = 0.
		assertTrue(grid.solid(2, 0, 3) && grid.solid(2, 1, 3) && grid.solid(2, 0, 2));
		assertFalse(grid.solid(2, 0, 4));
		// The post (rows 12..15): its front at the cell's south edge.
		assertTrue(grid.solid(10, 0, 15) && grid.solid(10, 3, 15) && grid.solid(10, 3, 14));
		assertEquals(VoxelGrid.texel(3, 2, 7), grid.at(10, 0, 15), "its foot wears the bottom pixel's texel");
	}

	@Test
	void aVerticallyRepeatingClusterIsNotAProp() {
		CellUnits.Context repeating = new CellUnits.Context(Background.SOUTH, false, true, true, false);
		CellUnits.Unit unit = CellUnits.build(ArtFixtures.tiles(ArtFixtures.sign()), repeating, 2, ArtFixtures.ART_PX);
		assertNotEquals(CellUnits.Kind.OBJECT, unit.kind());
		assertEquals(CellUnits.Kind.COLUMNS, unit.kind(), "scenery: the column version");
	}

	@Test
	void outdoorsAnObjectNeedsFlatGroundToItsSouth() {
		boolean[] skip = new boolean[256];
		int[] sign = ArtFixtures.sign();
		assertFalse(ObjectRule.applies(Background.of(sign, 16, 16, 0), skip, 2, 8, 0, false, false), "no flat side");
		assertFalse(ObjectRule.applies(Background.of(sign, 16, 16, 0), skip, 2, 8, Background.WEST, false, false),
				"outdoors a walkable west side doesn't ground it");
		assertTrue(ObjectRule.applies(Background.of(sign, 16, 16, Background.WEST), skip, 2, 8, Background.WEST, true, false),
				"indoors it does");
	}

	@Test
	void aDrawingWithoutBackgroundIsNotAnObject() {
		int[] wall = new int[256];
		for (int i = 0; i < 256; i++) {
			wall[i] = i % 5 == 0 ? 3 : 2;
		}
		CellUnits.Unit unit = CellUnits.build(ArtFixtures.tiles(wall), OUTDOOR_SOUTH, 2, ArtFixtures.ART_PX);
		assertEquals(CellUnits.Kind.COLUMNS, unit.kind());
		assertEquals(null, unit.failed(), "no candidate was even built");
	}
}
