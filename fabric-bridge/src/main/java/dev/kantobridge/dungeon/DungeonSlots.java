package dev.kantobridge.dungeon;

import dev.kantobridge.builder.plan.Box3;
import dev.kantobridge.world.WorldIndex;
import org.jetbrains.annotations.Nullable;

/**
 * Where dungeon floors go (docs/specs/WILD_ROUTES_PLAN.md §4.8, WR7): a region {@link #GAP} blocks north of Kanto's
 * surface box, cut into {@link #SLOTS} slots of {@link #SLOT_W}×{@link #SLOT_D} blocks, {@link #PER_ROW} to a row, east
 * from Kanto's west edge. A visit takes a slot; its floors stack {@link #FLOOR_STEP} blocks apart, floor 1 at Kanto's
 * walking level. Plain Java.
 */
public final class DungeonSlots {
	/** A slot: a PMD floor (56×32 cells, 112×64 blocks) and a margin of 8 blocks. */
	public static final int SLOT_W = 128;
	public static final int SLOT_D = 80;
	public static final int PER_ROW = 8;
	public static final int ROWS = 2;
	public static final int SLOTS = PER_ROW * ROWS;
	/** Blocks between Kanto's surface box and the region: farther than any render distance. */
	public static final int GAP = 3000;
	/** Feet levels of a visit's floors are this far apart. */
	public static final int FLOOR_STEP = 22;
	/** Floor 1's feet level: Kanto's walking level. */
	public static final int FIRST_FEET = 64;
	/** A floor's blocks sit inside the slot this far from its north-west corner. */
	public static final int MARGIN = 8;

	private DungeonSlots() {
	}

	/** The region for a build whose Kanto box is {@code kanto} (or, without one, its origin). */
	public static WorldIndex.Dungeons region(@Nullable Box3 kanto, int fallbackX, int fallbackZ) {
		int w = PER_ROW * SLOT_W;
		int d = ROWS * SLOT_D;
		int x;
		int z;
		if (kanto == null) {
			x = Math.floorDiv(fallbackX, 16) * 16;
			z = Math.floorDiv(fallbackZ - GAP - d, 16) * 16;
		} else {
			// whole chunks, so a slot never shares a chunk with Kanto
			x = Math.floorDiv(kanto.minX(), 16) * 16;
			z = Math.floorDiv(kanto.minZ() - GAP - d, 16) * 16;
		}
		return new WorldIndex.Dungeons(x, FIRST_FEET, z, w, d);
	}

	/** The north-west block (x, z) of slot {@code index} in {@code region}. */
	public static int[] slotOrigin(WorldIndex.Dungeons region, int index) {
		if (index < 0 || index >= SLOTS) {
			throw new IllegalArgumentException("no such slot: " + index);
		}
		return new int[] {region.x() + (index % PER_ROW) * SLOT_W, region.z() + (index / PER_ROW) * SLOT_D};
	}

	/** The slot whose box holds block column (bx, bz), or -1. */
	public static int slotAt(WorldIndex.Dungeons region, int bx, int bz) {
		if (!region.containsColumn(bx, bz)) {
			return -1;
		}
		return ((bz - region.z()) / SLOT_D) * PER_ROW + (bx - region.x()) / SLOT_W;
	}

	/** Floor {@code floor}'s feet level (1 is the first). */
	public static int feet(WorldIndex.Dungeons region, int floor) {
		return region.y() + FLOOR_STEP * (floor - 1);
	}
}
