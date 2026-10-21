package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.scene.profile.Profile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The landscape sections' models in the planner (docs/specs/LANDSCAPE_MODELS.md), on a made-up map: a model claims its
 * cells and the door its arch reaches over, stands as tall as its spec under the open sky (no cap there but the block
 * mask), and leaves a tile another claim holds to that claim.
 */
class LandscapeModelsTest {
	/** A rock two cells square over a cave door, under the open sky (a Battle Frontier outside). */
	private static final String[] ART = {
			"RRRR..",
			"RRRR..",
			"RRRR..",
			"RRRR..",
			"..+...",
			"..+..."};

	private static LandscapeModels.Model rock() {
		List<int[]> claim = new ArrayList<>();
		for (int y = 0; y < 4; y++) {
			for (int x = 0; x < 4; x++) {
				claim.add(new int[] {x, y});
			}
		}
		claim.add(new int[] {2, 4});
		claim.add(new int[] {3, 4});
		claim.add(new int[] {2, 5});
		claim.add(new int[] {3, 5});
		JsonObject spec = JsonParser.parseString("""
				{"height": 100, "roofRows": 0, "quadCap": 1024, "doorClear": 24,
				 "keep": [[0, 0], [1, 0], [2, 0], [3, 0], [0, 1], [1, 1], [2, 1], [3, 1], [0, 2], [1, 2], [2, 2], [3, 2], [0, 3], [1, 3], [2, 3], [3, 3]],
				 "doors": [[2, 4], [3, 4], [2, 5], [3, 5]],
				 "parts": [{"shape": "box", "x": [0, 32], "z": [0, 48], "y": [0, 100], "paint": {"front": [0, 0, 32, 32]}}]}""").getAsJsonObject();
		return new LandscapeModels.Model("rock", 0, 0, 6, 6, List.copyOf(claim), spec);
	}

	@Test
	void aModelClaimsItsCellsAndStandsAsTallAsItsSpecUnderTheOpenSky() {
		SceneMap map = ShapeFixtures.openAir("ROCK", ART);
		int cols = map.artCols();
		int n = cols * map.artRows();
		boolean[] held = new boolean[n];
		Map<String, Integer> tally = new TreeMap<>();
		Map<Profile.Building, FittedShapes.Building> of = FittedBuildings.index();
		Map<String, List<LandscapeModels.Model>> before = LandscapeModels.use(Map.of("ROCK", List.of(rock())));
		try {
			List<Buildings.Claim> claims = LandscapeModels.claims(map, held, tally, of);
			assertEquals(1, claims.size());
			assertTrue(held[0] && held[3 * cols + 3], "the rock's tiles");
			assertTrue(held[4 * cols + 2], "the door its arch reaches over");
			assertFalse(held[4], "a tile of no section");
			ShapeKey[][] keys = new ShapeKey[n][];
			int[] blocks = new int[n];
			int[] mask = new int[n];
			int[] decor = new int[n];
			int[] floor = new int[n];
			FittedBuildings.place(map, 0, claims.get(0), of.get(claims.get(0).building()), 1, keys, blocks, mask, decor, floor, tally,
					LandscapeModels.ROOM);
			assertEquals(13, blocks[0], "12.5 blocks: no cap under the open sky (the buildings' would be 4)");
			assertEquals(ShapeKey.Kind.TMPL, keys[0][12].kind());
			assertEquals(0, blocks[4 * cols + 2], "the door stays walkable");
			assertEquals((1 << 13) - 1, decor[4 * cols + 2], "the arch over it is decor");
			assertNull(keys[4]);
			assertEquals(1, tally.get("landscape_model"));
		} finally {
			LandscapeModels.use(before);
		}
	}

	@Test
	void aTileAnotherClaimHoldsStaysThatClaims() {
		SceneMap map = ShapeFixtures.openAir("ROCK", ART);
		int cols = map.artCols();
		boolean[] held = new boolean[cols * map.artRows()];
		held[1] = true;
		Map<String, Integer> tally = new TreeMap<>();
		Map<String, List<LandscapeModels.Model>> before = LandscapeModels.use(Map.of("ROCK", List.of(rock())));
		try {
			Buildings.Claim claim = LandscapeModels.claims(map, held, tally, FittedBuildings.index()).get(0);
			assertFalse(claim.claimed()[1]);
			assertTrue(claim.claimed()[0]);
			assertEquals(1, tally.get("landscape_tiles_held_elsewhere"));
		} finally {
			LandscapeModels.use(before);
		}
	}

	@Test
	void aTableReads() {
		Map<String, List<LandscapeModels.Model>> table = LandscapeModels.parse("""
				{"maps": {"ROCK": [{"name": "rock", "tx": 2, "ty": 4, "w": 6, "h": 6, "claim": [[0, 0], [1, 2]],
				 "spec": {"height": 40, "parts": []}}]}}""");
		LandscapeModels.Model m = table.get("ROCK").get(0);
		assertEquals("rock", m.name());
		assertEquals(4, m.ty());
		assertEquals(2, m.claim().size());
		assertEquals(40, m.spec().get("height").getAsInt());
	}

	@Test
	void theMarkedSlopesRead() {
		Map<String, List<LandscapeModels.Slope>> slopes = LandscapeModels.parseSlopes("""
				{"maps": {}, "slopes": {"ROUTE": [[1, 2, "up"], [3, 0, "left"]], "CHECKED": []}}""");
		Map<String, List<LandscapeModels.Slope>> before = LandscapeModels.useSlopes(slopes);
		try {
			dev.kantobridge.world.Dir[] route = LandscapeModels.slopes("ROUTE", 4, 3);
			assertEquals(dev.kantobridge.world.Dir.UP, route[2 * 4 + 1]);
			assertEquals(dev.kantobridge.world.Dir.LEFT, route[3]);
			assertNull(route[0]);
			assertEquals(12, LandscapeModels.slopes("CHECKED", 4, 3).length, "checked, none marked: the drawing still decides");
			assertNull(LandscapeModels.slopes("UNCHECKED", 4, 3), "not checked: the data decides");
		} finally {
			LandscapeModels.useSlopes(before);
		}
		assertEquals(Map.of(), LandscapeModels.parseSlopes("{\"maps\": {}}"));
	}
}
