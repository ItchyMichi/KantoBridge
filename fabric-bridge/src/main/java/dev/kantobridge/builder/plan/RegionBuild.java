package dev.kantobridge.builder.plan;

import dev.kantobridge.world.Games;
import dev.kantobridge.world.RegionRecord;
import dev.kantobridge.world.RegionTable;
import dev.kantobridge.world.SlotMap;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * What the planner needs to know about the region a build makes (docs/specs/MULTI_GAME_PLAN.md §4.6, MG3): its id and
 * slot, the art set its slot-map keys carry (its game's), the family range of the one world-level slot map its keys
 * are assigned in, the slot map the world had before (so every other region's entries stay untouched), and the prefix
 * its habitat pool ids take. A legacy build has none of this (null): the planner then works exactly as before regions.
 *
 * @param id              the region id
 * @param slot            its slot in the region table
 * @param artSet          the art set of its keys ({@link Games#artSet} of the bundle's game)
 * @param families        the family range its tiles use
 * @param previousSlotMap the world's slot map before this build (version 1 only for the legacy region; null for none)
 * @param poolPrefix      what its habitat pool ids start with after {@code kantobridge:} ({@link RegionRecord#poolPrefix})
 */
public record RegionBuild(String id, int slot, String artSet, SlotMap.Range families, @Nullable SlotMap previousSlotMap, String poolPrefix) {
	public RegionBuild {
		if (id == null || !Games.REGION_ID.matcher(id).matches()) {
			throw new IllegalArgumentException("invalid region id");
		}
		if (slot < 0 || slot > RegionTable.MAX_SLOT) {
			throw new IllegalArgumentException("slot out of range");
		}
		if (artSet == null || !Games.GAME_ID.matcher(artSet).matches()) {
			throw new IllegalArgumentException("invalid art set");
		}
		Objects.requireNonNull(families, "families");
		Objects.requireNonNull(poolPrefix, "poolPrefix");
	}

	/** The pool prefix of a region at {@code slot}: nothing at slot 0 (the legacy region's ids stay), {@code <id>_} otherwise. */
	public static String poolPrefix(String id, int slot) {
		return slot == 0 ? "" : id + "_";
	}
}
