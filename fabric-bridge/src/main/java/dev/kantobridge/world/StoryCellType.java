package dev.kantobridge.world;

import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * What decides whether a story cell is closed for a player (docs/PLAN.md §4.2, §4.5 "Story cells"; protocol
 * WorldIndex {@code storyCells[].type}).
 */
public enum StoryCellType {
	/** A STAY object (person, item ball, static Pokémon, guard) stands here while it is visible. */
	OBJECT,
	/** A runtime block swap (Cut tree, card-key door, scripted swap) keeps this cell closed until it happens. */
	TILE,
	/** Water: closed unless the player is surfing. */
	WATER,
	/** A walkable cell of a map with Strength boulders: closed where a boulder stands. */
	BOULDER_AREA,
	/**
	 * A Wild Routes item ball (docs/specs/WILD_ROUTES_PLAN.md §4.6): closed until the player's route progress says it
	 * was taken. Its cell is in the zone's route map's grid; {@code obj} names the ball.
	 */
	ROUTE,
	/**
	 * A connecting route's pass (docs/specs/MULTI_GAME_PLAN.md §4.8, MG5): the far doorway of a half's gate house, closed
	 * for a player while the far region isn't built or they have no link to its game ({@code pass_closed}). Its cell is
	 * in the zone's route map's grid; {@code obj} names the zone.
	 */
	PASS;

	public String id() {
		return this.name().toLowerCase(Locale.ROOT);
	}

	public static @Nullable StoryCellType byId(String id) {
		for (StoryCellType type : values()) {
			if (type.id().equals(id)) {
				return type;
			}
		}
		return null;
	}
}
