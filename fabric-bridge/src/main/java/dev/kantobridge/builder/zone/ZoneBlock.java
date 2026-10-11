package dev.kantobridge.builder.zone;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The vanilla blocks a Wild Routes zone is built from (docs/specs/WILD_ROUTES_PLAN.md §4.4, the forest theme): the
 * land, the maze's hedges and trees, ponds and their barriers. Planned as {@code BlockCode.vanilla(block)}; the
 * ordinal is the code's value, so new blocks go at the end.
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
	GRAVEL;

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
		};
	}

	private static BlockState persistent(BlockState leaves) {
		return leaves.setValue(LeavesBlock.PERSISTENT, true);
	}
}
