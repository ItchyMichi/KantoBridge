package dev.kantobridge.dungeon;

import dev.kantobridge.builder.zone.ZoneObjects;
import dev.kantobridge.routes.gen.Cell;
import dev.kantobridge.routes.gen.DungeonRules;
import dev.kantobridge.routes.gen.PmdFloor;
import dev.kantobridge.routes.gen.RouteRng;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * What a dungeon floor holds (docs/specs/WILD_ROUTES_PLAN.md §4.8, WR7), without Minecraft.
 *
 * <ul>
 * <li><b>Pokémon</b> at PMD's enemy spots, from the dungeon's pool by weight, at the pool's level plus
 * {@code DungeonRules.levelBonus} for the floor. A Monster House fills when someone first steps in: {@value #HOUSE_MIN}–
 * {@value #HOUSE_MAX} more appear on its free cells within {@value #HOUSE_NEAR} of them, as in PMD. One more appears
 * every {@value #LATER_EVERY_TICKS} ticks at least {@value #LATER_AWAY} cells from every player, up to
 * {@value #LATER_CAP} alive on a floor (the service keeps count).</li>
 * <li><b>Items</b> at PMD's item spots, and on the last floor a treasure of {@value #TREASURE} more on the floor cells
 * nearest the exit. Each from the item tiers the floor's depth draws on (deeper, better), kept to the dungeon's allowed
 * list (the game's own, never a Rare Candy or Master Ball), with at most one Nugget a visit. Ids are
 * {@code v<visit>.f<floor>.i<n>}: given with {@code keep: false}, never recorded.</li>
 * </ul>
 */
public final class DungeonContents {
	public static final String NUGGET = "NUGGET";
	public static final int TREASURE = 4;
	/** A Monster House's Pokémon: 8–11, PMD's count. */
	public static final int HOUSE_MIN = 8;
	public static final int HOUSE_MAX = 11;
	/** How far from whoever sets a Monster House off its Pokémon appear, in cells either way (a square, as PMD counts). */
	public static final int HOUSE_NEAR = 4;
	public static final int LATER_EVERY_TICKS = 600;
	public static final int LATER_CAP = 10;
	public static final int LATER_AWAY = 8;

	/** One entry of the dungeon's pool: a Cobblemon species id, its level and weight. */
	public record Mon(String species, int level, double weight) {
	}

	public record Spawn(Cell cell, String species, int level) {
	}

	public record Item(Cell cell, String id, String item) {
	}

	/** A floor's Pokémon and items, and whether it holds the visit's Nugget. */
	public record Floor(List<Spawn> spawns, List<Item> items, boolean nugget) {
	}

	private DungeonContents() {
	}

	/**
	 * @param nuggetPlaced the visit already has its Nugget on an earlier floor
	 */
	public static Floor make(DungeonFloorPlan plan, int floor, long visit, List<Mon> pool, List<String> allowed, ZoneObjects.ItemTiers tiers,
			long seed, boolean nuggetPlaced) {
		RouteRng rng = new RouteRng(RouteRng.mix(seed ^ 0x636F6E74656E7473L));
		PmdFloor pmd = plan.floor();
		List<Spawn> spawns = new ArrayList<>();
		if (!pool.isEmpty()) {
			for (Cell cell : pmd.enemies()) {
				spawns.add(pick(cell, pool, floor, rng));
			}
		}
		List<Cell> cells = new ArrayList<>(pmd.items());
		if (plan.last() && pmd.stairs() != null) {
			cells.addAll(treasure(pmd, cells));
		}
		List<String> choices = new ArrayList<>(tiers.forLevelOffset(Math.min(3, (floor + 1) / 2)));
		choices.retainAll(allowed);
		if (choices.isEmpty()) {
			choices.addAll(allowed);
		}
		boolean nugget = nuggetPlaced;
		List<Item> items = new ArrayList<>();
		for (Cell cell : cells) {
			List<String> now = new ArrayList<>(choices);
			if (nugget) {
				now.remove(NUGGET);
			}
			if (now.isEmpty()) {
				break;
			}
			String item = rng.pick(now);
			nugget |= item.equals(NUGGET);
			items.add(new Item(cell, "v" + visit + ".f" + floor + ".i" + (items.size() + 1), item));
		}
		return new Floor(spawns, items, nugget && !nuggetPlaced);
	}

	/** A Pokémon of the pool for a cell of floor {@code floor}. */
	public static Spawn pick(Cell cell, List<Mon> pool, int floor, RouteRng rng) {
		double total = 0;
		for (Mon mon : pool) {
			total += mon.weight();
		}
		double roll = rng.nextDouble() * total;
		Mon chosen = pool.get(pool.size() - 1);
		for (Mon mon : pool) {
			roll -= mon.weight();
			if (roll < 0) {
				chosen = mon;
				break;
			}
		}
		return new Spawn(cell, chosen.species(), Math.min(100, chosen.level() + DungeonRules.levelBonus(floor)));
	}

	/** The last floor's treasure: the floor cells nearest the exit (not the exit, nor an item spot). */
	static List<Cell> treasure(PmdFloor pmd, List<Cell> taken) {
		Cell exit = pmd.stairs();
		Set<Cell> skip = new HashSet<>(taken);
		skip.add(exit);
		List<Cell> floor = new ArrayList<>();
		for (int y = 0; y < pmd.height(); y++) {
			for (int x = 0; x < pmd.width(); x++) {
				Cell c = new Cell(x, y);
				if (pmd.at(c) == PmdFloor.Tile.FLOOR && !skip.contains(c)) {
					floor.add(c);
				}
			}
		}
		floor.sort(Comparator.<Cell>comparingInt(c -> Math.abs(c.x() - exit.x()) + Math.abs(c.y() - exit.y())).thenComparing(c -> c));
		return floor.subList(0, Math.min(TREASURE, floor.size()));
	}

	/** The Monster House's floor cells (empty on a floor without one). */
	public static List<Cell> house(PmdFloor pmd) {
		List<Cell> out = new ArrayList<>();
		for (int y = 0; y < pmd.height(); y++) {
			for (int x = 0; x < pmd.width(); x++) {
				if (pmd.house()[y * pmd.width() + x] && pmd.at(x, y) == PmdFloor.Tile.FLOOR) {
					out.add(new Cell(x, y));
				}
			}
		}
		return out;
	}

	/**
	 * Where a Monster House's Pokémon appear when someone standing on {@code by} sets it off: {@value #HOUSE_MIN}–
	 * {@value #HOUSE_MAX} different cells of {@code house} within {@value #HOUSE_NEAR} of {@code by}, none in
	 * {@code taken} (where players and Pokémon stand) nor {@code by} itself; fewer when that's all the room there is.
	 */
	public static List<Cell> houseSpots(List<Cell> house, Cell by, Collection<Cell> taken, RouteRng rng) {
		int count = rng.between(HOUSE_MIN, HOUSE_MAX);
		List<Cell> free = new ArrayList<>();
		for (Cell c : house) {
			if (Math.max(Math.abs(c.x() - by.x()), Math.abs(c.y() - by.y())) <= HOUSE_NEAR && !c.equals(by) && !taken.contains(c)) {
				free.add(c);
			}
		}
		rng.shuffle(free);
		return List.copyOf(free.subList(0, Math.min(count, free.size())));
	}

	/** A floor cell at least {@value #LATER_AWAY} cells from every player, for a Pokémon that appears later; null if none. */
	public static @Nullable Cell later(PmdFloor pmd, List<Cell> players, RouteRng rng) {
		List<Cell> spots = new ArrayList<>();
		for (int y = 0; y < pmd.height(); y++) {
			for (int x = 0; x < pmd.width(); x++) {
				if (pmd.at(x, y) != PmdFloor.Tile.FLOOR) {
					continue;
				}
				boolean far = true;
				for (Cell p : players) {
					far &= Math.abs(p.x() - x) + Math.abs(p.y() - y) >= LATER_AWAY;
				}
				if (far) {
					spots.add(new Cell(x, y));
				}
			}
		}
		return spots.isEmpty() ? null : rng.pick(spots);
	}
}
