package dev.kantobridge.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * The story cell of a swap site's cell (a Cut tree, a card-key door, a scripted swap; docs/PLAN.md §4.3
 * {@code StoryCellRenderer}). It collides exactly like {@link StoryCellBlock}; its block entity (its own type) carries
 * the cell's key. From the voxel plan's V5 it is a model block: the client's runtime model draws the bottom of the cell's
 * closed art here for players who still see the cell closed, in the chunk mesh (the {@code decor} carriers above it draw
 * the rest); the block-entity renderer draws only for a world built before V5.
 */
public class StoryTileBlock extends StoryCellBlock {
	public static final MapCodec<StoryTileBlock> CODEC = simpleCodec(StoryTileBlock::new);

	public StoryTileBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return StoryCellBlockEntity.tile(pos, state);
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}
}
