package dev.kantobridge.world;

/**
 * The fixed mapping between Gen 1 units and Minecraft blocks (docs/PLAN.md §2.2, defaults P1 and P4).
 *
 * <ul>
 * <li>A 16-px cell (the walk grid) is 2×2 blocks; each block shows one 8×8 tile on its top face.</li>
 * <li>A Gen 1 block (32 px, the unit of map sizes and connection offsets) is 4×4 blocks.</li>
 * <li>The outdoor walking surface (feet level) is Y 64, so outdoor ground blocks sit at Y 63.</li>
 * <li>Interior layer {@code k ≥ 1} has its walking surface at Y {@code 64 - 29k} (docs/specs/HEIGHTS_ALL_GAMES_PLAN.md D5;
 * 22 before the 3D heights plan).</li>
 * </ul>
 */
public final class Scale {
	/** Blocks per cell edge. */
	public static final int CELL_BLOCKS = 2;
	/** Blocks per Gen 1 block edge (a Gen 1 block is 2×2 cells). */
	public static final int GEN1_BLOCK_BLOCKS = 4;
	/** Tiles per Gen 1 block edge. */
	public static final int GEN1_BLOCK_TILES = 4;
	/** Feet level of the outdoor walking surface. */
	public static final int SURFACE_Y = 64;
	/** Y of the outdoor ground blocks. */
	public static final int GROUND_Y = SURFACE_Y - 1;
	/** The most blocks of air a room on a layer has over its feet level (its ceiling's level; HEIGHTS_ALL_GAMES_PLAN D5). */
	public static final int LAYER_ROOM = 11;
	/**
	 * Vertical distance between interior layers: a layer's tallest room ({@link #LAYER_ROOM}), its ceiling and floor blocks,
	 * and 16 blocks of rock between spaces (DESIGN.md's separation). One step for every layer, so a wild Pokémon's floor
	 * is always {@code 63 - 29k} (the spawn rule {@code walking_level_only}).
	 */
	public static final int LAYER_STEP = LAYER_ROOM + 2 + 16;

	private Scale() {
	}

	/** Feet level of layer {@code layer} (0 is the surface). */
	public static int feetY(int layer) {
		return SURFACE_Y - LAYER_STEP * layer;
	}

	/**
	 * The deepest layer whose floor block (feet level - 1) still lies inside a world whose lowest block is
	 * {@code minBuildHeight}: 14 for the deep overworld (P4, min Y -352 since the 3D heights plan's D5).
	 */
	public static int maxLayer(int minBuildHeight) {
		int max = Math.floorDiv(SURFACE_Y - 1 - minBuildHeight, LAYER_STEP);
		return Math.max(0, max);
	}

	/** Block offset of a cell coordinate (the cell's north-west block, relative to its map origin). */
	public static int cellToBlock(int cell) {
		return cell * CELL_BLOCKS;
	}

	/** The cell holding a block offset (relative to its map origin); floors for negative offsets. */
	public static int blockToCell(int block) {
		return Math.floorDiv(block, CELL_BLOCKS);
	}
}
