package dev.kantobridge.builder.zone;

import dev.kantobridge.builder.plan.Box3;
import org.jetbrains.annotations.Nullable;

/**
 * Where zones go (docs/specs/WILD_ROUTES_PLAN.md §4.4, §4.10): the wild zone starts {@link #GAP} blocks south of
 * Kanto's surface box and is cut into slots of {@link #SLOT} blocks, {@link #PER_ROW} to a row, east from Kanto's west
 * edge. North of Kanto is where dungeon slots will go (WR7); west and east are kept for other games' worlds.
 */
public final class ZoneRegions {
	/** A slot's side in blocks (40 chunks): one zone, its gates and its land. */
	public static final int SLOT = 640;
	/** Blocks between Kanto's surface box and the wild zone: farther than any render distance. */
	public static final int GAP = 3000;
	public static final int PER_ROW = 8;

	private final int originX;
	private final int originZ;

	private ZoneRegions(int originX, int originZ) {
		this.originX = originX;
		this.originZ = originZ;
	}

	/**
	 * @param kanto     Kanto's surface box, or null when the build has none (then the fallback origin stands in for it)
	 * @param fallbackX the build's origin X
	 * @param fallbackZ the build's origin Z
	 */
	public static ZoneRegions of(@Nullable Box3 kanto, int fallbackX, int fallbackZ) {
		if (kanto == null) {
			return new ZoneRegions(Math.floorDiv(fallbackX, 16) * 16, Math.floorDiv(fallbackZ + GAP, 16) * 16);
		}
		// whole chunks, so a slot never shares a chunk with Kanto or another slot
		return new ZoneRegions(Math.floorDiv(kanto.minX(), 16) * 16, Math.floorDiv(kanto.maxZ() + GAP + 15, 16) * 16);
	}

	/** The north-west block (x, z) of slot {@code index}. */
	public int[] slotOrigin(int index) {
		if (index < 0) {
			throw new IllegalArgumentException("negative slot");
		}
		return new int[] {this.originX + (index % PER_ROW) * SLOT, this.originZ + (index / PER_ROW) * SLOT};
	}
}
