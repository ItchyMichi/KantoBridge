package dev.kantobridge.client.story;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import dev.kantobridge.world.WorldIndex;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The renderer's art lookup: each block of a closed tile cell draws its own quarter's slot, up its column or flat. */
class StoryCellArtTest {
	private static final String HASH = "0".repeat(64);

	private static WorldIndex world(StoryCellTable.Entry... entries) {
		MapPlacement town = new MapPlacement("TOWN", 100, 64, 200, 20, 18, 0, MapPlacement.Kind.SURFACE, "PAL", "kantobridge:kanto_outdoor",
				false, null);
		return new WorldIndex("red", HASH, List.of(town), HASH, StoryCellTable.of(List.of(entries)), List.of());
	}

	@Test
	void eachBlockDrawsItsQuarter() {
		// Cell (10, 14) of a map whose cell (0, 0) is at block (100, 200): its blocks are x 120..121, z 228..229.
		StoryCellTable.Entry tree = new StoryCellTable.Entry(new CellPos("TOWN", 10, 14), StoryCellType.TILE, null,
				new StoryCellTable.Swap(5, 7, "cut", 14, 15), false, 14, new StoryCellTable.Closed(14, true, 2, List.of(300, 301, 302, 4095)));
		WorldIndex index = world(tree);
		assertEquals(new StoryCellArt.Piece(300, 2), StoryCellArt.piece(index, tree, 120, 228), "north-west");
		assertEquals(new StoryCellArt.Piece(301, 2), StoryCellArt.piece(index, tree, 121, 228), "north-east");
		assertEquals(new StoryCellArt.Piece(302, 2), StoryCellArt.piece(index, tree, 120, 229), "south-west");
		StoryCellArt.Piece last = StoryCellArt.piece(index, tree, 121, 229);
		assertEquals(4095, last.slot());
		assertEquals(4095 / SlotMap.SLOTS_PER_FAMILY, last.family(), "slot 4095 is the last state of family 15, the legacy families' last");
		assertEquals(SlotMap.LEGACY_FAMILIES.hi(), last.family());
		assertEquals(255, last.state());
		assertEquals(1, new StoryCellArt.Piece(300, 2).family());
		assertEquals(44, new StoryCellArt.Piece(300, 2).state());
		assertNull(StoryCellArt.piece(index, tree, 122, 228), "outside the cell");
		assertNull(StoryCellArt.piece(index, tree, 119, 229), "outside the cell");
	}

	@Test
	void aCellCellDrawsTheStateItShows() {
		// SE3: cell (10, 14): blocks x 120..121, z 228..229; its states are a wall two blocks tall and a flat pond
		StoryCellTable.Entry cell = new StoryCellTable.Entry(new CellPos("TOWN", 10, 14), 5, new StoryCellTable.Built(0, StoryCellTable.Pass.GROUND),
				List.of(new StoryCellTable.CellState(5, StoryCellTable.Pass.SOLID, 2, List.of(300, 301, 302, 303)),
						new StoryCellTable.CellState(2, StoryCellTable.Pass.WATER, 0, List.of(7, 8, 9, 10))));
		WorldIndex index = world(cell);
		assertEquals(new StoryCellArt.Piece(301, 2), StoryCellArt.piece(index, cell, 0, 121, 228), "the wall's north-east quarter");
		assertEquals(new StoryCellArt.Piece(9, 0), StoryCellArt.piece(index, cell, 1, 120, 229), "the pond, flat");
		assertNull(StoryCellArt.piece(index, cell, -1, 120, 228), "the built state draws nothing");
		assertNull(StoryCellArt.piece(index, cell, 2, 120, 228), "no such state");
		assertNull(StoryCellArt.piece(index, cell, 0, 122, 228), "outside the cell");
	}

	@Test
	void flatArtAndMissingArt() {
		StoryCellTable.Entry carpet = new StoryCellTable.Entry(new CellPos("TOWN", 0, 0), StoryCellType.TILE, null,
				new StoryCellTable.Swap(0, 0, "script", 0, 4), false, 0, new StoryCellTable.Closed(0, false, 0, List.of(1, 2, 3, 4)));
		StoryCellTable.Entry old = new StoryCellTable.Entry(new CellPos("TOWN", 2, 0), StoryCellType.TILE, null,
				new StoryCellTable.Swap(1, 0, "cut", 14, 15));
		StoryCellTable.Entry elsewhere = new StoryCellTable.Entry(new CellPos("NOWHERE", 0, 0), StoryCellType.TILE, null,
				new StoryCellTable.Swap(0, 0, "cut", 14, 15), false, 14, new StoryCellTable.Closed(14, true, 2, List.of(1, 1, 1, 1)));
		WorldIndex index = world(carpet, old, elsewhere);
		assertEquals(new StoryCellArt.Piece(4, 0), StoryCellArt.piece(index, carpet, 101, 201), "drawn flat on the floor");
		assertNull(StoryCellArt.piece(index, old, 104, 200), "a WorldIndex from before M8 has no closed art");
		assertNull(StoryCellArt.piece(index, elsewhere, 100, 200), "a map this world doesn't have");
	}
}
