package dev.kantobridge.builder.export;

/**
 * Per-cell flag bits of the map export ({@code map.cells}; docs/PROTOCOL.md "Export resources"). gen1-bridge
 * computes them with the engine's own map methods, so the builder never copies engine constants.
 */
public final class CellFlags {
	public static final int WALKABLE = 1;
	public static final int WATER = 2;
	public static final int SHORE = 4;
	public static final int GRASS = 8;
	public static final int DOOR = 16;
	public static final int WARP_TILE = 32;
	public static final int COUNTER = 64;
	public static final int CUT_TREE = 128;
	public static final int PAD = 256;
	public static final int HOLE = 512;
	public static final int SPINNER = 1024;
	public static final int WARP = 2048;
	public static final int SIGN = 4096;
	public static final int LEDGE = 8192;
	/** Bits 14-15: the ledge hop direction, 0 down, 1 up, 2 left, 3 right. */
	public static final int LEDGE_DIR_SHIFT = 14;
	/** Gen 3: the game refuses a bike onto the cell and lets a walker on (FA8 item 10: long grass, hot springs, ...). */
	public static final int NO_BIKE = 1 << 16;
	/** Gen 2: a ledge with a second facing (FA8 item 17), its direction in bits 17-18 as {@link #LEDGE_DIR_SHIFT}'s. */
	public static final int LEDGE2 = 1 << 19;
	public static final int LEDGE2_DIR_SHIFT = 17;

	private CellFlags() {
	}

	public static boolean has(int flags, int bit) {
		return (flags & bit) != 0;
	}

	/** The hop direction of a ledge cell: 0 down, 1 up, 2 left, 3 right. */
	public static int ledgeDir(int flags) {
		return (flags >> LEDGE_DIR_SHIFT) & 3;
	}

	/** A Gen 2 ledge's second facing, or -1 for none. */
	public static int ledgeDir2(int flags) {
		return (flags & LEDGE2) == 0 ? -1 : (flags >> LEDGE2_DIR_SHIFT) & 3;
	}
}
