package dev.kantobridge.dungeon;

import com.cobblemon.mod.common.CobblemonEntities;
import com.cobblemon.mod.common.CobblemonItems;
import com.cobblemon.mod.common.api.habitats.HabitatPool;
import com.cobblemon.mod.common.api.habitats.HabitatPools;
import com.cobblemon.mod.common.api.habitats.HabitatSpawn;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Species;
import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.builder.zone.ZoneObjects;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.routes.gen.Cell;
import dev.kantobridge.routes.gen.PmdParams;
import dev.kantobridge.routes.gen.RouteRng;
import dev.kantobridge.server.WorldIndexSync;
import dev.kantobridge.world.WorldIndex;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kotlin.ranges.IntRange;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Wild Routes dungeons on the server (docs/specs/WILD_ROUTES_PLAN.md §4.8, WR7): every tick, while the published
 * WorldIndex has a dungeon region, the players' positions go through {@link DungeonVisits}, whose actions this carries
 * out: {@code route.fade} to the player, floors built and cleared by the {@link DungeonPlacer} within a time budget, and
 * teleports in, between floors and out.
 *
 * <p>Inside ({@link DungeonContents}): the first time a floor is entered, its wild Pokémon (from the dungeon's habitat pool,
 * as Cobblemon knows it) roam from PMD's enemy spots, and Poké Ball item displays stand on its item spots. A player who
 * steps on an item gets {@code route.give} with {@code keep: false}; the item goes once the hidden game says it's in the
 * bag. A Monster House fills around whoever first steps into it; more Pokémon appear over time. A cleared slot takes its
 * entities with it, and any left over from before a restart are discarded as they load. Logs carry slots, floors and
 * counts only. Server thread only.
 */
public final class DungeonService {
	/** Time the placer may take each tick. */
	static final long BUDGET_NANOS = 6_000_000L;

	/** Entity tag of everything a dungeon spawns. */
	public static final String TAG = "kantobridge_dungeon";
	/** A give that failed (a full bag) isn't asked again for this long. */
	static final long GIVE_RETRY_TICKS = 100;

	private static @Nullable DungeonVisits visits;
	private static @Nullable DungeonPlacer placer;
	/** What each slot's entered floors hold. */
	private static final Map<Integer, SlotState> SLOTS = new HashMap<>();
	/** Entities a dungeon spawned this run; others with the tag are leftovers. */
	private static final Set<UUID> LIVE = new HashSet<>();
	private static final List<Entity> LEFTOVERS = new ArrayList<>();

	private static final class FloorState {
		final DungeonFloorPlan plan;
		final String zone;
		final Map<String, DungeonContents.Item> items = new LinkedHashMap<>();
		final Map<String, UUID> displays = new HashMap<>();
		final Map<String, Long> asked = new HashMap<>();
		final List<UUID> mons = new ArrayList<>();
		final List<Cell> house;
		boolean houseFilled;
		long lastLater;
		@Nullable String pool;
		int number;
		final RouteRng rng;

		FloorState(DungeonFloorPlan plan, String zone, long seed, long tick) {
			this.plan = plan;
			this.zone = zone;
			this.house = DungeonContents.house(plan.floor());
			this.lastLater = tick;
			this.rng = new RouteRng(seed);
		}

		int floorNumber() {
			return this.number;
		}
	}

	private static final class SlotState {
		final Map<Integer, FloorState> floors = new HashMap<>();
		boolean nugget;
	}

	private DungeonService() {
	}

	/** Call once from common init, after the relay. */
	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(DungeonService::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> reset());
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity.getTags().contains(TAG) && !LIVE.contains(entity.getUUID())) {
				// discarding while the level adds it is unsafe; the next tick does it
				LEFTOVERS.add(entity);
			}
		});
	}

	private static void tick(MinecraftServer server) {
		for (Entity entity : LEFTOVERS) {
			entity.discard();
		}
		LEFTOVERS.clear();
		WorldIndex index = WorldIndexSync.current(server);
		if (index == null || index.dungeons() == null) {
			return;
		}
		tickWith(server.overworld(), index, server.getTickCount(), server.getPlayerList().getPlayers());
	}

	/** One tick against {@code index} for {@code players} (GameTests call it with their own index and ticks). */
	public static void tickWith(ServerLevel level, WorldIndex index, long tick, List<ServerPlayer> players) {
		DungeonVisits v = visits(level);
		DungeonPlacer p = placer(level);
		Map<UUID, ServerPlayer> byId = new HashMap<>();
		List<DungeonVisits.Where> where = new ArrayList<>();
		for (ServerPlayer player : players) {
			byId.put(player.getUUID(), player);
			where.add(new DungeonVisits.Where(player.getUUID(), player.getX(), player.getY(), player.getZ()));
		}
		for (DungeonVisits.Action action : v.tick(tick, index, where)) {
			execute(level, index, tick, v, p, action, byId);
		}
		inside(level, tick, v, players);
		p.tick(System.nanoTime() + BUDGET_NANOS);
	}

	/** Items stepped on, Monster Houses entered, Pokémon that appear over time. */
	private static void inside(ServerLevel level, long tick, DungeonVisits v, List<ServerPlayer> players) {
		Map<FloorState, List<Cell>> occupied = new HashMap<>();
		for (ServerPlayer player : players) {
			int slot = v.slotOf(player.getUUID());
			int floor = v.floorOf(player.getUUID());
			SlotState state = SLOTS.get(slot);
			FloorState here = state == null ? null : state.floors.get(floor);
			if (here == null || Math.abs(player.getY() - here.plan.feet()) >= 2) {
				continue;
			}
			Cell cell = here.plan.cellAt(player.getBlockX(), player.getBlockZ());
			if (cell == null) {
				continue;
			}
			occupied.computeIfAbsent(here, f -> new ArrayList<>()).add(cell);
			for (DungeonContents.Item item : here.items.values()) {
				if (item.cell().equals(cell) && tick - here.asked.getOrDefault(item.id(), Long.MIN_VALUE / 2) > GIVE_RETRY_TICKS) {
					here.asked.put(item.id(), tick);
					JsonObject data = new JsonObject();
					data.addProperty("route", here.zone);
					data.addProperty("id", item.id());
					data.addProperty("item", item.item());
					data.addProperty("keep", false);
					RelayService.get().send(player, MsgTypes.ROUTE_GIVE, data, null);
				}
			}
			if (!here.houseFilled && here.house.contains(cell)) {
				here.houseFilled = true;
				List<DungeonContents.Mon> pool = poolOf(here);
				if (!pool.isEmpty()) {
					for (Cell spot : DungeonContents.houseSpots(here.house, cell, standing(level, here, players), here.rng)) {
						spawnMon(level, here, DungeonContents.pick(spot, pool, here.floorNumber(), here.rng));
					}
				}
			}
		}
		for (Map.Entry<FloorState, List<Cell>> e : occupied.entrySet()) {
			FloorState here = e.getKey();
			if (tick - here.lastLater < DungeonContents.LATER_EVERY_TICKS) {
				continue;
			}
			here.lastLater = tick;
			here.mons.removeIf(id -> {
				Entity mon = level.getEntity(id);
				return mon == null || !mon.isAlive();
			});
			List<DungeonContents.Mon> pool = poolOf(here);
			Cell spot = DungeonContents.later(here.plan.floor(), e.getValue(), here.rng);
			if (here.mons.size() < DungeonContents.LATER_CAP && !pool.isEmpty() && spot != null) {
				spawnMon(level, here, DungeonContents.pick(spot, pool, here.floorNumber(), here.rng));
			}
		}
	}

	/** The cells of {@code floor} where a player or one of its Pokémon stands. */
	private static Set<Cell> standing(ServerLevel level, FloorState floor, List<ServerPlayer> players) {
		Set<Cell> out = new HashSet<>();
		for (ServerPlayer player : players) {
			Cell cell = floor.plan.cellAt(player.getBlockX(), player.getBlockZ());
			if (cell != null && Math.abs(player.getY() - floor.plan.feet()) < 2) {
				out.add(cell);
			}
		}
		for (UUID id : floor.mons) {
			Entity mon = level.getEntity(id);
			Cell cell = mon == null || !mon.isAlive() ? null : floor.plan.cellAt(mon.getBlockX(), mon.getBlockZ());
			if (cell != null) {
				out.add(cell);
			}
		}
		return out;
	}

	private static void execute(ServerLevel level, WorldIndex index, long tick, DungeonVisits v, DungeonPlacer p, DungeonVisits.Action action,
			Map<UUID, ServerPlayer> players) {
		if (action instanceof DungeonVisits.Fade fade) {
			ServerPlayer player = players.get(fade.player());
			if (player != null) {
				JsonObject data = new JsonObject();
				data.addProperty("ticks", Math.min(fade.ticks(), 200));
				RelayService.get().send(player, MsgTypes.ROUTE_FADE, data, null);
			}
		} else if (action instanceof DungeonVisits.Build build) {
			p.build(build.floor(), () -> v.built(build.floor()));
			KantoBridge.LOGGER.debug("dungeon: building a floor at y {}", build.floor().feet());
		} else if (action instanceof DungeonVisits.Enter enter) {
			move(level, players.get(enter.player()), enter.x(), enter.y(), enter.z());
		} else if (action instanceof DungeonVisits.Leave leave) {
			move(level, players.get(leave.player()), leave.x(), leave.y(), leave.z());
		} else if (action instanceof DungeonVisits.Populate populate) {
			populate(level, index, tick, populate);
		} else if (action instanceof DungeonVisits.Clear clear) {
			SlotState state = SLOTS.remove(clear.slot());
			if (state != null) {
				for (FloorState floor : state.floors.values()) {
					discard(level, floor.mons);
					discard(level, floor.displays.values());
				}
			}
			if (clear.volumes().isEmpty()) {
				v.cleared(clear.slot());
			}
			for (int i = 0; i < clear.volumes().size(); i++) {
				boolean last = i == clear.volumes().size() - 1;
				p.clear(clear.volumes().get(i), last ? () -> v.cleared(clear.slot()) : () -> {
				});
			}
			KantoBridge.LOGGER.debug("dungeon: clearing slot {}", clear.slot());
		}
	}

	/** Fills a floor the first time anyone enters it: its Pokémon and its items. */
	private static void populate(ServerLevel level, WorldIndex index, long tick, DungeonVisits.Populate populate) {
		WorldIndex.Zone zone = null;
		for (WorldIndex.Zone z : index.zones()) {
			if (z.id().equals(populate.zone())) {
				zone = z;
			}
		}
		if (zone == null || zone.dungeon() == null) {
			return;
		}
		SlotState slot = SLOTS.computeIfAbsent(populate.slot(), s -> new SlotState());
		FloorState floor = new FloorState(populate.plan(), zone.id(), populate.seed(), tick);
		floor.pool = zone.dungeon().pool();
		floor.number = populate.floor();
		slot.floors.put(populate.floor(), floor);
		DungeonContents.Floor contents = DungeonContents.make(populate.plan(), populate.floor(), populate.visit(), poolOf(floor),
				zone.dungeon().items(), ZoneObjects.ItemTiers.bundled(), populate.seed(), slot.nugget);
		slot.nugget |= contents.nugget();
		for (DungeonContents.Spawn spawn : contents.spawns()) {
			spawnMon(level, floor, spawn);
		}
		for (DungeonContents.Item item : contents.items()) {
			floor.items.put(item.id(), item);
			double[] c = populate.plan().centre(item.cell());
			Display.ItemDisplay display = new Display.ItemDisplay(
					EntityType.ITEM_DISPLAY, level);
			display.setItemStack(new ItemStack(CobblemonItems.POKE_BALL));
			display.moveTo(c[0], c[1] + 0.3, c[2], 0.0F, 0.0F);
			display.addTag(TAG);
			LIVE.add(display.getUUID());
			if (level.addFreshEntity(display)) {
				floor.displays.put(item.id(), display.getUUID());
			}
		}
		KantoBridge.LOGGER.debug("dungeon: floor {} of slot {}: {} Pokémon, {} items", populate.floor(), populate.slot(), contents.spawns().size(),
				contents.items().size());
	}

	/** A wild Pokémon that roams the floor: persistent, outside the spawn cap, tagged for clearing. */
	private static void spawnMon(ServerLevel level, FloorState floor, DungeonContents.Spawn spawn) {
		Species species = PokemonSpecies.INSTANCE.getByIdentifier(
				ResourceLocation.parse(spawn.species()));
		if (species == null) {
			return;
		}
		PokemonEntity entity = new PokemonEntity(level,
				species.create(spawn.level()), CobblemonEntities.POKEMON);
		double[] c = floor.plan.centre(spawn.cell());
		entity.moveTo(c[0], c[1], c[2], 0.0F, 0.0F);
		entity.setPersistenceRequired();
		entity.setCountsTowardsSpawnCap(false);
		entity.getEntityData().set(PokemonEntity.getCOUNTS_TOWARDS_SPAWN_CAP(), false);
		entity.addTag(TAG);
		LIVE.add(entity.getUUID());
		if (level.addFreshEntity(entity)) {
			floor.mons.add(entity.getUUID());
		}
	}

	/** The dungeon's pool as Cobblemon knows it (empty when it has none, or the pool isn't loaded). */
	private static List<DungeonContents.Mon> poolOf(FloorState floor) {
		List<DungeonContents.Mon> out = new ArrayList<>();
		if (floor.pool == null) {
			return out;
		}
		HabitatPool pool = HabitatPools.INSTANCE.getHabitatPoolsById()
				.get(ResourceLocation.parse(floor.pool));
		if (pool == null) {
			return out;
		}
		for (HabitatSpawn spawn : pool.getSpawns()) {
			IntRange levels = spawn.getLevelRange();
			out.add(new DungeonContents.Mon(spawn.getSpecies().getResourceIdentifier().toString(), levels == null ? 5 : levels.getFirst(),
					spawn.getWeight()));
		}
		return out;
	}

	private static void discard(ServerLevel level, Collection<UUID> ids) {
		for (UUID id : ids) {
			Entity entity = level.getEntity(id);
			if (entity != null) {
				entity.discard();
			}
			LIVE.remove(id);
		}
	}

	/** {@code route.result} for a {@code route.give} (ZoneService passes them on): a dungeon item in the bag goes. */
	public static void onGiveResult(ServerPlayer player, @Nullable String id, boolean ok) {
		if (id == null || !ok) {
			return;
		}
		for (SlotState slot : SLOTS.values()) {
			for (FloorState floor : slot.floors.values()) {
				if (floor.items.remove(id) != null) {
					UUID display = floor.displays.remove(id);
					if (display != null) {
						discard(player.serverLevel(), List.of(display));
					}
					return;
				}
			}
		}
	}

	/** Tests: the items still lying on {@code player}'s floor. */
	public static List<DungeonContents.Item> itemsAround(ServerPlayer player) {
		DungeonVisits v = visits;
		if (v == null) {
			return List.of();
		}
		SlotState slot = SLOTS.get(v.slotOf(player.getUUID()));
		FloorState floor = slot == null ? null : slot.floors.get(v.floorOf(player.getUUID()));
		return floor == null ? List.of() : List.copyOf(floor.items.values());
	}

	/** Tests: the wild Pokémon spawned on {@code player}'s floor so far. */
	public static int monsAround(ServerPlayer player) {
		DungeonVisits v = visits;
		if (v == null) {
			return 0;
		}
		SlotState slot = SLOTS.get(v.slotOf(player.getUUID()));
		FloorState floor = slot == null ? null : slot.floors.get(v.floorOf(player.getUUID()));
		return floor == null ? 0 : floor.mons.size();
	}

	private static void move(ServerLevel level, @Nullable ServerPlayer player, double x, double y, double z) {
		if (player != null) {
			player.teleportTo(level, x, y, z, Set.of(), player.getYRot(), player.getXRot());
			player.resetFallDistance();
		}
	}

	/** The visits for {@code level}'s world (made with its seed on first use). */
	public static DungeonVisits visits(ServerLevel level) {
		if (visits == null) {
			visits = new DungeonVisits(level.getSeed(), PmdParams.standard());
		}
		return visits;
	}

	public static DungeonPlacer placer(ServerLevel level) {
		if (placer == null || placer.level() != level) {
			placer = new DungeonPlacer(level);
		}
		return placer;
	}

	/** Tests: forget every visit (a fresh service). */
	public static void reset() {
		visits = null;
		placer = null;
		SLOTS.clear();
	}
}
