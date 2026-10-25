package dev.kantobridge.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Per-cell positions (docs/specs/CAVES_3D_PLAN.md §3.1, H1): map layouts in the WorldIndex, their JSON, and the lookups
 * that go through them.
 */
class MapLayoutTest {
	private static final String HASH_A = "a".repeat(64);
	private static final String HASH_B = "b".repeat(64);

	/**
	 * A 6 × 4 cave: a west room (columns 0–2) and an east room (columns 4–5) placed apart, with a rock wall (column 3)
	 * that both draw. Cell (5, 3) is in neither.
	 */
	static MapPlacement cave() {
		return new MapPlacement("TEST_CAVE", 1000, 64, 2000, 6, 4, 1, MapPlacement.Kind.INTERIOR, "TEST_PALETTE", "kantobridge:kanto_cave",
				false, "TEST_TOWN");
	}

	static MapLayout twoRooms() {
		BitSet east = new BitSet();
		// rectangle (3, 0) 3 × 4: the wall column 3 and the room's columns 4–5, without cell (5, 3)
		east.set(0, 11);
		int[] levels = new int[6 * 4];
		levels[2 * 6 + 1] = 2; // cell (1, 2) raised
		return MapLayout.of(6, 4, List.of(new MapLayout.Piece(0, 0, 4, 4, 0, 0, 0), new MapLayout.Piece(3, 0, 3, 4, east, 40, -11, 8)), levels,
				List.of(new MapLayout.Ramp(1, 1, Dir.DOWN, 2)));
	}

	static WorldIndex index(MapLayout layout) {
		return new WorldIndex("red", HASH_A, List.of(cave()), Map.of("TEST_CAVE", layout), HASH_B, StoryCellTable.of(List.of()), List.of(),
				List.of(), null);
	}

	@Test
	void cellsSitAtTheirPieceAndLevel() {
		WorldIndex index = index(twoRooms());
		assertEquals(new BlockPos(1000, 64, 2000), index.cellOrigin("TEST_CAVE", 0, 0));
		assertEquals(new BlockPos(1002, 66, 2004), index.cellOrigin("TEST_CAVE", 1, 2), "a raised cell");
		assertEquals(new BlockPos(1002, 64, 2004), index.levelOrigin("TEST_CAVE", 1, 2), "its level is its piece's feet");
		assertEquals(new BlockPos(1000 + 8 + 40, 64 - 11, 2000 + 2 + 8), index.cellOrigin("TEST_CAVE", 4, 1), "the east room's cell");
		assertEquals(new Vec3(1049.0, 53.0, 2011.0), index.toBlock("TEST_CAVE", 4, 1));
		// The wall column 3 is listed by both pieces: the first one holds it for lookups by cell.
		assertEquals(new BlockPos(1006, 64, 2000), index.cellOrigin("TEST_CAVE", 3, 0));
		// Outside the map (an outdoor seam) a cell sits at the placement.
		assertEquals(new BlockPos(998, 64, 2000), index.cellOrigin("TEST_CAVE", -1, 0));
	}

	@Test
	void toCellFindsEachPieceAndInvertsCellOrigin() {
		WorldIndex index = index(twoRooms());
		for (int cy = 0; cy < 4; cy++) {
			for (int cx = 0; cx < 6; cx++) {
				if (cx == 3 || (cx == 5 && cy == 3)) {
					continue; // the wall, drawn by both pieces; a cell in neither
				}
				BlockPos o = index.cellOrigin("TEST_CAVE", cx, cy);
				for (int bx = 0; bx < 2; bx++) {
					for (int bz = 0; bz < 2; bz++) {
						assertEquals(Optional.of(new CellPos("TEST_CAVE", cx, cy)), index.toCell(o.getX() + bx, o.getY(), o.getZ() + bz),
								cx + "," + cy);
					}
				}
			}
		}
		// The east room's cells aren't found where they would be without the layout ...
		assertEquals(Optional.of(new CellPos("TEST_CAVE", 3, 0)), index.toCell(1006, 64, 2000));
		assertEquals(Optional.empty(), index.toCell(1008, 64, 2000));
		// ... and each cell's window is its own floor's, narrow enough for pieces 11 blocks apart.
		assertEquals(Optional.of(new CellPos("TEST_CAVE", 0, 0)), index.toCell(1000, 61, 2000));
		assertEquals(Optional.empty(), index.toCell(1000, 60, 2000));
		assertEquals(Optional.of(new CellPos("TEST_CAVE", 0, 0)), index.toCell(1000, 68, 2000));
		assertEquals(Optional.empty(), index.toCell(1000, 69, 2000));
		assertEquals(Optional.of(new CellPos("TEST_CAVE", 1, 2)), index.toCell(1002, 70, 2004), "a raised cell's window is raised too");
	}

	@Test
	void jsonRoundTripIsExact() throws Exception {
		WorldIndex index = index(twoRooms());
		WorldIndex read = WorldIndex.fromJson(index.toJson());
		assertEquals(index.toJson(), read.toJson());
		MapLayout layout = read.layout("TEST_CAVE");
		assertNotNull(layout);
		assertEquals(twoRooms().pieces(), layout.pieces());
		assertEquals(2, layout.level(1, 2));
		assertEquals(new MapLayout.Ramp(1, 1, Dir.DOWN, 2), layout.ramp(1, 1));
		JsonObject cave = ProtoJson.parseObject(index.toJson()).getAsJsonObject("maps").getAsJsonObject("TEST_CAVE");
		assertEquals(List.of("biome", "dark", "h", "kind", "layer", "levels", "palette", "parent", "pieces", "ramps", "w", "x", "y", "z"),
				cave.keySet().stream().sorted().toList());
		assertEquals("[0,13,2,1,0,10]", cave.get("levels").toString());
		assertEquals("[0,11,1]", cave.getAsJsonArray("pieces").get(1).getAsJsonObject().get("mask").toString());
		assertFalse(cave.getAsJsonArray("pieces").get(0).getAsJsonObject().has("mask"));
	}

	/** The 6 × 4 map: a deck across row 1 at 9 blocks over water at 0 (Route 110 in small), cell (3, 1) an end at 9. */
	static MapLayout deck() {
		int[] levels = new int[6 * 4];
		for (int x = 0; x < 6; x++) {
			levels[6 + x] = 9;
		}
		return MapLayout.flat(6, 4, levels, List.of()).withDecks(List.of(new MapLayout.Deck(1, 1, 0, 4), new MapLayout.Deck(2, 1, 0, 4)));
	}

	@Test
	void decksRoundTripAndAnswerForBothFloors() throws Exception {
		// WATER_AND_BRIDGES_PLAN D7: two-floor cells
		MapLayout layout = deck();
		assertEquals(0, layout.under(1, 1));
		assertEquals(4, layout.deckElev(2, 1));
		assertEquals(-1, layout.under(0, 1), "an end has one floor");
		assertFalse(layout.trivial());
		WorldIndex index = index(layout);
		WorldIndex back = WorldIndex.fromJson(index.toJson());
		assertEquals(index.toJson(), back.toJson(), "decks round-trip");
		assertTrue(index.toJson().contains("\"decks\""));
		BlockPos deckAt = index.cellOrigin("TEST_CAVE", 1, 1);
		BlockPos underAt = index.cellOrigin("TEST_CAVE", 1, 1, WorldIndex.Floor.UNDER);
		assertEquals(deckAt.below(9), underAt);
		CellPos cell = new CellPos("TEST_CAVE", 1, 1);
		assertEquals(Optional.of(cell), index.toCell(underAt.getX(), underAt.getY(), underAt.getZ()), "the under floor is in the cell");
		assertEquals(Optional.of(cell), index.toCell(deckAt.getX(), deckAt.getY(), deckAt.getZ()), "and the deck");
		assertEquals(WorldIndex.Floor.UNDER, index.floorAt(cell, underAt.getY()));
		assertEquals(WorldIndex.Floor.DECK, index.floorAt(cell, deckAt.getY()));
		assertEquals(WorldIndex.Floor.DECK, index.floorFor("TEST_CAVE", 1, 1, 4), "the deck's elevation: on the deck");
		assertEquals(WorldIndex.Floor.UNDER, index.floorFor("TEST_CAVE", 1, 1, 1), "a surfer's: under it");
		assertEquals(WorldIndex.Floor.UNDER, index.floorFor("TEST_CAVE", 1, 1, 3), "a walker's from the ground: under it");
		assertEquals(WorldIndex.Floor.DECK, index.floorFor("TEST_CAVE", 1, 1, -1), "unknown: the deck");
		assertEquals(WorldIndex.Floor.DECK, index.floorFor("TEST_CAVE", 0, 0, 1), "a cell of one floor has only its own");
		assertEquals(underAt.getY(), (int) index.toBlock("TEST_CAVE", 1, 1, 1).y, "a warp's arrival by elevation");
		assertEquals(deckAt.getY(), (int) index.toBlock("TEST_CAVE", 1, 1, 4).y);
		assertEquals(UNDER_ELEV_CHECK, index.floorFor("TEST_CAVE", 1, 1, index.elevOf("TEST_CAVE", 1, 1, WorldIndex.Floor.UNDER)));
		assertThrows(IllegalArgumentException.class, () -> MapLayout.flat(6, 4, null, List.of()).withDecks(List.of(new MapLayout.Deck(1, 1, 0, 4))),
				"a deck over its own floor");
	}

	private static final WorldIndex.Floor UNDER_ELEV_CHECK = WorldIndex.Floor.UNDER;

	@Test
	void ceilingsRoundTripAndBoundTheMap() throws Exception {
		// HEIGHTS_ALL_GAMES_PLAN D5: a room's ceilings, as (ceiling, count) runs; the least room over any cell
		int[] ceilings = new int[24];
		java.util.Arrays.fill(ceilings, 6);
		ceilings[2 * 6 + 1] = 8;
		MapLayout layout = twoRooms().withCeilings(ceilings);
		WorldIndex index = index(layout);
		WorldIndex read = WorldIndex.fromJson(index.toJson());
		assertEquals(index.toJson(), read.toJson());
		assertEquals(8, read.layout("TEST_CAVE").ceiling(1, 2));
		assertEquals(6, read.layout("TEST_CAVE").ceiling(0, 0));
		JsonObject cave = ProtoJson.parseObject(index.toJson()).getAsJsonObject("maps").getAsJsonObject("TEST_CAVE");
		assertEquals("[6,13,8,1,6,10]", cave.get("ceilings").toString());
		assertEquals(4, layout.headroom(), "the ramp's top (2) under a ceiling of 6");
		assertEquals(-1, twoRooms().ceiling(0, 0), "no ceilings: the default");
		// A layout with only ceilings is kept, and it bounds the map up to its highest ceiling block
		WorldIndex flat = index(MapLayout.flat(6, 4, null, List.of()).withCeilings(ceilings));
		assertNotNull(flat.layout("TEST_CAVE"));
		assertEquals(64 + 8, flat.boundsOfPlacements().maxY(), "the ceiling's own block over its feet");
		assertThrows(IllegalArgumentException.class, () -> twoRooms().withCeilings(new int[3]));
		assertThrows(IllegalArgumentException.class, () -> twoRooms().withCeilings(new int[24]), "a ceiling of 0");
	}

	@Test
	void onlyWhatDiffersIsWritten() throws Exception {
		// Levels alone: no pieces key.
		int[] levels = new int[24];
		levels[0] = 2;
		WorldIndex raised = index(MapLayout.flat(6, 4, levels, List.of()));
		JsonObject cave = ProtoJson.parseObject(raised.toJson()).getAsJsonObject("maps").getAsJsonObject("TEST_CAVE");
		assertTrue(cave.has("levels"));
		assertFalse(cave.has("pieces"));
		assertFalse(cave.has("ramps"));
		// A trivial layout is dropped: the index is byte for byte one without layouts.
		WorldIndex trivial = index(MapLayout.flat(6, 4, null, List.of()));
		assertNull(trivial.layout("TEST_CAVE"));
		WorldIndex none = new WorldIndex("red", HASH_A, List.of(cave()), HASH_B, StoryCellTable.of(List.of()), List.of());
		assertEquals(none.toJson(), trivial.toJson());
		assertEquals(none.cellOrigin("TEST_CAVE", 2, 3), trivial.cellOrigin("TEST_CAVE", 2, 3));
	}

	@Test
	void aMapWithoutLayoutKeepsItsLookups() {
		WorldIndex index = new WorldIndex("red", HASH_A, List.of(cave()), HASH_B, StoryCellTable.of(List.of()), List.of());
		assertEquals(new BlockPos(1008, 64, 2002), index.cellOrigin("TEST_CAVE", 4, 1));
		assertEquals(index.cellOrigin("TEST_CAVE", 4, 1), index.levelOrigin("TEST_CAVE", 4, 1));
		// Today's window: feet - 4 to feet + 5.
		assertEquals(Optional.of(new CellPos("TEST_CAVE", 4, 1)), index.toCell(1008, 60, 2002));
		assertEquals(Optional.of(new CellPos("TEST_CAVE", 4, 1)), index.toCell(1008, 69, 2002));
		assertEquals(Optional.empty(), index.toCell(1008, 70, 2002));
	}

	@Test
	void badLayoutsAreRefused() {
		assertThrows(IllegalArgumentException.class, () -> MapLayout.of(6, 4, List.of(), null, List.of()), "no piece");
		assertThrows(IllegalArgumentException.class, () -> MapLayout.of(6, 4, List.of(new MapLayout.Piece(4, 0, 3, 4, 0, 0, 0)), null, List.of()),
				"a piece outside its map");
		assertThrows(IllegalArgumentException.class, () -> MapLayout.flat(6, 4, new int[23], List.of()), "one level per cell");
		assertThrows(IllegalArgumentException.class, () -> MapLayout.flat(6, 4, new int[24], List.of(new MapLayout.Ramp(6, 0, Dir.UP, 2))));
		assertThrows(IllegalArgumentException.class,
				() -> MapLayout.flat(6, 4, null, List.of(new MapLayout.Ramp(1, 1, Dir.UP, 2), new MapLayout.Ramp(1, 1, Dir.DOWN, 2))));
		assertThrows(IllegalArgumentException.class, () -> new MapLayout.Piece(0, 0, 2, 2, new BitSet(), 0, 0, 0), "an empty mask");
		// A layout for another size of map.
		assertThrows(IllegalArgumentException.class, () -> index(MapLayout.flat(5, 4, new int[] {2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
				0, 0, 0}, List.of())));
	}

	@Test
	void badJsonIsRefused() {
		String good = index(twoRooms()).toJson();
		assertThrows(DataException.class, () -> WorldIndex.fromJson(good.replace("\"levels\":[0,13,2,1,0,10]", "\"levels\":[0,13,2,1,0,9]")),
				"levels must cover every cell");
		assertThrows(DataException.class, () -> WorldIndex.fromJson(good.replace("\"levels\":[0,13,2,1,0,10]", "\"levels\":[0,13,2,1,0]")),
				"levels come in pairs");
		assertThrows(DataException.class, () -> WorldIndex.fromJson(good.replace("\"dir\":\"down\"", "\"dir\":\"north\"")));
		assertThrows(DataException.class, () -> WorldIndex.fromJson(good.replace("\"rise\":2", "\"rise\":9")));
	}

	@Test
	void pieceLookupsGoToTheFirstPieceListingACell() {
		MapLayout layout = twoRooms();
		assertSame(layout.pieces().get(0), layout.pieceAt(3, 2));
		assertSame(layout.pieces().get(1), layout.pieceAt(5, 2));
		assertNull(layout.pieceAt(6, 0));
		assertEquals(0, layout.level(-1, 0));
	}
}
