package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.export.ExportMap;
import dev.kantobridge.builder.export.ExportSwaps;
import dev.kantobridge.builder.export.ExportTileset;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.story.StoryRules;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import dev.kantobridge.world.WorldIndex;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * M8 wave 2's builder cases on made-up maps: a scripted seal whose map data holds neither of its blocks (the engine
 * writes Agatha's exit seal on every entry, so the full build must take it without a warning), and a ship that sails
 * from her dock (VERMILION_DOCK: once she has gone, the water where she lay lets only surfers through), followed from
 * the plan through the story rules a client runs.
 */
class SealAndShipTest {
	private static final String HASH = "0".repeat(64);
	/** Slots stand for tiles one to one. */
	private static final StoryCellPlanner.ArtSlots SLOTS = (atlas, palette, tile) -> tile;

	// ---- the seal ----

	/** Tiles: 0 floor, 1 wall, 2 carpet (walkable). */
	private static final int FLOOR = 0;
	private static final int WALL = 1;
	private static final int CARPET = 2;
	/** Blocks: 0 floor, 1 wall, 2 the open seal (carpet over a wall row), 3 the data's own block there (floor and table). */
	private static final ExportTileset ROOM = new ExportTileset("ROOM", "tilesets.room", 16, 96,
			new int[][] {uniform(FLOOR), uniform(WALL), rows(CARPET, WALL), rows(FLOOR, FLOOR)}, "TILEANIM_NONE", Set.of(FLOOR, CARPET), Set.of(),
			null, Set.of(), Set.of(), Set.of(), Set.of(), Set.of());

	// ---- the ship ----

	/** Tiles: 0 dock (walkable), 1 hull, 2 deck (walkable), 3 water. */
	private static final int DOCK = 0;
	private static final int HULL = 1;
	private static final int DECK = 2;
	private static final int WATER = 3;
	/** Blocks: 0 dock, 1 the ship (deck over hull), 2 open water, 3 the shoreline she lay at (dock over water). */
	private static final int B_SHIP = 1;
	private static final int B_WATER = 2;
	private static final int B_SHORE = 3;
	private static final ExportTileset PORT = new ExportTileset("PORT", "tilesets.port", 16, 96,
			new int[][] {uniform(DOCK), rows(DECK, HULL), uniform(WATER), rows(DOCK, WATER)}, "TILEANIM_WATER", Set.of(DOCK, DECK), Set.of(), null,
			Set.of(), Set.of(), Set.of(WATER), Set.of(), Set.of());

	private static int[] uniform(int tile) {
		int[] block = new int[16];
		Arrays.fill(block, tile);
		return block;
	}

	/** A block whose upper two tile rows show {@code top} and lower two {@code bottom}. */
	private static int[] rows(int top, int bottom) {
		int[] block = new int[16];
		Arrays.fill(block, 0, 8, top);
		Arrays.fill(block, 8, 16, bottom);
		return block;
	}

	/** Per-cell flags as the export derives them from the data's blocks. */
	private static int[][] flags(ExportTileset tileset, int[] blocks, int width) {
		int height = blocks.length / width;
		int[][] cells = new int[height * 2][width * 2];
		for (int cy = 0; cy < height * 2; cy++) {
			for (int cx = 0; cx < width * 2; cx++) {
				int tile = tileset.tile(blocks[(cy / 2) * width + cx / 2], cx * 2, cy * 2 + 1);
				cells[cy][cx] = (tileset.walkable().contains(tile) ? CellFlags.WALKABLE : 0) | (tileset.water().contains(tile) ? CellFlags.WATER : 0);
			}
		}
		return cells;
	}

	private static ExportMap map(String id, ExportTileset tileset, int[] blocks, int width) {
		return new ExportMap(id, 40, tileset.id(), width, blocks.length / width, blocks, 0, false, false, Map.of(), List.of(), List.of(), List.of(),
				flags(tileset, blocks, width), null, null, false, "PAL");
	}

	private static PlacedMap placed(MapModel model) {
		return new PlacedMap(new MapPlacement(model.id(), 0, 42, 0, model.cellWidth(), model.cellHeight(), 1, MapPlacement.Kind.INTERIOR, "PAL",
				WorldPlan.BIOME_INTERIOR, false, "TOWN"), model);
	}

	private static JsonObject json(String text) throws Exception {
		return ProtoJson.parseObject(text);
	}

	private static JsonObject state(boolean surf) throws Exception {
		return json("{\"mode\":\"idle\",\"bike\":false,\"surf\":" + surf + ",\"slope\":false,\"hidden\":false,\"dark\":false,\"lit\":false}");
	}

	@Test
	void aSealItsScriptOverwritesIsBuiltOpenWithStoryCellsAndNoWarning() throws Exception {
		// Agatha's room: the seal's block (1, 0) holds the data's own block 3, neither the wall (shut) nor the open seal.
		ExportMap room = map("SEAL_ROOM", ROOM, new int[] {1, 3, 1, 0, 0, 0}, 3);
		ExportSwaps.Site seal = new ExportSwaps.Site("SEAL_ROOM", 1, 0, "script", 1, 2, "EVENT_BEAT_SEAL_TRAINER_0");
		Warnings warnings = new Warnings();
		MapModel model = MapModel.build(room, ROOM, List.of(seal), true, warnings, site -> 9);
		StoryCellTable table = StoryCellPlanner.plan(List.of(placed(model)), warnings, SLOTS);

		assertEquals(0, warnings.total(), "no warning: " + warnings.warnings());
		assertEquals(1, warnings.noteCount("swap_site_overwritten"));
		assertEquals(List.of(Map.of("kind", "script", "map", "SEAL_ROOM", "site", "9", "source", "EVENT_BEAT_SEAL_TRAINER_0")),
				warnings.details("swap_site_overwritten"), "symbols only");
		assertEquals(Set.of(1), model.sites().keySet(), "the site is applied");

		// Its carpet row: built open, a tile cell each that the wall closes; other maps show the data's block (open).
		for (String key : new String[] {"SEAL_ROOM:2:0", "SEAL_ROOM:3:0"}) {
			StoryCellTable.Entry entry = table.get(key);
			assertNotNull(entry, key);
			assertEquals(StoryCellType.TILE, entry.type());
			assertEquals(3, entry.start(), "the data's own block");
			assertEquals(new StoryCellTable.Closed(1, StoryCellTable.Pass.SOLID, HeightRules.INTERIOR_WALL, List.of(WALL, WALL, WALL, WALL)),
					entry.closed());
			assertFalse(entry.water());
		}
		assertEquals(MapModel.Kind.GROUND, model.kind(2, 0));
		assertEquals(CARPET, model.tile(4, 0), "built in its open state");
		// Its wall row never changes: built as the site shows it (a wall), whatever the data's block said (floor).
		assertEquals(MapModel.Kind.SOLID, model.kind(2, 1));
		assertEquals(0, model.flags(2, 1) & CellFlags.WALKABLE);
		assertNull(table.get("SEAL_ROOM:2:1"));

		// The story rules: open until the hidden game reports its live block; shut by the wall, open by the carpet.
		WorldIndex index = new WorldIndex("red", HASH, List.of(placed(model).placement()), HASH, table, List.of());
		StoryRules rules = new StoryRules();
		rules.setIndex(index);
		assertFalse(rules.cells().active().contains("SEAL_ROOM:2:0"), "away from the room it shows its data: open");
		rules.onPlayerMap("SEAL_ROOM");
		rules.onBlocks(json("{\"map\":\"SEAL_ROOM\",\"changes\":[{\"bx\":1,\"by\":0,\"block\":1}]}"));
		assertTrue(rules.cells().active().containsAll(Set.of("SEAL_ROOM:2:0", "SEAL_ROOM:3:0")), "sealed until she is beaten");
		rules.onBlocks(json("{\"map\":\"SEAL_ROOM\",\"changes\":[{\"bx\":1,\"by\":0,\"block\":2}]}"));
		assertTrue(rules.cells().active().isEmpty(), "open once she is");
	}

	@Test
	void otherKindsMustStillMatchTheData() {
		ExportMap room = map("SEAL_ROOM", ROOM, new int[] {1, 3, 1, 0, 0, 0}, 3);
		for (String kind : new String[] {"cut", "card_key", "boulder"}) {
			Warnings warnings = new Warnings();
			MapModel model = MapModel.build(room, ROOM, List.of(new ExportSwaps.Site("SEAL_ROOM", 1, 0, kind, 1, 2)), true, warnings);
			assertEquals(1, warnings.count("swap_block_mismatch"), kind);
			assertEquals(0, warnings.noteCount("swap_site_overwritten"), kind);
			assertTrue(model.sites().isEmpty(), kind);
		}
		assertTrue(MapModel.overwritable(new ExportSwaps.Site("SEAL_ROOM", 1, 0, "script", 1, 2)));
	}

	@Test
	void theWaterAShipLeavesLetsOnlySurfersThrough() throws Exception {
		// Two ship blocks on a dock row: one becomes open water, the other the shoreline she lay at.
		ExportMap dock = map("SHIP_DOCK", PORT, new int[] {B_SHIP, B_SHIP, 0, 0}, 2);
		ExportSwaps.Site toWater = new ExportSwaps.Site("SHIP_DOCK", 0, 0, "script", B_SHIP, B_WATER, "EVENT_SHIP_LEFT");
		ExportSwaps.Site toShore = new ExportSwaps.Site("SHIP_DOCK", 1, 0, "script", B_SHIP, B_SHORE, "EVENT_SHIP_LEFT");
		Warnings warnings = new Warnings();
		MapModel model = MapModel.build(dock, PORT, List.of(toWater, toShore), true, warnings);
		StoryCellTable table = StoryCellPlanner.plan(List.of(placed(model)), warnings, SLOTS);
		assertEquals(0, warnings.total(), warnings.warnings().toString());

		// Built: her decks as decks (walkable), her hull as the water it becomes.
		String deckToWater = "SHIP_DOCK:0:0";
		String hullToWater = "SHIP_DOCK:0:1";
		String deckToShore = "SHIP_DOCK:2:0";
		String hullBesideShore = "SHIP_DOCK:2:1";
		assertEquals(MapModel.Kind.GROUND, model.kind(0, 0));
		assertEquals(MapModel.Kind.WATER, model.kind(0, 1));
		assertEquals(StoryCellTable.Pass.WATER, table.get(deckToWater).closed().pass(), "her deck closes into water");
		assertTrue(table.get(hullToWater).water() && table.get(hullBesideShore).water(), "her hull is built as water");
		assertEquals(StoryCellTable.Pass.SOLID, table.get(hullToWater).closed().pass(), "moored, her hull bars the way");
		assertEquals(StoryCellTable.Pass.GROUND, table.get(deckToShore).closed().pass(), "deck to shore: only the art differs");

		WorldIndex index = new WorldIndex("red", HASH, List.of(placed(model).placement()), HASH, table, List.of());
		StoryRules walker = new StoryRules();
		StoryRules surfer = new StoryRules();
		for (StoryRules rules : List.of(walker, surfer)) {
			rules.setIndex(index);
			rules.onPlayerMap("SHIP_DOCK");
		}
		walker.onPlayerState(state(false));
		surfer.onPlayerState(state(true));

		// Moored: her hull bars everyone, her decks are open.
		for (StoryRules rules : List.of(walker, surfer)) {
			Set<String> active = rules.cells().active();
			assertTrue(active.containsAll(Set.of(hullToWater, hullBesideShore)), "the hull bars everyone");
			assertFalse(active.contains(deckToWater) || active.contains(deckToShore), "the deck is walkable");
		}

		// She sails (the engine's live blocks): where she lay is water for walkers, open to surfers; the shoreline is land.
		String sailed = "{\"map\":\"SHIP_DOCK\",\"changes\":[{\"bx\":0,\"by\":0,\"block\":2},{\"bx\":1,\"by\":0,\"block\":3}]}";
		walker.onBlocks(json(sailed));
		surfer.onBlocks(json(sailed));
		assertTrue(walker.cells().active().containsAll(Set.of(deckToWater, hullToWater, hullBesideShore)), "water bars walkers");
		assertFalse(walker.cells().active().contains(deckToShore), "the shoreline is land");
		assertTrue(surfer.cells().active().isEmpty(), "a surfer crosses it all: " + surfer.cells().active());
		assertTrue(walker.cells().drawn().contains(deckToWater) && !walker.cells().drawn().contains(hullToWater),
				"the deck draws the water over it; the hull's cell is built as water");
	}
}
