package dev.kantobridge.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A seat (docs/specs/HAND_MODELS.md section 6): a stool or a chair the hidden game draws on a walkable cell, where
 * characters stand. Half a block high, so a player steps onto it with Minecraft's 0.6-block step and no jump, and a
 * puppet on the cell stands on it (the client's top height counts it as half a block).
 *
 * <p>Invisible itself, with no outline and no occlusion: a model block (a carrier), so the client's runtime model draws
 * the seat unit's art the world's shape map gives its position, as for the step collider.
 */
public class SeatColliderBlock extends Block {
	public static final MapCodec<SeatColliderBlock> CODEC = simpleCodec(SeatColliderBlock::new);
	/** The seat's collision: the lower half of the block. */
	public static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 8, 16);

	public SeatColliderBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return Shapes.empty();
	}

	@Override
	protected VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return Shapes.empty();
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
		return 1.0F;
	}

	@Override
	protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
		return true;
	}
}
