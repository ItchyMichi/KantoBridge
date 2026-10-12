package dev.kantobridge.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A cave shaft's ladder (docs/specs/CAVES_3D_PLAN.md §3.5, HD6): Minecraft's ladder, looks and all, in the
 * {@code climbable} tag, but standing without the wall behind it, which the builder may not have put there yet and which
 * nothing can break. {@code FACING} points away from its wall, as a ladder's does.
 */
public class ShaftLadderBlock extends LadderBlock {
	public static final MapCodec<LadderBlock> CODEC = simpleCodec(ShaftLadderBlock::new);

	public ShaftLadderBlock(Properties properties) {
		super(properties);
	}

	@Override
	public MapCodec<LadderBlock> codec() {
		return CODEC;
	}

	@Override
	protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
		return true;
	}

	@Override
	protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
		return state;
	}
}
