package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

/** Story cells on a made-up 2×2-block room: objects, a card-key door, a boulder area, and an item on a table. */
class StoryCellPlannerTest {
	private static int[] uniform(int tile) {
		int[] block = new int[16];
		java.util.Arrays.fill(block, tile);
		return block;
	}

	@Test
	void choosesCellsByKindWithObjectsFirst() {
		// Tiles: 0 floor (walkable), 1 table, 2 locked door. Blocks: 0 floor, 1 table, 2 door, 3 open door (floor).
		ExportTileset tileset = new ExportTileset("ROOM", "tilesets.room", 16, 96,
				new int[][] {uniform(0), uniform(1), uniform(2), uniform(0)}, "TILEANIM_NONE", Set.of(0), Set.of(), null, Set.of(),
				Set.of(), Set.of(), Set.of(), Set.of());
		int[][] cells = new int[4][4];
		for (int cy = 0; cy < 4; cy++) {
			for (int cx = 0; cx < 4; cx++) {
				boolean floor = (cx < 2 && cy < 2) || (cx >= 2 && cy >= 2);
				cells[cy][cx] = floor ? CellFlags.WALKABLE : 0;
			}
		}
		List<ExportMap.MapObject> objects = List.of(
				new ExportMap.MapObject(1, "ROOM_BOULDER", "SPRITE_BOULDER", 0, 0, "STAY", "BOULDER_MOVEMENT_BYTE_2", false, null, false, false),
				new ExportMap.MapObject(2, "ROOM_ITEM", "SPRITE_POKE_BALL", 2, 0, "STAY", "NONE", false, "POTION", false, false),
				new ExportMap.MapObject(3, "ROOM_GUARD", "SPRITE_GUARD", 1, 1, "STAY", "DOWN", false, null, false, false),
				new ExportMap.MapObject(4, "ROOM_WALKER", "SPRITE_GIRL", 3, 3, "WALK", "ANY_DIR", false, null, false, false));
		ExportMap map = new ExportMap("ROOM", 40, "ROOM", 2, 2, new int[] {0, 1, 2, 0}, 1, false, false, Map.of(), List.of(), List.of(),
				objects, cells, null, null, false, "PAL");
		ExportSwaps.Site door = new ExportSwaps.Site("ROOM", 0, 1, "card_key", 2, 3);
		Warnings warnings = new Warnings();
		MapModel model = MapModel.build(map, tileset, List.of(door), true, warnings);
		PlacedMap placed = new PlacedMap(new MapPlacement("ROOM", 0, 42, 0, 4, 4, 1, MapPlacement.Kind.INTERIOR, "PAL",
				WorldPlan.BIOME_INTERIOR, false, "TOWN"), model);

		StoryCellTable table = StoryCellPlanner.plan(List.of(placed), warnings);
		assertEquals(0, warnings.total(), warnings.warnings().toString());
		assertEquals(1, warnings.noteCount("story_cell_on_solid"), "the item lies on the table: terrain already blocks it");

		assertEquals(StoryCellType.OBJECT, table.get("ROOM:1:1").type());
		assertEquals("ROOM_GUARD", table.get("ROOM:1:1").obj());
		assertNull(table.get("ROOM:2:0"));
		// The door block is built open, and closed per player by tile story cells.
		for (String key : new String[] {"ROOM:0:2", "ROOM:1:2", "ROOM:0:3", "ROOM:1:3"}) {
			StoryCellTable.Entry entry = table.get(key);
			assertEquals(StoryCellType.TILE, entry.type(), key);
			assertEquals(new StoryCellTable.Swap(0, 1, "card_key", 2, 3), entry.swap());
		}
		assertEquals(MapModel.Kind.GROUND, model.kind(0, 2));
		// The boulder map's other walkable cells, the boulder's own cell and the walker's included.
		for (String key : new String[] {"ROOM:0:0", "ROOM:1:0", "ROOM:0:1", "ROOM:2:2", "ROOM:3:2", "ROOM:2:3", "ROOM:3:3"}) {
			assertEquals(StoryCellType.BOULDER_AREA, table.get(key).type(), key);
		}
		assertEquals(12, table.size());
		assertEquals(new CellPos("ROOM", 0, 0), table.entries().get(0).cell());
	}
}
