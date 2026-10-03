package dev.kantobridge.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * The story cell of a swap site's cell (a Cut tree, a card-key door, a scripted swap; docs/PLAN.md §4.3
 * {@code StoryCellRenderer}). It collides exactly like {@link StoryCellBlock}; its block entity has its own type so the
 * client's renderer, which draws the closed art for players who still see the cell closed, runs only for these few
 * cells.
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
}
