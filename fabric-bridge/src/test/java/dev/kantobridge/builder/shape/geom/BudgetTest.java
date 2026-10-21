package dev.kantobridge.builder.shape.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The quad budgets (docs/specs/VOXEL_BLOCKS_PLAN.md §3.8) against Tier B's heaviest shapes: a 16×16×16 section whose
 * walking layer is all canopy (8 × 8 tree units, side by side, as in a tree wall), and one all props, stay within the
 * section budget after face-mask culling, every block within the per-block cap; and a unit over the cap draws its
 * column version.
 */
class BudgetTest {
	/** The section budget's maximum (§3.8). */
	static final int SECTION_MAX = 30_000;

	private static final CellUnits.Context TREE = new CellUnits.Context(Background.SOUTH, false, false, false, true);
	private static final CellUnits.Context SIGN = new CellUnits.Context(Background.SOUTH, false, false, true, false);

	/** The quads a section draws when its walking layer is {@code n × n} copies of a unit (opaque floor below). */
	static int sectionQuads(CellUnits.Unit unit, int n) {
		int side = n * 2;
		ShapeMesh[] meshes = new ShapeMesh[8];
		for (int b = 0; b < 8; b++) {
			meshes[b] = new ShapeMesh(unit.blocks().get(b));
		}
		int total = 0;
		for (int y = 0; y < 2; y++) {
			for (int z = 0; z < side; z++) {
				for (int x = 0; x < side; x++) {
					ShapeMesh mesh = meshes[CellUnits.block(2, x % 2, y, z % 2)];
					for (int q = 0; q < mesh.size(); q++) {
						int cull = mesh.quads().get(q).cull();
						if (cull == ShapeQuad.DOWN && y == 0) {
							continue;  // on the opaque floor block
						}
						if (cull >= 0) {
							int nx = x + FaceMasks.DX[cull];
							int ny = y + FaceMasks.DY[cull];
							int nz = z + FaceMasks.DZ[cull];
							if (nx >= 0 && nz >= 0 && nx < side && nz < side && ny >= 0 && ny < 2
									&& FaceMasks.culled(mesh, q, meshes[CellUnits.block(2, nx % 2, ny, nz % 2)])) {
								continue;
							}
						}
						total++;
					}
				}
			}
		}
		return total;
	}

	@Test
	void aSectionOfTheHeaviestCanopyStaysWithinTheBudget() {
		int heaviest = 0;
		for (double r : new double[] {6.5, 7.0, 7.5}) {
			CellUnits.Unit unit = CellUnits.build(ArtFixtures.tiles(ArtFixtures.crown(r)), TREE, 2, ArtFixtures.ART_PX);
			assertEquals(CellUnits.Kind.HULL, unit.kind(), "radius " + r);
			assertTrue(unit.maxQuads() <= CellUnits.BUDGET_QUADS);
			heaviest = Math.max(heaviest, sectionQuads(unit, 8));
		}
		assertTrue(heaviest <= SECTION_MAX, heaviest + " quads in a section of canopy");
	}

	@Test
	void aSectionOfTheHeaviestPropStaysWithinTheBudget() {
		CellUnits.Unit unit = CellUnits.build(ArtFixtures.tiles(ArtFixtures.sign()), SIGN, 2, ArtFixtures.ART_PX);
		assertEquals(CellUnits.Kind.OBJECT, unit.kind());
		int quads = sectionQuads(unit, 8);
		assertTrue(quads <= SECTION_MAX, quads + " quads in a section of signs");
	}

	@Test
	void aUnitOverTheCapDrawsItsColumnVersion() {
		CellUnits.Unit unit = CellUnits.build(ArtFixtures.tiles(ArtFixtures.crown(7.0)), TREE, 2, ArtFixtures.ART_PX, 10);
		assertEquals(CellUnits.Kind.COLUMNS, unit.kind());
		assertEquals(CellUnits.Kind.OBJECT, unit.failed(), "the hull and then the object were over the cap");
		for (List<ShapeQuad> block : unit.blocks()) {
			assertEquals(6, block.size(), "a plain cube per block");
		}
	}
}
