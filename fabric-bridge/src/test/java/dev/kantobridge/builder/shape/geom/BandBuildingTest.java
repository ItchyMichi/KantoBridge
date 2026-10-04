package dev.kantobridge.builder.shape.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.shape.Templates;
import dev.kantobridge.scene.ArtSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A band-table building's model (docs/specs/PROFILE_PLAN.md §5.5): the roof slab on the facade, its top the drawing row
 * its depth shows and its rim black; the facade extruded, front and back the drawing; a pane sunk one voxel; a doorway
 * carved with its art at the back; the awning two voxels proud; the template's blocks; the fallbacks. A made-up building
 * of four tiles by three; no game art.
 */
class BandBuildingTest {
	static final String ATLAS = "tilesets.fix";
	/** 32 x 24 pixels: a black outline, a light roof band (rows 1-6), a dark wall, a framed light window, a black threshold. */
	static final String[] DRAWING = {
			"33333333333333333333333333333333",
			"31111111111111111111111111111113",
			"31111111111111111111111111111113",
			"31111111111111111111111111111113",
			"31111111111111111111111111111113",
			"31111111111111111111111111111113",
			"31111111111111111111111111111113",
			"33333333333333333333333333333333",
			"32222222222222222222222222222223",
			"32222222222222222222222222222223",
			"32222222222222222222222222222223",
			"32222333333222222222222222222223",
			"32222311113222222222222222222223",
			"32222311113222222222222222222223",
			"32222311113222222222222222222223",
			"32222311113222222222222222222223",
			"32222333333222222222222222222223",
			"32222222222222222222222222222223",
			"32222222222222222222222222222223",
			"32222222222222222222222222222223",
			"32222222222222222222222222222223",
			"32222222222222222222222222222223",
			"32222222222222222222222222222223",
			"33333333333333333333333333333333"};
	static final int FIRST_TILE = 100;

	/** The drawing's tiles (ids 100..111, row-major) and an art source over them. */
	static ArtSource art() {
		Map<Integer, int[]> tiles = new HashMap<>();
		int[] all = ArtFixtures.shades(DRAWING);
		for (int ty = 0; ty < 3; ty++) {
			for (int tx = 0; tx < 4; tx++) {
				int[] tile = new int[64];
				for (int y = 0; y < 8; y++) {
					for (int x = 0; x < 8; x++) {
						tile[y * 8 + x] = all[(ty * 8 + y) * 32 + tx * 8 + x];
					}
				}
				tiles.put(FIRST_TILE + ty * 4 + tx, tile);
			}
		}
		return ArtFixtures.art(ATLAS, tiles, Map.of());
	}

	/**
	 * The entry the planner would write for this building, with a doorway over the bottom row's east half and the given
	 * overhang rows and awning.
	 */
	static Templates.Entry entry(boolean awning, boolean claimOnly) {
		int north = awning ? 1 : 0;
		int south = awning ? 1 : 0;
		int d = 3 + north + south;
		JsonObject entry = new JsonObject();
		entry.addProperty("template", Templates.FORMAT);
		entry.addProperty("atlas", 0);
		entry.addProperty("w", 4);
		entry.addProperty("d", d);
		entry.addProperty("perCell", 2);
		JsonArray tiles = new JsonArray();
		JsonArray columns = new JsonArray();
		JsonArray air = new JsonArray();
		JsonArray solid = new JsonArray();
		for (int y = 0; y < d; y++) {
			for (int x = 0; x < 4; x++) {
				int gy = y - north;
				boolean inGrid = gy >= 0 && gy < 3;
				boolean doorway = gy == 2 && x >= 2;
				tiles.add(inGrid ? FIRST_TILE + gy * 4 + x : -1);
				columns.add(new JsonArray());
				solid.add(inGrid && !doorway ? 3 : 0);
				air.add(doorway || !inGrid ? 3 : 0);
			}
		}
		entry.add("tiles", tiles);
		entry.add("doors", new JsonArray());
		entry.addProperty("ground", -1);
		entry.addProperty("outdoor", true);
		entry.add("columns", columns);
		JsonObject band = new JsonObject();
		band.addProperty("format", 1);
		band.addProperty("index", 0);
		band.addProperty("north", north);
		band.addProperty("south", south);
		band.add("top", new JsonArray());
		JsonArray rows = new JsonArray();
		for (int gy = 0; gy < 3; gy++) {
			JsonArray row = new JsonArray();
			for (int x = 0; x < 4; x++) {
				row.add(FIRST_TILE + gy * 4 + x);
			}
			rows.add(row);
		}
		band.add("rows", rows);
		band.add("solid", solid);
		band.add("air", air);
		if (claimOnly) {
			band.addProperty("claimOnly", true);
		} else {
			band.addProperty("roofRows", 8);
			band.addProperty("roofBack", 1);
			band.addProperty("roofFront", 2);
			JsonArray cycle = new JsonArray();
			cycle.add(1);
			cycle.add(5);
			band.add("roofCycle", cycle);
			band.addProperty("slab", 2);
			band.addProperty("frontEave", 0);
			if (awning) {
				JsonArray ledge = new JsonArray();
				ledge.add(20);
				ledge.add(21);
				band.add("ledge", ledge);
			}
			band.addProperty("seal", "");
			band.addProperty("depthPx", 24);
			band.addProperty("panes", true);
			band.add("scrub", new JsonArray());
		}
		entry.add("band", band);
		entry.addProperty("sig", Templates.signature(entry));
		return Templates.Entry.of(entry);
	}

	private static BandBuilding.Model model(Templates.Entry entry) {
		return BandBuilding.Model.of(entry, entry.band(), ATLAS, art());
	}

	/** The texel of drawing pixel (x, y): its tile's slot in the model's tile list, its column and row there. */
	private static int texel(BandBuilding.Model model, int x, int y) {
		int tile = FIRST_TILE + (y / 8) * 4 + x / 8;
		return VoxelGrid.texel(model.tiles.indexOf(tile), x % 8, y % 8);
	}

	@Test
	void theModelStandsAsTallAsItsFacadePlusItsSlab() {
		BandBuilding.Model m = model(entry(false, false));
		assertEquals(24, m.ground, "the threshold row is the ground line");
		assertEquals(15 + 2, m.ytop, "16 facade rows, the top at 15, and a slab of 2");
		// The roof's top layer at depth 10 shows the cycled row 1 + (10 - 1) % 5 = 5; its rim is black, the layer below dark.
		assertEquals(texel(m, 12, 5), m.at(12, 17, 10));
		assertEquals(texel(m, 12, 1), m.at(12, 17, 1), "roofBack: depth 1 shows row 1");
		assertEquals(texel(m, 12, 6), m.at(12, 17, 22), "roofFront: the depth before the south rim shows row 7 - 1");
		assertEquals(m.shadeTexel[3], m.at(12, 17, 23), "the south rim, black");
		assertEquals(m.shadeTexel[3], m.at(0, 17, 10), "the rim's top, black");
		assertEquals(m.shadeTexel[3], m.at(12, 17, 0), "the north rim");
		assertEquals(m.shadeTexel[2], m.at(12, 16, 10), "under the top, dark");
		assertEquals(VoxelGrid.EMPTY, m.at(12, 18, 10), "nothing above the roof");
	}

	@Test
	void theFacadeIsTheDrawingFrontAndBackAndAPaneSinksOne() {
		BandBuilding.Model m = model(entry(false, false));
		// y 4 shows drawing row 23 - 4 = 19, the dark wall.
		assertEquals(texel(m, 20, 19), m.at(20, 4, 23), "the front");
		assertEquals(texel(m, 20, 19), m.at(20, 4, 0), "the back");
		assertEquals(texel(m, 20, 19), m.at(20, 4, 10), "between: a dark pixel is its own");
		// The window's light pixel (7, 13) at y 10: gone from the front, shown one voxel in.
		assertEquals(VoxelGrid.EMPTY, m.at(7, 10, 23), "the pane's front voxel");
		assertEquals(texel(m, 7, 13), m.at(7, 10, 22));
		// The frame's black pixel (5, 13) between front and back takes the nearest non-black toward the middle.
		assertEquals(texel(m, 6, 13), m.at(5, 10, 10));
	}

	@Test
	void aDoorwayIsCarvedTwoBlocksHighWithItsArtAtTheBack() {
		Templates.Entry entry = entry(false, false);
		BandBuilding.Model m = model(entry);
		VoxelGrid grid = m.voxels(entry, entry.band());
		for (int y = 0; y < 16; y++) {
			assertFalse(grid.solid(20, y, 20), "carved at y " + y);
		}
		assertTrue(grid.solid(20, 16, 20), "the roof above the doorway stays");
		assertEquals(texel(m, 20, 19), grid.at(20, 4, 15), "the door's art on the voxel behind the doorway");
		assertTrue(grid.solid(12, 4, 20), "beside the doorway the wall stands");
	}

	@Test
	void anAwningJutsTwoVoxelsNorthAndSouth() {
		Templates.Entry entry = entry(true, false);
		BandBuilding.Model m = model(entry);
		// Rows 20-21 of the drawing stand at y 2-3; the grid's north row is one tile, so model z -1 is grid z 7.
		assertEquals(texel(m, 12, 20), m.at(12, 3, -1));
		assertEquals(texel(m, 12, 21), m.at(12, 2, 24));
		assertEquals(VoxelGrid.EMPTY, m.at(12, 6, 24), "only the awning's rows");
		VoxelGrid grid = m.voxels(entry, entry.band());
		assertTrue(grid.solid(12, 3, 7) && grid.solid(12, 3, 6), "two voxels north of the footprint");
		assertTrue(grid.solid(12, 3, 8 + 24) && grid.solid(12, 3, 8 + 25), "two south");
	}

	@Test
	void theModelFillsTheTemplatesBlocks() {
		Templates.Entry entry = entry(false, false);
		TemplateRule.Template template = BandBuilding.build(entry, ATLAS, art());
		assertEquals(TemplateRule.Kind.BAND, template.kind());
		assertTrue(template.maxQuads() <= CellUnits.MAX_QUADS);
		assertFalse(template.block(1, 2, 1).isEmpty(), "the roof's block");
		assertFalse(template.block(0, 0, 2).isEmpty(), "the facade's corner");
		assertTrue(template.block(3, 3, 0).isEmpty(), "nothing above three blocks");
		for (ShapeQuad quad : template.block(1, 2, 1)) {
			assertTrue(quad.texture() >= FIRST_TILE && quad.texture() < FIRST_TILE + 12, "textures are tile ids");
		}
	}

	@Test
	void aClaimOnlyEntryDrawsNothingAndMissingArtFallsBack() {
		TemplateRule.Template none = BandBuilding.build(entry(false, true), ATLAS, art());
		assertTrue(none.blocks().isEmpty());
		ArtSource lacking = ArtFixtures.art(ATLAS, Map.of(FIRST_TILE, new int[64]), Map.of());
		TemplateRule.Template columns = BandBuilding.build(entry(false, false), ATLAS, lacking);
		assertEquals(TemplateRule.Kind.COLUMNS, columns.kind(), "a tile this art lacks: the plain cubes");
		assertFalse(columns.block(0, 2, 0).isEmpty(), "a claimed solid tile: its volume high");
		assertTrue(columns.block(0, 3, 0).isEmpty());
		for (ShapeQuad quad : columns.block(1, 0, 1)) {
			assertEquals(FIRST_TILE + 5, quad.texture(), "its own tile on every face");
		}
		assertEquals(List.of(), columns.block(2, 0, 2), "the doorway has no fallback");
	}
}
