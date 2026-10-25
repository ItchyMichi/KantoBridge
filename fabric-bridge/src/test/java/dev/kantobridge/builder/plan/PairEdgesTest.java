package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.block.EdgeWallBlock;
import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportField;
import dev.kantobridge.builder.export.ExportMap;
import dev.kantobridge.builder.export.ExportTileset;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.MapPlacement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tile-pair edges (docs/specs/CAVES_3D_PLAN.md §3.2, H2): the {@code tilePairs} table, the edges a map's pairs close,
 * and the invisible walls the plan puts along them.
 */
class PairEdgesTest {
	private static final int FLOOR = 0;
	private static final int RAISED = 1;
	private static final int ROCK = 2;
	private static final int WATER = 3;
	/** Blocks: 0 floor, 1 raised floor, 2 rock, 3 water, each one tile throughout. */
	private static final ExportTileset CAVERN = new ExportTileset("CAVERN", "tilesets.cavern", 16, 96,
			new int[][] {uniform(FLOOR), uniform(RAISED), uniform(ROCK), uniform(WATER)}, "TILEANIM_NONE", Set.of(FLOOR, RAISED), Set.of(), null,
			Set.of(), Set.of(), Set.of(WATER), Set.of(), Set.of());
	/** Walking between floor and raised floor is refused, and surfing between water and raised floor. */
	private static final ExportField.TilePairs PAIRS = new ExportField.TilePairs(Map.of("CAVERN", Set.of(ExportField.TilePairs.key(RAISED, FLOOR))),
			Map.of("CAVERN", Set.of(ExportField.TilePairs.key(WATER, RAISED))));

	@TempDir
	Path dir;

	private static int[] uniform(int tile) {
		int[] block = new int[16];
		java.util.Arrays.fill(block, tile);
		return block;
	}

	/** A 2 × 2-block (4 × 4-cell) cave: floor north-west, raised floor north-east, rock south-west, water south-east. */
	private static MapModel cave() {
		int[] blocks = {0, 1, 2, 3};
		int[][] cells = new int[4][4];
		for (int cy = 0; cy < 4; cy++) {
			for (int cx = 0; cx < 4; cx++) {
				int block = blocks[(cy / 2) * 2 + cx / 2];
				cells[cy][cx] = block <= 1 ? CellFlags.WALKABLE : block == 3 ? CellFlags.WATER : 0;
			}
		}
		ExportMap map = new ExportMap("CAVE", 60, "CAVERN", 2, 2, blocks, 2, false, false, Map.of(), List.of(), List.of(), List.of(), cells,
				null, null, false, "PAL");
		return MapModel.build(map, CAVERN, List.of(), true, new Warnings());
	}

	@Test
	void theTableIsReadInEitherShapeAndOrder() {
		JsonObject table = new JsonObject();
		JsonArray land = new JsonArray();
		JsonObject pair = new JsonObject();
		pair.addProperty("tileset", "CAVERN");
		pair.addProperty("a", 32);
		pair.addProperty("b", 5);
		land.add(pair);
		JsonArray asArray = new JsonArray();
		asArray.add("FOREST");
		asArray.add(48);
		asArray.add(46);
		land.add(asArray);
		land.add("not a pair");
		JsonObject bad = new JsonObject();
		bad.addProperty("tileset", "CAVERN");
		bad.addProperty("a", 300);
		bad.addProperty("b", 5);
		land.add(bad);
		table.add("land", land);
		table.add("water", new JsonArray());
		ExportField.TilePairs pairs = ExportField.TilePairs.parse(table);
		assertTrue(pairs.land("CAVERN", 32, 5));
		assertTrue(pairs.land("CAVERN", 5, 32), "either order");
		assertTrue(pairs.land("FOREST", 46, 48));
		assertFalse(pairs.water("CAVERN", 32, 5), "walking pairs aren't surfing pairs");
		assertFalse(pairs.land("CAVERN", 32, 6));
		assertEquals(2, pairs.size(), "malformed entries are left out");
		assertSame(ExportField.TilePairs.NONE, ExportField.TilePairs.parse(null));
	}

	@Test
	void pairsCloseTheEdgesBetweenTheirTiles() {
		PairEdges edges = PairEdges.of(cave(), PAIRS);
		assertEquals(4, edges.edges(), "two between floor and raised floor, two between raised floor and water");
		assertEquals(EdgeWallBlock.SIDE_EAST, edges.sides(1, 0));
		assertEquals(EdgeWallBlock.SIDE_WEST, edges.sides(2, 0));
		assertEquals(EdgeWallBlock.SIDE_EAST, edges.sides(1, 1));
		assertEquals(EdgeWallBlock.SIDE_WEST | EdgeWallBlock.SIDE_SOUTH, edges.sides(2, 1));
		assertEquals(EdgeWallBlock.SIDE_SOUTH, edges.sides(3, 1));
		assertEquals(EdgeWallBlock.SIDE_NORTH, edges.sides(2, 2));
		assertEquals(0, edges.sides(0, 0), "floor next to floor");
		assertEquals(0, edges.sides(1, 2), "rock walls nothing");
		assertEquals(0, edges.sides(9, 9), "outside the map");
		// The blocks along a walled side carry it; the others don't.
		assertEquals(EdgeWallBlock.SIDE_EAST, edges.blockSides(1, 0, 1, 0));
		assertEquals(EdgeWallBlock.SIDE_EAST, edges.blockSides(1, 0, 1, 1));
		assertEquals(0, edges.blockSides(1, 0, 0, 1));
		assertEquals(EdgeWallBlock.SIDE_WEST | EdgeWallBlock.SIDE_SOUTH, edges.blockSides(2, 1, 0, 1), "a corner block, both sides");
	}

	@Test
	void aTilesetWithoutPairsHasNoEdges() {
		assertSame(PairEdges.NONE, PairEdges.of(cave(), ExportField.TilePairs.NONE));
		ExportField.TilePairs forestOnly = new ExportField.TilePairs(Map.of("FOREST", Set.of(ExportField.TilePairs.key(RAISED, FLOOR))), Map.of());
		assertSame(PairEdges.NONE, PairEdges.of(cave(), forestOnly));
	}

	@Test
	void aStoryBlockCarriesWallSidesInItsCode() {
		// Where neither feet nor the block above is free, the story block carries the wall (CAVES_3D_PLAN §3.2).
		int code = BlockCode.story(123_456, EdgeWallBlock.SIDE_EAST | EdgeWallBlock.SIDE_SOUTH);
		assertEquals(BlockCode.KIND_STORY, BlockCode.kind(code));
		assertEquals(123_456, BlockCode.storyIndex(code));
		assertEquals(EdgeWallBlock.SIDE_EAST | EdgeWallBlock.SIDE_SOUTH, BlockCode.storySides(code));
		int tile = BlockCode.storyTile(BlockCode.STORY_INDEX_MASK, EdgeWallBlock.SIDE_WEST);
		assertEquals(BlockCode.KIND_STORY_TILE, BlockCode.kind(tile));
		assertEquals(BlockCode.STORY_INDEX_MASK, BlockCode.storyIndex(tile));
		assertEquals(EdgeWallBlock.SIDE_WEST, BlockCode.storySides(tile));
		assertEquals(BlockCode.story(7), BlockCode.story(7, 0), "no sides: the code as before");
		assertTrue(BlockCode.isStory(code) && BlockCode.isStory(tile));
	}

	@Test
	void thePlanWallsEverySideOfAPairEdge() throws Exception {
		// The synthetic bundle with its house in a tileset of its own whose floor and exit mat tiles form a pair (the
		// profile pins nothing in it).
		Path red = TestData.syntheticBundle();
		JsonObject tileset = ProtoJson.parseObject(Files.readString(red.resolve("tileset/TEST.json")));
		tileset.addProperty("id", "PAIRED");
		JsonObject house = ProtoJson.parseObject(Files.readString(red.resolve("map/TEST_HOUSE.json")));
		house.addProperty("tileset", "PAIRED");
		JsonObject field = ProtoJson.parseObject(Files.readString(red.resolve("field/all.json")));
		JsonObject pairs = new JsonObject();
		JsonArray land = new JsonArray();
		JsonObject pair = new JsonObject();
		pair.addProperty("tileset", "PAIRED");
		pair.addProperty("a", 9); // the synthetic tileset's FLOOR
		pair.addProperty("b", 12); // and its MAT
		land.add(pair);
		pairs.add("land", land);
		pairs.add("water", new JsonArray());
		field.getAsJsonObject("tables").add("tilePairs", pairs);
		Path copy = TestData.syntheticCopyWith(this.dir, Map.of("tileset/PAIRED", tileset, "map/TEST_HOUSE", house, "field/all", field));
		WorldPlan plan = WorldPlanner.plan(ExportBundle.open(copy),
				new WorldPlanner.Options(null, TestData.ORIGIN_X, TestData.ORIGIN_Z, TestData.MIN_BUILD_HEIGHT));
		PlacedMap placed = plan.maps().stream().filter(m -> m.id().equals("TEST_HOUSE")).findFirst().orElseThrow();
		assertTrue(placed.edges().edges() > 0, "the house's floor meets its exit mat");
		assertEquals(placed.edges().edges(), plan.warnings().noteCount("tile_pair_edges"));
		ChunkCodesView view = new ChunkCodesView(plan);
		MapPlacement p = placed.placement();
		int walled = 0;
		for (int cy = 0; cy < p.h(); cy++) {
			for (int cx = 0; cx < p.w(); cx++) {
				for (int b = 0; b < 4; b++) {
					int bx = b % 2;
					int by = b / 2;
					int sides = placed.edges().blockSides(cx, cy, bx, by);
					int x = p.x() + cx * 2 + bx;
					int z = p.z() + cy * 2 + by;
					int atFeet = view.code(x, p.y(), z);
					int above = view.code(x, p.y() + 1, z);
					int wall = BlockCode.kind(atFeet) == BlockCode.KIND_EDGE ? atFeet : BlockCode.kind(above) == BlockCode.KIND_EDGE ? above : -1;
					if (sides == 0) {
						assertEquals(-1, wall, "no wall where no pair edge runs, " + cx + "," + cy);
						continue;
					}
					assertEquals(BlockCode.edge(sides), wall, "the wall along cell " + cx + "," + cy + "'s pair edges");
					walled++;
				}
			}
		}
		assertTrue(walled > 0);
		// The town's tileset has no pairs: no wall anywhere on it.
		PlacedMap town = plan.maps().stream().filter(m -> m.id().equals("TEST_TOWN")).findFirst().orElseThrow();
		assertSame(PairEdges.NONE, town.edges());
	}

	@Test
	void gen3OneWaySidesFollowTheEnginesBehaviours() {
		// MB_IMPASSABLE_EAST 0x30, WEST 0x31, NORTH 0x32, SOUTH 0x33, NE 0x34, NW 0x35, SE 0x36, SW 0x37 (game3/collision.lua)
		assertTrue(PairEdges.eastBlocked(0x30) && PairEdges.eastBlocked(0x34) && PairEdges.eastBlocked(0x36));
		assertTrue(PairEdges.westBlocked(0x31) && PairEdges.westBlocked(0x35) && PairEdges.westBlocked(0x37));
		assertTrue(PairEdges.northBlocked(0x32) && PairEdges.northBlocked(0x34) && PairEdges.northBlocked(0x35));
		assertTrue(PairEdges.southBlocked(0x33) && PairEdges.southBlocked(0x36) && PairEdges.southBlocked(0x37));
		assertFalse(PairEdges.eastBlocked(0x31) || PairEdges.westBlocked(0x30) || PairEdges.northBlocked(0x33) || PairEdges.southBlocked(0x32));
		assertFalse(PairEdges.eastBlocked(0x00) || PairEdges.northBlocked(0x38), "plain ground and others close nothing");
	}
}
