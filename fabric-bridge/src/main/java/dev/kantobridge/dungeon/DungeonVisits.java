package dev.kantobridge.dungeon;

import dev.kantobridge.builder.plan.Box3;
import dev.kantobridge.routes.gen.Cell;
import dev.kantobridge.routes.gen.DungeonRules;
import dev.kantobridge.routes.gen.PmdFloor;
import dev.kantobridge.routes.gen.PmdFloorGenerator;
import dev.kantobridge.routes.gen.PmdParams;
import dev.kantobridge.routes.gen.RouteRng;
import dev.kantobridge.routes.gen.RouteSeeds;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.Scale;
import dev.kantobridge.world.WorldIndex;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Dungeon visits on the server (docs/specs/WILD_ROUTES_PLAN.md §4.8, WR7), without Minecraft.
 *
 * <ul>
 * <li><b>Going in:</b> a player standing on a zone's dungeon entrance starts a gathering; everyone who steps on it within
 * {@value #GATHER_TICKS} ticks joins (R9: one copy per party). Each is faded out at once ({@link Fade}). When the window
 * closes the party takes a free slot, floor 1 is built ({@link Build}) and, once it stands, they enter it
 * ({@link Enter}). With no slot free they are put back beside the entrance ({@link Leave}).</li>
 * <li><b>Floors:</b> {@code DungeonRules.floorCount} for the dungeon's level, each a PMD floor seeded from the zone, the
 * visit and the floor. A floor is built as soon as someone enters the one before it.</li>
 * <li><b>Moving on:</b> a player on the stairs is faded and moved to the next floor once it stands; on the last floor
 * the stairs are the exit, back beside the entrance (the entrance's open neighbour).</li>
 * <li><b>Leaving:</b> the exit, or anything that takes a player out of the visit's slot (the hidden game's blackout warp,
 * Fly) or offline. A visit without players is cleared ({@link Clear}); its slot is free again once
 * {@link #cleared} says so. A player found in the dungeon region outside every visit (after a restart) is put back
 * beside a dungeon's entrance.</li>
 * </ul>
 * Server thread only.
 */
public final class DungeonVisits {
	public static final long GATHER_TICKS = 100;
	/** A fade before a move within a visit, in ticks. */
	public static final int FADE_TICKS = 10;

	/** What the server does. */
	public sealed interface Action permits Fade, Build, Enter, Leave, Clear, Populate {
	}

	/** The first time anyone enters a floor: fill it ({@link DungeonContents}). */
	public record Populate(int slot, int floor, DungeonFloorPlan plan, String zone, long visit, long seed) implements Action {
	}

	/** {@code route.fade}: the player will be moved in about {@code ticks}. */
	public record Fade(UUID player, int ticks) implements Action {
	}

	/** Build a floor's blocks; report back with {@link #built}. */
	public record Build(DungeonFloorPlan floor) implements Action {
	}

	/** Move a player onto a floor's start. */
	public record Enter(UUID player, DungeonFloorPlan floor, double x, double y, double z) implements Action {
	}

	/** Move a player out, beside a zone's dungeon entrance. */
	public record Leave(UUID player, double x, double y, double z) implements Action {
	}

	/** Clear a slot's floors; report back with {@link #cleared}. */
	public record Clear(int slot, List<Box3> volumes) implements Action {
	}

	/** Where an online player stands this tick. */
	public record Where(UUID player, double x, double y, double z) {
	}

	private static final class Gathering {
		final String zone;
		final long until;
		final Set<UUID> members = new LinkedHashSet<>();

		Gathering(String zone, long until) {
			this.zone = zone;
			this.until = until;
		}
	}

	private static final class Visit {
		final String zone;
		final int slot;
		final long seed;
		final long number;
		final int floors;
		final Map<Integer, DungeonFloorPlan> plans = new HashMap<>();
		final Set<Integer> populated = new HashSet<>();
		final Set<Integer> built = new HashSet<>();
		final Set<Integer> building = new HashSet<>();
		/** The floor each member stands on (0 while waiting to enter). */
		final Map<UUID, Integer> floorOf = new LinkedHashMap<>();
		/** Members on their way: where to (floors + 1 is out) and from which tick. */
		final Map<UUID, Integer> movingTo = new HashMap<>();
		final Map<UUID, Long> movingAt = new HashMap<>();

		Visit(String zone, int slot, long seed, long number, int floors) {
			this.zone = zone;
			this.slot = slot;
			this.seed = seed;
			this.number = number;
			this.floors = floors;
		}
	}

	private final long worldSeed;
	private final PmdParams params;
	private final Map<String, Gathering> gatherings = new HashMap<>();
	private final Visit[] slots = new Visit[DungeonSlots.SLOTS];
	private final boolean[] clearing = new boolean[DungeonSlots.SLOTS];
	private final Map<UUID, Visit> visitOf = new HashMap<>();
	private long visitCount;
	private int floorsOverride = -1;

	public DungeonVisits(long worldSeed, PmdParams params) {
		this.worldSeed = worldSeed;
		this.params = params;
	}

	/** Tests: every visit gets this many floors (-1: the rules decide). */
	public void forceFloors(int floors) {
		this.floorsOverride = floors;
	}

	/** One tick: every online player's position; returns what to do, in order. */
	public List<Action> tick(long tick, WorldIndex index, Collection<Where> players) {
		List<Action> out = new ArrayList<>();
		WorldIndex.Dungeons region = index.dungeons();
		if (region == null) {
			return out;
		}
		Map<UUID, Where> online = new HashMap<>();
		for (Where w : players) {
			online.put(w.player(), w);
		}
		this.dropOffline(online.keySet());
		for (Where w : players) {
			Visit visit = this.visitOf.get(w.player());
			if (visit != null) {
				this.visitTick(visit, w, tick, index, region, out);
			} else if (!this.gathering(w.player())) {
				this.outsideTick(w, tick, index, region, out);
			}
		}
		for (Iterator<Gathering> it = this.gatherings.values().iterator(); it.hasNext();) {
			Gathering g = it.next();
			if (tick >= g.until) {
				it.remove();
				this.start(g, tick, index, region, out);
			}
		}
		for (int slot = 0; slot < this.slots.length; slot++) {
			Visit visit = this.slots[slot];
			if (visit != null && visit.floorOf.isEmpty()) {
				List<Box3> volumes = new ArrayList<>();
				for (int floor : visit.built) {
					volumes.add(visit.plans.get(floor).volume());
				}
				for (int floor : visit.building) {
					volumes.add(visit.plans.get(floor).volume());
				}
				this.slots[slot] = null;
				this.clearing[slot] = true;
				out.add(new Clear(slot, volumes));
			}
		}
		return out;
	}

	/** The placer finished a floor. */
	public void built(DungeonFloorPlan floor) {
		for (Visit visit : this.slots) {
			if (visit == null) {
				continue;
			}
			for (Map.Entry<Integer, DungeonFloorPlan> e : visit.plans.entrySet()) {
				if (e.getValue() == floor) {
					visit.building.remove(e.getKey());
					visit.built.add(e.getKey());
				}
			}
		}
	}

	/** A slot's floors are cleared: it may be taken again. */
	public void cleared(int slot) {
		this.clearing[slot] = false;
	}

	// ---- per player ----

	private boolean gathering(UUID player) {
		for (Gathering g : this.gatherings.values()) {
			if (g.members.contains(player)) {
				return true;
			}
		}
		return false;
	}

	private void outsideTick(Where w, long tick, WorldIndex index, WorldIndex.Dungeons region, List<Action> out) {
		int bx = (int) Math.floor(w.x());
		int bz = (int) Math.floor(w.z());
		if (region.containsColumn(bx, bz)) {
			// in the region with no visit (a restart, a slot gone): back beside a dungeon's entrance
			for (WorldIndex.Zone zone : index.zones()) {
				double[] at = outside(index, zone);
				if (at != null) {
					out.add(new Fade(w.player(), FADE_TICKS));
					out.add(new Leave(w.player(), at[0], at[1], at[2]));
					return;
				}
			}
			return;
		}
		WorldIndex.Zone zone = index.zoneAt(bx, bz);
		if (zone == null || zone.dungeon() == null || !onEntrance(index, zone, w)) {
			return;
		}
		Gathering g = this.gatherings.computeIfAbsent(zone.id(), id -> new Gathering(id, tick + GATHER_TICKS));
		g.members.add(w.player());
		out.add(new Fade(w.player(), (int) Math.max(FADE_TICKS, g.until - tick)));
	}

	private void visitTick(Visit visit, Where w, long tick, WorldIndex index, WorldIndex.Dungeons region, List<Action> out) {
		Integer to = visit.movingTo.get(w.player());
		if (to != null) {
			if (tick < visit.movingAt.get(w.player())) {
				return;
			}
			if (to > visit.floors) {
				WorldIndex.Zone zone = zone(index, visit.zone);
				double[] at = zone == null ? null : outside(index, zone);
				this.leave(visit, w.player());
				if (at != null) {
					out.add(new Leave(w.player(), at[0], at[1], at[2]));
				}
				return;
			}
			if (!visit.built.contains(to)) {
				// still being built: wait behind the fade
				this.ensureBuilt(visit, to, region, out);
				return;
			}
			DungeonFloorPlan plan = visit.plans.get(to);
			double[] start = plan.centre(start(plan.floor()));
			visit.movingTo.remove(w.player());
			visit.movingAt.remove(w.player());
			visit.floorOf.put(w.player(), to);
			out.add(new Enter(w.player(), plan, start[0], start[1], start[2]));
			if (visit.populated.add(to)) {
				out.add(new Populate(visit.slot, to, plan, visit.zone, visit.number, RouteRng.mix(visit.seed ^ (to * 0x9E3779B97F4A7C15L))));
			}
			if (to < visit.floors) {
				this.ensureBuilt(visit, to + 1, region, out);
			}
			return;
		}
		int floor = visit.floorOf.getOrDefault(w.player(), 0);
		DungeonFloorPlan plan = visit.plans.get(floor);
		int bx = (int) Math.floor(w.x());
		int bz = (int) Math.floor(w.z());
		if (plan == null || DungeonSlots.slotAt(region, bx, bz) != visit.slot) {
			// out of the slot some other way (a blackout, Fly): the visit goes on without them
			if (floor > 0) {
				this.leave(visit, w.player());
			}
			return;
		}
		Cell cell = plan.cellAt(bx, bz);
		if (cell != null && cell.equals(plan.floor().stairs()) && Math.abs(w.y() - plan.feet()) < 2) {
			visit.movingTo.put(w.player(), floor + 1);
			visit.movingAt.put(w.player(), tick + FADE_TICKS);
			out.add(new Fade(w.player(), FADE_TICKS));
		}
	}

	private void start(Gathering g, long tick, WorldIndex index, WorldIndex.Dungeons region, List<Action> out) {
		WorldIndex.Zone zone = zone(index, g.zone);
		int slot = -1;
		for (int i = 0; i < this.slots.length && slot < 0; i++) {
			if (this.slots[i] == null && !this.clearing[i]) {
				slot = i;
			}
		}
		if (zone == null || zone.dungeon() == null || slot < 0) {
			double[] at = zone == null ? null : outside(index, zone);
			for (UUID player : g.members) {
				if (at != null) {
					out.add(new Leave(player, at[0], at[1], at[2]));
				}
			}
			return;
		}
		long number = ++this.visitCount;
		long seed = RouteSeeds.forVisit(RouteSeeds.forRoute(this.worldSeed, zone.id()), number);
		int floors = this.floorsOverride > 0 ? this.floorsOverride : DungeonRules.floorCount(new RouteRng(seed), zone.dungeon().level());
		Visit visit = new Visit(zone.id(), slot, seed, number, floors);
		this.slots[slot] = visit;
		for (UUID player : g.members) {
			this.visitOf.put(player, visit);
			visit.floorOf.put(player, 0);
			visit.movingTo.put(player, 1);
			visit.movingAt.put(player, tick);
		}
		this.ensureBuilt(visit, 1, region, out);
	}

	private void ensureBuilt(Visit visit, int floor, WorldIndex.Dungeons region, List<Action> out) {
		if (visit.built.contains(floor) || visit.building.contains(floor)) {
			return;
		}
		int[] origin = DungeonSlots.slotOrigin(region, visit.slot);
		PmdFloor pmd = PmdFloorGenerator.generate(this.params, RouteRng.mix(visit.seed + floor));
		DungeonFloorPlan plan = new DungeonFloorPlan(pmd, origin[0], origin[1], DungeonSlots.feet(region, floor), floor == visit.floors);
		visit.plans.put(floor, plan);
		visit.building.add(floor);
		out.add(new Build(plan));
	}

	private void leave(Visit visit, UUID player) {
		visit.floorOf.remove(player);
		visit.movingTo.remove(player);
		visit.movingAt.remove(player);
		this.visitOf.remove(player);
	}

	private void dropOffline(Set<UUID> online) {
		for (Gathering g : this.gatherings.values()) {
			g.members.retainAll(online);
		}
		this.gatherings.values().removeIf(g -> g.members.isEmpty());
		for (UUID player : List.copyOf(this.visitOf.keySet())) {
			if (!online.contains(player)) {
				this.leave(this.visitOf.get(player), player);
			}
		}
	}

	// ---- geometry ----

	private static @Nullable WorldIndex.Zone zone(WorldIndex index, String id) {
		for (WorldIndex.Zone zone : index.zones()) {
			if (zone.id().equals(id)) {
				return zone;
			}
		}
		return null;
	}

	/** True when a player stands on a zone's dungeon entrance cell, at the zone's floor. */
	static boolean onEntrance(WorldIndex index, WorldIndex.Zone zone, Where w) {
		MapPlacement routeMap = index.map(zone.routeMap());
		if (routeMap == null || zone.dungeon() == null || Math.abs(w.y() - zone.y()) >= 2) {
			return false;
		}
		int cx = Math.floorDiv((int) Math.floor(w.x()) - routeMap.x(), Scale.CELL_BLOCKS);
		int cy = Math.floorDiv((int) Math.floor(w.z()) - routeMap.z(), Scale.CELL_BLOCKS);
		return cx == zone.dungeon().x() && cy == zone.dungeon().y();
	}

	/** The middle of the cell beside a zone's dungeon entrance (its open neighbour), at the zone's floor; null without one. */
	static double @Nullable [] outside(WorldIndex index, WorldIndex.Zone zone) {
		MapPlacement routeMap = index.map(zone.routeMap());
		WorldIndex.ZoneDungeon d = zone.dungeon();
		if (routeMap == null || d == null) {
			return null;
		}
		int cx = d.x() + d.out().dx();
		int cy = d.y() + d.out().dy();
		return new double[] {routeMap.x() + Scale.cellToBlock(cx) + 1.0, zone.y(), routeMap.z() + Scale.cellToBlock(cy) + 1.0};
	}

	/** A floor's start: PMD's player spot, or its first floor cell. */
	static Cell start(PmdFloor floor) {
		if (floor.player() != null) {
			return floor.player();
		}
		for (int y = 0; y < floor.height(); y++) {
			for (int x = 0; x < floor.width(); x++) {
				if (floor.at(x, y) == PmdFloor.Tile.FLOOR) {
					return new Cell(x, y);
				}
			}
		}
		throw new IllegalStateException("a floor without a floor cell");
	}

	// ---- views (tests, diagnostics) ----

	public int visits() {
		int n = 0;
		for (Visit visit : this.slots) {
			n += visit != null ? 1 : 0;
		}
		return n;
	}

	/** The slot of {@code player}'s visit, or -1. */
	public int slotOf(UUID player) {
		Visit visit = this.visitOf.get(player);
		return visit == null ? -1 : visit.slot;
	}

	/** The floor {@code player} stands on (0 before entering), or -1 outside every visit. */
	public int floorOf(UUID player) {
		Visit visit = this.visitOf.get(player);
		return visit == null ? -1 : visit.floorOf.getOrDefault(player, 0);
	}

	/** The plan of floor {@code floor} of {@code player}'s visit, or null. */
	public @Nullable DungeonFloorPlan plan(UUID player, int floor) {
		Visit visit = this.visitOf.get(player);
		return visit == null ? null : visit.plans.get(floor);
	}

	public int floors(UUID player) {
		Visit visit = this.visitOf.get(player);
		return visit == null ? 0 : visit.floors;
	}
}
