package dev.kantobridge.builder.shape;

import static dev.kantobridge.builder.shape.ShapeFixtures.surface;
import static dev.kantobridge.builder.shape.ShapeFixtures.tile;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.shape.geom.CellUnits;
import dev.kantobridge.scene.SceneMap;
import dev.kantobridge.scene.profile.Profile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The profile plan's units (docs/specs/PROFILE_PLAN.md §5.3, §5.4): classes per position from pins and conditional pins,
 * pinned units with their class, ground and background in the key, pins left alone where the cell isn't fit, and the
 * probe's repeating cells. Made-up maps and profiles; pixel-free (the probe is a stand-in).
 */
class ProfileUnitsTest {
	/** A profile whose FIX tileset has {@code body}; a fixture character between backticks stands for its tile id. */
	static Profile.Tileset tileset(String body) throws Exception {
		StringBuilder lua = new StringBuilder();
		for (int k = 0; k < body.length(); k++) {
			char c = body.charAt(k);
			if (c == '`') {
				lua.append(tile(body.charAt(k + 1)));
				k = body.indexOf('`', k + 1);
			} else {
				lua.append(c);
			}
		}
		return Profile.parse("return { tilesets = { FIX = { " + lua + " } } }").tileset("FIX");
	}

	static ShapePlanner.MapShapes plan(SceneMap map, Profile.Tileset tileset, UnitProbe probe, Map<String, Integer> tally) {
		return ShapePlanner.planMap(map, ShapeFixtures.TIER_B, 0, ShapeParams.p1(), tileset, probe, tally);
	}

	private static int i(SceneMap map, int tx, int ty) {
		return ty * map.artCols() + tx;
	}

	@Test
	void pinnedCylindersInARepeatingWallAreUnits() throws Exception {
		SceneMap map = surface("TREES",
				"......",
				"......",
				"..AB..",
				"..CD..",
				"..AB..",
				"..CD..",
				"......",
				"......");
		Map<String, Integer> tally = new TreeMap<>();
		ShapePlanner.MapShapes shapes = plan(map, tileset("cylinder = { `A`, `B`, `C`, `D` }"), null, tally);
		assertEquals(2, shapes.units().size(), "a repeating wall without the foliage hint, units by the pin");
		for (Units.Cell cell : shapes.units()) {
			assertEquals("pin", cell.source());
			CellUnits.Context context = CellUnits.Context.parse(cell.key().variant());
			assertEquals("cylinder", context.pinned());
			assertTrue(context.repeatsVertically());
		}
		assertEquals("g0r_cylinder", shapes.units().get(0).key().variant());
		assertEquals("g2r_cylinder", shapes.units().get(1).key().variant(), "the south cell borders walkable ground");
		assertEquals(Map.of("pin", 2), tally);
		// Without the profile the same wall stays a column.
		assertTrue(plan(map, null, null, new TreeMap<>()).units().isEmpty());
	}

	@Test
	void aCellPinnedToOtherClassesStaysWhatTierAMadeIt() throws Exception {
		SceneMap map = surface("PILLAR",
				"..AB..",
				"..CD..",
				"......",
				"......");
		ShapePlanner.MapShapes plain = plan(map, null, null, new TreeMap<>());
		assertEquals(1, plain.units().size(), "a lone cell is a unit by our own rule");
		Map<String, Integer> tally = new TreeMap<>();
		ShapePlanner.MapShapes pinned = plan(map, tileset("wall = { `A` }"), null, tally);
		assertTrue(pinned.units().isEmpty(), "the profile calls it a wall");
		assertEquals(ShapeKey.Kind.CUBE, pinned.open()[i(map, 2, 1)][0].kind(), "a Tier A column");
		assertEquals(Map.of(), tally);
	}

	@Test
	void aPinnedPropStandsOnItsPropGroundWithItsBackgroundInTheKey() throws Exception {
		SceneMap map = surface("PROP",
				"..PQ..",
				"..RS..",
				"......",
				"......");
		ShapePlanner.MapShapes shapes = plan(map,
				tileset("prop = { `P`, `Q`, `R`, `S` }, prop_ground = { [`P`] = `g` }, prop_bg = { { tiles = { `Q` }, shades = { \"white\", \"light\" } } }"),
				null, new TreeMap<>());
		assertEquals(1, shapes.units().size());
		ShapeKey key = shapes.units().get(0).key();
		assertEquals("g2_prop_bg3", key.variant());
		assertEquals(tile('g'), key.number(4), "the unit's ground is in its key");
		assertEquals(tile('g'), shapes.floor()[i(map, 2, 1)], "and the floor wears it");
	}

	@Test
	void pinnedCellsLeaveTierAWhileAWalkablePinIsCounted() throws Exception {
		SceneMap map = surface("ODD",
				"..p.....",
				"........",
				"..IJ....",
				"..KL....",
				"..MN....",
				"..OA....",
				"........",
				"........");
		ShapePlanner.MapShapes plain = plan(map, null, null, new TreeMap<>());
		assertTrue(plain.units().isEmpty());
		assertEquals(3, plain.blocks()[i(map, 2, 5)], "without the profile, a roofed Tier A run: two walls and a roof");
		Map<String, Integer> tally = new TreeMap<>();
		ShapePlanner.MapShapes shapes = plan(map, tileset("prop = { `p` }, cylinder = { `I`, `M` }"), null, tally);
		assertEquals(2, shapes.units().size(), "pinned cells are never structural: each is its own unit");
		assertEquals(2, shapes.blocks()[i(map, 2, 5)], "standing two blocks high");
		assertEquals(Map.of("pin", 2, "pin_not_solid", 1), tally, "the pin on a walkable cell is counted, not forced");
	}

	@Test
	void aPinnedSignBesideAHouseLeavesTheHousesTemplateAlone() throws Exception {
		SceneMap map = surface("TOWN",
				"..ABBBBC........",
				"..DEEEEF........",
				"..GHIJKLST......",
				"..MNOPQRUV......",
				"....++..........",
				"....++..........",
				"................",
				"................");
		ShapePlanner.MapShapes plain = plan(map, null, null, new TreeMap<>());
		assertEquals(1, plain.templates().size());
		assertEquals(8, plain.templates().get(0).entry().get("w").getAsInt(), "without the profile the sign is part of the house");
		ShapePlanner.MapShapes shapes = plan(map, tileset("signpost = { `S`, `T`, `U`, `V` }"), null, new TreeMap<>());
		assertEquals(1, shapes.templates().size(), "the pinned sign no longer joins the house's region");
		assertEquals(6, shapes.templates().get(0).entry().get("w").getAsInt());
		assertEquals(1, shapes.units().size());
		assertEquals("pin", shapes.units().get(0).source());
	}

	@Test
	void conditionalPinsReadAboveThenBelowAndTheOwnBorderOutsideTheMap() throws Exception {
		SceneMap map = surface("COND",
				"KxKx",
				"WxKx",
				"KxXx",
				"xxxx");
		Profile.Tileset t = tileset("when_above = { [`K`] = { { above = { `W` }, class = \"post\" }, { above = { `#` }, class = \"wall\" } } }, "
				+ "when_below = { [`K`] = { { below = { `X` }, class = \"prop\" } } }, cylinder = { `K` }");
		ProfileClasses classes = ProfileClasses.of(map, t);
		assertEquals("wall", classes.at(0, 0), "row 0 reads the map's own border above it");
		assertEquals("post", classes.at(0, 2), "a W above");
		assertEquals("prop", classes.at(2, 1), "an X below");
		assertEquals("wall", classes.at(2, 0), "above rules come first");
		assertEquals("cylinder", ProfileClasses.of(surface("PLAIN", "xx", "Kx"), t).at(0, 1), "no rule matches: the pin");
		assertNull(classes.at(1, 0));
	}

	@Test
	void theProbeTurnsRepeatingCellsIntoUnitsWhereItSaysSo() throws Exception {
		SceneMap map = surface("FENCE",
				"..........",
				"..........",
				"..EFEFEFIJ",
				"..GHGHGHKL");
		int[] fence = {tile('E'), tile('F'), tile('G'), tile('H')};
		for (boolean answer : new boolean[] {true, false}) {
			List<int[]> asked = new ArrayList<>();
			UnitProbe probe = (atlas, tiles, context) -> {
				asked.add(tiles);
				return answer;
			};
			Map<String, Integer> tally = new TreeMap<>();
			ShapePlanner.MapShapes shapes = plan(map, null, probe, tally);
			assertEquals(answer ? 3 : 0, shapes.units().size(), "the three repeating cells");
			assertEquals(3, asked.size());
			assertTrue(asked.stream().allMatch(tiles -> Arrays.equals(tiles, fence)), "the odd one out is never asked");
			assertEquals(answer ? Map.of("probe", 3) : Map.of(), tally);
		}
	}

	@Test
	void withoutAProfileOrAProbeTheP1PlannerPlansAsV7() {
		SceneMap map = surface("SAME",
				"..TT..AB",
				"..TT..CD",
				"........",
				"........",
				"..EFEF..",
				"..GHGH..");
		ShapePlanner.MapShapes v7 = ShapePlanner.planMap(map, ShapeFixtures.TIER_B, 0, ShapeParams.v7());
		ShapePlanner.MapShapes p1 = plan(map, null, null, new TreeMap<>());
		assertEquals(v7.open().length, p1.open().length);
		for (int k = 0; k < v7.open().length; k++) {
			assertArrayEquals(v7.open()[k], p1.open()[k], "tile " + k);
		}
		assertEquals(2, p1.units().size(), "the foliage and the lone cell, as before");
	}
}
