package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.Scale;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Plans the synthetic bundle (a 10×9-block town with a house and its upper floor) exactly as the GameTest build does,
 * and checks placements, cell model, story cells, slot map, spawn, rendering and the golden WorldIndex.
 */
class SyntheticPlanTest {
	private static ExportBundle bundle;
	private static WorldPlan plan;

	@BeforeAll
	static void plan() throws Exception {
		bundle = ExportBundle.open(TestData.syntheticBundle());
		plan = WorldPlanner.plan(bundle, new WorldPlanner.Options(null, TestData.ORIGIN_X, TestData.ORIGIN_Z, TestData.MIN_BUILD_HEIGHT));
	}

	private static int ox(int blocks) {
		return TestData.ORIGIN_X + blocks;
	}

	private static int oz(int blocks) {
		return TestData.ORIGIN_Z + blocks;
	}

	@Test
	void placements() {
		assertEquals(0, plan.warnings().total(), plan.warnings().warnings().toString());
		assertEquals(4, plan.maps().size());
		assertEquals(4, plan.mapsRequested());
		MapPlacement town = plan.index().map("TEST_TOWN");
		assertEquals(new MapPlacement("TEST_TOWN", ox(0), 64, oz(0), 20, 18, 0, MapPlacement.Kind.SURFACE, "TEST_PALETTE",
				"kantobridge:kanto_outdoor", false, null), town);
		// The hill joins the town's north edge: 12 cells (24 blocks) north of it.
		assertEquals(new MapPlacement("TEST_HILL", ox(0), 64, oz(-24), 16, 12, 0, MapPlacement.Kind.SURFACE, "TEST_PALETTE",
				"kantobridge:kanto_outdoor", false, null), plan.index().map("TEST_HILL"));
		// The house's arrival cell (4, 8) sits right under the town door (5, 5), one layer down.
		MapPlacement house = plan.index().map("TEST_HOUSE");
		assertEquals(new MapPlacement("TEST_HOUSE", ox(10 - 8), 42, oz(10 - 16), 10, 10, 1, MapPlacement.Kind.INTERIOR, "TEST_PALETTE",
				"kantobridge:kanto_interior", false, "TEST_TOWN"), house);
		// The upper floor's arrival (5, 2) under the stairs (7, 2), stacked below.
		MapPlacement upstairs = plan.index().map("TEST_HOUSE_2F");
		assertEquals(new MapPlacement("TEST_HOUSE_2F", house.x() + 14 - 10, 20, house.z() + 4 - 4, 8, 8, 2, MapPlacement.Kind.INTERIOR,
				"TEST_PALETTE", "kantobridge:kanto_interior", false, "TEST_HOUSE"), upstairs);
		assertEquals(2, plan.maxLayer());
		assertEquals(0, plan.overlaps());
		assertEquals(0, plan.edgeMismatches());
		assertEquals(new Box3(ox(-12), 63, oz(-36), ox(52), 71, oz(48)), plan.surfaceBox(), "ground + 8 since the voxel plan's V3");
	}

	@Test
	void cellModel() {
		MapModel town = plan.map("TEST_TOWN").model();
		assertEquals(MapModel.Kind.SOLID, town.kind(0, 0), "border trees");
		assertEquals(HeightRules.WALL, town.height(0, 0));
		assertEquals(MapModel.Kind.GROUND, town.kind(2, 2));
		assertEquals(MapModel.Kind.WATER, town.kind(12, 12));
		assertEquals(MapModel.Kind.LEDGE, town.kind(2, 13));
		assertEquals(MapModel.Kind.SOLID, town.kind(11, 5), "the sign post");
		assertEquals(HeightRules.WALL, town.height(11, 5));
		assertEquals(MapModel.Kind.GROUND, town.kind(5, 5), "the door cell is walkable");
		assertEquals(MapModel.Kind.SOLID, town.kind(4, 2), "the roof");
		// The cut tree is built in its open state: ground, with the site remembered.
		assertEquals(MapModel.Kind.GROUND, town.kind(10, 14));
		assertNotNull(town.site(10, 14));
		assertEquals(0, town.tile(20, 28), "the swapped block shows ground");

		MapModel house = plan.map("TEST_HOUSE").model();
		assertEquals(MapModel.Kind.COUNTER, house.kind(4, 3));
		assertEquals(HeightRules.COUNTER, house.height(4, 3));
		assertEquals(MapModel.Kind.SOLID, house.kind(0, 0));
		assertEquals(HeightRules.INTERIOR_WALL, house.height(0, 0));
		assertEquals(MapModel.Kind.GROUND, house.kind(7, 2), "the stairs");
	}

	@Test
	void storyCells() {
		StoryCellTable cells = plan.storyCells();
		assertEquals(20, cells.size());
		Map<StoryCellType, Integer> counts = cells.countByType();
		assertEquals(3, counts.get(StoryCellType.OBJECT), "the item ball, the guard and Mom; the walking girl gets none");
		assertEquals(1, counts.get(StoryCellType.TILE), "the cut tree");
		assertEquals(16, counts.get(StoryCellType.WATER));
		assertEquals(0, counts.get(StoryCellType.BOULDER_AREA));
		assertEquals("TESTTOWN_BALL", cells.get("TEST_TOWN:15:4").obj());
		assertEquals("TESTTOWN_GUARD", cells.get("TEST_TOWN:12:10").obj());
		assertEquals("TESTHOUSE_MOM", cells.get("TEST_HOUSE:6:4").obj());
		assertNull(cells.get("TEST_TOWN:10:8"), "walking NPCs collide on the client only");
		StoryCellTable.Entry tree = cells.get("TEST_TOWN:10:14");
		assertEquals(StoryCellType.TILE, tree.type());
		assertEquals(new StoryCellTable.Swap(5, 7, "cut", 14, 15), tree.swap());
		// The map data shows the tree; while it stands the cell is solid and draws the cut-tree tile, two blocks high.
		assertEquals(14, tree.start());
		int cutTree = plan.slotMap().slot(new SlotMap.Key("tilesets.test", "TEST_PALETTE", 13, SlotMap.Shape.FULL)).index();
		assertEquals(new StoryCellTable.Closed(14, true, HeightRules.WALL, List.of(cutTree, cutTree, cutTree, cutTree)), tree.closed());
		assertNull(cells.get("TEST_TOWN:11:14"), "the rest of the cut block doesn't change");
		assertEquals(StoryCellType.WATER, cells.get("TEST_TOWN:12:12").type());
	}

	@Test
	void slotMapAndSpawn() {
		SlotMap slots = plan.slotMap();
		// Town: ground, grass, tree, wall, roof, door, flower, ledge, post, and the cut tree's closed art; house: floor,
		// counter, mat, stairs (walls and trees are shared); the hill's 13 tiles (roof edge and row, facade, window,
		// fence, rim, cliff, the sign's two and the round tree's four); water is the only half-height tile.
		assertEquals(27, slots.count(SlotMap.Shape.FULL));
		assertEquals(1, slots.count(SlotMap.Shape.HALF));
		assertNotNull(slots.slot(new SlotMap.Key("tilesets.test", "TEST_PALETTE", 6, SlotMap.Shape.HALF)));
		assertNotNull(slots.slot(new SlotMap.Key("tilesets.test", "TEST_PALETTE", 13, SlotMap.Shape.FULL)), "cut tree overlay art");
		assertEquals(plan.index().slotMapHash(), slots.hash());

		WorldPlan.Spawn spawn = plan.spawn();
		assertEquals(new WorldPlan.Spawn("TEST_TOWN", 9, 8, ox(18), 64, oz(16)), spawn);
		assertEquals(4, plan.spawnPlatform().size());
	}

	@Test
	void renderedColumns() {
		ChunkCodesView view = new ChunkCodesView(plan);
		int full = BlockCode.KIND_FULL;
		// A border tree cell: a unit (V4), two shape_full blocks on the ground block.
		assertEquals(full, view.kind(ox(0), 63, oz(0)));
		assertEquals(BlockCode.KIND_SHAPE, view.kind(ox(0), 64, oz(0)));
		assertEquals(BlockCode.KIND_SHAPE, view.kind(ox(0), 65, oz(0)));
		assertEquals(BlockCode.AIR, view.code(ox(0), 66, oz(0)));
		// Water: a half block, with a story cell above it.
		assertEquals(BlockCode.KIND_HALF, view.kind(ox(24), 63, oz(24)));
		assertEquals(BlockCode.KIND_STORY, view.kind(ox(24), 64, oz(24)));
		// The ledge row: art below, a collider facing the hop direction (down = 0) above.
		assertEquals(BlockCode.ledge(0), view.code(ox(4), 64, oz(26)));
		assertEquals(full, view.kind(ox(4), 63, oz(26)));
		// Void fill around the town: within two cells of it, border trees as units (two shape_full blocks); beyond, 3 high.
		assertEquals(full, view.kind(ox(-1), 63, oz(-1)));
		assertEquals(BlockCode.KIND_SHAPE, view.kind(ox(-1), 64, oz(-1)));
		assertEquals(BlockCode.KIND_SHAPE, view.kind(ox(-4), 65, oz(-4)));
		assertEquals(BlockCode.AIR, view.code(ox(-1), 66, oz(-1)));
		assertEquals(full, view.kind(ox(-5), 66, oz(-5)));
		assertEquals(BlockCode.AIR, view.code(ox(-5), 67, oz(-5)));
		assertEquals(BlockCode.UNOWNED, view.code(ox(-13), 63, oz(0)), "outside the surface volume");

		MapPlacement house = plan.index().map("TEST_HOUSE");
		int feet = house.y();
		// Floor, a 2-high wall, the ceiling 4 blocks above the floor, a light on the 4-block grid.
		assertEquals(full, view.kind(house.x() + 4, feet - 1, house.z() + 6));
		assertEquals(BlockCode.AIR, view.code(house.x() + 4, feet, house.z() + 6));
		assertEquals(full, view.kind(house.x() + 4, feet + 3, house.z() + 6));
		assertEquals(BlockCode.light(15), view.code(house.x() + 5, feet + 2, house.z() + 5));
		assertEquals(full, view.kind(house.x(), feet + 1, house.z()));
		assertEquals(BlockCode.AIR, view.code(house.x(), feet + 2, house.z()));
		// The counter is one block high.
		assertEquals(full, view.kind(house.x() + 8, feet, house.z() + 6));
		assertEquals(BlockCode.AIR, view.code(house.x() + 8, feet + 1, house.z() + 6));
		// The enclosure ring runs from the floor block to the ceiling.
		for (int y = feet - 1; y <= feet + 3; y++) {
			assertEquals(full, view.kind(house.x() - 1, y, house.z() + 3));
		}
		// Mom's cell carries a story cell; the cut tree's cell the tile kind, whose block draws its closed art.
		assertEquals(BlockCode.KIND_STORY, view.kind(house.x() + 12, feet, house.z() + 8));
		assertEquals(BlockCode.KIND_STORY_TILE, view.kind(ox(20), 64, oz(28)));
		assertEquals(BlockCode.KIND_STORY_TILE, view.kind(ox(21), 64, oz(29)));
	}

	@Test
	void volumesAndChunks() {
		List<Box3> volumes = plan.volumes();
		assertEquals(3, volumes.size(), "the surface (town and hill) and two interiors");
		List<long[]> chunks = WorldPlan.chunks(volumes);
		assertEquals(30, chunks.size(), "5 × 6 chunks of surface; the interiors lie under it");
		assertEquals(3, plan.biomeRegions().size());
	}

	@Test
	void goldenWorldIndex() throws Exception {
		Path golden = TestData.goldenIndex();
		String json = plan.index().toJson();
		if (TestData.updateGolden()) {
			Files.createDirectories(golden.getParent());
			Files.writeString(golden, json, StandardCharsets.UTF_8);
		}
		assertTrue(Files.isRegularFile(golden), "golden WorldIndex missing: run the tests with -Pkb.updateGolden=true once");
		assertEquals(Files.readString(golden, StandardCharsets.UTF_8), json, "the synthetic WorldIndex changed; review and update the golden");
		assertEquals(bundle.exportHash(), plan.index().exportHash());
	}

	@Test
	void slotMapAndPackDoNotDependOnTheFilter() throws Exception {
		WorldPlan townOnly = WorldPlanner.plan(bundle,
				new WorldPlanner.Options(List.of("TEST_TOWN"), TestData.ORIGIN_X, TestData.ORIGIN_Z, TestData.MIN_BUILD_HEIGHT));
		assertEquals(1, townOnly.maps().size());
		assertEquals(plan.slotMap().hash(), townOnly.slotMap().hash());
		assertEquals(plan.slotMap().toJson(), townOnly.slotMap().toJson());
		dev.kantobridge.builder.pack.PackFiles full = new dev.kantobridge.builder.pack.PackFiles();
		dev.kantobridge.builder.pack.PackFiles filtered = new dev.kantobridge.builder.pack.PackFiles();
		dev.kantobridge.builder.pack.ResourcePackWriter.generate(plan, bundle, full, new Warnings());
		dev.kantobridge.builder.pack.ResourcePackWriter.generate(townOnly, bundle, filtered, new Warnings());
		assertEquals(full.hash(), filtered.hash(), "a filtered build must write the same shared resource pack");

		WorldPlan houseOnly = WorldPlanner.plan(bundle,
				new WorldPlanner.Options(List.of("TEST_HOUSE"), TestData.ORIGIN_X, TestData.ORIGIN_Z, TestData.MIN_BUILD_HEIGHT));
		assertEquals(0, houseOnly.maps().size(), "an interior without its parent's map isn't placed");
		assertEquals(1, houseOnly.warnings().count("interior_parent_not_built"));
		assertEquals(plan.slotMap().hash(), houseOnly.slotMap().hash());
	}

	@Test
	void unreachableMapsAreLeftOutOfFullBuildsQuietly(@org.junit.jupiter.api.io.TempDir Path temp) throws Exception {
		// An interior no warp leads to (like COLOSSEUM): no parent.
		com.google.gson.JsonObject arena = bundle.json("map", "TEST_HOUSE_2F").deepCopy();
		arena.addProperty("id", "TEST_ARENA");
		arena.addProperty("index", 39);
		arena.add("parent", com.google.gson.JsonNull.INSTANCE);
		arena.add("lastMap", com.google.gson.JsonNull.INSTANCE);
		ExportBundle withArena = ExportBundle.open(TestData.syntheticCopyWith(temp, Map.of("map/TEST_ARENA", arena)));

		WorldPlan full = WorldPlanner.plan(withArena, new WorldPlanner.Options(null, 0, 0, TestData.MIN_BUILD_HEIGHT));
		assertEquals(4, full.mapsRequested(), "unreachable maps aren't requested by a full build");
		assertEquals(4, full.maps().size());
		assertEquals(0, full.warnings().total(), full.warnings().warnings().toString());
		assertEquals(1, full.warnings().noteCount("map_unreachable"));

		WorldPlan asked = WorldPlanner.plan(withArena, new WorldPlanner.Options(List.of("TEST_TOWN", "TEST_ARENA"), 0, 0,
				TestData.MIN_BUILD_HEIGHT));
		assertEquals(2, asked.mapsRequested());
		assertEquals(1, asked.maps().size());
		assertEquals(1, asked.warnings().count("map_unreachable"), "asked for by name, it is a warning");
	}

	@Test
	void unknownFilterIdsAreCounted() throws Exception {
		WorldPlan withUnknown = WorldPlanner.plan(bundle,
				new WorldPlanner.Options(List.of("TEST_TOWN", "NOT_A_MAP"), TestData.ORIGIN_X, TestData.ORIGIN_Z, TestData.MIN_BUILD_HEIGHT));
		assertEquals(2, withUnknown.mapsRequested());
		assertEquals(1, withUnknown.maps().size());
		assertEquals(1, withUnknown.warnings().count("filter_unknown_map"));
	}

	@Test
	void scaleIsConsistent() {
		assertEquals(Scale.SURFACE_Y - 1, WorldPlan.SURFACE_MIN_Y);
	}
}
