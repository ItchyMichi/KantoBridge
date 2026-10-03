package dev.kantobridge.world;

/**
 * The fixed mapping between Gen 1 units and Minecraft blocks (docs/PLAN.md §2.2, defaults P1 and P4).
 *
 * <ul>
 * <li>A 16-px cell (the walk grid) is 2×2 blocks; each block shows one 8×8 tile on its top face.</li>
 * <li>A Gen 1 block (32 px, the unit of map sizes and connection offsets) is 4×4 blocks.</li>
 * <li>The outdoor walking surface (feet level) is Y 64, so outdoor ground blocks sit at Y 63.</li>
 * <li>Interior layer {@code k ≥ 1} has its walking surface at Y {@code 64 - 22k}.</li>
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
	/** Vertical distance between interior layers. */
	public static final int LAYER_STEP = 22;

	private Scale() {
	}

	/** Feet level of layer {@code layer} (0 is the surface). */
	public static int feetY(int layer) {
		return SURFACE_Y - LAYER_STEP * layer;
	}

	/**
	 * The deepest layer whose floor block (feet level - 1) still lies inside a world whose lowest block is
	 * {@code minBuildHeight}: 14 for the deep overworld (P4, min Y -256).
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
