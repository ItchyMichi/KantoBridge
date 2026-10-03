package dev.kantobridge.client.fx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.story.StoryState;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import dev.kantobridge.world.WorldIndex;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * When another player is drawn faded (DESIGN §8 "Smoothing it over"): they stand on a story cell that the local player's
 * own story still has closed (an object, a closed tile, a boulder), never on water, open cells, ordinary ground or off
 * every map.
 */
class BlockadeFadeTest {
	private static final String HASH = "0".repeat(64);
	private static final String GUARD = "TOWN:3:4";
	private static final String TREE = "TOWN:6:4";
	private static final String WATER = "TOWN:8:4";
	private static final String BOULDER = "CAVE:2:2";
	private static final String SIGN = "TOWN:9:9";

	/** A town at the origin (cells are 2×2 blocks, feet at Y 64) and a cave one layer down. */
	private static WorldIndex world() {
		List<MapPlacement> maps = List.of(
				new MapPlacement("TOWN", 0, 64, 0, 20, 18, 0, MapPlacement.Kind.SURFACE, "PAL", "kantobridge:kanto_outdoor", false, null),
				new MapPlacement("CAVE", 100, 42, 100, 10, 10, 1, MapPlacement.Kind.INTERIOR, "PAL", "kantobridge:kanto_cave", true, "TOWN"));
		StoryCellTable cells = StoryCellTable.of(List.of(
				new StoryCellTable.Entry(CellPos.parse(GUARD), StoryCellType.OBJECT, "TOWN_GUARD", null),
				new StoryCellTable.Entry(CellPos.parse(TREE), StoryCellType.TILE, null, new StoryCellTable.Swap(3, 2, "cut", 14, 15), false, 14,
						new StoryCellTable.Closed(14, true, 2, List.of(13, 13, 13, 13))),
				new StoryCellTable.Entry(CellPos.parse(WATER), StoryCellType.WATER, null, null),
				new StoryCellTable.Entry(CellPos.parse(BOULDER), StoryCellType.BOULDER_AREA, null, null),
				new StoryCellTable.Entry(CellPos.parse(SIGN), StoryCellType.OBJECT, "TOWN_SIGN", null)));
		return new WorldIndex("red", HASH, maps, HASH, cells, List.of());
	}

	/** Somewhere inside a town cell's 2×2 footprint, standing on its floor. */
	private static boolean fadesAt(WorldIndex index, StoryState state, int cellX, int cellY, double dx, double dz) {
		return BlockadeFade.fades(index, state, cellX * 2 + dx, 64.0, cellY * 2 + dz);
	}

	@Test
	void anotherPlayerFadesInsideTheLocalPlayersClosedCells() {
		WorldIndex index = world();
		StoryState local = new StoryState();
		local.setActive(Set.of(GUARD, TREE));
		assertTrue(fadesAt(index, local, 3, 4, 0.5, 0.5), "where the local player's guard stands");
		assertTrue(fadesAt(index, local, 3, 4, 1.9, 1.9), "anywhere in the cell's footprint");
		assertTrue(fadesAt(index, local, 6, 4, 1.0, 1.0), "where the local player's Cut tree stands");
		assertFalse(fadesAt(index, local, 9, 9, 1.0, 1.0), "the other object is open for the local player");
		assertFalse(fadesAt(index, local, 4, 4, 0.5, 0.5), "ordinary ground next to the guard");
		assertFalse(fadesAt(index, local, 2, 4, 1.99, 0.5), "just outside the cell");
	}

	@Test
	void waterIsNoBlockade() {
		WorldIndex index = world();
		StoryState local = new StoryState();
		assertFalse(fadesAt(index, local, 8, 4, 1.0, 1.0), "a surfer seen by a walker is just surfing");
		assertFalse(BlockadeFade.fades(index, local, 17.0, 63.5, 9.0), "standing on the water's surface");
	}

	@Test
	void boulderCellsFadeWhereTheLocalPlayersBoulderStands() {
		WorldIndex index = world();
		StoryState local = new StoryState();
		double x = 100 + 2 * 2 + 1.0;
		double z = 100 + 2 * 2 + 1.0;
		assertFalse(BlockadeFade.fades(index, local, x, 42.0, z), "no boulder there for the local player");
		local.setActive(Set.of(BOULDER));
		assertTrue(BlockadeFade.fades(index, local, x, 42.0, z), "the local player's boulder stands there");
		local.setActive(Set.of());
		local.setBoulders(Set.of(BOULDER));
		assertTrue(BlockadeFade.fades(index, local, x, 42.0, z));
	}

	@Test
	void nothingFadesOutsideKantoOrWithoutAState() {
		WorldIndex index = world();
		StoryState local = new StoryState();
		local.setActive(Set.of(GUARD));
		assertFalse(BlockadeFade.fades(null, local, 7.0, 64.0, 9.0), "not a Kanto world");
		assertFalse(BlockadeFade.fades(index, null, 7.0, 64.0, 9.0), "no story state yet");
		assertFalse(BlockadeFade.fades(index, local, 7.0, 90.0, 9.0), "far above the map's floor");
		assertFalse(BlockadeFade.fades(index, local, -500.0, 64.0, -500.0), "off every map");
	}
}
