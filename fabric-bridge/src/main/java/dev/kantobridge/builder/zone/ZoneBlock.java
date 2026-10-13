package dev.kantobridge.builder.zone;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The vanilla blocks a Wild Routes zone is built from (docs/specs/WILD_ROUTES_PLAN.md §4.4, the forest theme): the
 * land, the maze's hedges and trees, ponds and their barriers, vines, and (MG5) a connecting route's gate house. Planned
 * as {@code BlockCode.vanilla(block)}; the ordinal is the code's value, so new blocks go at the end.
 */
public enum ZoneBlock {
	STONE,
	DIRT,
	GRASS_BLOCK,
	DIRT_PATH,
	COARSE_DIRT,
	COBBLESTONE,
	MOSSY_COBBLESTONE,
	OAK_LOG,
	OAK_LEAVES,
	BIRCH_LOG,
	BIRCH_LEAVES,
	DARK_OAK_LOG,
	DARK_OAK_LEAVES,
	WATER,
	SHORT_GRASS,
	FERN,
	DANDELION,
	POPPY,
	AZURE_BLUET,
	OXEYE_DAISY,
	/** Invisible and solid: keeps you out of a pond, as Kanto's water does until Surf. */
	BARRIER,
	MOSS_BLOCK,
	GRAVEL,
	/**
	 * Vines (the caves and woods plan's CW4), each hanging on the leaves to its north, east, south or west; never lower
	 * than feet + 2 beside the maze, where a player who can't jump never reaches one to climb.
	 */
	VINE_NORTH,
	VINE_EAST,
	VINE_SOUTH,
	VINE_WEST,
	/**
	 * The gate house at a connecting route's far gate (docs/specs/MULTI_GAME_PLAN.md §4.8, D12; MG5): plank walls with
	 * glass-pane windows under a dark plank roof.
	 */
	OAK_PLANKS,
	DARK_OAK_PLANKS,
	GLASS_PANE;

	/** The block state; only called on the server (the plan itself never needs Minecraft's registries). */
	public BlockState state() {
		return switch (this) {
			case STONE -> Blocks.STONE.defaultBlockState();
			case DIRT -> Blocks.DIRT.defaultBlockState();
			case GRASS_BLOCK -> Blocks.GRASS_BLOCK.defaultBlockState();
			case DIRT_PATH -> Blocks.DIRT_PATH.defaultBlockState();
			case COARSE_DIRT -> Blocks.COARSE_DIRT.defaultBlockState();
			case COBBLESTONE -> Blocks.COBBLESTONE.defaultBlockState();
			case MOSSY_COBBLESTONE -> Blocks.MOSSY_COBBLESTONE.defaultBlockState();
			case OAK_LOG -> Blocks.OAK_LOG.defaultBlockState();
			case OAK_LEAVES -> persistent(Blocks.OAK_LEAVES.defaultBlockState());
			case BIRCH_LOG -> Blocks.BIRCH_LOG.defaultBlockState();
			case BIRCH_LEAVES -> persistent(Blocks.BIRCH_LEAVES.defaultBlockState());
			case DARK_OAK_LOG -> Blocks.DARK_OAK_LOG.defaultBlockState();
			case DARK_OAK_LEAVES -> persistent(Blocks.DARK_OAK_LEAVES.defaultBlockState());
			case WATER -> Blocks.WATER.defaultBlockState();
			case SHORT_GRASS -> Blocks.SHORT_GRASS.defaultBlockState();
			case FERN -> Blocks.FERN.defaultBlockState();
			case DANDELION -> Blocks.DANDELION.defaultBlockState();
			case POPPY -> Blocks.POPPY.defaultBlockState();
			case AZURE_BLUET -> Blocks.AZURE_BLUET.defaultBlockState();
			case OXEYE_DAISY -> Blocks.OXEYE_DAISY.defaultBlockState();
			case BARRIER -> Blocks.BARRIER.defaultBlockState();
			case MOSS_BLOCK -> Blocks.MOSS_BLOCK.defaultBlockState();
			case GRAVEL -> Blocks.GRAVEL.defaultBlockState();
			case VINE_NORTH -> Blocks.VINE.defaultBlockState().setValue(VineBlock.NORTH, true);
			case VINE_EAST -> Blocks.VINE.defaultBlockState().setValue(VineBlock.EAST, true);
			case VINE_SOUTH -> Blocks.VINE.defaultBlockState().setValue(VineBlock.SOUTH, true);
			case VINE_WEST -> Blocks.VINE.defaultBlockState().setValue(VineBlock.WEST, true);
			case OAK_PLANKS -> Blocks.OAK_PLANKS.defaultBlockState();
			case DARK_OAK_PLANKS -> Blocks.DARK_OAK_PLANKS.defaultBlockState();
			case GLASS_PANE -> Blocks.GLASS_PANE.defaultBlockState();
		};
	}

	/** The vine hanging on a block in direction {@code d}: 0 north, 1 east, 2 south, 3 west. */
	public static ZoneBlock vineOn(int d) {
		return switch (d) {
			case 0 -> VINE_NORTH;
			case 1 -> VINE_EAST;
			case 2 -> VINE_SOUTH;
			default -> VINE_WEST;
		};
	}

	private static BlockState persistent(BlockState leaves) {
		return leaves.setValue(LeavesBlock.PERSISTENT, true);
	}
}
