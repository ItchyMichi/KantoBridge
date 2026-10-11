package dev.kantobridge.gametest;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.kantobridge.block.KbBlocks;
import dev.kantobridge.block.PropBlock;
import dev.kantobridge.builder.plan.Box3;
import dev.kantobridge.dungeon.DungeonContents;
import dev.kantobridge.dungeon.DungeonFloorPlan;
import dev.kantobridge.dungeon.DungeonService;
import dev.kantobridge.dungeon.DungeonSlots;
import dev.kantobridge.dungeon.DungeonVisits;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.routes.gen.Cell;
import dev.kantobridge.routes.gen.PmdFloor;
import dev.kantobridge.routes.gen.PmdParams;
import dev.kantobridge.server.HabitatService;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.WorldIndex;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Wild Routes dungeons in a real world (WR7, docs/specs/WILD_ROUTES_PLAN.md §4.8), with mock players and a made-up zoned
 * WorldIndex handed straight to {@link DungeonService#tickWith}: in through the entrance, down two floors (built while
 * the one before is played) and out beside the entrance, the slot cleared after; two parties in two slots at once; and a
 * Monster House filling around whoever steps in.
 */
public class DungeonGameTests implements FabricGameTest {
	private static final String HASH = "0".repeat(64);
	/** Far from every other test: the route map's A at (6,000,000, 64, 6,000,000), the dungeon region north of it. */
	private static final int AX = 6_000_000;
	private static final int AZ = 6_000_000;
	private static final WorldIndex INDEX = index(null, List.of());
	/** The third test's dungeon pool, handed to Cobblemon as a build would. */
	private static final String POOL = "kantobridge:zone_dg_zone_a1";
	private static final String POOL_JSON = "{\"name\":\"Kanto Bridge KB_ROUTE_DG a1\",\"spawns\":[{\"species\":\"cobblemon:pidgey\","
			+ "\"spawnablePositionType\":\"grounded\",\"bucket\":\"common\",\"weight\":60.0,\"levelRange\":\"5\"},{\"species\":"
			+ "\"cobblemon:rattata\",\"spawnablePositionType\":\"grounded\",\"bucket\":\"common\",\"weight\":40.0,\"levelRange\":\"6\"}]}";

	private static WorldIndex index(@org.jetbrains.annotations.Nullable String pool, List<String> items) {
		return new WorldIndex("red", HASH,
				List.of(new MapPlacement("KB_ROUTE_DG", AX, 64, AZ, 2, 2, 0, MapPlacement.Kind.ROUTE, "PAL", "kantobridge:wild_forest", false, null)),
				HASH, StoryCellTable.empty(), List.of(), List.of(new WorldIndex.Zone("dg_zone", "KB_ROUTE_DG", "forest", AX - 20, 64, AZ - 40, 200, 200,
						"kantobridge:wild_forest", List.of(), List.of(), null, new WorldIndex.ZoneDungeon(30, 4, Dir.LEFT, pool, 8, items))),
				new WorldIndex.Dungeons(AX, 64, AZ - 3_400, DungeonSlots.PER_ROW * DungeonSlots.SLOT_W, DungeonSlots.ROWS * DungeonSlots.SLOT_D));
	}

	private static void onEntrance(ServerPlayer player) {
		player.teleportTo(AX + 61.0, 64, AZ + 9.0);
	}

	/** Ticks the service until {@code done} (at most {@code limit} ticks); returns the tick it stopped at. */
	private static long until(GameTestHelper helper, ServerLevel level, List<ServerPlayer> players, long tick, int limit, BooleanSupplier done,
			String what) {
		return until(INDEX, level, players, tick, limit, done, what);
	}

	private static long until(WorldIndex index, ServerLevel level, List<ServerPlayer> players, long tick, int limit, BooleanSupplier done,
			String what) {
		for (int i = 0; i < limit; i++) {
			DungeonService.tickWith(level, index, tick++, players);
			if (done.getAsBoolean()) {
				return tick;
			}
		}
		throw new net.minecraft.gametest.framework.GameTestAssertException("never: " + what);
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 2000)
	public void inDownTwoFloorsAndOut(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		DungeonService.reset();
		DungeonVisits visits = DungeonService.visits(level);
		visits.forceFloors(3);
		MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
			List<ServerPlayer> players = List.of(player);
			onEntrance(player);
			long t = until(helper, level, players, 10_000, 400, () -> visits.floorOf(player.getUUID()) == 1, "floor 1");
			DungeonFloorPlan floor1 = visits.plan(player.getUUID(), 1);
			int[] stairs = floor1.blockOf(floor1.floor().stairs());
			BlockState prop = level.getBlockState(new BlockPos(stairs[0], floor1.feet(), stairs[1]));
			helper.assertTrue(prop.is(KbBlocks.PROP) && prop.getValue(PropBlock.KIND) == PropBlock.Kind.STAIRS, "floor 1 has no stairs prop");
			helper.assertTrue(level.getBlockState(new BlockPos(stairs[0], floor1.feet() - 1, stairs[1])).is(Blocks.COARSE_DIRT), "no floor");
			helper.assertTrue(Math.abs(player.getY() - floor1.feet()) < 0.01 && DungeonSlots.slotAt(INDEX.dungeons(), player.getBlockX(), player.getBlockZ())
					== visits.slotOf(player.getUUID()), "not standing on floor 1");
			for (int floor = 1; floor <= 2; floor++) {
				DungeonFloorPlan plan = visits.plan(player.getUUID(), floor);
				double[] c = plan.centre(plan.floor().stairs());
				player.teleportTo(c[0], c[1], c[2]);
				int next = floor + 1;
				t = until(helper, level, players, t, 400, () -> visits.floorOf(player.getUUID()) == next, "floor " + next);
			}
			DungeonFloorPlan last = visits.plan(player.getUUID(), 3);
			helper.assertTrue(last.last(), "floor 3 isn't the last");
			int[] exit = last.blockOf(last.floor().stairs());
			helper.assertTrue(level.getBlockState(new BlockPos(exit[0], last.feet(), exit[1])).getValue(PropBlock.KIND) == PropBlock.Kind.EXIT,
					"the last floor's stairs aren't the exit");
			double[] c = last.centre(last.floor().stairs());
			player.teleportTo(c[0], c[1], c[2]);
			t = until(helper, level, players, t, 100, () -> visits.floorOf(player.getUUID()) == -1, "out");
			helper.assertTrue(Math.abs(player.getX() - (AX + 59.0)) < 0.01 && Math.abs(player.getZ() - (AZ + 9.0)) < 0.01, "not beside the entrance");
			// the slot empties
			until(helper, level, players, t, 2000, () -> DungeonService.placer(level).idle() && visits.visits() == 0, "the slot cleared");
			helper.assertTrue(level.getBlockState(new BlockPos(stairs[0], floor1.feet(), stairs[1])).isAir(), "floor 1 is still there");
			long fades = sent.stream().map(Envelope::type).filter(MsgTypes.ROUTE_FADE::equals).count();
			helper.assertTrue(fades >= 4, "fades " + fades);
		}));
		DungeonService.reset();
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 2000)
	public void floorsHoldPokemonAndItemsAndABlackoutEndsTheVisit(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		DungeonService.reset();
		HabitatService.ensurePools(java.util.Map.of(POOL, POOL_JSON));
		WorldIndex index = index(POOL, List.of("POTION", "POKE_BALL"));
		DungeonVisits visits = DungeonService.visits(level);
		visits.forceFloors(2);
		MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
			List<ServerPlayer> players = List.of(player);
			onEntrance(player);
			long t = until(index, level, players, 30_000, 400, () -> visits.floorOf(player.getUUID()) == 1, "floor 1");
			helper.assertTrue(DungeonService.monsAround(player) > 0, "no Pokémon on floor 1");
			List<DungeonContents.Item> items = DungeonService.itemsAround(player);
			helper.assertTrue(!items.isEmpty(), "no items on floor 1");
			// stepping on an item asks the hidden game, without recording it
			DungeonContents.Item item = items.get(0);
			double[] c = visits.plan(player.getUUID(), 1).centre(item.cell());
			player.teleportTo(c[0], c[1], c[2]);
			DungeonService.tickWith(level, index, t++, players);
			Envelope give = sent.stream().filter(m -> m.type().equals(MsgTypes.ROUTE_GIVE) && m.data().get("id").getAsString().equals(item.id()))
					.findFirst().orElse(null);
			helper.assertTrue(give != null && !give.data().get("keep").getAsBoolean() && "dg_zone".equals(give.data().get("route").getAsString()),
					"no route.give keep:false for the item stepped on");
			com.google.gson.JsonObject result = new com.google.gson.JsonObject();
			result.addProperty("op", "give");
			result.addProperty("ok", true);
			result.addProperty("route", "dg_zone");
			result.addProperty("id", item.id());
			dev.kantobridge.net.RelayService.get().handler(MsgTypes.ROUTE_RESULT).handle(player, new Envelope(MsgTypes.ROUTE_RESULT, 9, give.seq(), result));
			helper.assertTrue(DungeonService.itemsAround(player).stream().noneMatch(i -> i.id().equals(item.id())), "the item stayed");
			// a blackout's warp takes the player out: the visit ends and the slot clears, its Pokémon with it
			int slot = visits.slotOf(player.getUUID());
			player.teleportTo(AX - 800.0, 64, AZ - 800.0);
			until(index, level, players, t, 3000, () -> visits.visits() == 0 && DungeonService.placer(level).idle(), "the slot cleared");
			int[] o = DungeonSlots.slotOrigin(index.dungeons(), slot);
			net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(o[0], 40, o[1], o[0] + DungeonSlots.SLOT_W, 120, o[1] + DungeonSlots.SLOT_D);
			helper.assertTrue(level.getEntitiesOfClass(com.cobblemon.mod.common.entity.pokemon.PokemonEntity.class, box).isEmpty(),
					"the dungeon's Pokémon stayed after it was cleared");
		}));
		DungeonService.reset();
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 2000)
	public void aMonsterHouseFillsAroundWhoeverStepsIn(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		DungeonService.reset();
		HabitatService.ensurePools(java.util.Map.of(POOL, POOL_JSON));
		WorldIndex index = index(POOL, List.of("POTION"));
		DungeonVisits visits = DungeonService.visits(level);
		visits.forceFloors(1);
		visits.forceParams(PmdParams.standard().withRooms(0, 100, 0));
		MockPlayers.with(helper, player -> {
			List<ServerPlayer> players = List.of(player);
			onEntrance(player);
			long t = until(index, level, players, 40_000, 400, () -> visits.floorOf(player.getUUID()) == 1, "floor 1");
			DungeonFloorPlan plan = visits.plan(player.getUUID(), 1);
			PmdFloor pmd = plan.floor();
			List<Cell> house = DungeonContents.house(pmd);
			helper.assertTrue(!house.isEmpty(), "floor 1 has no Monster House");
			Cell by = plan.cellAt(player.getBlockX(), player.getBlockZ());
			if (!house.contains(by)) { // arrived outside it: step in where no Pokémon stands
				by = house.stream().filter(c -> !pmd.enemies().contains(c)).findFirst().orElseThrow();
				double[] c = plan.centre(by);
				player.teleportTo(c[0], c[1], c[2]);
			}
			DungeonService.tickWith(level, index, t, players);
			// the floor's Pokémon off its enemy spots are the house's (nothing has moved yet)
			Box3 v = plan.volume();
			List<Cell> cells = new ArrayList<>();
			for (PokemonEntity mon : level.getEntitiesOfClass(PokemonEntity.class,
					new net.minecraft.world.phys.AABB(v.minX(), v.minY(), v.minZ(), v.maxX(), v.maxY(), v.maxZ()))) {
				Cell cell = plan.cellAt(mon.getBlockX(), mon.getBlockZ());
				if (cell != null && !pmd.enemies().contains(cell)) {
					cells.add(cell);
				}
			}
			helper.assertTrue(cells.size() >= DungeonContents.HOUSE_MIN && cells.size() <= DungeonContents.HOUSE_MAX,
					"the house holds " + cells.size());
			helper.assertTrue(new HashSet<>(cells).size() == cells.size(), "two house Pokémon on one cell");
			for (Cell c : cells) {
				helper.assertTrue(house.contains(c) && !c.equals(by) && Math.max(Math.abs(c.x() - by.x()), Math.abs(c.y() - by.y()))
						<= DungeonContents.HOUSE_NEAR, "a house Pokémon at " + c + ", the player at " + by);
			}
			// out, so the slot clears with its Pokémon before the next test takes it
			player.teleportTo(AX - 800.0, 64, AZ - 800.0);
			until(index, level, players, t + 1, 3000, () -> visits.visits() == 0 && DungeonService.placer(level).idle(), "the slot cleared");
		});
		DungeonService.reset();
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 2000)
	public void twoPartiesTakeTwoSlotsAtOnce(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		DungeonService.reset();
		DungeonVisits visits = DungeonService.visits(level);
		visits.forceFloors(1);
		MockPlayers.with(helper, first -> MockPlayers.with(helper, second -> {
			List<ServerPlayer> players = List.of(first, second);
			onEntrance(first);
			second.teleportTo(AX - 500.0, 64, AZ - 500.0);
			long t = until(helper, level, players, 20_000, 400, () -> visits.slotOf(first.getUUID()) >= 0, "the first party's visit");
			onEntrance(second);
			until(helper, level, players, t, 400, () -> visits.floorOf(second.getUUID()) == 1 && visits.floorOf(first.getUUID()) == 1, "both inside");
			helper.assertTrue(visits.slotOf(first.getUUID()) != visits.slotOf(second.getUUID()), "two parties share a slot");
			helper.assertTrue(visits.visits() == 2, "visits " + visits.visits());
		}));
		DungeonService.reset();
		helper.succeed();
	}
}
