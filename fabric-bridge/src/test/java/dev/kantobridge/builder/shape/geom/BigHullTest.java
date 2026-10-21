package dev.kantobridge.builder.shape.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.builder.shape.Templates;
import dev.kantobridge.scene.ArtSource;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A two-cell hull's model (docs/specs/PROFILE_PLAN.md §5.6, P3) on made-up drawings: a canopy's crown, a planter. */
class BigHullTest {
	static final String ATLAS = "tilesets.fix";

	/** A disc of radius {@code r} centred in a {@code w × h} drawing, outlined; the grid's tiles numbered from 100. */
	static ArtSource disc(int w, int h, double cx, double cy, double r) {
		Map<Integer, int[]> tiles = new HashMap<>();
		for (int ty = 0; ty < h / 8; ty++) {
			for (int tx = 0; tx < w / 8; tx++) {
				int[] tile = new int[64];
				for (int y = 0; y < 8; y++) {
					for (int x = 0; x < 8; x++) {
						double dx = tx * 8 + x + 0.5 - cx;
						double dy = ty * 8 + y + 0.5 - cy;
						double d = Math.sqrt(dx * dx + dy * dy);
						tile[y * 8 + x] = d < r - 1.5 ? 2 : d < r ? 3 : 0;
					}
				}
				tiles.put(100 + ty * (w / 8) + tx, tile);
			}
		}
		return ArtFixtures.art(ATLAS, tiles, Map.of());
	}

	static Templates.Entry entry(String kind, int w, int d, int[] solid) {
		JsonObject entry = new JsonObject();
		entry.addProperty("template", Templates.FORMAT);
		entry.addProperty("atlas", 0);
		entry.addProperty("w", w);
		entry.addProperty("d", d);
		entry.addProperty("perCell", 2);
		JsonArray tiles = new JsonArray();
		JsonArray columns = new JsonArray();
		JsonArray heights = new JsonArray();
		for (int i = 0; i < w * d; i++) {
			tiles.add(100 + i);
			columns.add(new JsonArray());
			heights.add(solid[i]);
		}
		entry.add("tiles", tiles);
		entry.add("doors", new JsonArray());
		entry.addProperty("ground", -1);
		entry.addProperty("outdoor", true);
		entry.add("columns", columns);
		JsonObject hull = new JsonObject();
		hull.addProperty("format", 1);
		hull.addProperty("kind", kind);
		hull.addProperty("height", 32);
		hull.add("solid", heights);
		entry.add("hull", hull);
		entry.addProperty("sig", Templates.signature(entry));
		return Templates.Entry.of(entry);
	}

	@Test
	void aCanopyIsOneRoundCrownOverItsFourCells() {
		int[] solid = new int[16];
		java.util.Arrays.fill(solid, 4);
		TemplateRule.Template t = BigHull.build(entry("canopy", 4, 4, solid), ATLAS, disc(32, 32, 16, 15, 15));
		assertEquals(TemplateRule.Kind.HULL, t.kind());
		assertFalse(t.block(1, 3, 1).isEmpty(), "its top in the fourth block");
		assertFalse(t.block(1, 0, 1).isEmpty(), "down to the floor");
		assertTrue(t.maxQuads() <= CellUnits.BUDGET_QUADS);
	}

	@Test
	void aPlanterStandsOnItsSouthCellOnly() {
		int[] solid = {2, 2, 2, 2, 4, 4, 4, 4};
		TemplateRule.Template t = BigHull.build(entry("planter", 2, 4, solid), ATLAS, disc(16, 32, 8, 16, 7.5));
		assertEquals(TemplateRule.Kind.HULL, t.kind());
		for (int k = 0; k < 4; k++) {
			assertTrue(t.block(0, k, 0).isEmpty() && t.block(1, k, 1).isEmpty(), "nothing over the crown's cell");
		}
		assertFalse(t.block(0, 0, 3).isEmpty(), "the pot on the south cell");
		assertFalse(t.block(0, 2, 2).isEmpty() && t.block(1, 2, 2).isEmpty(), "the crown (drawn from row 9) in the third block");
	}

	@Test
	void aPlantersCrownIsShallowItsPotRound() {
		boolean[] mask = new boolean[16 * 32];
		java.util.Arrays.fill(mask, true);
		int[] texels = new int[16 * 32];
		VoxelGrid g = HullVariants.planter(mask, texels, 16, 32, 8);
		int crown = 0;
		int pot = 0;
		for (int z = 0; z < 16; z++) {
			crown += g.solid(8, 20, z) ? 1 : 0;
			pot += g.solid(8, 3, z) ? 1 : 0;
		}
		assertEquals(HullVariants.SPRAY, crown);
		assertEquals(16, pot);
	}
}
