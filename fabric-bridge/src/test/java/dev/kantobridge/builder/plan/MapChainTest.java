package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapLayout;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.Scale;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The chain of a region's surface maps (docs/specs/LEVELS_PIPELINE.md, the seams), on four made-up maps in a square. */
class MapChainTest {
	private static PlacedMap map(String id, int x, int z, MapLayout layout, String... rows) {
		MapPlacement p = new MapPlacement(id, x, Scale.SURFACE_Y, z, 4, 4, 0, MapPlacement.Kind.SURFACE, "PAL", "kantobridge:kanto_outdoor", false, null);
		return new PlacedMap(p, RaisedRegionsTest.model(rows), PairEdges.NONE, layout);
	}

	@Test
	void theMapsChainAtTheirSeamsAndALoopLeftOpenShows() {
		// four maps of 4 x 4 cells in a square: A's east edge climbs 3 blocks on three of its cells, so A -> B lifts B by 3
		// while A -> C -> D doesn't: the loop can't close; B's rock on its south edge makes B | D the seam with fewest pairs
		MapLayout climbs = MapLayout.flat(4, 4, new int[] {
				0, 0, 0, 3,
				0, 0, 0, 3,
				0, 0, 0, 3,
				0, 0, 0, 0}, List.of());
		String[] open = {"....", "....", "....", "...."};
		List<PlacedMap> placed = new ArrayList<>(List.of(
				map("A", 0, 0, climbs, open),
				map("B", 8, 0, null, "....", "....", "....", ".#.."),
				map("C", 0, 8, null, open),
				map("D", 8, 8, null, open)));
		MapChain.Result chain = MapChain.chain(placed);
		assertEquals(Map.of("A", 0, "B", 3, "C", 0, "D", 0), chain.bases(), "B starts where A's raised edge leaves off");
		assertEquals(1, chain.open().size());
		Map.Entry<MapChain.Seam, Integer> loop = chain.open().entrySet().iterator().next();
		assertEquals("B", loop.getKey().a());
		assertEquals("D", loop.getKey().b());
		assertEquals(-3, loop.getValue(), "the loop is 3 blocks open at B | D: D's ground 3 under B's");
		assertEquals(3, loop.getKey().pairs(), "the rock meets nothing");

		Map<String, MapPlacement> moved = MapChain.apply(placed, chain.bases());
		assertEquals(List.of("B"), List.copyOf(moved.keySet()));
		assertEquals(Scale.SURFACE_Y + 3, placed.get(1).placement().y());
		assertEquals(Scale.SURFACE_Y, placed.get(0).placement().y());
	}

	@Test
	void aRegionsLowestGroundStandsOnTheSurface() {
		// two maps side by side, the west one climbing 6 blocks to its east edge: the east one is the bigger and is placed
		// first, at the surface; then the group is lifted so the west map's low ground stands on the surface
		MapLayout raised = MapLayout.flat(4, 4, new int[] {
				0, 0, 0, 6,
				0, 0, 0, 6,
				0, 0, 0, 6,
				0, 0, 0, 6}, List.of());
		MapPlacement big = new MapPlacement("BIG", 8, Scale.SURFACE_Y, 0, 4, 6, 0, MapPlacement.Kind.SURFACE, "PAL", "kantobridge:kanto_outdoor", false, null);
		List<PlacedMap> placed = new ArrayList<>(List.of(
				map("WEST", 0, 0, raised, "....", "....", "....", "...."),
				new PlacedMap(big, RaisedRegionsTest.model("....", "....", "....", "....", "....", "...."), PairEdges.NONE, null)));
		MapChain.Result chain = MapChain.chain(placed);
		assertEquals(Map.of("BIG", 6, "WEST", 0), chain.bases(), "WEST's low ground on the surface, BIG's floor where WEST's edge leaves off");
		assertEquals(Map.of(), chain.open());
	}

	@Test
	void aLoopLeftOpenGetsAStairAtItsJoin() {
		// U (its base 9) north of L (on the surface), a corridor two cells wide between rock running up to their seam: three
		// cells deep in U, two in L; the loop leaves U's ground 9 blocks over L's at the seam
		MapPlacement up = new MapPlacement("U", 0, Scale.SURFACE_Y + 9, 0, 4, 4, 0, MapPlacement.Kind.SURFACE, "PAL", "kantobridge:kanto_outdoor", false,
				null);
		MapPlacement down = new MapPlacement("L", 0, Scale.SURFACE_Y, 8, 4, 4, 0, MapPlacement.Kind.SURFACE, "PAL", "kantobridge:kanto_outdoor", false,
				null);
		List<PlacedMap> placed = new ArrayList<>(List.of(
				new PlacedMap(up, RaisedRegionsTest.model("....", "#..#", "#..#", "#..#"), PairEdges.NONE, null),
				new PlacedMap(down, RaisedRegionsTest.model("#..#", "#..#", "....", "...."), PairEdges.NONE, null)));
		MapChain.Seam seam = new MapChain.Seam("L", "U", 2, 0, 2);
		Warnings warnings = new Warnings();
		MapChain.Joins joins = MapChain.joins(placed, new MapChain.Result(Map.of("L", 0, "U", 9), List.of(seam), Map.of(seam, 9)), warnings);
		assertEquals(1, warnings.noteCount("chain_join_stair"));
		MapLayout low = joins.layouts().get("L");
		assertEquals(0, low.level(1, 1), "L's corridor climbs out to the seam: its deepest step on its own ground");
		assertEquals(3, low.level(2, 0));
		assertEquals(Dir.UP, low.ramp(2, 0).dir());
		assertEquals(RaisedRegions.OUTDOOR_RISE, low.ramp(2, 0).rise());
		assertEquals(0, low.level(1, 2), "past its corridor, its own ground");
		MapLayout high = joins.layouts().get("U");
		assertEquals(Scale.SURFACE_Y + 6, joins.placements().get("U").y(), "U on a floor 3 lower for its last step");
		assertEquals(0, high.level(1, 3), "U's last step climbs in from L's top");
		assertEquals(Dir.UP, high.ramp(1, 3).dir());
		assertEquals(3, high.level(1, 2), "U's own ground where it was");
		assertEquals(3, high.level(0, 0));
		assertEquals(Scale.SURFACE_Y + 6, placed.get(0).placement().y(), "placed takes them");
		assertEquals(low, placed.get(1).layout());
	}

	@Test
	void aRaggedSeamClimbsOnItsLowerSide() {
		// A west of B, both on the surface: B's west column stands 1 block up on rows 0-1 and 4 up on row 3 (the chain left
		// them apart), level on row 2
		MapLayout ragged = MapLayout.flat(4, 4, new int[] {
				1, 0, 0, 0,
				1, 0, 0, 0,
				0, 0, 0, 0,
				4, 4, 4, 4}, List.of());
		String[] open = {"....", "....", "....", "...."};
		List<PlacedMap> placed = new ArrayList<>(List.of(map("A", 0, 0, null, open), map("B", 8, 0, ragged, open)));
		Warnings warnings = new Warnings();
		Map<String, MapLayout> layouts = MapChain.mend(placed, warnings);
		assertEquals(List.of("A"), List.copyOf(layouts.keySet()), "only the lower side changes");
		MapLayout a = placed.get(0).layout();
		assertEquals(Dir.RIGHT, a.ramp(3, 0).dir());
		assertEquals(1, a.ramp(3, 0).rise(), "a 1-block step: one ramp of 1");
		assertEquals(0, a.level(3, 0), "a ramp stands at its foot's level");
		assertEquals(1, a.ramp(3, 1).rise());
		assertEquals(null, a.ramp(3, 2), "where the seam meets, nothing");
		// 4 blocks: the cell behind climbs the first 3, the edge cell the last 1
		assertEquals(0, a.level(2, 3));
		assertEquals(RaisedRegions.OUTDOOR_RISE, a.ramp(2, 3).rise());
		assertEquals(3, a.level(3, 3));
		assertEquals(1, a.ramp(3, 3).rise());
		assertEquals(4, warnings.noteCount("seam_mend_ramps"));
		assertEquals(0, warnings.noteCount("seam_mend_unmet"));
	}

	@Test
	void aRaggedSeamNextToRockOrAWallStaysAsItIs() {
		// row 1's 6 blocks need two cells and the one behind A's edge cell is rock; row 2's 3 blocks meet A's walled side
		MapLayout ragged = MapLayout.flat(4, 4, new int[] {
				0, 0, 0, 0,
				6, 6, 6, 6,
				3, 3, 3, 3,
				0, 0, 0, 0}, List.of());
		byte[] walls = new byte[16];
		walls[2 * 4 + 3] = (byte) dev.kantobridge.block.EdgeWallBlock.SIDE_EAST;
		PlacedMap a = map("A", 0, 0, null, "....", "..#.", "....", "....");
		a = new PlacedMap(a.placement(), a.model(), PairEdges.plus(PairEdges.NONE, a.model(), walls), null);
		List<PlacedMap> placed = new ArrayList<>(List.of(a, map("B", 8, 0, ragged, "....", "....", "....", "....")));
		Warnings warnings = new Warnings();
		MapChain.mend(placed, warnings);
		assertEquals(null, placed.get(0).layout(), "nothing could be ramped");
		assertEquals(1, warnings.noteCount("seam_mend_unmet"), "row 1 (row 2 is walled: not asked)");
	}
}
