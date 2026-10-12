package dev.kantobridge.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Two half steps of a cave's stairs (docs/specs/CAVES_3D_PLAN.md §3.4, H4): a stairs cell climbs its plateau's 2 blocks
 * in four half-block steps, two per block, so a player walks up them with Minecraft's 0.6-block step and no jump.
 * {@code FACING} is the way the stairs climb: the half on the downhill side is 8 px high, the uphill half 16 px. The
 * cell's lower block holds one; its upper block stands on a full block and holds the other.
 *
 * <p>Invisible itself, with no outline and no occlusion: a model block (a carrier), so the client's runtime model draws
 * the stair unit's art the world's shape map gives its position, as for the ledge collider.
 */
public class StepColliderBlock extends HorizontalDirectionalBlock {
	public static final MapCodec<StepColliderBlock> CODEC = simpleCodec(StepColliderBlock::new);
	private static final VoxelShape[] SHAPES = new VoxelShape[4];

	static {
		for (Direction climb : Direction.Plane.HORIZONTAL) {
			VoxelShape low = switch (climb) {
				case EAST -> Block.box(0, 0, 0, 8, 8, 16);
				case WEST -> Block.box(8, 0, 0, 16, 8, 16);
				case SOUTH -> Block.box(0, 0, 0, 16, 8, 8);
				default -> Block.box(0, 0, 8, 16, 8, 16);
			};
			VoxelShape high = switch (climb) {
				case EAST -> Block.box(8, 0, 0, 16, 16, 16);
				case WEST -> Block.box(0, 0, 0, 8, 16, 16);
				case SOUTH -> Block.box(0, 0, 8, 16, 16, 16);
				default -> Block.box(0, 0, 0, 16, 16, 8);
			};
			SHAPES[climb.get2DDataValue()] = Shapes.or(low, high).optimize();
		}
	}

	public StepColliderBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.EAST));
	}

	@Override
	protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	/** The collision of steps climbing {@code climb}. */
	public static VoxelShape shape(Direction climb) {
		return SHAPES[climb.get2DDataValue()];
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
		return shape(state.getValue(FACING));
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
