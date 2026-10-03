package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.export.ExportMap;
import dev.kantobridge.builder.export.ExportSwaps;
import dev.kantobridge.builder.export.ExportTileset;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Story cells on a made-up 2×2-block room: objects, a card-key door, a boulder area, and an item on a table; and the
 * swap sites M8 handles: one the engine stamps closed on map load (the data shows it open), one whose data matches
 * neither block, one whose {@code after} is the solid state, and an art-only swap.
 */
class StoryCellPlannerTest {
	/** Tiles: 0 floor (walkable), 1 table, 2 locked door, 4 carpet (walkable), 5 water. */
	private static final int FLOOR = 0;
	private static final int TABLE = 1;
	private static final int DOOR = 2;
	private static final int CARPET = 4;
	private static final int WATER = 5;
	/** Blocks: 0 floor, 1 table, 2 door, 3 open door (floor), 4 carpet, 5 water. */
	private static final ExportTileset TILESET = new ExportTileset("ROOM", "tilesets.room", 16, 96,
			new int[][] {uniform(FLOOR), uniform(TABLE), uniform(DOOR), uniform(FLOOR), uniform(CARPET), uniform(WATER)}, "TILEANIM_NONE",
			Set.of(FLOOR, CARPET), Set.of(), null, Set.of(), Set.of(), Set.of(WATER), Set.of(), Set.of());
	/** Slots stand for tiles one to one, so tests read the closed art's tiles straight off it. */
	private static final StoryCellPlanner.ArtSlots SLOTS = (atlas, palette, tile) -> tile;

	private static int[] uniform(int tile) {
		int[] block = new int[16];
		java.util.Arrays.fill(block, tile);
		return block;
	}

	private static int[][] flags(int[] blocks) {
		int[][] cells = new int[4][4];
		for (int cy = 0; cy < 4; cy++) {
			for (int cx = 0; cx < 4; cx++) {
				int block = blocks[(cy / 2) * 2 + cx / 2];
				cells[cy][cx] = block == 0 || block == 3 || block == 4 ? CellFlags.WALKABLE : block == 5 ? CellFlags.WATER : 0;
			}
		}
		return cells;
	}

	private static ExportMap room(int[] blocks, List<ExportMap.MapObject> objects) {
		return new ExportMap("ROOM", 40, "ROOM", 2, 2, blocks, 1, false, false, Map.of(), List.of(), List.of(), objects, flags(blocks), null,
				null, false, "PAL");
	}

	private static PlacedMap placed(MapModel model) {
		return new PlacedMap(new MapPlacement("ROOM", 0, 42, 0, 4, 4, 1, MapPlacement.Kind.INTERIOR, "PAL", WorldPlan.BIOME_INTERIOR, false,
				"TOWN"), model);
	}

	@Test
	void choosesCellsByKindWithObjectsFirst() {
		List<ExportMap.MapObject> objects = List.of(
				new ExportMap.MapObject(1, "ROOM_BOULDER", "SPRITE_BOULDER", 0, 0, "STAY", "BOULDER_MOVEMENT_BYTE_2", false, null, false, false),
				new ExportMap.MapObject(2, "ROOM_ITEM", "SPRITE_POKE_BALL", 2, 0, "STAY", "NONE", false, "POTION", false, false),
				new ExportMap.MapObject(3, "ROOM_GUARD", "SPRITE_GUARD", 1, 1, "STAY", "DOWN", true, null, false, false),
				new ExportMap.MapObject(4, "ROOM_WALKER", "SPRITE_GIRL", 3, 3, "WALK", "ANY_DIR", false, null, false, false));
		ExportMap map = room(new int[] {0, 1, 2, 0}, objects);
		ExportSwaps.Site door = new ExportSwaps.Site("ROOM", 0, 1, "card_key", 2, 3);
		Warnings warnings = new Warnings();
		MapModel model = MapModel.build(map, TILESET, List.of(door), true, warnings);

		StoryCellTable table = StoryCellPlanner.plan(List.of(placed(model)), warnings, SLOTS);
		assertEquals(0, warnings.total(), warnings.warnings().toString());
		assertEquals(1, warnings.noteCount("story_cell_on_solid"), "the item lies on the table: terrain already blocks it");

		StoryCellTable.Entry guard = table.get("ROOM:1:1");
		assertEquals(StoryCellType.OBJECT, guard.type());
		assertEquals("ROOM_GUARD", guard.obj());
		assertTrue(guard.hidden(), "the guard starts hidden: the entry says so");
		assertNull(table.get("ROOM:2:0"));
		// The door block is built open, and closed per player by tile story cells.
		for (String key : new String[] {"ROOM:0:2", "ROOM:1:2", "ROOM:0:3", "ROOM:1:3"}) {
			StoryCellTable.Entry entry = table.get(key);
			assertEquals(StoryCellType.TILE, entry.type(), key);
			assertEquals(new StoryCellTable.Swap(0, 1, "card_key", 2, 3), entry.swap());
			assertEquals(2, entry.start(), "the map data shows the door closed");
			assertEquals(new StoryCellTable.Closed(2, true, HeightRules.INTERIOR_WALL, List.of(DOOR, DOOR, DOOR, DOOR)), entry.closed());
		}
		assertEquals(MapModel.Kind.GROUND, model.kind(0, 2));
		// The boulder map's other walkable cells, the boulder's own cell and the walker's included.
		for (String key : new String[] {"ROOM:0:0", "ROOM:1:0", "ROOM:0:1", "ROOM:2:2", "ROOM:3:2", "ROOM:2:3", "ROOM:3:3"}) {
			assertEquals(StoryCellType.BOULDER_AREA, table.get(key).type(), key);
		}
		assertEquals(12, table.size());
		assertEquals(new CellPos("ROOM", 0, 0), table.entries().get(0).cell());
	}

	@Test
	void aSiteTheEngineStampsClosedIsAcceptedOpen() {
		// The map data already holds the open door (3); the engine stamps the closed one (2) on map load.
		ExportMap map = room(new int[] {0, 0, 3, 0}, List.of());
		ExportSwaps.Site door = new ExportSwaps.Site("ROOM", 0, 1, "card_key", 2, 3, "EVENT_ROOM_DOOR");
		Warnings warnings = new Warnings();
		MapModel model = MapModel.build(map, TILESET, List.of(door), true, warnings, site -> 7);
		StoryCellTable table = StoryCellPlanner.plan(List.of(placed(model)), warnings, SLOTS);

		assertEquals(0, warnings.total(), "a stamped site is no mismatch: " + warnings.warnings());
		assertEquals(1, warnings.noteCount("swap_site_stamped"));
		assertEquals(List.of(Map.of("kind", "card_key", "map", "ROOM", "site", "7", "source", "EVENT_ROOM_DOOR")),
				warnings.details("swap_site_stamped"));
		StoryCellTable.Entry entry = table.get("ROOM:1:3");
		assertEquals(StoryCellType.TILE, entry.type());
		assertEquals(3, entry.start(), "other maps show the map data: open");
		assertEquals(2, entry.closed().block(), "closed while the live block is the stamped door");
		assertEquals(StoryCellTable.Pass.SOLID, entry.closed().pass());
		assertEquals(MapModel.Kind.GROUND, model.kind(1, 3));
	}

	@Test
	void aSiteMatchingNeitherBlockIsAWarningNamingItsSymbols() {
		ExportMap map = room(new int[] {0, 0, 1, 0}, List.of());
		ExportSwaps.Site door = new ExportSwaps.Site("ROOM", 0, 1, "script", 2, 3, "EVENT_ROOM_DOOR");
		Warnings warnings = new Warnings();
		MapModel model = MapModel.build(map, TILESET, List.of(door), true, warnings, site -> 3);
		StoryCellTable table = StoryCellPlanner.plan(List.of(placed(model)), warnings, SLOTS);

		assertEquals(1, warnings.count("swap_block_mismatch"));
		assertEquals(List.of(Map.of("kind", "script", "map", "ROOM", "site", "3", "source", "EVENT_ROOM_DOOR")),
				warnings.details("swap_block_mismatch"), "symbols only: no coordinates, no block ids");
		assertNull(table.get("ROOM:0:2"), "a mismatched site gets no story cells");
		assertEquals(MapModel.Kind.SOLID, model.kind(0, 2), "the table stays as the data has it");
	}

	@Test
	void aCellWhoseAfterIsSolidIsBuiltInItsBeforeState() {
		// A barrier that appears later: open floor (3) becomes the door (2). Built walkable; closed by the door.
		ExportMap map = room(new int[] {0, 0, 3, 0}, List.of());
		ExportSwaps.Site barrier = new ExportSwaps.Site("ROOM", 0, 1, "script", 3, 2);
		Warnings warnings = new Warnings();
		MapModel model = MapModel.build(map, TILESET, List.of(barrier), true, warnings);
		StoryCellTable table = StoryCellPlanner.plan(List.of(placed(model)), warnings, SLOTS);

		assertEquals(0, warnings.total(), warnings.warnings().toString());
		assertEquals(MapModel.Kind.GROUND, model.kind(0, 2), "built in its walkable state");
		assertEquals(FLOOR, model.tile(0, 4));
		StoryCellTable.Entry entry = table.get("ROOM:0:2");
		assertEquals(3, entry.start());
		assertEquals(2, entry.closed().block(), "the after block closes this cell");
		assertEquals(StoryCellTable.Pass.SOLID, entry.closed().pass());
		assertEquals(List.of(DOOR, DOOR, DOOR, DOOR), entry.closed().art());
		assertEquals(MapModel.Kind.SOLID, model.closedModel().kind(0, 2));
	}

	@Test
	void anArtOnlySwapIsDrawnFlatAndNeverSolid() {
		ExportMap map = room(new int[] {0, 0, 0, 0}, List.of());
		ExportSwaps.Site carpet = new ExportSwaps.Site("ROOM", 1, 1, "script", 0, 4);
		Warnings warnings = new Warnings();
		MapModel model = MapModel.build(map, TILESET, List.of(carpet), true, warnings);
		StoryCellTable table = StoryCellPlanner.plan(List.of(placed(model)), warnings, SLOTS);

		StoryCellTable.Entry entry = table.get("ROOM:3:3");
		assertNotNull(entry);
		assertEquals(new StoryCellTable.Closed(0, false, 0, List.of(FLOOR, FLOOR, FLOOR, FLOOR)), entry.closed());
		assertEquals(CARPET, model.tile(6, 6), "built in its after state, the carpet");
		assertTrue(model.closedModel().closedState() && !model.closedState());
	}

	@Test
	void aShipThatSailsLeavesWaterOnlySurfersCross() {
		// The hull (the table here) and the deck (floor) both become water when she sails.
		ExportMap map = room(new int[] {1, 0, 0, 0}, List.of());
		ExportSwaps.Site hull = new ExportSwaps.Site("ROOM", 0, 0, "script", 1, 5);
		ExportSwaps.Site deck = new ExportSwaps.Site("ROOM", 1, 0, "script", 0, 5);
		Warnings warnings = new Warnings();
		MapModel model = MapModel.build(map, TILESET, List.of(hull, deck), true, warnings);
		StoryCellTable table = StoryCellPlanner.plan(List.of(placed(model)), warnings, SLOTS);
		assertEquals(0, warnings.total(), warnings.warnings().toString());

		// The hull is built as the water it becomes (neither state is walkable): open water, closed by the hull.
		StoryCellTable.Entry water = table.get("ROOM:0:0");
		assertEquals(MapModel.Kind.WATER, model.kind(0, 0));
		assertEquals(StoryCellType.TILE, water.type());
		assertTrue(water.water(), "open, it closes unless the player surfs");
		assertEquals(new StoryCellTable.Closed(1, StoryCellTable.Pass.SOLID, HeightRules.INTERIOR_WALL, List.of(TABLE, TABLE, TABLE, TABLE)),
				water.closed());
		// The deck is built walkable; once she has sailed it is water that only surfers cross.
		StoryCellTable.Entry deckCell = table.get("ROOM:2:1");
		assertEquals(MapModel.Kind.GROUND, model.kind(2, 1));
		assertFalse(deckCell.water());
		assertEquals(new StoryCellTable.Closed(5, StoryCellTable.Pass.WATER, 0, List.of(WATER, WATER, WATER, WATER)), deckCell.closed());
	}

	@Test
	void unchangedSitesAndBadSitesAreCounted() {
		ExportMap map = room(new int[] {0, 0, 2, 0}, List.of());
		Warnings warnings = new Warnings();
		MapModel.build(map, TILESET, List.of(
				new ExportSwaps.Site("ROOM", 5, 0, "cut", 2, 3),
				new ExportSwaps.Site("ROOM", 0, 0, "script", 0, 0),
				new ExportSwaps.Site("ROOM", 0, 1, "card_key", 2, 3),
				new ExportSwaps.Site("ROOM", 0, 1, "script", 2, 0)), true, warnings);
		assertEquals(1, warnings.count("swap_out_of_bounds"));
		assertEquals(1, warnings.noteCount("swap_site_unchanged"));
		assertEquals(1, warnings.count("swap_site_duplicate"), "one site per block");
	}
}
