package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.block.EdgeWallBlock;
import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.G3Data;
import dev.kantobridge.builder.export.G3Map;
import dev.kantobridge.builder.pack.ArtKeys;
import dev.kantobridge.builder.pack.PackFiles;
import dev.kantobridge.builder.pack.Png;
import dev.kantobridge.builder.pack.ResourcePackWriter;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.scene.gen3.QuarterPages;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import dev.kantobridge.world.WorldIndex;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * MG7 (G3E, docs/specs/GEN3_BRIDGE_PLAN.md §6): the synthetic Gen 3 export (tools/fake_gen1/synth3.py: a town, a route
 * north of it, a house) planned as a build plans it: placements by the export's layout, the house under its door, cells
 * from the collision bytes, elevation walls, story cells, the quarter-page slot map, habitats and pools, the spawn, the
 * rendered blocks and the baked pack.
 */
class Gen3PlanTest {
	private static ExportBundle bundle;
	private static WorldPlan plan;

	@BeforeAll
	static void plan() throws Exception {
		bundle = ExportBundle.open(TestData.syntheticGen3Bundle());
		plan = WorldPlanner.plan(bundle, new WorldPlanner.Options(null, TestData.G3_ORIGIN_X, TestData.G3_ORIGIN_Z, TestData.MIN_BUILD_HEIGHT, false));
	}

	private static int ox(int blocks) {
		return TestData.G3_ORIGIN_X + blocks;
	}

	private static int oz(int blocks) {
		return TestData.G3_ORIGIN_Z + blocks;
	}

	@Test
	void placements() {
		assertEquals(0, plan.warnings().total(), plan.warnings().warnings().toString());
		assertEquals(3, plan.maps().size());
		assertEquals(3, plan.mapsRequested());
		assertEquals(0, plan.mapsUnplaced());
		assertEquals(new MapPlacement("EM_TEST_TOWN", ox(0), 64, oz(0), 8, 6, 0, MapPlacement.Kind.SURFACE, QuarterPages.PALETTE,
				WorldPlan.BIOME_OUTDOOR, false, null), plan.index().map("EM_TEST_TOWN"));
		// The layout's cells (1, -5): 2 blocks east, 10 north.
		assertEquals(new MapPlacement("EM_TEST_ROUTE", ox(2), 64, oz(-10), 6, 5, 0, MapPlacement.Kind.SURFACE, QuarterPages.PALETTE,
				WorldPlan.BIOME_OUTDOOR, false, null), plan.index().map("EM_TEST_ROUTE"));
		// The house's arrival (1, 3), its warp 0 (0-based), right under the town's door (3, 3), a layer down; its whole
		// padded grid (4 x 4, true width 3).
		assertEquals(new MapPlacement("EM_TEST_HOUSE", ox(6 - 2), 42, oz(6 - 6), 4, 4, 1, MapPlacement.Kind.INTERIOR, QuarterPages.PALETTE,
				WorldPlan.BIOME_INTERIOR, false, "EM_TEST_TOWN"), plan.index().map("EM_TEST_HOUSE"));
		assertEquals(0, plan.edgeMismatches());
		assertEquals(0, plan.overlaps());
		assertEquals(new Box3(ox(-12), 63, oz(-22), ox(28), 95, oz(24)), plan.surfaceBox());
		assertEquals(20 * 23 - 48 - 30, plan.warnings().notes().get("void_fill_cells"));
		assertEquals("emerald", plan.index().game());
	}

	@Test
	void cellsFromTheCollisionBytes() throws Exception {
		MapModel town = plan.map("EM_TEST_TOWN").model();
		assertTrue(town.gen3());
		assertEquals("EM_TEST_TOWN", town.id());
		assertEquals(MapModel.Kind.SOLID, town.kind(0, 0), "a tree");
		assertEquals(MapModel.Kind.GROUND, town.kind(1, 1));
		assertEquals(MapModel.Kind.LEDGE, town.kind(0, 4));
		assertEquals(0, CellFlags.ledgeDir(town.flags(0, 4)), "0xA3 jumps south: down");
		assertTrue(CellFlags.has(town.flags(3, 3), CellFlags.DOOR));
		assertTrue(CellFlags.has(town.flags(3, 3), CellFlags.WARP), "the warp's cell");
		assertEquals(MapModel.Kind.SOLID, town.kind(4, 2), "a sign is solid");
		assertTrue(CellFlags.has(town.flags(4, 2), CellFlags.SIGN));
		assertThrows(IllegalStateException.class, town::map, "no Gen 1 export map");
		// art: each cell's four quarters; the tree's top-left quarter is the over plane's
		int[] tree = bundle.g3tiles("TEST_PAIR_A").quarters(5);
		assertArrayEquals(new int[] {tree[0], tree[1], tree[2], tree[3]}, new int[] {town.tile(0, 0), town.tile(1, 0), town.tile(0, 1), town.tile(1, 1)});
		// the border (2 x 2 trees) repeats from the map's cell (0, 0)
		assertEquals(tree[0], town.borderTile(0, 0));
		assertEquals(tree[3], town.borderTile(-1, -1));
		assertEquals(town.borderTile(5, 2), town.drawnBorderTile(5, 2));

		MapModel route = plan.map("EM_TEST_ROUTE").model();
		assertEquals(MapModel.Kind.WATER, route.kind(4, 0));
		assertTrue(route.wild(0, 0), "tall grass rolls land encounters");
		assertFalse(route.wild(0, 2), "a path doesn't");
		assertFalse(route.wild(4, 0), "nor water");

		MapModel house = plan.map("EM_TEST_HOUSE").model();
		assertEquals(MapModel.Kind.COUNTER, house.kind(1, 1));
		assertEquals(HeightRules.COUNTER, house.height(1, 1));
		assertEquals(HeightRules.INTERIOR_WALL, house.height(0, 0));
		assertEquals(MapModel.Kind.SOLID, house.kind(3, 1), "the padding past the true width");
		assertTrue(CellFlags.has(house.flags(1, 3), CellFlags.WARP_TILE), "the exit mat keeps the facing");
	}

	@Test
	void theCollisionVocabulary() {
		assertEquals(CellFlags.WALKABLE, G3Cells.flags(G3Cells.WALK));
		assertEquals(CellFlags.WALKABLE, G3Cells.flags(G3Cells.CAVE));
		assertEquals(CellFlags.WALKABLE | CellFlags.GRASS, G3Cells.flags(G3Cells.TALL_GRASS));
		assertEquals(CellFlags.WATER, G3Cells.flags(G3Cells.WATER));
		assertEquals(0, G3Cells.flags(G3Cells.PC));
		assertEquals(0, G3Cells.flags(G3Cells.NOTHING));
		assertEquals(3, CellFlags.ledgeDir(G3Cells.flags(G3Cells.LEDGE_EAST)), "east: right");
		assertEquals(2, CellFlags.ledgeDir(G3Cells.flags(G3Cells.LEDGE_WEST)), "west: left");
		assertEquals(1, CellFlags.ledgeDir(G3Cells.flags(G3Cells.LEDGE_NORTH)), "north: up");
		assertEquals(MapModel.Kind.LEDGE, MapModel.kindOf(G3Cells.flags(G3Cells.LEDGE_SOUTH)));
		assertEquals(MapModel.Kind.COUNTER, MapModel.kindOf(G3Cells.flags(G3Cells.COUNTER)));
		assertEquals(-1, G3Cells.flags(0x55), "not in the vocabulary");
	}

	@Test
	void elevationWalls() {
		// The route's raised patch (elevation 4) at (0..1, 3): walls on its north and south sides; its east side meets
		// the stairs (elevation 0), which any elevation steps on.
		PairEdges edges = plan.map("EM_TEST_ROUTE").edges();
		assertEquals(4, edges.edges());
		assertEquals(EdgeWallBlock.SIDE_NORTH | EdgeWallBlock.SIDE_SOUTH, edges.sides(0, 3));
		assertEquals(EdgeWallBlock.SIDE_NORTH | EdgeWallBlock.SIDE_SOUTH, edges.sides(1, 3));
		assertEquals(EdgeWallBlock.SIDE_SOUTH, edges.sides(0, 2));
		assertEquals(0, edges.sides(2, 3));
		assertEquals(0, plan.map("EM_TEST_TOWN").edges().edges());
		assertEquals(4, plan.warnings().notes().get("elevation_edges"));
	}

	@Test
	void storyCells() {
		List<StoryCellTable.Entry> entries = plan.storyCells().entries();
		// the route's four water cells, then the town's standing person and its item ball; the walker gets none, and the
		// house's person stands on a wall (noted)
		assertEquals(6, entries.size());
		assertEquals(4, entries.stream().filter(e -> e.type() == StoryCellType.WATER).count());
		StoryCellTable.Entry person = entries.stream().filter(e -> "O1".equals(e.obj())).findFirst().orElseThrow();
		assertEquals("EM_TEST_TOWN", person.cell().map());
		assertEquals(5, person.cell().x());
		assertEquals(5, person.cell().y());
		assertTrue(entries.stream().anyMatch(e -> "O2".equals(e.obj())), "the item ball");
		assertFalse(entries.stream().anyMatch(e -> "O3".equals(e.obj())), "the walker");
		assertEquals(1, plan.warnings().noteCount("story_cell_on_solid"));
	}

	@Test
	void theSlotMapKeysQuarterPages() {
		SlotMap slots = plan.slotMap();
		long half = slots.slots().keySet().stream().filter(k -> k.shape() == SlotMap.Shape.HALF).count();
		// synth3's quarters: 32 shown on land and borders, 4 on water (half height)
		assertEquals(32, slots.slots().size() - half);
		assertEquals(4, half);
		for (SlotMap.Key key : slots.slots().keySet()) {
			assertEquals("g3q.0", key.atlas());
			assertEquals(QuarterPages.PALETTE, key.palette());
			assertTrue(key.tile() < 36);
		}
		assertTrue(plan.art().baked());
		assertEquals(32, plan.art().tilesPerRow("g3q.0"));
		assertEquals(slots.hash(), plan.index().slotMapHash());
	}

	@Test
	void habitatsAndPools() throws Exception {
		List<WorldIndex.Habitat> habitats = plan.habitats();
		assertFalse(habitats.isEmpty());
		for (WorldIndex.Habitat habitat : habitats) {
			assertEquals("EM_TEST_ROUTE", habitat.map());
			assertEquals("kantobridge:em_test_route", habitat.pool());
			assertEquals(64 - HabitatPlanner.BELOW_FEET, habitat.y());
		}
		assertEquals(Set.of("kantobridge:em_test_route"), plan.pools().keySet());
		JsonObject pool = ProtoJson.parseObject(plan.pools().get("kantobridge:em_test_route"));
		JsonArray spawns = pool.getAsJsonArray("spawns");
		assertEquals(12, spawns.size());
		JsonObject first = spawns.get(0).getAsJsonObject();
		assertEquals("cobblemon:treecko", first.get("species").getAsString());
		assertEquals(20, first.get("weight").getAsInt());
		assertEquals("2-4", first.get("levelRange").getAsString());
		assertEquals("grounded", first.get("spawnablePositionType").getAsString());
		assertEquals("cobblemon:wurmple", spawns.get(11).getAsJsonObject().get("species").getAsString());
		assertEquals(1, spawns.get(11).getAsJsonObject().get("weight").getAsInt());
	}

	@Test
	void spawnOnTheRoot() {
		WorldPlan.Spawn spawn = plan.spawn();
		assertNotNull(spawn);
		// the walkable cell nearest the town's middle (3.5, 2.5) without a door, warp, grass or story cell
		assertEquals("EM_TEST_TOWN", spawn.map());
		assertEquals(4, spawn.x());
		assertEquals(3, spawn.y());
		assertEquals(ox(8), spawn.blockX());
		assertEquals(oz(6), spawn.blockZ());
	}

	@Test
	void rendering() {
		ChunkCodesView view = new ChunkCodesView(plan);
		// ground: a full tile block under the feet, air above
		assertEquals(BlockCode.KIND_FULL, view.kind(ox(2), 63, oz(2)));
		assertEquals(BlockCode.AIR, view.code(ox(2), 64, oz(2)));
		// the ledge row (y = 4) jumps south
		assertEquals(BlockCode.ledge(0), view.code(ox(0), 64, oz(8)));
		// water on the route: a half block, its story block at the feet
		assertEquals(BlockCode.KIND_HALF, view.kind(ox(2 + 8), 63, oz(-10)));
		assertEquals(BlockCode.KIND_STORY, view.kind(ox(2 + 8), 64, oz(-10)));
		// the raised patch's north wall, at the feet of its north-west block
		assertEquals(BlockCode.edge(EdgeWallBlock.SIDE_NORTH), view.code(ox(2), 64, oz(-10 + 6)));
		assertEquals(BlockCode.edge(EdgeWallBlock.SIDE_SOUTH), view.code(ox(2), 64, oz(-10 + 5)));
		// the house's floor, a layer down
		MapPlacement house = plan.index().map("EM_TEST_HOUSE");
		assertEquals(BlockCode.KIND_FULL, view.kind(house.x() + 2, house.y() - 1, house.z() + 4));
		assertEquals(BlockCode.AIR, view.code(house.x() + 2, house.y(), house.z() + 4));
	}

	@Test
	void theBakedPack() throws Exception {
		PackFiles files = new PackFiles();
		Warnings warnings = new Warnings();
		ResourcePackWriter.Result result = ResourcePackWriter.generate(plan.art(), bundle, files, warnings);
		assertEquals(36, result.tileTextures(), "one texture per quarter, both shapes");
		assertEquals(0, result.npcTextures());
		assertEquals(0, warnings.total(), warnings.warnings().toString());
		int[] tree = bundle.g3tiles("TEST_PAIR_A").quarters(5);
		byte[] png = files.files().get(ArtKeys.textureFile("emerald", "g3q.0", QuarterPages.PALETTE, tree[0]));
		assertNotNull(png);
		Png.Image image = Png.decode(png);
		assertEquals(16, image.width());
		// the tree's over plane: its top-left pixel, 2x2 in the texture
		assertEquals(0xFF0A280A, image.get(0, 0));
		assertEquals(0xFF0A280A, image.get(1, 1));
		assertTrue(files.files().containsKey("assets/kantobridge/textures/block/emerald/tile/g3q.0/baked/0000.png"));
	}

	@Test
	void theWholeExportsCatalogueMatchesTheBuilds() throws Exception {
		ArtCatalog art = ArtCatalog.ofExport(bundle, new Warnings());
		assertTrue(art.baked());
		assertEquals(plan.slotMap().hash(), art.slotMap().hash(), "the pack's keys are the build's");
	}

	@Test
	void aFilteredBuildPlacesTheSameWay() throws Exception {
		WorldPlan town = WorldPlanner.plan(bundle,
				new WorldPlanner.Options(List.of("EM_TEST_TOWN", "EM_TEST_HOUSE"), TestData.G3_ORIGIN_X, TestData.G3_ORIGIN_Z, TestData.MIN_BUILD_HEIGHT,
						false));
		assertEquals(plan.index().map("EM_TEST_TOWN"), town.index().map("EM_TEST_TOWN"));
		assertEquals(plan.index().map("EM_TEST_HOUSE"), town.index().map("EM_TEST_HOUSE"));
		assertNull(town.index().map("EM_TEST_ROUTE"));
		assertEquals(plan.slotMap().hash(), town.slotMap().hash(), "the slot map never depends on the filter");
		assertTrue(town.habitats().isEmpty());
		assertEquals(plan.pools(), town.pools(), "pools come from the whole export");
	}

	@Test
	void theIndexIsTheGolden() throws Exception {
		TestData.assertGolden(TestData.goldenG3Index(), plan.index().toJson(), "Gen 3 WorldIndex");
	}

	@Test
	void theShapePlannerReadsTheGen3Scene() throws Exception {
		WorldPlan shaped = WorldPlanner.plan(bundle,
				new WorldPlanner.Options(null, TestData.G3_ORIGIN_X, TestData.G3_ORIGIN_Z, TestData.MIN_BUILD_HEIGHT, true));
		assertEquals(0, shaped.warnings().total(), shaped.warnings().warnings().toString());
		assertNotNull(shaped.scene());
		assertEquals("gen3", shaped.scene().family());
		dev.kantobridge.builder.shape.ShapePlan shapes = shaped.shapes();
		assertNotNull(shapes);
		assertEquals(shaped.index().hash(), shapes.map().indexHash(), "the shape map is bound to the build's index");
		JsonObject golden = new JsonObject();
		golden.add("index", ProtoJson.parse(shapes.index().toJson()));
		golden.addProperty("indexHash", shapes.index().hash());
		golden.addProperty("mapHash", shapes.map().hash());
		golden.addProperty("shapedBlocks", shapes.shapedBlocks());
		TestData.assertGolden(TestData.goldenG3Shapes(), ProtoJson.write(golden), "Gen 3 shapes");
		// every texture the shape table's geometry names is a quarter page's
		dev.kantobridge.builder.shape.geom.ShapeTable table = dev.kantobridge.builder.shape.geom.ShapeTable.build(shapes.index(),
				dev.kantobridge.scene.FrontEnds.artSource(bundle));
		for (String key : table.textureKeys()) {
			dev.kantobridge.builder.shape.geom.TextureKey texture = dev.kantobridge.builder.shape.geom.TextureKey.parse(key);
			assertNotNull(texture, key);
			assertTrue(QuarterPages.pageNumber(texture.atlas()) >= 0, key);
		}
		// the same placements and slot map as without shapes
		assertEquals(plan.index().maps(), shaped.index().maps());
		assertEquals(plan.slotMap().hash(), shaped.slotMap().hash());
	}

	@Test
	void doorsFollowWarpsThenDivesAndConnections() {
		G3Map.Border border = new G3Map.Border(1, 1, new int[] {0});
		G3Map sea = map("A_SEA", List.of(new G3Map.Connection("dive", "A_DEEP", 0)), List.of(new G3Map.Warp(1, 1, "A_HUT", 2, false),
				new G3Map.Warp(2, 1, null, 0, true)), border);
		G3Map deep = map("A_DEEP", List.of(new G3Map.Connection("emerge", "A_CITY", 0)), List.of(), border);
		G3Map city = map("A_CITY", List.of(), List.of(), border);
		G3Map hut = map("A_HUT", List.of(), List.of(new G3Map.Warp(0, 1, "A_SEA", 0, false)), border);
		Map<String, G3Map> all = Map.of("A_SEA", sea, "A_DEEP", deep, "A_CITY", city, "A_HUT", hut);
		Map<String, Gen3Planner.Door> doors = Gen3Planner.doors(all, Set.of("A_SEA"));
		assertEquals(new Gen3Planner.Door("A_SEA", 1, 1, 0, 1, false, "warp"), doors.get("A_HUT"), "warp 2 doesn't exist: its first warp");
		assertEquals(new Gen3Planner.Door("A_SEA", 0, 0, 0, 0, true, "dive"), doors.get("A_DEEP"));
		assertEquals(new Gen3Planner.Door("A_DEEP", 0, 0, 0, 0, true, "emerge"), doors.get("A_CITY"));
		assertFalse(doors.containsKey("A_SEA"));
	}

	@Test
	void aMapOnlyLeftByAWarpIsPlacedUnderItsLanding() {
		// MG8: the moving truck a new game starts in: no warp leads in, its door leads out onto the town's warp 1
		G3Map.Border border = new G3Map.Border(1, 1, new int[] {0});
		G3Map town = map("A_TOWN", List.of(), List.of(new G3Map.Warp(1, 1, "A_HOUSE", 0, false), new G3Map.Warp(3, 2, "A_TRUCK", 0, false)),
				border);
		G3Map house = map("A_HOUSE", List.of(), List.of(new G3Map.Warp(0, 2, "A_TOWN", 0, false)), border);
		G3Map truck = map("A_TRUCK", List.of(), List.of(new G3Map.Warp(3, 1, "A_TOWN", 1, false)), border);
		G3Map nowhere = map("A_NOWHERE", List.of(), List.of(new G3Map.Warp(0, 0, "A_GONE", 0, false), new G3Map.Warp(1, 0, null, 0, true)),
				border);
		G3Map cabin = map("A_CABIN", List.of(), List.of(new G3Map.Warp(2, 2, "A_TRUCK", 0, false)), border);
		Map<String, G3Map> all = Map.of("A_TOWN", town, "A_HOUSE", house, "A_TRUCK", truck, "A_NOWHERE", nowhere, "A_CABIN", cabin);
		// the town's warp into the truck makes it an ordinary door; without it, the truck is placed by its way out
		Map<String, Gen3Planner.Door> doors = Gen3Planner.doors(all, Set.of("A_TOWN"));
		assertEquals("warp", doors.get("A_TRUCK").via());
		G3Map townWithoutIt = map("A_TOWN", List.of(), List.of(new G3Map.Warp(1, 1, "A_HOUSE", 0, false), new G3Map.Warp(3, 2, "A_HOUSE", 0,
				false)), border);
		all = Map.of("A_TOWN", townWithoutIt, "A_HOUSE", house, "A_TRUCK", truck, "A_NOWHERE", nowhere, "A_CABIN", cabin);
		doors = Gen3Planner.doors(all, Set.of("A_TOWN"));
		assertEquals(new Gen3Planner.Door("A_TOWN", 3, 2, 3, 1, true, "exit"), doors.get("A_TRUCK"), "under the cell its warp lands on");
		assertEquals(new Gen3Planner.Door("A_TRUCK", 3, 1, 2, 2, true, "exit"), doors.get("A_CABIN"), "a later round");
		assertFalse(doors.containsKey("A_NOWHERE"), "its warps lead nowhere placed");
	}

	@Test
	void theRoomANewGameStartsInIsPlacedUnderTheRoot() {
		// MG8: the moving truck's door is a dynamic warp, so nothing reaches it; the export names it as the start
		G3Map.Border border = new G3Map.Border(1, 1, new int[] {0});
		G3Map town = map("A_TOWN", List.of(), List.of(new G3Map.Warp(1, 1, "A_HOUSE", 0, false)), border);
		G3Map house = map("A_HOUSE", List.of(), List.of(new G3Map.Warp(0, 2, "A_TOWN", 0, false)), border);
		G3Map truck = map("A_TRUCK", List.of(), List.of(new G3Map.Warp(3, 1, null, 0, true)), border);
		Map<String, G3Map> all = Map.of("A_TOWN", town, "A_HOUSE", house, "A_TRUCK", truck);
		Map<String, Gen3Planner.Door> doors = Gen3Planner.doors(all, Set.of("A_TOWN"));
		assertFalse(doors.containsKey("A_TRUCK"), "a dynamic warp leads nowhere known");
		assertEquals(new Gen3Planner.Door("A_TOWN", 2, 1, 2, 2, true, "start"),
				Gen3Planner.startDoor(all, Set.of("A_TOWN"), doors, "A_TOWN", new G3Data.Start("A_TRUCK", 2, 2)), "under the root's middle");
		assertNull(Gen3Planner.startDoor(all, Set.of("A_TOWN"), doors, "A_TOWN", new G3Data.Start("A_HOUSE", 0, 0)), "reached already");
		assertNull(Gen3Planner.startDoor(all, Set.of("A_TOWN"), doors, "A_TOWN", new G3Data.Start("A_GONE", 0, 0)), "not exported");
		assertNull(Gen3Planner.startDoor(all, Set.of("A_TOWN"), doors, "A_TOWN", null));
	}

	@Test
	void mapsOnlyAScriptReachesArePlacedUnderTheRoot() {
		// the user's item 6: the ferry's islands, contest halls and the like; the start room first; the unused never
		G3Map.Border border = new G3Map.Border(1, 1, new int[] {0});
		G3Map town = map("A_TOWN", List.of(), List.of(new G3Map.Warp(1, 1, "A_HOUSE", 0, false)), border);
		G3Map house = map("A_HOUSE", List.of(), List.of(new G3Map.Warp(0, 2, "A_TOWN", 0, false)), border);
		G3Map truck = map("A_TRUCK", List.of(), List.of(new G3Map.Warp(3, 1, null, 0, true)), border);
		G3Map harbor = map("A_ISLAND_HARBOR", List.of(), List.of(new G3Map.Warp(2, 1, "A_ISLAND_TOP", 0, false)), border);
		G3Map top = map("A_ISLAND_TOP", List.of(), List.of(new G3Map.Warp(1, 2, "A_ISLAND_HARBOR", 0, false)), border);
		G3Map hall = map("A_CONTEST_HALL", List.of(), List.of(), border);
		G3Map unused = map("A_UNUSED_HALL", List.of(), List.of(), border);
		Map<String, G3Map> all = Map.of("A_TOWN", town, "A_HOUSE", house, "A_TRUCK", truck, "A_ISLAND_HARBOR", harbor, "A_ISLAND_TOP", top,
				"A_CONTEST_HALL", hall, "A_UNUSED_HALL", unused);
		Map<String, Gen3Planner.Door> doors = Gen3Planner.doors(all, Set.of("A_TOWN"), "A_TOWN", new G3Data.Start("A_TRUCK", 2, 2));
		assertEquals(new Gen3Planner.Door("A_TOWN", 2, 1, 2, 2, true, "start"), doors.get("A_TRUCK"));
		assertEquals(new Gen3Planner.Door("A_TOWN", 2, 1, 0, 0, true, "script"), doors.get("A_CONTEST_HALL"), "no warps: its corner");
		assertEquals(new Gen3Planner.Door("A_TOWN", 2, 1, 2, 1, true, "script"), doors.get("A_ISLAND_HARBOR"), "its first warp's cell");
		assertEquals("warp", doors.get("A_ISLAND_TOP").via(), "the rest of the group through its warps");
		assertFalse(doors.containsKey("A_UNUSED_HALL"));
		assertFalse(Gen3Planner.doors(all, Set.of("A_TOWN")).containsKey("A_CONTEST_HALL"), "without a root: warps and exits only");
	}

	private static G3Map map(String id, List<G3Map.Connection> connections, List<G3Map.Warp> warps, G3Map.Border border) {
		int n = 4 * 3;
		return new G3Map(id, 4, 3, 4, 3, "P", new int[n], new int[n], new int[n], border, connections, warps, List.of(), 3, "route", 0, 0);
	}
}
