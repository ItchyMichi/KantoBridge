package dev.kantobridge.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * {@code decor} and {@code decor_leaves}: visual-only geometry in positions that are air today (grass tufts, eaves,
 * foliage; docs/specs/VOXEL_BLOCKS_PLAN.md §3.3). No collision and no outline, so nothing about walking or the
 * crosshair changes; no occlusion; sky light passes. It draws only what the world's shape map gives its position.
 * {@link #LIT} makes it give an interior light's light, where a hanging takes that light's position (CW1, CD15).
 */
public class DecorBlock extends Block {
	public static final MapCodec<DecorBlock> CODEC = simpleCodec(DecorBlock::new);
	/** Gives light level {@link #LIGHT}, as the interior light it stands in for. */
	public static final BooleanProperty LIT = BlockStateProperties.LIT;
	/** The light a lit carrier gives: the interiors' light level. */
	public static final int LIGHT = 15;

	public DecorBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(LIT, false));
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(LIT);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return Shapes.empty();
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return Shapes.empty();
	}

	@Override
	protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
		return true;
	}

	@Override
	protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
		return 1.0F;
	}
}
