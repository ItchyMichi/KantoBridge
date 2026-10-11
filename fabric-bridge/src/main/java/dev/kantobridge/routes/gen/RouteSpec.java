package dev.kantobridge.routes.gen;

import java.util.Objects;

/**
 * What to generate (docs/specs/WILD_ROUTES_PLAN.md §4.1, §4.3): the shape of a zone or route, without any game data.
 * Species, items and trainer classes are chosen later from each end's game.
 *
 * @param kind           a free-roam zone (a dead end, left the way you came) or a connecting route (a far gate)
 * @param areasMin       fewest areas on the main line
 * @param areasMax       most areas on the main line (Q4: 3–4)
 * @param sideAreaChance the chance of a side area, a dead-end branch off the main line (Q4: half the time)
 * @param dungeonChance  the chance of a dungeon's entrance in a dead end (R8)
 * @param trainersMin    fewest trainers per area
 * @param trainersMax    most trainers per area
 * @param itemsMin       fewest item balls per area (DWM sets them by tier; ours are Gen 1 numbers)
 * @param itemsMax       most item balls per area, before the bonus for areas with many dead ends
 * @param levelStep      levels added per area along the main line (Q5: +1)
 * @param levelCap       the most levels added (Q5: up to +3)
 * @param bound          the most cells the areas and gates may span in each direction
 * @param theme          how blocks are drawn
 */
public record RouteSpec(Kind kind, int areasMin, int areasMax, double sideAreaChance, double dungeonChance,
		int trainersMin, int trainersMax, int itemsMin, int itemsMax, int levelStep, int levelCap, int bound, Theme theme) {
	public enum Kind {
		FREE_ROAM, CONNECTING
	}

	/** The default bound: 288 cells, 576 blocks, inside a 640-block slot with room for the scenery (§4.4). */
	public static final int DEFAULT_BOUND = 288;

	public RouteSpec {
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(theme, "theme");
		if (areasMin < 1 || areasMax < areasMin) {
			throw new IllegalArgumentException("areas " + areasMin + ".." + areasMax);
		}
		if (trainersMin < 0 || trainersMax < trainersMin || itemsMin < 0 || itemsMax < itemsMin) {
			throw new IllegalArgumentException("trainers " + trainersMin + ".." + trainersMax + ", items " + itemsMin
					+ ".." + itemsMax);
		}
		if (!(sideAreaChance >= 0 && sideAreaChance <= 1 && dungeonChance >= 0 && dungeonChance <= 1)) {
			throw new IllegalArgumentException("chances must be in [0, 1]");
		}
		if (levelStep < 0 || levelCap < 0 || bound < 2 * AreaGenerator.SIZE) {
			throw new IllegalArgumentException("levels " + levelStep + "/" + levelCap + ", bound " + bound);
		}
	}

	/** A free-roam zone with the plan's defaults (§1: Q4, Q5, R8). */
	public static RouteSpec freeRoam(Theme theme) {
		return new RouteSpec(Kind.FREE_ROAM, 3, 4, 0.5, 0.6, 1, 3, 1, 2, 1, 3, DEFAULT_BOUND, theme);
	}

	/** A connecting route with the same defaults; its levels follow {@link RouteLayout.AreaPlacement#progress()}. */
	public static RouteSpec connecting(Theme theme) {
		return new RouteSpec(Kind.CONNECTING, 3, 4, 0.5, 0.6, 1, 3, 1, 2, 1, 3, DEFAULT_BOUND, theme);
	}

	public RouteSpec withAreas(int min, int max) {
		return new RouteSpec(this.kind, min, max, this.sideAreaChance, this.dungeonChance, this.trainersMin,
				this.trainersMax, this.itemsMin, this.itemsMax, this.levelStep, this.levelCap, this.bound, this.theme);
	}

	public RouteSpec withChances(double sideArea, double dungeon) {
		return new RouteSpec(this.kind, this.areasMin, this.areasMax, sideArea, dungeon, this.trainersMin,
				this.trainersMax, this.itemsMin, this.itemsMax, this.levelStep, this.levelCap, this.bound, this.theme);
	}
}
