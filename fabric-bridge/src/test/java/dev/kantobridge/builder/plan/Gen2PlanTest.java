package dev.kantobridge.builder.plan;

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
import dev.kantobridge.builder.export.G2Data;
import dev.kantobridge.builder.export.G2Map;
import dev.kantobridge.builder.export.G2Tiles;
import dev.kantobridge.builder.export.G3Data;
import dev.kantobridge.builder.pack.ArtKeys;
import dev.kantobridge.builder.pack.PackFiles;
import dev.kantobridge.builder.pack.Png;
import dev.kantobridge.builder.pack.ResourcePackWriter;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.scene.gen3.QuarterPages;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.RegionRecord;
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
 * MG9 (G2E, docs/specs/GEN2_BRIDGE_PLAN.md §7): the synthetic Gen 2 export (tools/fake_gen1/synth2.py: Johto's town, a
 * route north of it, a house, a dark cave, and Crystal's Kanto's town) planned as a build plans it: both surface areas by
 * the export's layouts, interiors under their doors, cells from the meanings of the collision bytes, one-way walls, swap
 * sites built open, story cells, the baked-page slot map, habitats and pools with the times of day merged, the spawn,
 * the rendered blocks and the baked pack.
 */
class Gen2PlanTest {
	private static ExportBundle bundle;
	private static WorldPlan plan;
	private static G2Tiles outdoor;

	@BeforeAll
	static void plan() throws Exception {
		bundle = ExportBundle.open(TestData.syntheticGen2Bundle());
		outdoor = bundle.g2tiles("TILESET_TEST_1");
		plan = WorldPlanner.plan(bundle, new WorldPlanner.Options(null, TestData.G2_ORIGIN_X, TestData.G2_ORIGIN_Z, TestData.MIN_BUILD_HEIGHT, false));
	}

	private static int ox(int blocks) {
		return TestData.G2_ORIGIN_X + blocks;
	}

	private static int oz(int blocks) {
		return TestData.G2_ORIGIN_Z + blocks;
	}

	@Test
	void placements() {
		assertEquals(0, plan.warnings().total(), plan.warnings().warnings().toString());
		assertEquals(6, plan.maps().size());
		assertEquals(6, plan.mapsRequested());
		assertEquals(0, plan.mapsUnplaced());
		assertEquals(new MapPlacement("G2_TEST_TOWN", ox(0), 64, oz(0), 10, 8, 0, MapPlacement.Kind.SURFACE, QuarterPages.PALETTE,
				WorldPlan.BIOME_OUTDOOR, false, null), plan.index().map("G2_TEST_TOWN"));
		// the layout's blocks (1, -3): 2 cells (4 blocks) east, 6 cells (12 blocks) north
		assertEquals(new MapPlacement("G2_TEST_ROUTE", ox(4), 64, oz(-12), 6, 6, 0, MapPlacement.Kind.SURFACE, QuarterPages.PALETTE,
				WorldPlan.BIOME_OUTDOOR, false, null), plan.index().map("G2_TEST_ROUTE"));
		// J3: Crystal's Kanto east of Johto, past Johto's 10 cells and 32 of sea, its top at Johto's top (the route's, -6 cells)
		assertEquals(new MapPlacement("G2_TEST_KANTO_TOWN", ox(84), 64, oz(-12), 4, 4, 0, MapPlacement.Kind.SURFACE, QuarterPages.PALETTE,
				WorldPlan.BIOME_OUTDOOR, false, null), plan.index().map("G2_TEST_KANTO_TOWN"));
		// the cave (its door the route's warp (2, 5)) in the 3D area north of the dungeons (HEIGHTS_ALL_GAMES_PLAN D1): its top
		// floor at feet 64, dark, the area's cave biome, inside the area's box
		MapPlacement cave = plan.index().map("G2_TEST_CAVE");
		assertEquals(new MapPlacement("G2_TEST_CAVE", cave.x(), CavePlanner.TOP_FEET, cave.z(), 8, 4, 1, MapPlacement.Kind.INTERIOR,
				QuarterPages.PALETTE, WorldPlan.BIOME_CAVE_3D, true, "G2_TEST_ROUTE"), cave);
		RegionRecord.Area area = plan.caveArea();
		assertTrue(area != null && cave.x() >= area.x() && cave.z() >= area.z() && cave.x() + cave.blockWidth() <= area.x() + area.w()
				&& cave.z() + cave.blockDepth() <= area.z() + area.d(), "in the 3D area");
		assertTrue(cave.z() + cave.blockDepth() <= dev.kantobridge.dungeon.DungeonSlots.region(plan.surfaceBox(), TestData.G2_ORIGIN_X,
				TestData.G2_ORIGIN_Z).z() - CavePlanner.GAP, "north of the region's dungeons");
		// its B1F a floor below (W3: the ladder between them lines up, a real shaft from 1F's (7, 0) down to B1F's (3, 0))
		MapPlacement b1f = plan.index().map("G2_TEST_CAVE_B1F");
		assertEquals(CavePlanner.TOP_FEET - CavePlanner.FLOOR_STEP, b1f.y());
		assertEquals(List.of(new WorldIndex.Shaft(WorldIndex.Shaft.LADDER, new dev.kantobridge.world.CellPos("G2_TEST_CAVE", 7, 0),
				new dev.kantobridge.world.CellPos("G2_TEST_CAVE_B1F", 3, 0), true, plan.index().shafts().get(0).x(), plan.index().shafts().get(0).z(),
				b1f.y(), cave.y() - 1, plan.index().shafts().get(0).wall())), plan.index().shafts());
		// the house under the town's door (4, 3), its mat (2, 5) the arrival, on layer 1 now that the cave left the layers
		assertEquals(new MapPlacement("G2_TEST_HOUSE", ox(4), 42, oz(-4), 6, 6, 1, MapPlacement.Kind.INTERIOR, QuarterPages.PALETTE,
				WorldPlan.BIOME_INTERIOR, false, "G2_TEST_TOWN"), plan.index().map("G2_TEST_HOUSE"));
		assertEquals(0, plan.edgeMismatches());
		assertEquals(0, plan.overlaps());
		assertEquals(new Box3(ox(-12), 63, oz(-24), ox(104), 95, oz(28)), plan.surfaceBox());
		assertEquals(1376, plan.warnings().notes().get("void_fill_cells"));
		assertEquals("crystal", plan.index().game());
	}

	@Test
	void cellsFromTheMeanings() {
		MapModel town = plan.map("G2_TEST_TOWN").model();
		assertTrue(town.gen2());
		assertFalse(town.gen3());
		assertTrue(town.baked());
		assertEquals("G2_TEST_TOWN", town.id());
		assertEquals(MapModel.Kind.SOLID, town.kind(0, 0), "a tree");
		assertEquals(MapModel.Kind.GROUND, town.kind(0, 2));
		assertTrue(CellFlags.has(town.flags(4, 3), CellFlags.DOOR));
		assertTrue(CellFlags.has(town.flags(4, 3), CellFlags.WARP), "the warp's cell");
		assertEquals(MapModel.Kind.SOLID, town.kind(5, 3), "the door block's other quarters are wall");
		assertEquals(MapModel.Kind.SOLID, town.kind(6, 2), "a sign is solid");
		assertTrue(CellFlags.has(town.flags(6, 2), CellFlags.SIGN));
		assertEquals(MapModel.Kind.LEDGE, town.kind(0, 7));
		assertEquals(0, CellFlags.ledgeDir(town.flags(0, 7)), "0xA3 hops down");
		assertEquals(MapModel.Kind.GROUND, town.kind(0, 6), "the ledge block's top row");
		assertEquals(MapModel.Kind.WATER, town.kind(8, 4));
		assertThrows(IllegalStateException.class, town::map, "no Gen 1 export map");
		// art: each cell a quadrant of its block's 4 x 4 tiles; the border block repeats from the map's cell (0, 0)
		assertEquals(outdoor.tile(7, 0, 0), town.tile(0, 0));
		assertEquals(outdoor.tile(7, 3, 3), town.tile(3, 3));
		assertEquals(outdoor.tile(5, 0, 2), town.tile(8, 6), "the door block's bottom-left quadrant");
		assertEquals(outdoor.tile(7, 0, 0), town.borderTile(0, 0));
		assertEquals(outdoor.tile(7, 3, 3), town.borderTile(-1, -1));
		// the Cut tree, built open (its stump), the tree in the closed model; the whirlpool, built as plain water
		assertEquals(MapModel.Kind.GROUND, town.kind(6, 4));
		assertTrue(town.changes(6, 4));
		assertEquals(6, town.closedBlock(6, 4));
		assertEquals(6, town.dataBlock(6, 4));
		MapModel closed = town.closedModel();
		assertTrue(closed.closedState());
		assertEquals(MapModel.Kind.SOLID, closed.kind(6, 4));
		assertTrue(CellFlags.has(closed.flags(6, 4), CellFlags.CUT_TREE));
		assertEquals(outdoor.tile(6, 0, 0), closed.tile(12, 8));
		assertEquals(outdoor.tile(12, 0, 0), town.tile(12, 8));
		assertEquals(MapModel.Kind.WATER, town.kind(6, 6));
		assertEquals(11, town.closedBlock(6, 6));
		assertEquals(outdoor.tile(3, 0, 0), town.tile(12, 12));
		assertEquals(outdoor.tile(11, 0, 0), closed.tile(12, 12));
		assertFalse(town.changes(0, 0));
		assertEquals(-1, town.closedBlock(0, 0));

		MapModel route = plan.map("G2_TEST_ROUTE").model();
		assertTrue(route.wild(0, 0), "tall grass rolls encounters");
		assertTrue(CellFlags.has(route.flags(0, 0), CellFlags.GRASS));
		assertFalse(route.wild(0, 4), "a path doesn't");
		assertEquals(MapModel.Kind.GROUND, route.kind(2, 2), "the gate a script opens, built open");
		assertEquals(13, route.dataBlock(2, 2), "the data still has it shut");
		assertEquals(13, route.closedBlock(2, 2));

		MapModel cave = plan.map("G2_TEST_CAVE").model();
		assertTrue(cave.wild(0, 0), "a cave's floor rolls encounters");
		assertEquals(MapModel.Kind.GROUND, cave.kind(2, 0), "ice");
		assertFalse(cave.wild(2, 0), "never on ice");
		assertEquals(MapModel.Kind.SOLID, cave.kind(4, 0), "rock");
		assertTrue(CellFlags.has(cave.flags(2, 2), CellFlags.WARP_TILE), "the ladder");

		MapModel house = plan.map("G2_TEST_HOUSE").model();
		assertEquals(MapModel.Kind.COUNTER, house.kind(2, 2));
		assertEquals(HeightRules.COUNTER, house.height(2, 2));
		assertTrue(CellFlags.has(house.flags(2, 5), CellFlags.WARP_TILE), "the mat out");
		assertTrue(CellFlags.has(house.flags(2, 5), CellFlags.WARP));
		assertFalse(house.wild(0, 2));

		assertTrue(CellFlags.has(plan.map("G2_TEST_KANTO_TOWN").model().flags(2, 2), CellFlags.GRASS));
	}

	@Test
	void theMeaningsAsFlags() throws Exception {
		G2Tiles house = bundle.g2tiles("TILESET_TEST_HOUSE_1");
		G2Tiles cave = bundle.g2tiles("TILESET_TEST_CAVE_1");
		assertEquals(CellFlags.WALKABLE, G2Cells.flags(outdoor.meaning(0x00)));
		assertEquals(CellFlags.WALKABLE | CellFlags.GRASS, G2Cells.flags(outdoor.meaning(0x18)));
		assertEquals(CellFlags.WATER, G2Cells.flags(outdoor.meaning(0x29)));
		assertEquals(CellFlags.WATER, G2Cells.flags(outdoor.meaning(0x24)), "a whirlpool is water");
		assertEquals(CellFlags.CUT_TREE, G2Cells.flags(outdoor.meaning(0x12)));
		assertEquals(CellFlags.WALKABLE | CellFlags.DOOR, G2Cells.flags(outdoor.meaning(0x71)));
		assertEquals(CellFlags.WALKABLE, G2Cells.flags(outdoor.meaning(0xB2)), "a side wall's cell is walked on");
		assertEquals(CellFlags.LEDGE, G2Cells.flags(outdoor.meaning(0xA3)), "down: direction 0");
		assertEquals(CellFlags.COUNTER, G2Cells.flags(house.meaning(0x90)));
		assertEquals(CellFlags.WALKABLE | CellFlags.WARP_TILE, G2Cells.flags(house.meaning(0x70)));
		assertEquals(CellFlags.WALKABLE | CellFlags.WARP_TILE, G2Cells.flags(cave.meaning(0x72)));
		assertEquals(CellFlags.WALKABLE, G2Cells.flags(cave.meaning(0x23)), "ice");
		assertEquals(0, G2Cells.flags(outdoor.meaning(0x07)));
		assertEquals(0, G2Cells.flags(outdoor.meaning(0x33)), "a byte the export doesn't describe: solid");
	}

	@Test
	void oneWayWalls() {
		// the fence block (0, 2): its top row may not be left upward, nor entered from above (0xB2's side and arm)
		PairEdges edges = plan.map("G2_TEST_TOWN").edges();
		assertEquals(2, edges.edges());
		assertEquals(EdgeWallBlock.SIDE_NORTH, edges.sides(0, 4));
		assertEquals(EdgeWallBlock.SIDE_NORTH, edges.sides(1, 4));
		assertEquals(EdgeWallBlock.SIDE_SOUTH, edges.sides(0, 3));
		assertEquals(0, edges.sides(0, 5));
		assertEquals(0, plan.map("G2_TEST_ROUTE").edges().edges());
		assertEquals(2, plan.warnings().notes().get("one_way_edges"));
	}

	@Test
	void storyCells() {
		List<StoryCellTable.Entry> entries = plan.storyCells().entries();
		// 5 objects that keep their cells, the 3 sites' 12 changing cells, the town's 8 other water cells, the route's 29
		// ground cells (it has a boulder)
		assertEquals(54, entries.size());
		assertEquals(5, entries.stream().filter(e -> e.type() == StoryCellType.OBJECT).count());
		assertEquals(12, entries.stream().filter(e -> e.type() == StoryCellType.TILE).count());
		assertEquals(8, entries.stream().filter(e -> e.type() == StoryCellType.WATER).count());
		assertEquals(29, entries.stream().filter(e -> e.type() == StoryCellType.BOULDER_AREA).count());
		assertTrue(entries.stream().filter(e -> e.type() == StoryCellType.BOULDER_AREA).allMatch(e -> "G2_TEST_ROUTE".equals(e.cell().map())));
		StoryCellTable.Entry person = entry(entries, "G2_TEST_TOWN", 1, 4);
		assertEquals("O1", person.obj());
		assertEquals("O2", entry(entries, "G2_TEST_TOWN", 5, 4).obj(), "the item ball");
		assertEquals("O4", entry(entries, "G2_TEST_TOWN", 5, 5).obj(), "the rock");
		assertEquals("O5", entry(entries, "G2_TEST_TOWN", 0, 5).obj(), "the doll a variable draws");
		assertFalse(entries.stream().anyMatch(e -> "G2_TEST_TOWN".equals(e.cell().map()) && "O3".equals(e.obj())), "the walker");
		assertEquals("O1", entry(entries, "G2_TEST_HOUSE", 1, 2).obj());
		StoryCellTable.Entry tree = entry(entries, "G2_TEST_TOWN", 6, 4);
		assertEquals(StoryCellType.TILE, tree.type());
		assertEquals(new StoryCellTable.Swap(3, 2, "cut", 6, 12), tree.swap());
		assertEquals(6, tree.start());
		assertEquals(6, tree.closed().block());
		assertEquals(StoryCellTable.Pass.SOLID, tree.closed().pass());
		assertFalse(tree.water());
		StoryCellTable.Entry whirlpool = entry(entries, "G2_TEST_TOWN", 7, 7);
		assertEquals("whirlpool", whirlpool.swap().kind());
		assertEquals(StoryCellTable.Pass.WATER, whirlpool.closed().pass());
		assertTrue(whirlpool.water());
		StoryCellTable.Entry gate = entry(entries, "G2_TEST_ROUTE", 3, 3);
		assertEquals("script", gate.swap().kind());
		assertEquals(13, gate.closed().block());
		// the closed art: the tree's tiles, by their full-shape slots
		SlotMap slots = plan.slotMap();
		assertEquals(slots.slot(key(outdoor.tile(6, 0, 0), SlotMap.Shape.FULL)).index(), tree.closed().art().get(0));
		assertEquals(slots.slot(key(outdoor.tile(6, 1, 1), SlotMap.Shape.FULL)).index(), tree.closed().art().get(3));
		assertEquals(0, plan.warnings().noteCount("story_cell_on_solid"));
	}

	private static StoryCellTable.Entry entry(List<StoryCellTable.Entry> entries, String map, int x, int y) {
		return entries.stream().filter(e -> e.cell().map().equals(map) && e.cell().x() == x && e.cell().y() == y).findFirst().orElseThrow();
	}

	private static SlotMap.Key key(int tile, SlotMap.Shape shape) {
		return new SlotMap.Key(QuarterPages.page(tile), QuarterPages.PALETTE, QuarterPages.index(tile), shape);
	}

	@Test
	void theSlotMapKeysTheBakedPages() throws Exception {
		SlotMap slots = plan.slotMap();
		G3Data.Anims anims = bundle.g2anims();
		long half = slots.slots().keySet().stream().filter(k -> k.shape() == SlotMap.Shape.HALF).count();
		// the water's 16 animated tiles, half-height; every key on page 0 of the one baked palette
		assertEquals(16, half);
		for (SlotMap.Key key : slots.slots().keySet()) {
			assertEquals("g3q.0", key.atlas());
			assertEquals(QuarterPages.PALETTE, key.palette());
			if (key.shape() == SlotMap.Shape.HALF) {
				assertTrue(anims.byQuarter().containsKey(key.tile()), "the water animates");
			}
		}
		for (int t = 0; t < 16; t++) {
			assertNotNull(slots.slot(key(outdoor.tile(6, t % 4, t / 4), SlotMap.Shape.FULL)), "the Cut tree's closed art");
			assertNotNull(slots.slot(key(outdoor.tile(11, t % 4, t / 4), SlotMap.Shape.FULL)), "the whirlpool's");
			assertNotNull(slots.slot(key(outdoor.tile(13, t % 4, t / 4), SlotMap.Shape.FULL)), "the gate's");
		}
		assertTrue(plan.art().baked());
		assertEquals(32, plan.art().tilesPerRow("g3q.0"));
		assertEquals(slots.hash(), plan.index().slotMapHash());
	}

	@Test
	void habitatsAndPools() throws Exception {
		List<WorldIndex.Habitat> habitats = plan.habitats();
		// the cave's blocks reach 6 in the 3D area (HD8), so it takes two; the route's one
		assertEquals(List.of("G2_TEST_CAVE", "G2_TEST_CAVE", "G2_TEST_ROUTE"), habitats.stream().map(WorldIndex.Habitat::map).toList());
		assertEquals(CavePlanner.TOP_FEET - HabitatPlanner.BELOW_FEET, habitats.get(0).y());
		assertEquals(HabitatPlanner.CAVE_RANGE, habitats.get(0).range());
		assertEquals(64 - HabitatPlanner.BELOW_FEET, habitats.get(2).y());
		assertEquals(Set.of("kantobridge:g2_test_cave", "kantobridge:g2_test_route"), plan.pools().keySet());
		// J6: the route's morning, day and night tables in one pool, an entry per species and level
		JsonArray route = ProtoJson.parseObject(plan.pools().get("kantobridge:g2_test_route")).getAsJsonArray("spawns");
		assertEquals(12, route.size());
		assertSpawn(route.get(0).getAsJsonObject(), "pidgey", 60, "3");
		assertSpawn(route.get(5).getAsJsonObject(), "rattata", 50, "4");
		assertSpawn(route.get(8).getAsJsonObject(), "hoothoot", 60, "5");
		assertSpawn(route.get(11).getAsJsonObject(), "pidgey", 1, "5");
		assertEquals("grounded", route.get(0).getAsJsonObject().get("spawnablePositionType").getAsString());
		// the cave's three equal tables: each species' chances three times over
		JsonArray cave = ProtoJson.parseObject(plan.pools().get("kantobridge:g2_test_cave")).getAsJsonArray("spawns");
		assertEquals(3, cave.size());
		assertSpawn(cave.get(0).getAsJsonObject(), "zubat", 195, "6");
		assertSpawn(cave.get(1).getAsJsonObject(), "geodude", 102, "6");
		assertSpawn(cave.get(2).getAsJsonObject(), "onix", 3, "6");
	}

	private static void assertSpawn(JsonObject spawn, String species, int weight, String levels) {
		assertEquals("cobblemon:" + species, spawn.get("species").getAsString());
		assertEquals(weight, spawn.get("weight").getAsInt());
		assertEquals(levels, spawn.get("levelRange").getAsString());
	}

	@Test
	void spawnOnTheRoot() {
		WorldPlan.Spawn spawn = plan.spawn();
		assertNotNull(spawn);
		assertEquals("G2_TEST_TOWN", spawn.map());
		assertEquals(4, spawn.x());
		assertEquals(4, spawn.y());
		assertEquals(ox(8), spawn.blockX());
		assertEquals(oz(8), spawn.blockZ());
	}

	@Test
	void rendering() {
		ChunkCodesView view = new ChunkCodesView(plan);
		// ground (the cell (0, 2)): a full tile block under the feet, air above
		assertEquals(BlockCode.KIND_FULL, view.kind(ox(1), 63, oz(5)));
		assertEquals(BlockCode.AIR, view.code(ox(1), 64, oz(5)));
		// the ledges hop down
		assertEquals(BlockCode.ledge(0), view.code(ox(0), 64, oz(14)));
		// water: a half block, its story block at the feet
		assertEquals(BlockCode.KIND_HALF, view.kind(ox(16), 63, oz(8)));
		assertEquals(BlockCode.KIND_STORY, view.kind(ox(16), 64, oz(8)));
		// the Cut tree: built open, its tile story block (the tree drawn per player) at the feet
		assertEquals(BlockCode.KIND_STORY_TILE, view.kind(ox(12), 64, oz(8)));
		// the fence's wall on its north side, and the cell above's south side
		assertEquals(BlockCode.edge(EdgeWallBlock.SIDE_NORTH), view.code(ox(0), 64, oz(8)));
		assertEquals(BlockCode.edge(EdgeWallBlock.SIDE_SOUTH), view.code(ox(0), 64, oz(7)));
		// the house's floor (the cell (0, 3)), two layers down
		MapPlacement house = plan.index().map("G2_TEST_HOUSE");
		assertEquals(BlockCode.KIND_FULL, view.kind(house.x() + 1, house.y() - 1, house.z() + 7));
		assertEquals(BlockCode.AIR, view.code(house.x() + 1, house.y(), house.z() + 7));
	}

	@Test
	void theBakedPack() throws Exception {
		PackFiles files = new PackFiles();
		Warnings warnings = new Warnings();
		ResourcePackWriter.Result result = ResourcePackWriter.generate(plan.art(), bundle, files, warnings);
		// one texture per tile, whichever shapes use it (the water's tiles are full-shape too, as the whirlpool's open state)
		assertEquals(plan.slotMap().slots().keySet().stream().map(k -> k.atlas() + "/" + k.tile()).distinct().count(), result.tileTextures());
		assertEquals(0, warnings.total(), warnings.warnings().toString());
		// the water's animated tiles: their two frames one below the other
		int water = outdoor.tile(3, 0, 0);
		String strip = ArtKeys.textureFile("crystal", "g3q.0", QuarterPages.PALETTE, QuarterPages.index(water));
		Png.Image frames = Png.decode(files.files().get(strip));
		assertEquals(16, frames.width());
		assertEquals(32, frames.height(), "two frames");
		assertTrue(files.files().containsKey(strip + ".mcmeta"));
		int ground = outdoor.tile(1, 0, 0);
		assertFalse(files.files().containsKey(ArtKeys.textureFile("crystal", "g3q.0", QuarterPages.PALETTE, ground) + ".mcmeta"));
		// the sheets: Gen 1's layout at Gen 1's scale (four 16-pixel columns, standing and walking rows)
		assertEquals(5, result.npcTextures());
		Png.Image lass = Png.decode(files.files().get(ArtKeys.file(ArtKeys.npcTexture("crystal", QuarterPages.PALETTE, "SPRITE_TEST_LASS"))));
		assertEquals(64, lass.width());
		assertEquals(32, lass.height());
		assertTrue(files.files().containsKey(ArtKeys.file(ArtKeys.npcTexture("crystal", QuarterPages.PALETTE, "SPRITE_TEST_BALL.p0"))));
		byte[] png = files.files().get(ArtKeys.textureFile("crystal", "g3q.0", QuarterPages.PALETTE, ground));
		assertNotNull(png);
		Png.Image image = Png.decode(png);
		assertEquals(16, image.width());
		// synth2's ground: (150, 200, 120), its diagonal 30 darker, each pixel 2 x 2 in the texture
		assertEquals(0xFF78AA78, image.get(0, 0));
		assertEquals(0xFF96C878, image.get(2, 0));
	}

	@Test
	void theWholeExportsCatalogueMatchesTheBuilds() throws Exception {
		ArtCatalog art = ArtCatalog.ofExport(bundle, new Warnings());
		assertTrue(art.baked());
		assertEquals(plan.slotMap().hash(), art.slotMap().hash(), "the pack's keys are the build's");
	}

	@Test
	void aFilteredBuildPlacesTheSameWay() throws Exception {
		WorldPlan route = WorldPlanner.plan(bundle, new WorldPlanner.Options(List.of("G2_TEST_ROUTE", "G2_TEST_CAVE"), TestData.G2_ORIGIN_X,
				TestData.G2_ORIGIN_Z, TestData.MIN_BUILD_HEIGHT, false));
		assertEquals(plan.index().map("G2_TEST_ROUTE"), route.index().map("G2_TEST_ROUTE"));
		assertEquals(plan.index().map("G2_TEST_CAVE"), route.index().map("G2_TEST_CAVE"));
		assertNull(route.index().map("G2_TEST_TOWN"));
		assertEquals(plan.slotMap().hash(), route.slotMap().hash(), "the slot map never depends on the filter");
		assertEquals(plan.habitats(), route.habitats());
		assertEquals(plan.pools(), route.pools(), "pools come from the whole export");
	}

	@Test
	void theIndexIsTheGolden() throws Exception {
		TestData.assertGolden(TestData.goldenG2Index(), plan.index().toJson(), "Gen 2 WorldIndex");
	}

	@Test
	void theShapePlannerReadsTheGen2Scene() throws Exception {
		WorldPlan shaped = WorldPlanner.plan(bundle,
				new WorldPlanner.Options(null, TestData.G2_ORIGIN_X, TestData.G2_ORIGIN_Z, TestData.MIN_BUILD_HEIGHT, true));
		assertEquals(0, shaped.warnings().total(), shaped.warnings().warnings().toString());
		assertNotNull(shaped.scene());
		assertEquals("gen2", shaped.scene().family());
		dev.kantobridge.builder.shape.ShapePlan shapes = shaped.shapes();
		assertNotNull(shapes);
		assertEquals(shaped.index().hash(), shapes.map().indexHash(), "the shape map is bound to the build's index");
		JsonObject golden = new JsonObject();
		golden.add("index", ProtoJson.parse(shapes.index().toJson()));
		golden.addProperty("indexHash", shapes.index().hash());
		golden.addProperty("mapHash", shapes.map().hash());
		golden.addProperty("shapedBlocks", shapes.shapedBlocks());
		TestData.assertGolden(TestData.goldenG2Shapes(), ProtoJson.write(golden), "Gen 2 shapes");
		// every texture the shape table's geometry names is a baked page's
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
	void areasSitSideBySide() {
		G2Map a = map("A_TOWN", 4, 2, List.of(), List.of());
		G2Map b = map("B_TOWN", 3, 3, List.of(), List.of());
		Map<String, G2Map> all = Map.of("A_TOWN", a, "B_TOWN", b);
		List<G2Data.Layout> layouts = List.of(new G2Data.Layout("johto", "A_TOWN", Map.of("A_TOWN", new G3Data.Rect(-2, 5, 4, 2)), null),
				new G2Data.Layout("kanto", "B_TOWN", Map.of("B_TOWN", new G3Data.Rect(7, 9, 3, 3)), null));
		Map<String, G3Data.Rect> origins = Gen2Planner.areaOrigins(layouts, all, new Warnings());
		assertEquals(new G3Data.Rect(-4, 10, 8, 4), origins.get("A_TOWN"), "Johto as its layout has it, in cells");
		// Johto's east edge (cell 4) and the sea's 32 cells; the tops aligned (cell 10)
		assertEquals(new G3Data.Rect(4 + Gen2Planner.AREA_GAP_CELLS, 10, 6, 6), origins.get("B_TOWN"));
	}

	@Test
	void doorsFollowWarpsAndConnectionsThenTheStartAndScripts() {
		G2Map town = map("A_TOWN", 4, 4, List.of(), List.of(new G2Map.Warp(1, 1, "A_HOUSE", 1, false), new G2Map.Warp(2, 1, null, 0, true)));
		G2Map house = map("A_HOUSE", 2, 2, List.of(new G2Map.Connection("north", "A_YARD", 0)),
				List.of(new G2Map.Warp(0, 3, "A_TOWN", 0, false), new G2Map.Warp(1, 3, "A_TOWN", 0, false)));
		G2Map yard = map("A_YARD", 2, 2, List.of(), List.of());
		G2Map shed = map("A_SHED", 2, 2, List.of(), List.of(new G2Map.Warp(1, 3, "A_TOWN", 0, false)));
		G2Map room = map("A_ROOM", 2, 2, List.of(), List.of(new G2Map.Warp(0, 0, "A_ATTIC", 0, false)));
		G2Map attic = map("A_ATTIC", 2, 2, List.of(), List.of(new G2Map.Warp(3, 3, "A_ROOM", 0, false)));
		G2Map stage = map("A_STAGE", 2, 2, List.of(), List.of(new G2Map.Warp(2, 0, "A_NOWHERE", 0, false)));
		G2Map unused = map("A_UNUSED_ROOM", 2, 2, List.of(), List.of());
		Map<String, G2Map> all = Map.of("A_TOWN", town, "A_HOUSE", house, "A_YARD", yard, "A_SHED", shed, "A_ROOM", room, "A_ATTIC", attic,
				"A_STAGE", stage, "A_UNUSED_ROOM", unused);
		Map<String, Gen2Planner.Door> doors = Gen2Planner.doors(all, Set.of("A_TOWN"), "A_TOWN", new G3Data.Start("A_ROOM", 1, 1));
		assertEquals(new Gen2Planner.Door("A_TOWN", 1, 1, 1, 3, true, "warp"), doors.get("A_HOUSE"), "its warp 1 (0-based) is the arrival");
		assertEquals(new Gen2Planner.Door("A_HOUSE", 0, 0, 0, 0, true, "north"), doors.get("A_YARD"), "a connection off the surface");
		assertEquals(new Gen2Planner.Door("A_TOWN", 1, 1, 1, 3, true, "exit"), doors.get("A_SHED"), "only left by a warp: under its landing");
		assertEquals(new Gen2Planner.Door("A_TOWN", 4, 4, 1, 1, true, "start"), doors.get("A_ROOM"), "the start room, under the root's middle");
		assertEquals(new Gen2Planner.Door("A_ROOM", 0, 0, 3, 3, true, "warp"), doors.get("A_ATTIC"));
		assertEquals(new Gen2Planner.Door("A_TOWN", 4, 4, 2, 0, true, "script"), doors.get("A_STAGE"), "a map only a script reaches");
		assertFalse(doors.containsKey("A_UNUSED_ROOM"), "left in the data");
		assertFalse(doors.containsKey("A_TOWN"));
	}

	private static G2Map map(String id, int w, int h, List<G2Map.Connection> connections, List<G2Map.Warp> warps) {
		return new G2Map(id, w, h, new int[w * h], 1, "TILESET_TEST_1", "TILESET_TEST", "TOWN", null, "DAY", 1, 1, "johto", false, null,
				connections, warps, List.of(), List.of(), List.of());
	}
}
