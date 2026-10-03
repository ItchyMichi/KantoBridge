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
	BOULDER_AREA;

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
