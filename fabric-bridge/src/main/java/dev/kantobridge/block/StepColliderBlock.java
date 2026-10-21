package dev.kantobridge.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Two half steps of a cave's stairs (docs/specs/CAVES_3D_PLAN.md §3.4, H4): a stairs cell climbs its plateau's 2 blocks
 * in four half-block steps, two per block, so a player walks up them with Minecraft's 0.6-block step and no jump.
 * {@code FACING} is the way the stairs climb: the half on the downhill side is 8 px high, the uphill half 16 px. The
 * cell's lower block holds one; its upper block stands on a full block and holds the other.
 *
 * <p>Outdoors a stairs cell climbs 3 blocks (docs/specs/HEIGHTS_ALL_GAMES_PLAN.md D4, the landscape models' scale): six
 * half-block steps, three per block ({@link Stage}). The lower block's thirds stand 8, 16 and 24 px high (its last step
 * reaches into the block over it, which holds no collider); the upper block stands on two full blocks, its thirds 0, 8
 * and 16 px.
 *
 * <p>Invisible itself, with no outline and no occlusion: a model block (a carrier), so the client's runtime model draws
 * the stair unit's art the world's shape map gives its position, as for the ledge collider.
 */
public class StepColliderBlock extends HorizontalDirectionalBlock {
	public static final MapCodec<StepColliderBlock> CODEC = simpleCodec(StepColliderBlock::new);
	/** Which steps the block holds: a cave's two half steps, or the lower or upper block of an outdoor 3-block cell. */
	public static final EnumProperty<Stage> STAGE = EnumProperty.create("stage", Stage.class);
	private static final VoxelShape[] SHAPES = new VoxelShape[4];
	private static final VoxelShape[] LOW3 = new VoxelShape[4];
	private static final VoxelShape[] HIGH3 = new VoxelShape[4];

	/** The steps a block holds. */
	public enum Stage implements StringRepresentable {
		/** A cave's stairs cell (2 blocks): two half steps. */
		TWO("two"),
		/** The lower block of an outdoor stairs cell (3 blocks): three half steps, to 1.5 blocks. */
		LOW3("low3"),
		/** The upper block of an outdoor stairs cell, on two full blocks: two half steps over a flat third. */
		HIGH3("high3");

		private final String id;

		Stage(String id) {
			this.id = id;
		}

		@Override
		public String getSerializedName() {
			return this.id;
		}

		/** The stage a block code's number names (0 two, 1 low3, 2 high3). */
		public static Stage of(int number) {
			return number == 1 ? LOW3 : number == 2 ? HIGH3 : TWO;
		}
	}

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
			double third = 1.0 / 3;
			LOW3[climb.get2DDataValue()] = Shapes.or(band(climb, 0, third, 8), band(climb, third, 2 * third, 16), band(climb, 2 * third, 1, 24))
					.optimize();
			HIGH3[climb.get2DDataValue()] = Shapes.or(band(climb, third, 2 * third, 8), band(climb, 2 * third, 1, 16)).optimize();
		}
	}

	/** A band across the block from {@code from} to {@code to} of the way up {@code climb}, {@code height} px high. */
	private static VoxelShape band(Direction climb, double from, double to, double height) {
		double a = 16 * from;
		double b = 16 * to;
		return switch (climb) {
			case EAST -> Block.box(a, 0, 0, b, height, 16);
			case WEST -> Block.box(16 - b, 0, 0, 16 - a, height, 16);
			case SOUTH -> Block.box(0, 0, a, 16, height, b);
			default -> Block.box(0, 0, 16 - b, 16, height, 16 - a);
		};
	}

	public StepColliderBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.EAST).setValue(STAGE, Stage.TWO));
	}

	@Override
	protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, STAGE);
	}

	/** The collision of steps climbing {@code climb}. */
	public static VoxelShape shape(Direction climb) {
		return SHAPES[climb.get2DDataValue()];
	}

	/** The collision of {@code stage}'s steps climbing {@code climb}. */
	public static VoxelShape shape(Direction climb, Stage stage) {
		return switch (stage) {
			case LOW3 -> LOW3[climb.get2DDataValue()];
			case HIGH3 -> HIGH3[climb.get2DDataValue()];
			default -> SHAPES[climb.get2DDataValue()];
		};
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
		return shape(state.getValue(FACING), state.getValue(STAGE));
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
