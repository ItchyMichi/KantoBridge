package dev.kantobridge.gametest;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.CobblemonBlocks;
import com.cobblemon.mod.common.api.spawning.SpawnCause;
import com.cobblemon.mod.common.api.spawning.SpawningZone;
import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail;
import com.cobblemon.mod.common.api.spawning.detail.SpawnDetail;
import com.cobblemon.mod.common.api.spawning.position.AreaSpawnablePosition;
import com.cobblemon.mod.common.api.spawning.position.calculators.SpawnablePositionCalculator;
import com.cobblemon.mod.common.api.spawning.spawner.PlayerSpawner;
import com.cobblemon.mod.common.api.spawning.spawner.SpawningZoneInput;
import com.cobblemon.mod.common.block.habitat.HabitatBlockEntity;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.block.StoryCellBlockEntity;
import dev.kantobridge.builder.BuildTask;
import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportRoutes;
import dev.kantobridge.builder.place.BlockPlacer;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.builder.plan.WorldPlanner;
import dev.kantobridge.builder.zone.VanillaTerrain;
import dev.kantobridge.builder.zone.ZoneContent;
import dev.kantobridge.builder.zone.ZoneObjects;
import dev.kantobridge.builder.zone.ZonePlan;
import dev.kantobridge.routes.gen.Cell;
import dev.kantobridge.routes.gen.CellRole;
import dev.kantobridge.routes.gen.RouteLayout;
import dev.kantobridge.server.HabitatService;
import dev.kantobridge.server.WarpService;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.Place;
import dev.kantobridge.world.WorldIndex;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A Wild Routes zone built into the test level (docs/specs/WILD_ROUTES_PLAN.md §4.4; check C2's second half): a small
 * one-area forest zone, planned on Minecraft's own noise from this server's registries, placed by the builder's own
 * {@link BlockPlacer} beside the synthetic Kanto (at its own origin, away from the other build tests), then checked
 * block by block: the trailhead's floor under the route map, walkable floors with room above, persistent leaves in the
 * hedges, barriers over ponds. The log line {@code C2b} records the planning and placing times for the plan's record.
 * Then WR4's warps (§4.5): the hidden warp into the route map lands a player on the trailhead's x1, standing on its
 * floor inside the zone's box, and the warp back lands them in town.
 */
public class ZoneBuildGameTests implements FabricGameTest {
	/** Far from SyntheticBuildGameTests' origin, so the two builds never meet. */
	private static final int ORIGIN_X = 3_600_000;
	private static final int ORIGIN_Z = 3_600_000;
	private static final long SEED = 1234L;

	private static ExportBundle bundle() {
		try {
			return ExportBundle.fromClasspath(ZoneBuildGameTests.class.getClassLoader(), "kantobridge/synthetic_bundle/red/");
		} catch (BundleException e) {
			throw new GameTestAssertException("synthetic bundle unreadable: " + e.code());
		}
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 12000)
	public void aSmallForestZoneBuilds(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ExportBundle bundle = bundle();
		long t0 = System.nanoTime();
		WorldPlan base;
		try {
			base = WorldPlanner.plan(bundle, new WorldPlanner.Options(null, ORIGIN_X, ORIGIN_Z, level.getMinBuildHeight()));
		} catch (BundleException | dev.kantobridge.builder.plan.PlanException e) {
			throw new GameTestAssertException("the synthetic plan failed: " + e.getClass().getSimpleName());
		}
		// off TEST_HILL, whose neighbour TEST_TOWN has a grass table: trainers get parties from it (WR5)
		ExportRoutes routes = new ExportRoutes(List.of(new ExportRoutes.Zone("test_zone", "TEST WILDS", "free_roam", "forest", "TEST_HILL",
				"KB_ROUTE_TEST_ZONE", 1, 1, 0.0, 1, 3, List.of("OPP_TEST"), 1, 3, 1.0)));
		ZoneContent content;
		try {
			content = ZoneContent.of(bundle, List.of("TEST_HILL"));
		} catch (BundleException e) {
			throw new GameTestAssertException("the synthetic content failed: " + e.code());
		}
		WorldPlan plan = BuildTask.withZones(base, routes, SEED, () -> VanillaTerrain.of(level.registryAccess(), SEED), ORIGIN_X, ORIGIN_Z,
				content);
		long planMs = (System.nanoTime() - t0) / 1_000_000L;
		helper.assertTrue(plan.zones().size() == 1, "the zone wasn't planned");
		// WR6: the zone's habitat blocks get their pools, as a real build hands them over
		BlockPlacer placer = new BlockPlacer(level, plan, List.of(), List.of(), HabitatService.ensurePools(plan.pools()));
		long[] placing = {0, 0, 0};
		helper.startSequence()
				.thenWaitUntil(() -> {
					long tick = System.nanoTime();
					boolean done = placer.tick(tick + 25_000_000L);
					placing[0] += System.nanoTime() - tick;
					placing[1]++;
					placing[2] = Math.max(placing[2], System.nanoTime() - tick);
					helper.assertTrue(done, "still placing");
				})
				.thenExecute(() -> {
					try {
						verify(helper, plan.zones().get(0), level);
						warps(helper, plan, level);
						wild(helper, plan.zones().get(0), level);
					} finally {
						placer.release();
					}
					KantoBridge.LOGGER.info("C2b: zone planned in {} ms (with the synthetic Kanto); {} blocks placed, {} chunks, in {} ticks, {} ms "
							+ "placing, longest tick {} ms", planMs, placer.counts().blocksPlaced, placer.counts().chunks, placing[1],
							placing[0] / 1_000_000L, placing[2] / 1_000_000L);
				})
				.thenSucceed();
	}

	/**
	 * Check C3 (WR6): every habitat block of the zone is configured with its area's pool, and Cobblemon's own spawner (as
	 * {@code /checkspawn} runs it) offers that pool's Pokémon, at its levels, on the zone's tall grass at the walking
	 * level, and nothing above it.
	 */
	private static void wild(GameTestHelper helper, ZonePlan zone, ServerLevel level) {
		helper.assertTrue(!zone.habitats().isEmpty() && !zone.pools().isEmpty(), "no habitats or pools in the zone");
		WorldIndex.Habitat first = null;
		for (WorldIndex.Habitat habitat : zone.habitats()) {
			BlockPos pos = new BlockPos(habitat.x(), habitat.y(), habitat.z());
			helper.assertTrue(level.getBlockState(pos).is(CobblemonBlocks.HABITAT_BLOCK) && level.getBlockEntity(pos) instanceof HabitatBlockEntity,
					"a zone habitat block is missing");
			HabitatBlockEntity entity = (HabitatBlockEntity) level.getBlockEntity(pos);
			helper.assertTrue(entity.getPool().getId().toString().equals(habitat.pool()) && HabitatService.configuredFor(entity, habitat.pool()),
					"a zone habitat block without its pool");
			if (first == null && habitat.pool().equals(ZonePlan.poolId(zone.id(), 0))) {
				first = habitat;
			}
		}
		helper.assertTrue(first != null, "the first area has no habitat");
		HabitatBlockEntity habitat = (HabitatBlockEntity) level.getBlockEntity(new BlockPos(first.x(), first.y(), first.z()));
		ServerPlayer player = MockPlayers.join(helper);
		try {
			PlayerSpawner spawner = PlayerExtensionsKt.getSpawner(player);
			int x0 = first.x() - 8;
			int z0 = first.z() - 8;
			for (int cx = x0 >> 4; cx <= (x0 + 16) >> 4; cx++) {
				for (int cz = z0 >> 4; cz <= (z0 + 16) >> 4; cz++) {
					level.getChunk(cx, cz);
				}
			}
			SpawningZoneInput input = new SpawningZoneInput(new SpawnCause(spawner, player), level, x0, 60, z0, 16, 10, 16);
			SpawningZone spawning = Cobblemon.INSTANCE.getSpawningZoneGenerator().generate(spawner, input);
			List<AreaSpawnablePosition> positions = Cobblemon.INSTANCE.getAreaSpawnablePositionResolver().resolve(spawner,
					SpawnablePositionCalculator.Companion.getPrioritizedAreaCalculators(), spawning);
			int offered = 0;
			java.util.Set<String> species = new java.util.HashSet<>();
			for (AreaSpawnablePosition position : positions) {
				List<SpawnDetail> details = spawner.getMatchingSpawns("common", position);
				if (position.getPosition().getY() != ZonePlan.FLOOR) {
					helper.assertTrue(details.isEmpty(), "something may spawn off the walking level, at y " + position.getPosition().getY());
					continue;
				}
				if (details.isEmpty() || position.getPosition().distSqr(habitat.getBlockPos()) > 14 * 14) {
					continue;
				}
				offered++;
				for (SpawnDetail detail : details) {
					PokemonSpawnDetail pokemon = (PokemonSpawnDetail) detail;
					species.add(pokemon.getPokemon().getSpecies());
					kotlin.ranges.IntRange levels = pokemon.getLevelRange();
					// the first area keeps the neighbours' levels (the synthetic grass table's 2 to 5)
					helper.assertTrue(levels != null && levels.getFirst() >= 2 && levels.getLast() <= 5, "levels out of range");
				}
			}
			helper.assertTrue(offered > 0, "no zone grass offers the pool (" + positions.size() + " positions)");
			helper.assertTrue(species.equals(java.util.Set.of("pidgey", "rattata")), "the pool's species don't come through: " + species);
			KantoBridge.LOGGER.info("C3: zone habitats {}, pools {}; the first area's pool offered on {} grass positions", zone.habitats().size(),
					zone.pools().size(), offered);
		} finally {
			MockPlayers.leave(helper, player);
		}
	}

	private static void warps(GameTestHelper helper, WorldPlan plan, ServerLevel level) {
		WorldIndex index = plan.index();
		MockPlayers.with(helper, player -> {
			WarpService.Result in = WarpService.teleport(player, index, new Place("KB_ROUTE_TEST_ZONE", 1, 0, Dir.RIGHT));
			helper.assertTrue(in.refusal() == null, "the warp in was refused: " + in.refusal());
			Vec3 x1 = index.toBlock("KB_ROUTE_TEST_ZONE", 1, 0);
			helper.assertTrue(player.position().distanceTo(x1) < 0.01, "not on x1");
			helper.assertTrue(index.zoneAt(player.getBlockX(), player.getBlockZ()) != null, "x1 isn't inside the zone's box");
			BlockPos under = BlockPos.containing(x1.x, ZonePlan.FLOOR, x1.z);
			helper.assertTrue(!level.getBlockState(under).getCollisionShape(level, under).isEmpty(), "nothing to stand on at x1");
			WarpService.Result back = WarpService.teleport(player, index, new Place("TEST_TOWN", 5, 8, Dir.DOWN));
			helper.assertTrue(back.refusal() == null && index.zoneAt(player.getBlockX(), player.getBlockZ()) == null,
					"the warp back didn't leave the zone");
		});
	}

	private static void verify(GameTestHelper helper, ZonePlan zone, ServerLevel level) {
		RouteLayout layout = zone.layout();
		MapPlacement routeMap = zone.routeMap();
		BlockState trailhead = level.getBlockState(new BlockPos(routeMap.x(), ZonePlan.FLOOR, routeMap.z()));
		helper.assertTrue(trailhead.is(Blocks.DIRT_PATH) || trailhead.is(Blocks.GRASS_BLOCK), "the trailhead's A has no floor");
		helper.assertTrue(level.getBlockState(new BlockPos(routeMap.x() + 2, ZonePlan.FEET, routeMap.z())).getCollisionShape(level,
				new BlockPos(routeMap.x() + 2, ZonePlan.FEET, routeMap.z())).isEmpty(), "x1 is blocked");
		// WR5: trainers stand on barriers; balls on story cells that know their key
		java.util.Set<Cell> occupied = new java.util.HashSet<>();
		helper.assertTrue(!zone.objects().trainers().isEmpty() && !zone.objects().balls().isEmpty(), "no trainers or balls");
		for (ZoneObjects.Trainer t : zone.objects().trainers()) {
			int[] b = zone.blockOf(t.cell());
			helper.assertTrue(level.getBlockState(new BlockPos(b[0], ZonePlan.FEET, b[1])).is(Blocks.BARRIER), "a trainer without its blocker");
			occupied.add(t.cell());
		}
		for (int i = 0; i < zone.objects().balls().size(); i++) {
			ZoneObjects.Ball ball = zone.objects().balls().get(i);
			int[] b = zone.blockOf(ball.cell());
			String key = zone.storyEntries().get(i).key();
			helper.assertTrue(level.getBlockEntity(new BlockPos(b[0], ZonePlan.FEET, b[1])) instanceof StoryCellBlockEntity cell
					&& key.equals(cell.cellKey()), "a ball without its story cell");
			occupied.add(ball.cell());
		}
		int walkable = 0;
		int hedges = 0;
		int ponds = 0;
		for (int y = 0; y < layout.height(); y++) {
			for (int x = 0; x < layout.width(); x++) {
				CellRole role = layout.at(x, y);
				if (occupied.contains(new Cell(x, y))) {
					continue;
				}
				int[] b = zone.blockOf(new Cell(x, y));
				BlockPos floor = new BlockPos(b[0], ZonePlan.FLOOR, b[1]);
				BlockPos feet = floor.above();
				if (role.walkable()) {
					// a dirt path is 15/16 high: something to stand on, not a full cube
					helper.assertTrue(!level.getBlockState(floor).getCollisionShape(level, floor).isEmpty(), "no floor at " + x + "," + y);
					helper.assertTrue(level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
							&& level.getBlockState(feet.above()).isAir(), "no room above " + x + "," + y);
					walkable++;
				} else if (role == CellRole.WALL) {
					BlockState s = level.getBlockState(feet.above());
					helper.assertTrue(!s.getCollisionShape(level, feet.above()).isEmpty(), "a hedge you can walk into at " + x + "," + y);
					if (s.getBlock() instanceof LeavesBlock) {
						helper.assertTrue(s.getValue(LeavesBlock.PERSISTENT), "leaves that would decay");
					}
					hedges++;
				} else if (role == CellRole.WATER) {
					helper.assertTrue(level.getBlockState(floor).is(Blocks.WATER) && level.getBlockState(feet).is(Blocks.BARRIER),
							"a pond without its barrier at " + x + "," + y);
					ponds++;
				}
			}
		}
		helper.assertTrue(walkable > 100 && hedges > 100, "walkable " + walkable + ", hedges " + hedges + ", ponds " + ponds);
	}
}
