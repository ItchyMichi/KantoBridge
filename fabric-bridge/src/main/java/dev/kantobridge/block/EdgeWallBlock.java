package dev.kantobridge.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * An invisible wall on one or more sides of a block (docs/specs/CAVES_3D_PLAN.md §3.2): where two neighbouring cells'
 * collision tiles form one of Gen 1's tile pairs, the engine refuses the step between them, so Minecraft must too.
 * Each side set is a plate 2 px thick and 24 px (1.5 blocks) high on that face of the block: higher than the 0.6 step
 * height and than a jump can clear, from feet level or from the block above it alike, since a player is 1.8 tall.
 *
 * <p>It stops every entity (with none, pathfinding and shape caches, it is the same walls). No outline, so it never
 * catches the crosshair; no occlusion; it renders nothing.
 */
public class EdgeWallBlock extends Block {
	public static final MapCodec<EdgeWallBlock> CODEC = simpleCodec(EdgeWallBlock::new);
	public static final BooleanProperty NORTH = BooleanProperty.create("north");
	public static final BooleanProperty EAST = BooleanProperty.create("east");
	public static final BooleanProperty SOUTH = BooleanProperty.create("south");
	public static final BooleanProperty WEST = BooleanProperty.create("west");
	/** Side bits, as {@link dev.kantobridge.builder.plan.PairEdges} and {@code BlockCode.edge} use them. */
	public static final int SIDE_NORTH = 1;
	public static final int SIDE_EAST = 2;
	public static final int SIDE_SOUTH = 4;
	public static final int SIDE_WEST = 8;
	/** The plates' height in pixels: 1.5 blocks, as the ledge collider's wall. */
	public static final int HEIGHT = 24;
	private static final int THICKNESS = 2;
	private static final VoxelShape[] SHAPES = new VoxelShape[16];

	static {
		VoxelShape north = Block.box(0, 0, 0, 16, HEIGHT, THICKNESS);
		VoxelShape east = Block.box(16 - THICKNESS, 0, 0, 16, HEIGHT, 16);
		VoxelShape south = Block.box(0, 0, 16 - THICKNESS, 16, HEIGHT, 16);
		VoxelShape west = Block.box(0, 0, 0, THICKNESS, HEIGHT, 16);
		for (int sides = 0; sides < 16; sides++) {
			VoxelShape shape = Shapes.empty();
			if ((sides & SIDE_NORTH) != 0) {
				shape = Shapes.or(shape, north);
			}
			if ((sides & SIDE_EAST) != 0) {
				shape = Shapes.or(shape, east);
			}
			if ((sides & SIDE_SOUTH) != 0) {
				shape = Shapes.or(shape, south);
			}
			if ((sides & SIDE_WEST) != 0) {
				shape = Shapes.or(shape, west);
			}
			SHAPES[sides] = shape.optimize();
		}
	}

	public EdgeWallBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(NORTH, false).setValue(EAST, false).setValue(SOUTH, false)
				.setValue(WEST, false));
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(NORTH, EAST, SOUTH, WEST);
	}

	/** The state with the walls of {@code sides} (bits {@link #SIDE_NORTH} … {@link #SIDE_WEST}). */
	public BlockState withSides(int sides) {
		return this.defaultBlockState().setValue(NORTH, (sides & SIDE_NORTH) != 0).setValue(EAST, (sides & SIDE_EAST) != 0)
				.setValue(SOUTH, (sides & SIDE_SOUTH) != 0).setValue(WEST, (sides & SIDE_WEST) != 0);
	}

	/** A state's side bits. */
	public static int sides(BlockState state) {
		return (state.getValue(NORTH) ? SIDE_NORTH : 0) | (state.getValue(EAST) ? SIDE_EAST : 0) | (state.getValue(SOUTH) ? SIDE_SOUTH : 0)
				| (state.getValue(WEST) ? SIDE_WEST : 0);
	}

	/** The collision of a block with walls on {@code sides}. */
	public static VoxelShape shape(int sides) {
		return SHAPES[sides & 15];
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
		return shape(sides(state));
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
