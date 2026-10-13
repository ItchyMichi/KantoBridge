package dev.kantobridge.builder.zone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import dev.kantobridge.routes.gen.Dir;
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
	private static final Set<ZoneBlock> VINES = Set.of(ZoneBlock.VINE_NORTH, ZoneBlock.VINE_EAST, ZoneBlock.VINE_SOUTH, ZoneBlock.VINE_WEST);

	private static RouteLayout layout;
	private static ZonePlan plan;
	/** Layout cells holding a trainer or a ball (as "x,y"). */
	private static final Set<String> OCCUPIED = new java.util.HashSet<>();

	@BeforeAll
	static void plan() {
		layout = RouteGenerator.generate(RouteSpec.freeRoam(ForestTheme.INSTANCE).withChances(1, 1), 1);
		ZoneObjects objects = ZoneObjects.make(ZoneObjectsTest.zone("TEST_TOWN", List.of("OPP_TEST")), layout, ZoneObjectsTest.CONTENT,
				ZoneObjectsTest.TIERS, 1L);
		// gentle hills: 66..78
		plan = new ZonePlan("test_zone", "KB_ROUTE_TEST_ZONE", "TEST_PALETTE", layout, 0, 6400,
				(x, z) -> 72 + (int) Math.round(6 * Math.sin(x / 23.0) * Math.cos(z / 31.0)), objects);
		objects.trainers().forEach(t -> OCCUPIED.add(t.cell().x() + "," + t.cell().y()));
		objects.balls().forEach(b -> OCCUPIED.add(b.cell().x() + "," + b.cell().y()));
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
		return columnsOf(plan, cell);
	}

	private static List<int[]> columnsOf(ZonePlan zone, Cell cell) {
		int[] b = zone.blockOf(cell);
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
				if (!layout.at(x, y).walkable() || OCCUPIED.contains(x + "," + y)) {
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
	void trainersStandOnBarriersAndBallsOnTheirStoryCells() {
		ZoneObjects objects = plan.objects();
		assertFalse(objects.trainers().isEmpty());
		assertFalse(objects.balls().isEmpty());
		for (ZoneObjects.Trainer t : objects.trainers()) {
			for (int[] c : columnsOf(t.cell())) {
				Map<Integer, Integer> col = column(plan, c[0], c[1]);
				assertEquals(ZoneBlock.BARRIER, block(col.get(ZonePlan.FEET)), "a blocker everyone bumps into");
				assertTrue(WALKABLE_TOPS.contains(block(col.get(ZonePlan.FLOOR))));
			}
		}
		int[] story = new int[objects.balls().size()];
		for (int i = 0; i < story.length; i++) {
			story[i] = 100 + i;
		}
		for (int i = 0; i < story.length; i++) {
			for (int[] c : columnsOf(objects.balls().get(i).cell())) {
				Map<Integer, Integer> col = new HashMap<>();
				plan.renderColumn((bx, by, bz, code) -> col.put(by, code), c[0], c[1], story);
				assertEquals(BlockCode.story(100 + i), col.get(ZonePlan.FEET), "the ball's story cell");
				Integer without = column(plan, c[0], c[1]).get(ZonePlan.FEET);
				assertTrue(without == null || PASSABLE.contains(block(without)), "no story cell without the table's indices");
			}
		}
	}

	@Test
	void theIndexListsTrainersAndBallsInTheRouteMapsGrid() {
		WorldIndex.Zone zone = plan.zone();
		MapPlacement routeMap = plan.routeMap();
		assertEquals(plan.objects().trainers().size(), zone.trainers().size());
		for (int i = 0; i < zone.trainers().size(); i++) {
			WorldIndex.ZoneTrainer t = zone.trainers().get(i);
			int[] b = plan.blockOf(plan.objects().trainers().get(i).cell());
			assertEquals(b[0], routeMap.x() + 2 * t.x(), "the route map's grid lines up with the zone's");
			assertEquals(b[1], routeMap.z() + 2 * t.y());
			assertTrue(t.sees(t.x() + t.facing().dx(), t.y() + t.facing().dy()), "the cell in front");
			assertFalse(t.sees(t.x() - t.facing().dx(), t.y() - t.facing().dy()), "not behind");
			assertFalse(t.sees(t.x() + t.facing().dx() * (t.sight() + 1), t.y() + t.facing().dy() * (t.sight() + 1)), "not past its sight");
		}
		List<dev.kantobridge.world.StoryCellTable.Entry> cells = plan.storyEntries();
		assertEquals(zone.balls().size(), cells.size());
		for (int i = 0; i < cells.size(); i++) {
			WorldIndex.ZoneBall ball = zone.balls().get(i);
			assertEquals("KB_ROUTE_TEST_ZONE:" + ball.x() + ":" + ball.y(), cells.get(i).key());
			assertEquals(dev.kantobridge.world.StoryCellType.ROUTE, cells.get(i).type());
			assertEquals(ball.id(), cells.get(i).obj());
		}
		assertEquals("SPRITE_TEST_BALL", zone.ballSprite());
	}

	@Test
	void eachAreasTallGrassIsCoveredByItsOwnPoolAtItsLevels() throws Exception {
		Map<String, String> pools = plan.pools();
		List<WorldIndex.Habitat> habitats = plan.habitats();
		assertFalse(habitats.isEmpty());
		int reach2 = dev.kantobridge.builder.plan.HabitatPlanner.REACH2;
		int grass = 0;
		for (int y = 0; y < layout.height(); y++) {
			for (int x = 0; x < layout.width(); x++) {
				int area = layout.at(x, y) == CellRole.GRASS ? layout.areaOf(new Cell(x, y)) : -1;
				if (area < 0) {
					continue;
				}
				grass++;
				String pool = ZonePlan.poolId("test_zone", area);
				assertTrue(pools.containsKey(pool), pool);
				for (int[] c : columnsOf(new Cell(x, y))) {
					assertTrue(habitats.stream().anyMatch(h -> h.pool().equals(pool) && (h.x() - c[0]) * (h.x() - c[0]) + (h.z() - c[1]) * (h.z() - c[1]) <= reach2),
							"grass at " + x + "," + y + " outside its area's spheres");
				}
			}
		}
		assertTrue(grass > 50, "grass " + grass);
		for (WorldIndex.Habitat h : habitats) {
			assertEquals(ZonePlan.FEET - 3, h.y(), "2 under the floor block, as in Kanto");
			assertEquals("KB_ROUTE_TEST_ZONE", h.map());
		}
		// each area's pool: the neighbours' slots, levels raised by the area's offset (a mix: offsets differ between areas)
		java.util.Set<Integer> offsets = new java.util.TreeSet<>();
		for (Map.Entry<String, String> e : pools.entrySet()) {
			int area = Integer.parseInt(e.getKey().substring(e.getKey().lastIndexOf("_a") + 2)) - 1;
			int offset = layout.areas().get(area).levelOffset();
			offsets.add(offset);
			com.google.gson.JsonArray spawns = dev.kantobridge.proto.ProtoJson.parseObject(e.getValue()).getAsJsonArray("spawns");
			assertEquals(2, spawns.size());
			assertEquals("cobblemon:pidgey", spawns.get(0).getAsJsonObject().get("species").getAsString());
			assertEquals(Integer.toString(3 + offset), spawns.get(0).getAsJsonObject().get("levelRange").getAsString());
			assertEquals(Integer.toString(5 + offset), spawns.get(1).getAsJsonObject().get("levelRange").getAsString());
		}
		assertTrue(offsets.size() > 1, "areas at different levels: " + offsets);
	}

	@Test
	void theDungeonsEntranceIsAPropInTheIndexsGrid() {
		Cell entrance = layout.dungeon();
		assertNotNull(entrance, "withChances(1, 1) always makes a dungeon");
		Cell door = layout.trailhead().door();
		WorldIndex.ZoneDungeon dungeon = plan.zone().dungeon();
		assertEquals(entrance.x() - door.x(), dungeon.x());
		assertEquals(entrance.y() - door.y(), dungeon.y());
		Cell out = new Cell(entrance.x() + dungeon.out().dx(), entrance.y() + dungeon.out().dy());
		assertTrue(layout.at(out).walkable(), "the way out leads onto the maze");
		assertNotNull(dungeon.pool());
		assertTrue(plan.pools().containsKey(dungeon.pool()));
		assertTrue(dungeon.level() >= 5, "at least the neighbours' top level");
		Set<Integer> parts = new java.util.HashSet<>();
		for (int[] c : columnsOf(entrance)) {
			Integer code = column(plan, c[0], c[1]).get(ZonePlan.FEET);
			assertEquals(BlockCode.KIND_PROP, BlockCode.kind(code));
			assertEquals(dev.kantobridge.block.PropBlock.Kind.DUNGEON_ENTRANCE.ordinal(), BlockCode.value(code) / 4);
			parts.add(BlockCode.value(code) % 4);
		}
		assertEquals(Set.of(0, 1, 2, 3), parts);
	}

	@Test
	void theMazeIsClosedThereIsNoWalkingOffIt() {
		int edges = 0;
		for (int y = 0; y < layout.height(); y++) {
			for (int x = 0; x < layout.width(); x++) {
				if (!layout.at(x, y).walkable()) {
					continue;
				}
				for (Dir d : Dir.values()) {
					Cell n = new Cell(x, y).step(d);
					CellRole role = layout.at(n);
					if (role.walkable() || role == CellRole.DUNGEON) {
						continue;
					}
					assertNotEquals(CellRole.SCENERY, role, "the maze opens onto the land at " + x + "," + y);
					for (int[] c : columnsOf(n)) {
						Integer feet = column(plan, c[0], c[1]).get(ZonePlan.FEET);
						assertTrue(feet != null && !PASSABLE.contains(block(feet)), "a way off the maze at " + n);
					}
					edges++;
				}
			}
		}
		assertTrue(edges > 500, "edges " + edges);
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
		// only A and x1 can be stood on, as in the hidden game: the lower row and A's other sides are hedges
		Cell door = layout.trailhead().door();
		for (Cell closed : List.of(door.step(Dir.SOUTH), cells.get(1).step(Dir.SOUTH), door.step(Dir.WEST), door.step(Dir.NORTH))) {
			assertEquals(CellRole.WALL, layout.at(closed), "closed at " + closed);
			int[] b = plan.blockOf(closed);
			assertTrue(HEDGE.contains(block(column(plan, b[0], b[1]).get(ZonePlan.FEET + 1))), "a hedge at " + closed);
		}
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
					if (e.getKey() >= ZonePlan.FEET + 1 && !(VINES.contains(block(e.getValue())) && e.getKey() >= ZonePlan.VINE_LOW)) {
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

	/** A chunk's codes with story codes replaced by their entries' keys' hashes, so two tables can be compared. */
	private static Map<Long, String> keyed(ChunkCodes chunk, WorldPlan plan) {
		Map<Long, String> out = new HashMap<>();
		for (Map.Entry<Integer, int[]> e : chunk.levels().entrySet()) {
			int[] codes = e.getValue();
			for (int i = 0; i < codes.length; i++) {
				int code = codes[i];
				out.put(((long) e.getKey() << 16) | i, BlockCode.isStory(code)
						? BlockCode.kind(code) + "@" + plan.storyCells().entries().get(BlockCode.value(code)).key() : Integer.toString(code));
			}
		}
		return out;
	}

	@Test
	void zoneBallsJoinTheStoryTableAndKantosCellsKeepTheirEntries() throws Exception {
		ExportBundle bundle = ExportBundle.open(TestData.syntheticBundle());
		WorldPlan base = WorldPlanner.plan(bundle, new WorldPlanner.Options(null, TestData.ORIGIN_X, TestData.ORIGIN_Z, TestData.MIN_BUILD_HEIGHT));
		WorldPlan plan = BuildTask.withZones(base, new ExportRoutes(List.of(zoneOn("TEST_TOWN"))), 1234L, () -> TerrainSampler.flat(70),
				TestData.ORIGIN_X, TestData.ORIGIN_Z, ZoneObjectsTest.CONTENT);
		ZonePlan zone = plan.zones().get(0);
		int balls = zone.zone().balls().size();
		assertTrue(balls > 0 && !zone.zone().trainers().isEmpty());
		assertEquals(base.storyCells().size() + balls, plan.storyCells().size());
		for (dev.kantobridge.world.StoryCellTable.Entry e : zone.storyEntries()) {
			assertEquals(e, plan.storyCells().get(e.key()));
		}
		// "KB_ROUTE_..." sorts before the synthetic maps, so their entries moved: every story code still names the same cell
		for (int cx = base.surfaceBox().minX() >> 4; cx <= base.surfaceBox().maxX() >> 4; cx++) {
			for (int cz = base.surfaceBox().minZ() >> 4; cz <= base.surfaceBox().maxZ() >> 4; cz++) {
				assertEquals(keyed(base.renderChunk(cx, cz, List.of()), base), keyed(plan.renderChunk(cx, cz, List.of()), plan));
			}
		}
		// a ball's columns carry its story cell; the index round-trips the zone's trainers, balls and story cells
		WorldIndex.ZoneBall ball = zone.zone().balls().get(0);
		int bx = zone.routeMap().x() + 2 * ball.x();
		int bz = zone.routeMap().z() + 2 * ball.y();
		ChunkCodes chunk = plan.renderChunk(bx >> 4, bz >> 4, List.of());
		int code = chunk.get(bx, ZonePlan.FEET, bz);
		assertTrue(BlockCode.isStory(code));
		assertEquals("KB_ROUTE_TEST_ZONE:" + ball.x() + ":" + ball.y(), plan.storyCells().entries().get(BlockCode.value(code)).key());
		WorldIndex index = plan.index();
		WorldIndex back = WorldIndex.fromJson(index.toJson());
		assertEquals(index.toJson(), back.toJson());
		assertEquals(zone.zone(), back.zones().get(0));
		// WR7: a zone with a dungeon brings the dungeon region, north of Kanto
		if (zone.zone().dungeon() != null) {
			assertNotNull(index.dungeons());
			assertTrue(index.dungeons().z() + index.dungeons().d() <= base.surfaceBox().minZ() - dev.kantobridge.dungeon.DungeonSlots.GAP);
			assertEquals(index.dungeons(), back.dungeons());
		}
		// the shape map names this index, the one with the zone (a stale hash and no client would draw the shapes)
		assertNotNull(base.shapes());
		assertEquals(base.index().hash(), base.shapes().map().indexHash());
		assertEquals(index.hash(), plan.shapes().map().indexHash());
		assertEquals(base.shapes().map().shapeHash(), plan.shapes().map().shapeHash());
		// WR6: the zone's habitat blocks and pools join Kanto's
		assertTrue(index.habitats().containsAll(zone.habitats()) && index.habitats().containsAll(base.index().habitats()));
		assertEquals(base.index().habitats().size() + zone.habitats().size(), index.habitats().size());
		assertTrue(plan.pools().keySet().containsAll(zone.pools().keySet()) && plan.pools().keySet().containsAll(base.pools().keySet()));
		assertEquals(dev.kantobridge.world.StoryCellType.ROUTE, back.storyCells().get(zone.storyEntries().get(0).key()).type());
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

	/**
	 * Vines (docs/specs/CAVES_AND_WOODS_PLAN.md §5.5, CW4): beside the maze only at feet + 2 or higher (a player who can't
	 * jump never reaches one to climb a hedge), each hanging on the hedge or leaves its facing names or on the vine above,
	 * never on a wall, rock or pond, in the scenery two blocks over the ground; about one face in nine.
	 */
	@Test
	void vinesHangOnLeavesOutOfReach() {
		Box3 v = plan.volume();
		int beside = 0;
		int onTrees = 0;
		Map<Long, Map<Integer, Integer>> columns = new HashMap<>();
		java.util.function.BiFunction<Integer, Integer, Map<Integer, Integer>> at = (x, z) -> columns.computeIfAbsent(
				((long) z << 32) ^ (x & 0xFFFFFFFFL), k -> column(plan, x, z));
		for (int z = v.minZ() + 1; z < v.maxZ() - 1; z++) {
			for (int x = v.minX() + 1; x < v.maxX() - 1; x++) {
				CellRole role = plan.roleAt(x, z);
				Map<Integer, Integer> col = at.apply(x, z);
				for (Map.Entry<Integer, Integer> e : col.entrySet()) {
					if (BlockCode.kind(e.getValue()) != BlockCode.KIND_VANILLA || !VINES.contains(block(e.getValue()))) {
						continue;
					}
					int y = e.getKey();
					ZoneBlock vine = block(e.getValue());
					assertTrue(role.walkable() || role == CellRole.SCENERY, "a vine over a " + role + " cell");
					if (role.walkable()) {
						assertTrue(y >= ZonePlan.VINE_LOW, "out of reach beside the maze");
						beside++;
					} else {
						assertTrue(y >= plan.sceneryHeight(x, z) + 2, "two blocks over the ground");
						onTrees++;
					}
					int d = vine.ordinal() - ZoneBlock.VINE_NORTH.ordinal();
					int[][] steps = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
					Integer held = at.apply(x + steps[d][0], z + steps[d][1]).get(y);
					Integer above = col.get(y + 1);
					boolean onLeaves = held != null && BlockCode.kind(held) == BlockCode.KIND_VANILLA && HEDGE.contains(block(held));
					boolean underVine = above != null && above.intValue() == e.getValue().intValue();
					assertTrue(onLeaves || underVine, "a vine hangs on leaves or the vine above at " + x + "," + y + "," + z);
				}
			}
		}
		assertTrue(beside > 20 && onTrees > 20, beside + " beside the maze, " + onTrees + " on trees");
	}

	// ---- MG5: a connecting route's half, its gate house and its pass ----

	/** One half of a connecting route, as the game registering its `from` end exports it (routes v2). */
	private static ExportRoutes.Zone halfOn(String town) {
		return new ExportRoutes.Zone("test_pass", "TEST PASS", "connecting", "forest", town, "KB_ROUTE_TEST_PASS", 3, 4, 0.5, 1, 3,
				List.of("OPP_TEST"), 1, 3, 0.0, "kanto", "test2", "from");
	}

	@Test
	void aConnectingHalfEndsInAGateHouseWhoseFarDoorIsThePass() {
		RouteLayout route = RouteGenerator.generate(RouteSpec.connecting(ForestTheme.INSTANCE), 2);
		RouteLayout.Gate far = route.farGate();
		assertNotNull(far);
		ZoneObjects objects = ZoneObjects.make(halfOn("TEST_TOWN"), route, ZoneObjectsTest.CONTENT, ZoneObjectsTest.TIERS, 2L);
		ZonePlan half = new ZonePlan("test_pass", "KB_ROUTE_TEST_PASS", "TEST_PALETTE", route, 0, 6400, TerrainSampler.flat(70), objects, "",
				new ZonePlan.Crossing("test_pass", "test2", "from"));
		Cell a = route.trailhead().routeMapCells().get(0);
		Cell b = far.door();
		// the WorldIndex entry: the pass cell relative to A, the house around the far plaza, facing from the stand to B
		WorldIndex.Zone zone = half.zone();
		assertNotNull(zone.pass());
		assertNotNull(zone.gateHouse());
		assertEquals(List.of(List.of(b.x() - a.x(), b.y() - a.y())), zone.pass().cells().stream().map(c -> List.of(c[0], c[1])).toList());
		assertEquals("test_pass", zone.pass().route());
		assertEquals("test2", zone.pass().toRegion());
		assertEquals("from", zone.pass().end());
		assertEquals("to", zone.pass().dir());
		assertEquals(new WorldIndex.GateHouse(far.x() - 1 - a.x(), far.y() - 1 - a.y(), far.width() + 2, far.height() + 2,
				dev.kantobridge.world.Dir.RIGHT), zone.gateHouse());
		assertTrue(zone.passCell(b.x() - a.x(), b.y() - a.y()));
		assertFalse(zone.passCell(0, 0));
		assertTrue(zone.gateHouse().contains(far.x() - a.x(), far.y() - a.y()));
		assertEquals(new ZonePlan.Crossing("test_pass", "test2", "from"), half.crossing());
		// the story entries: the balls, then the pass (obj: the zone)
		List<dev.kantobridge.world.StoryCellTable.Entry> entries = half.storyEntries();
		assertEquals(zone.balls().size() + 1, entries.size());
		dev.kantobridge.world.StoryCellTable.Entry pass = entries.get(entries.size() - 1);
		assertEquals(dev.kantobridge.world.StoryCellType.PASS, pass.type());
		assertEquals("test_pass", pass.obj());
		assertEquals("KB_ROUTE_TEST_PASS:" + (b.x() - a.x()) + ":" + (b.y() - a.y()), pass.key());
		assertEquals(List.of(b), half.passCells());
		// the house: plank walls (some with a window) under a dark plank roof on the ring, a roofed path inside
		int wallColumns = 0;
		int windows = 0;
		int insideColumns = 0;
		for (int y = far.y() - 1; y <= far.y() + far.height(); y++) {
			for (int x = far.x() - 1; x <= far.x() + far.width(); x++) {
				Cell cell = new Cell(x, y);
				if (route.areaOf(cell) >= 0 || cell.equals(b)) {
					continue;
				}
				boolean ring = x == far.x() - 1 || x == far.x() + far.width() || y == far.y() - 1 || y == far.y() + far.height();
				for (int[] c : columnsOf(half, cell)) {
					Map<Integer, Integer> col = column(half, c[0], c[1]);
					assertEquals(ZoneBlock.DARK_OAK_PLANKS, block(col.get(ZonePlan.FEET + ZonePlan.HOUSE_WALL)), "roof at " + cell);
					if (ring || route.at(cell) == CellRole.WALL) {
						assertEquals(ZoneBlock.OAK_PLANKS, block(col.get(ZonePlan.FEET)), "wall at " + cell);
						assertEquals(ZoneBlock.OAK_PLANKS, block(col.get(ZonePlan.FEET + 2)), "wall at " + cell);
						ZoneBlock eye = block(col.get(ZonePlan.FEET + 1));
						assertTrue(eye == ZoneBlock.OAK_PLANKS || eye == ZoneBlock.GLASS_PANE, "a wall or a window at " + cell);
						if (eye == ZoneBlock.GLASS_PANE) {
							windows++;
						}
						wallColumns++;
					} else {
						assertEquals(ZoneBlock.DIRT_PATH, block(col.get(ZonePlan.FLOOR)), "the floor inside at " + cell);
						assertNull(col.get(ZonePlan.FEET), "room to walk inside at " + cell);
						Integer under = col.get(ZonePlan.FEET + ZonePlan.HOUSE_WALL - 1);
						assertTrue(under == null || BlockCode.kind(under) == BlockCode.KIND_LIGHT, "only lights under the roof");
						insideColumns++;
					}
				}
			}
		}
		assertTrue(wallColumns > 0 && windows > 0 && insideColumns > 0, wallColumns + " walls, " + windows + " windows, " + insideColumns + " inside");
		// the pass: a path cell under the roof; its story cell is written when the table gives its index
		int[] bb = half.blockOf(b);
		Map<Integer, Integer> doorway = column(half, bb[0], bb[1]);
		assertEquals(ZoneBlock.DIRT_PATH, block(doorway.get(ZonePlan.FLOOR)));
		assertEquals(ZoneBlock.DARK_OAK_PLANKS, block(doorway.get(ZonePlan.FEET + ZonePlan.HOUSE_WALL)));
		assertNull(doorway.get(ZonePlan.FEET), "nothing closes the pass but its story cell");
		int[] story = new int[entries.size()];
		java.util.Arrays.fill(story, -1);
		story[entries.size() - 1] = 77;
		Map<Integer, Integer> withStory = new HashMap<>();
		half.renderColumn((x, y, z, code) -> withStory.put(y, code), bb[0], bb[1], story);
		assertTrue(BlockCode.isStory(withStory.get(ZonePlan.FEET)));
		assertEquals(77, BlockCode.storyIndex(withStory.get(ZonePlan.FEET)));
		// a connecting layout needs its crossing, a free-roam one takes none
		assertThrows(IllegalArgumentException.class,
				() -> new ZonePlan("x", "KB_ROUTE_X", "TEST_PALETTE", route, 0, 6400, TerrainSampler.flat(70)));
		assertThrows(IllegalArgumentException.class, () -> new ZonePlan("x", "KB_ROUTE_X", "TEST_PALETTE", layout, 0, 6400,
				TerrainSampler.flat(70), ZoneObjects.NONE, "", new ZonePlan.Crossing("x", "test2", "from")));
	}

	@Test
	void aConnectingHalfJoinsTheWorldPlanAndItsIndexRoundTrips() throws Exception {
		ExportBundle bundle = ExportBundle.open(TestData.syntheticBundle());
		WorldPlan base = WorldPlanner.plan(bundle, new WorldPlanner.Options(null, TestData.ORIGIN_X, TestData.ORIGIN_Z, TestData.MIN_BUILD_HEIGHT));
		WorldPlan plan = BuildTask.withZones(base, new ExportRoutes(List.of(halfOn("TEST_TOWN"))), 1234L, () -> TerrainSampler.flat(70),
				TestData.ORIGIN_X, TestData.ORIGIN_Z, ZoneObjectsTest.CONTENT);
		ZonePlan half = plan.zones().get(0);
		WorldIndex index = plan.index();
		WorldIndex.Zone zone = index.zones().get(0);
		assertEquals("test_pass", zone.id());
		assertNotNull(zone.pass());
		assertNotNull(zone.gateHouse());
		WorldIndex back = WorldIndex.fromJson(index.toJson());
		assertEquals(index.toJson(), back.toJson());
		assertEquals(zone, back.zones().get(0));
		dev.kantobridge.world.StoryCellTable.Entry passEntry = half.storyEntries().get(half.storyEntries().size() - 1);
		assertEquals(dev.kantobridge.world.StoryCellType.PASS, back.storyCells().get(passEntry.key()).type());
		assertEquals("test_pass", back.storyCells().get(passEntry.key()).obj());
		// the pass's columns carry its story cell, pointing at the PASS entry
		int[] cell = zone.pass().cells().get(0);
		int bx = half.routeMap().x() + 2 * cell[0];
		int bz = half.routeMap().z() + 2 * cell[1];
		ChunkCodes chunk = plan.renderChunk(bx >> 4, bz >> 4, List.of());
		int code = chunk.get(bx, ZonePlan.FEET, bz);
		assertTrue(BlockCode.isStory(code));
		assertEquals(passEntry.key(), plan.storyCells().entries().get(BlockCode.storyIndex(code)).key());
		// strictness: the pass comes with its gate house; the end is from or to; the far region is an id
		String json = index.toJson();
		assertThrows(DataException.class, () -> WorldIndex.fromJson(json.replace("\"gateHouse\":", "\"gateHut\":")));
		assertThrows(DataException.class, () -> WorldIndex.fromJson(json.replace("\"end\":\"from\"", "\"end\":\"middle\"")));
		assertThrows(DataException.class, () -> WorldIndex.fromJson(json.replace("\"toRegion\":\"test2\"", "\"toRegion\":\"Test2\"")));
	}

	@Test
	void bothEndsOfARouteInOneGameAreTwoZones() {
		ExportRoutes.Zone from = halfOn("TEST_TOWN");
		ExportRoutes.Zone to = new ExportRoutes.Zone("test_pass", "TEST PASS", "connecting", "forest", "TEST_TOWN", "KB_ROUTE_TEST_PASS_TO", 3, 4,
				0.5, 1, 3, List.of("OPP_TEST"), 1, 3, 0.0, "test2", "kanto", "to");
		List<ExportRoutes.Zone> both = List.of(from, to);
		assertEquals("test_pass", ZonePlanner.zoneId(both, from));
		assertEquals("test_pass_to", ZonePlanner.zoneId(both, to));
		assertEquals("test_pass", ZonePlanner.zoneId(List.of(to), to), "alone, the to end keeps the route's id");
		assertEquals(RouteSpec.Kind.CONNECTING, ZonePlanner.specOf(from).kind());
		assertEquals(RouteSpec.Kind.FREE_ROAM, ZonePlanner.specOf(zoneOn("TEST_TOWN")).kind());
	}
}
