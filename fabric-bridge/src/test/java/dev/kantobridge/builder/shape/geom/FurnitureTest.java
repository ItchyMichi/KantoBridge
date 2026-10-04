package dev.kantobridge.builder.shape.geom;

import static dev.kantobridge.builder.shape.geom.BandBuildingTest.ATLAS;
import static dev.kantobridge.builder.shape.geom.BandBuildingTest.FIRST_TILE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kantobridge.builder.shape.Templates;
import org.junit.jupiter.api.Test;

/**
 * A furniture entry's pieces (docs/specs/PROFILE_PLAN.md §5.6, P3), on {@link BandBuildingTest}'s made-up drawing (32 × 24
 * pixels, all inside: its border is black; a framed light window at columns 6-9, rows 12-15, the only pane below row 8;
 * the ground line under row 23). No game art.
 */
class FurnitureTest {
	/** The furniture entry over the drawing's grid, its plot 24 voxels deep, with these pieces. */
	static Templates.Entry entry(String parts) {
		Templates.Entry roof = BandBuildingTest.entry(false, false);
		JsonObject band = roof.band().deepCopy();
		for (String key : new String[] {"roofBack", "roofFront", "roofCycle", "frontEave"}) {
			band.remove(key);
		}
		band.addProperty("roofRows", 8);
		band.add("parts", JsonParser.parseString(parts).getAsJsonObject());
		JsonObject entry = new JsonObject();
		entry.addProperty("template", Templates.FORMAT);
		entry.addProperty("atlas", 0);
		entry.addProperty("w", 4);
		entry.addProperty("d", 3);
		entry.addProperty("perCell", 2);
		JsonArray tiles = new JsonArray();
		JsonArray columns = new JsonArray();
		for (int i = 0; i < 12; i++) {
			tiles.add(FIRST_TILE + i);
			columns.add(new JsonArray());
		}
		entry.add("tiles", tiles);
		entry.add("doors", new JsonArray());
		entry.addProperty("ground", -1);
		entry.addProperty("outdoor", false);
		entry.add("columns", columns);
		entry.add("band", band);
		entry.addProperty("sig", Templates.signature(entry));
		return Templates.Entry.of(entry);
	}

	private static BandBuilding.Model model(Templates.Entry entry) {
		return BandBuilding.Model.of(entry, entry.band(), ATLAS, BandBuildingTest.art());
	}

	private static int texel(BandBuilding.Model m, int x, int y) {
		int tile = FIRST_TILE + (y / 8) * 4 + x / 8;
		return VoxelGrid.texel(m.tiles.indexOf(tile), x % 8, y % 8);
	}

	@Test
	void aDeskStandsOnItsBaseRowsUnderItsFasciaAndLid() {
		Templates.Entry entry = entry("{plane: 5, desk: {fascia: [8, 9], base: [20, 22], depthPx: 0, z: 0, x: [0, 31], top: [1, 6], white: false},"
				+ " list: []}");
		BandBuilding.Model m = model(entry);
		VoxelGrid g = m.voxels(entry, entry.band());
		assertEquals(8, g.height(), "the plane at 5: the lid on layer 4, one block");
		assertEquals(texel(m, 12, 22), g.at(12, 0, 23), "the base's last row on the floor, front");
		assertEquals(texel(m, 12, 20), g.at(12, 2, 0), "its first row two up, back");
		assertEquals(texel(m, 12, 9), g.at(12, 3, 10), "the fascia's second row under the lid, through the whole box");
		assertEquals(texel(m, 12, 1), g.at(12, 4, 0), "the lid: the top rows by depth");
		assertEquals(texel(m, 12, 4), g.at(12, 4, 3));
		assertEquals(texel(m, 12, 6), g.at(12, 4, 10), "the last top row continues");
		assertEquals(texel(m, 12, 8), g.at(12, 4, 23), "the front edge: the fascia's first row");
		assertFalse(g.solid(12, 5, 10), "nothing over the lid");
	}

	@Test
	void aDesksBaseLosesItsPanesFrontVoxel() {
		Templates.Entry entry = entry("{plane: 7, desk: {fascia: [8, 9], base: [12, 16], depthPx: 0, z: 0, x: [0, 31], white: true}, list: []}");
		BandBuilding.Model m = model(entry);
		VoxelGrid g = m.voxels(entry, entry.band());
		// The window's light pixel (7, 13) stands at y 16 - 13 = 3: gone from the front layer, there behind it.
		assertFalse(g.solid(7, 3, 23));
		assertEquals(texel(m, 7, 13), g.at(7, 3, 22));
		// No top rows: a lid of the drawing's shades, its rim black, a white highlight row behind it, the field white.
		assertEquals(m.shadeTexel[3], g.at(0, 6, 10));
		assertEquals(m.shadeTexel[0], g.at(12, 6, 1));
		assertEquals(m.shadeTexel[0], g.at(12, 6, 10));
	}

	@Test
	void anUprightPartIsALidOverItsFacade() {
		Templates.Entry entry = entry("{plane: 0, list: [{kind: 'upright', x: [0, 31], top: [1, 6], facade: [8, 22], depth: 10, rise: 0, z: 2,"
				+ " stretch: false}]}");
		BandBuilding.Model m = model(entry);
		VoxelGrid g = m.voxels(entry, entry.band());
		assertEquals(texel(m, 20, 8), g.at(20, 14, 11), "the lid's front edge: the facade's first row");
		assertEquals(texel(m, 20, 1), g.at(20, 14, 2), "its back: the top rows by depth");
		assertEquals(texel(m, 20, 4), g.at(20, 14, 5));
		assertEquals(texel(m, 20, 22), g.at(20, 0, 11), "the facade's last row on the plane");
		assertEquals(texel(m, 20, 9), g.at(20, 13, 2), "its second row under the lid, at the back");
		assertFalse(g.solid(7, 9, 11), "the pane's front voxel left out");
		assertEquals(texel(m, 7, 13), g.at(7, 9, 10), "so it shows one voxel in");
		assertFalse(g.solid(20, 5, 1), "nothing behind its back row");
		assertFalse(g.solid(20, 5, 12), "nothing in front of its front row");
	}

	@Test
	void aFlatPartLaysItsRowsAsDepths() {
		Templates.Entry entry = entry("{plane: 0, list: [{kind: 'flat', x: [8, 15], rows: [1, 3], z: 5, at: 0, thick: 1}]}");
		BandBuilding.Model m = model(entry);
		VoxelGrid g = m.voxels(entry, entry.band());
		assertEquals(texel(m, 10, 1), g.at(10, 0, 5));
		assertEquals(texel(m, 10, 3), g.at(10, 0, 7));
		assertFalse(g.solid(10, 0, 8));
		assertFalse(g.solid(16, 0, 5), "only its columns");
	}

	@Test
	void aBoxPartStandsAtItsDrawnElevation() {
		Templates.Entry entry = entry("{plane: 0, list: [{kind: 'box', x: [0, 3], rows: [17, 20], depth: 4, z: 0, base: -1, cycle: [20, 20]}]}");
		BandBuilding.Model m = model(entry);
		VoxelGrid g = m.voxels(entry, entry.band());
		// The ground line under row 23: row 17 stands at 23 - 17 = 6, row 20 at 3; nothing under it.
		assertEquals(texel(m, 2, 17), g.at(2, 6, 0));
		assertEquals(texel(m, 2, 18), g.at(2, 5, 3));
		assertEquals(texel(m, 2, 20), g.at(2, 3, 0));
		assertFalse(g.solid(2, 2, 0));
		assertFalse(g.solid(2, 7, 0));
	}

	@Test
	void anIsoPartFillsADiamondOnThePlane() {
		Templates.Entry entry = entry("{plane: 0, list: [{kind: 'iso', x: [0, 15], rows: [0, 15], z: 10, plan: 4}]}");
		BandBuilding.Model m = model(entry);
		VoxelGrid g = m.voxels(entry, entry.band());
		// rx 8, rz 4: the base rhombus centred on row 11, the box 7 high; the centre column reaches four deep each way.
		assertEquals(texel(m, 7, 11), g.at(7, 0, 10));
		assertEquals(texel(m, 7, 4), g.at(7, 7, 10));
		assertTrue(g.solid(7, 0, 14) && g.solid(7, 0, 6));
		assertFalse(g.solid(7, 0, 15));
		assertTrue(g.solid(0, 0, 10), "the west tip: its centre row only");
		assertFalse(g.solid(0, 0, 11));
	}

	@Test
	void aTrayIsAnOpenBox() {
		Templates.Entry entry = entry("{plane: 3, tray: {top: [1, 6], front: [20, 22], x: [0, 31], inner: [4, 27], floor: 0}, list: []}");
		BandBuilding.Model m = model(entry);
		VoxelGrid g = m.voxels(entry, entry.band());
		// The opening's floor at depth 10 shows row 2 + floor(9 · 3 / 21) = 3; above it, air.
		assertEquals(texel(m, 10, 3), g.at(10, 0, 10));
		assertFalse(g.solid(10, 1, 10));
		// A side wall three high: its rim the row by depth, below it the front rows folded up, de-outlined between.
		assertEquals(texel(m, 0, 3), g.at(0, 2, 10));
		assertEquals(texel(m, 1, 22), g.at(0, 0, 10));
		assertEquals(texel(m, 10, 22), g.at(10, 0, 23), "the front wall: the drawing");
		assertEquals(texel(m, 10, 20), g.at(10, 2, 23), "its rim the near rim row");
	}

	@Test
	void aPieceNeverLeavesThePlot() {
		Templates.Entry entry = entry("{plane: 0, list: [{kind: 'flat', x: [0, 7], rows: [0, 23], z: 10, at: 0, thick: 1}]}");
		VoxelGrid g = model(entry).voxels(entry, entry.band());
		assertTrue(g.solid(2, 0, 23));
		assertEquals(24, g.depth(), "rows 14 on would lie past the plot's 24 voxels: left out");
	}

	@Test
	void theTemplateIsBuiltWithoutDoorwaysCarved() {
		Templates.Entry entry = entry("{plane: 0, list: [{kind: 'upright', x: [0, 31], top: [1, 6], facade: [8, 22], depth: 24, rise: 0, z: 0,"
				+ " stretch: false}]}");
		TemplateRule.Template template = BandBuilding.build(entry, ATLAS, BandBuildingTest.art());
		assertEquals(TemplateRule.Kind.BAND, template.kind());
		// BandBuildingTest's air marks the bottom row's east half: a band building would carve it, a furniture entry doesn't.
		assertFalse(template.block(2, 0, 2).isEmpty());
	}
}
