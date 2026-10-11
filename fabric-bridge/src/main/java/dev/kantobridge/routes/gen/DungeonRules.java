package dev.kantobridge.routes.gen;

/**
 * How big a dungeon is and how its Pokémon grow on the way down (docs/specs/WILD_ROUTES_PLAN.md §4.8, Q6, D5, D6).
 *
 * <p>Floors: random on every visit, 3–5 for a dungeon whose wild Pokémon are under level 30 (the Viridian zone's are
 * far below); both ends rise by one for every full 10 levels above 20, up to 7–9:
 *
 * <pre>
 * highest wild level   floors
 *  1–29                3–5
 * 30–39                4–6
 * 40–49                5–7
 * 50–59                6–8
 * 60+                  7–9
 * </pre>
 *
 * <p>Pokémon: a level stronger every second floor.
 */
public final class DungeonRules {
	/** Floors at the lowest levels. */
	public static final int MIN_FLOORS = 3;
	public static final int MAX_FLOORS = 5;
	/** The most the range rises. */
	public static final int MAX_RISE = 4;

	/** An inclusive range of floor counts. */
	public record FloorRange(int min, int max) {
	}

	private DungeonRules() {
	}

	/** The floor counts a dungeon can have when its wild Pokémon reach {@code wildLevel}. */
	public static FloorRange floorRange(int wildLevel) {
		int rise = wildLevel < 30 ? 0 : Math.min(MAX_RISE, (wildLevel - 20) / 10);
		return new FloorRange(MIN_FLOORS + rise, MAX_FLOORS + rise);
	}

	/** One visit's floor count, drawn from {@link #floorRange}. */
	public static int floorCount(RouteRng rng, int wildLevel) {
		FloorRange range = floorRange(wildLevel);
		return rng.between(range.min(), range.max());
	}

	/** Levels added to the dungeon's Pokémon on floor {@code floor} (1 is the first): 0, 0, 1, 1, 2, 2, … */
	public static int levelBonus(int floor) {
		if (floor < 1) {
			throw new IllegalArgumentException("floors count from 1: " + floor);
		}
		return (floor - 1) / 2;
	}
}
