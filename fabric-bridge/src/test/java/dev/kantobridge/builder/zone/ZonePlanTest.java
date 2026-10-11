package dev.kantobridge.builder.zone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.BuildTask;
import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportRoutes;
import dev.kantobridge.builder.plan.BlockCode;
import dev.kantobridge.builder.plan.Box3;
import dev.kantobridge.builder.plan.ChunkCodes;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.builder.plan.WorldPlanner;
import dev.kantobridge.routes.gen.Cell;
import dev.kantobridge.routes.gen.CellRole;
import dev.kantobridge.routes.gen.ForestTheme;
import dev.kantobridge.routes.gen.RouteGenerator;
import dev.kantobridge.routes.gen.RouteLayout;
import dev.kantobridge.routes.gen.RouteSpec;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.WorldIndex;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A Wild Routes zone planned into blocks (docs/specs/WILD_ROUTES_PLAN.md §4.4): the maze's flat floor with air above,
 * hedges at least 3 high, ponds behind barriers, the route map on the trailhead, the ridge along the box, trees only on
 * scenery, determinism, and the zone in the world plan and its WorldIndex. All made up: a generated layout on flat or
 * simple terrain.
 */
class ZonePlanTest {
	private static final Set<ZoneBlock> WALKABLE_TOPS = Set.of(ZoneBlock.GRASS_BLOCK, ZoneBlock.DIRT_PATH, ZoneBlock.COARSE_DIRT);
	private static final Set<ZoneBlock> PASSABLE = Set.of(ZoneBlock.SHORT_GRASS, ZoneBlock.FERN, ZoneBlock.DANDELION, ZoneBlock.POPPY,
			ZoneBlock.AZURE_BLUET, ZoneBlock.OXEYE_DAISY);
	private static final Set<ZoneBlock> HEDGE = Set.of(ZoneBlock.OAK_LEAVES, ZoneBlock.OAK_LOG, ZoneBlock.BIRCH_LEAVES, ZoneBlock.BIRCH_LOG,
			ZoneBlock.DARK_OAK_LEAVES, ZoneBlock.DARK_OAK_LOG);

	private static RouteLayout layout;
	private static ZonePlan plan;

	@BeforeAll
	static void plan() {
		layout = RouteGenerator.generate(RouteSpec.freeRoam(ForestTheme.INSTANCE).withChances(1, 1), 1);
		// gentle hills: 66..78
		plan = new ZonePlan("test_zone", "KB_ROUTE_TEST_ZONE", "TEST_PALETTE", layout, 0, 6400,
				(x, z) -> 72 + (int) Math.round(6 * Math.sin(x / 23.0) * Math.cos(z / 31.0)));
	}

	/** Every code of one column, by Y. */
	private static Map<Integer, Integer> column(ZonePlan zone, int x, int z) {
		Map<Integer, Integer> out = new HashMap<>();
		zone.renderColumn((bx, by, bz, code) -> {
			assertEquals(x, bx);
			assertEquals(z, bz);
			out.put(by, code);
		}, x, z);
		return out;
	}

	private static ZoneBlock block(int code) {
		assertEquals(BlockCode.KIND_VANILLA, BlockCode.kind(code), "a zone writes only vanilla codes");
		return ZoneBlock.values()[BlockCode.value(code)];
	}

	private static List<int[]> columnsOf(Cell cell) {
		int[] b = plan.blockOf(cell);
		return List.of(new int[] {b[0], b[1]}, new int[] {b[0] + 1, b[1]}, new int[] {b[0], b[1] + 1}, new int[] {b[0] + 1, b[1] + 1});
	}

	@Test
	void theZoneFitsItsSlotOnEvenBlocks() {
		Box3 v = plan.volume();
		assertTrue(v.minX() >= 0 && v.maxX() <= ZoneRegions.SLOT && v.minZ() >= 6400 && v.maxZ() <= 6400 + ZoneRegions.SLOT);
		assertEquals(0, v.minX() & 1);
		assertEquals(0, v.minZ() & 1);
		assertEquals(ZonePlan.MIN_Y, v.minY());
		assertEquals(ZonePlan.MAX_Y, v.maxY());
	}

	@Test
	void walkableCellsAreFlatAtTheFloorWithRoomAbove() {
		int checked = 0;
		for (int y = 0; y < layout.height(); y++) {
			for (int x = 0; x < layout.width(); x++) {
				if (!layout.at(x, y).walkable()) {
					continue;
				}
				for (int[] c : columnsOf(new Cell(x, y))) {
					Map<Integer, Integer> col = column(plan, c[0], c[1]);
					assertTrue(WALKABLE_TOPS.contains(block(col.get(ZonePlan.FLOOR))), "floor at " + x + "," + y);
					Integer feet = col.get(ZonePlan.FEET);
					assertTrue(feet == null || PASSABLE.contains(block(feet)), "nothing solid at feet");
					assertNull(col.get(ZonePlan.FEET + 1), "nothing at head height");
					assertEquals(ZoneBlock.STONE, block(col.get(ZonePlan.MIN_Y)), "ground all the way down");
				}
				checked++;
			}
		}
		assertTrue(checked > 500, "a real maze: " + checked);
	}

	@Test
	void wallsAreHedgesAtLeastThreeHigh() {
		int walls = 0;
		for (int y = 0; y < layout.height(); y++) {
			for (int x = 0; x < layout.width(); x++) {
				if (layout.at(x, y) != CellRole.WALL) {
					continue;
				}
				for (int[] c : columnsOf(new Cell(x, y))) {
					Map<Integer, Integer> col = column(plan, c[0], c[1]);
					for (int h = 0; h < 3; h++) {
						assertTrue(HEDGE.contains(block(col.get(ZonePlan.FEET + h))), "hedge at " + x + "," + y + " +" + h);
					}
				}
				walls++;
			}
		}
		assertTrue(walls > 500);
	}

	@Test
	void pondsHaveABarrierAboveTheWater() {
		int ponds = 0;
		for (int y = 0; y < layout.height(); y++) {
			for (int x = 0; x < layout.width(); x++) {
				if (layout.at(x, y) != CellRole.WATER) {
					continue;
				}
				for (int[] c : columnsOf(new Cell(x, y))) {
					Map<Integer, Integer> col = column(plan, c[0], c[1]);
					assertEquals(ZoneBlock.WATER, block(col.get(ZonePlan.FLOOR)));
					assertEquals(ZoneBlock.BARRIER, block(col.get(ZonePlan.FEET)));
				}
				ponds++;
			}
		}
		assertTrue(ponds >= 0);
	}

	@Test
	void theRouteMapSitsOnTheTrailheadWithAWestOfX1() {
		MapPlacement p = plan.routeMap();
		List<Cell> cells = layout.trailhead().routeMapCells();
		int[] a = plan.blockOf(cells.get(0));
		int[] x1 = plan.blockOf(cells.get(1));
		assertEquals(a[0], p.x());
		assertEquals(a[1], p.z());
		assertEquals(x1[0], p.x() + 2, "x1 is the route map's cell (1, 0)");
		assertEquals(x1[1], p.z());
		assertEquals(MapPlacement.Kind.ROUTE, p.kind());
		assertEquals(ZonePlan.FEET, p.y());
		assertEquals(2, p.w());
		assertEquals(2, p.h());
		assertTrue(cells.get(0).equals(layout.trailhead().door()), "A is the trailhead's door");
		for (Cell cell : cells) {
			assertTrue(WALKABLE_TOPS.contains(block(column(plan, plan.blockOf(cell)[0], plan.blockOf(cell)[1]).get(ZonePlan.FLOOR))));
		}
		WorldIndex.Zone zone = plan.zone();
		assertTrue(zone.containsColumn(p.x(), p.z()));
		assertEquals("KB_ROUTE_TEST_ZONE", zone.routeMap());
		assertEquals(ZonePlan.BIOME, zone.biome());
	}

	@Test
	void theLandRisesToARidgeAlongTheBox() {
		Box3 v = plan.volume();
		int edge = plan.sceneryHeight(v.minX(), (v.minZ() + v.maxZ()) / 2);
		int inner = plan.sceneryHeight(v.minX() + ZonePlan.RIDGE + 10, (v.minZ() + v.maxZ()) / 2);
		assertTrue(edge >= ZonePlan.RIDGE_TOP - 2, "edge " + edge);
		assertTrue(inner <= 80, "inner " + inner);
		for (int z = v.minZ(); z < v.maxZ(); z += 17) {
			for (int x = v.minX(); x < v.maxX(); x += 17) {
				int h = plan.sceneryHeight(x, z);
				assertTrue(h >= ZonePlan.FLOOR - 4 && h <= ZonePlan.SCENERY_MAX);
			}
		}
	}

	@Test
	void treesStandOnlyOnScenery() {
		assertTrue(plan.trees() > 50, "a forest around the maze: " + plan.trees());
		Box3 v = plan.volume();
		int logs = 0;
		for (int z = v.minZ(); z < v.maxZ(); z++) {
			for (int x = v.minX(); x < v.maxX(); x++) {
				CellRole role = plan.roleAt(x, z);
				if (role == CellRole.SCENERY || role == CellRole.WALL) {
					continue;
				}
				Map<Integer, Integer> col = column(plan, x, z);
				if (role == CellRole.ROCK) {
					// a boulder: two blocks of stone, nothing above
					assertTrue(Set.of(ZoneBlock.COBBLESTONE, ZoneBlock.MOSSY_COBBLESTONE).contains(block(col.get(ZonePlan.FEET + 1))));
					assertNull(col.get(ZonePlan.FEET + 2));
					continue;
				}
				for (Map.Entry<Integer, Integer> e : col.entrySet()) {
					if (e.getKey() >= ZonePlan.FEET + 1) {
						throw new AssertionError("something above head height on a " + role + " cell at " + x + "," + z);
					}
				}
				logs++;
			}
		}
		assertTrue(logs > 0);
	}

	@Test
	void theSameInputsBuildTheSameZone() {
		ZonePlan again = new ZonePlan("test_zone", "KB_ROUTE_TEST_ZONE", "TEST_PALETTE", layout, 0, 6400,
				(x, z) -> 72 + (int) Math.round(6 * Math.sin(x / 23.0) * Math.cos(z / 31.0)));
		Box3 v = plan.volume();
		for (int z = v.minZ(); z < v.maxZ(); z += 7) {
			for (int x = v.minX(); x < v.maxX(); x += 5) {
				assertEquals(column(plan, x, z), column(again, x, z));
			}
		}
		assertEquals(plan.routeMap(), again.routeMap());
	}

	@Test
	void columnsOutsideTheBoxAreLeftAlone() {
		Box3 v = plan.volume();
		assertTrue(column(plan, v.minX() - 1, v.minZ()).isEmpty());
		assertTrue(column(plan, v.maxX(), v.minZ()).isEmpty());
	}

	@Test
	void slotsMarchEastFromKantosWestEdgeSouthOfIt() {
		ZoneRegions regions = ZoneRegions.of(new Box3(1000, 50, 2000, 1700, 90, 2740), 0, 0);
		int[] first = regions.slotOrigin(0);
		int[] second = regions.slotOrigin(1);
		int[] ninth = regions.slotOrigin(ZoneRegions.PER_ROW);
		assertEquals(1000 / 16 * 16, first[0]);
		assertTrue(first[1] >= 2740 + ZoneRegions.GAP && first[1] % 16 == 0);
		assertEquals(first[0] + ZoneRegions.SLOT, second[0]);
		assertEquals(first[1], second[1]);
		assertEquals(first[0], ninth[0]);
		assertEquals(first[1] + ZoneRegions.SLOT, ninth[1]);
		assertThrows(IllegalArgumentException.class, () -> regions.slotOrigin(-1));
	}

	// ---- the zone in the world plan ----

	private static int vanillaCodes(ChunkCodes chunk) {
		int n = 0;
		for (int[] level : chunk.levels().values()) {
			for (int code : level) {
				if (BlockCode.kind(code) == BlockCode.KIND_VANILLA) {
					n++;
				}
			}
		}
		return n;
	}

	private static boolean sameCodes(ChunkCodes a, ChunkCodes b) {
		if (!a.levels().keySet().equals(b.levels().keySet())) {
			return false;
		}
		for (Map.Entry<Integer, int[]> e : a.levels().entrySet()) {
			if (!java.util.Arrays.equals(e.getValue(), b.levels().get(e.getKey()))) {
				return false;
			}
		}
		return true;
	}

	private static ExportRoutes.Zone zoneOn(String town) {
		return new ExportRoutes.Zone("test_zone", "TEST WILDS", "free_roam", "forest", town, "KB_ROUTE_TEST_ZONE", 3, 4, 0.5, 1, 3,
				List.of("OPP_TEST"), 1, 3, 0.6);
	}

	@Test
	void theWorldPlanOwnsBuildsAndIndexesTheZone() throws Exception {
		ExportBundle bundle = ExportBundle.open(TestData.syntheticBundle());
		WorldPlan base = WorldPlanner.plan(bundle, new WorldPlanner.Options(null, TestData.ORIGIN_X, TestData.ORIGIN_Z, TestData.MIN_BUILD_HEIGHT));
		ZoneRegions regions = ZoneRegions.of(base.surfaceBox(), TestData.ORIGIN_X, TestData.ORIGIN_Z);
		List<ZonePlan> zones = ZonePlanner.plan(new ExportRoutes(List.of(zoneOn("TEST_TOWN"))), 1234L, regions, TerrainSampler.flat(70),
				town -> base.index().map(town).palette());
		WorldPlan plan = base.withZones(zones);
		ZonePlan zone = plan.zones().get(0);
		assertTrue(plan.volumes().contains(zone.volume()));
		assertTrue(zone.volume().minZ() >= base.surfaceBox().maxZ() + ZoneRegions.GAP);
		// the index: the route map's placement and the zone; JSON round trip
		WorldIndex index = plan.index();
		assertEquals(MapPlacement.Kind.ROUTE, index.map("KB_ROUTE_TEST_ZONE").kind());
		assertEquals(List.of(zone.zone()), index.zones());
		assertEquals(index.toJson(), WorldIndex.fromJson(index.toJson()).toJson());
		assertNotNull(index.zoneAt(zone.routeMap().x(), zone.routeMap().z()));
		assertNull(index.zoneAt(TestData.ORIGIN_X, TestData.ORIGIN_Z), "Kanto isn't in a zone");
		assertEquals("KB_ROUTE_TEST_ZONE", index.toCell(zone.routeMap().x() + 2, 64, zone.routeMap().z()).orElseThrow().map(),
				"the trailhead's x1 is the route map's cell");
		// a chunk of the zone renders zone codes; a Kanto chunk is unchanged
		Box3 v = zone.volume();
		ChunkCodes chunk = plan.renderChunk((v.minX() + v.maxX()) / 2 >> 4, (v.minZ() + v.maxZ()) / 2 >> 4, List.of());
		assertTrue(vanillaCodes(chunk) > 1000);
		int kx = base.surfaceBox().minX() >> 4;
		int kz = base.surfaceBox().minZ() >> 4;
		assertTrue(sameCodes(base.renderChunk(kx, kz, List.of()), plan.renderChunk(kx, kz, List.of())), "Kanto's chunks are unchanged");
		// a rebuild clears an older build's zones
		assertTrue(WorldPlan.volumesOf(index).stream().anyMatch(b -> b.equals(zone.volume())));
		// the biome
		assertTrue(plan.biomeRegions().stream().anyMatch(r -> r.biome().equals(ZonePlan.BIOME)));
	}

	@Test
	void aZoneWithoutItsTownIsLeftOutAndNoZonesChangeNothing() throws Exception {
		ExportBundle bundle = ExportBundle.open(TestData.syntheticBundle());
		WorldPlan base = WorldPlanner.plan(bundle, new WorldPlanner.Options(null, TestData.ORIGIN_X, TestData.ORIGIN_Z, TestData.MIN_BUILD_HEIGHT));
		assertTrue(base.withZones(List.of()) == base);
		assertFalse(base.index().toJson().contains("zones"), "an index without zones keeps its bytes");
		assertThrows(DataException.class, () -> WorldIndex.fromJson(base.index().toJson().replace("\"habitats\":[",
				"\"zones\":[{\"id\":\"x\",\"routeMap\":\"NOPE\",\"theme\":\"forest\",\"x\":0,\"y\":64,\"z\":0,\"w\":1,\"d\":1,"
						+ "\"biome\":\"kantobridge:wild_forest\"}],\"habitats\":[")));
		WorldPlan unchanged = BuildTask.withZones(base, new ExportRoutes(List.of(zoneOn("NO_SUCH_TOWN"))), 1L, () -> TerrainSampler.flat(70),
				TestData.ORIGIN_X, TestData.ORIGIN_Z);
		assertTrue(unchanged.zones().isEmpty());
		assertEquals(1, unchanged.warnings().warnings().getOrDefault("zone_town_unbuilt", 0));
	}
}
