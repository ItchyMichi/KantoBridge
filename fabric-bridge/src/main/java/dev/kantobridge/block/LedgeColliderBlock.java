package dev.kantobridge.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A one-way ledge (docs/PLAN.md P3, §4.2). {@code FACING} is the downhill direction, the way a Gen 1 ledge is hopped.
 *
 * <p>It is a 24-px wall only for an entity whose bounding box lies entirely on the downhill side, so nobody climbs
 * back up; from the uphill side it is empty, so Minecraft lets the player move down across it while the hidden game
 * performs the hop. With no entity (pathfinding, shape caches) it is the wall, which also marks the shape as larger
 * than a block so collision checks look at it from a block away (refs/_research/P5_mc_content_testing.md §1.4, §1.6).
 *
 * <p>Invisible, no outline, no occlusion: the ledge art is on the tile block below.
 */
public class LedgeColliderBlock extends HorizontalDirectionalBlock {
	public static final MapCodec<LedgeColliderBlock> CODEC = simpleCodec(LedgeColliderBlock::new);
	/** 1.5 blocks: higher than the 0.6 step height and than a jump can clear. */
	public static final VoxelShape WALL = Block.box(0, 0, 0, 16, 24, 16);
	private static final double EPSILON = 1.0E-7;

	public LedgeColliderBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.SOUTH));
	}

	@Override
	protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.INVISIBLE;
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
		Entity entity = context instanceof EntityCollisionContext entityContext ? entityContext.getEntity() : null;
		if (entity == null) {
			return WALL;
		}
		return entirelyDownhill(entity.getBoundingBox(), pos, state.getValue(FACING)) ? WALL : Shapes.empty();
	}

	/** True when {@code box} lies wholly beyond the block at {@code pos} in the {@code downhill} direction. */
	public static boolean entirelyDownhill(AABB box, BlockPos pos, Direction downhill) {
		return switch (downhill) {
			case SOUTH -> box.minZ >= pos.getZ() + 1 - EPSILON;
			case NORTH -> box.maxZ <= pos.getZ() + EPSILON;
			case EAST -> box.minX >= pos.getX() + 1 - EPSILON;
			case WEST -> box.maxX <= pos.getX() + EPSILON;
			default -> false;
		};
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
