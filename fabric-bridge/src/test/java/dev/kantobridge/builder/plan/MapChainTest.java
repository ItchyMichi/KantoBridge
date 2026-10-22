package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
