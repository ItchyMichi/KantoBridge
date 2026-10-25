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
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A one-way ledge (docs/PLAN.md P3, §4.2). {@code FACING} is the downhill direction, the way a Gen 1 ledge is hopped.
 *
 * <p>It is a 24-px wall for an entity whose bounding box lies on the downhill side or beside it, so nobody climbs back
 * up or walks onto it sideways; from the uphill side (and to a box already over it) it is empty, so Minecraft lets the
 * player move down across it while the hidden game performs the hop. With no entity (pathfinding, shape caches) it is the wall, which also marks the shape as larger
 * than a block so collision checks look at it from a block away (refs/_research/P5_mc_content_testing.md §1.4, §1.6).
 *
 * <p>No outline, no occlusion: the ledge art is on the tile block below. Since the voxel plan's V3 it is a model block:
 * the client's runtime model draws the ledge lip the world's shape map gives its position, and nothing without one.
 */
public class LedgeColliderBlock extends HorizontalDirectionalBlock {
	public static final MapCodec<LedgeColliderBlock> CODEC = simpleCodec(LedgeColliderBlock::new);
	/** 1.5 blocks: higher than the 0.6 step height and than a jump can clear. */
	public static final VoxelShape WALL = Block.box(0, 0, 0, 16, 24, 16);
	/** Who the ledge walls ({@link Mode}; FIELD_MECHANICS_AUDIT_PLAN §13.8 item 3). */
	public static final EnumProperty<Mode> MODE = EnumProperty.create("mode", Mode.class);

	/** Who a ledge walls. */
	public enum Mode implements StringRepresentable {
		/** The default (worlds built before FA8): a wall only from wholly downhill ({@link #entirelyDownhill}). */
		DOWNHILL("downhill"),
		/** Gen 1's and Gen 3's ledges: from downhill and from its sides too (the game refuses a ledge entered sideways). */
		SIDES("sides"),
		/** Gen 2's: never (its ledge cell is land, the hop starts on it; the cliff below is the wall). */
		OPEN("open");

		private final String id;

		Mode(String id) {
			this.id = id;
		}

		@Override
		public String getSerializedName() {
			return this.id;
		}

		/** The mode of a ledge code's value bits 2-3. */
		public static Mode of(int bits) {
			return switch (bits & 3) {
				case 1 -> SIDES;
				case 2 -> OPEN;
				default -> DOWNHILL;
			};
		}
	}
	private static final double EPSILON = 1.0E-7;

	public LedgeColliderBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.SOUTH).setValue(MODE, Mode.DOWNHILL));
	}

	@Override
	protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, MODE);
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
		Entity entity = context instanceof EntityCollisionContext entityContext ? entityContext.getEntity() : null;
		if (entity == null) {
			return WALL;
		}
		return walls(entity.getBoundingBox(), pos, state.getValue(FACING), state.getValue(MODE)) ? WALL : Shapes.empty();
	}

	/**
	 * True when the ledge is a wall for a player whose box is {@code box}. {@link Mode#SIDES}: open only from wholly uphill
	 * (the hop) and to a box already over it (a hop under way), a wall from downhill and from either side.
	 */
	public static boolean walls(AABB box, BlockPos pos, Direction downhill, Mode mode) {
		if (mode == Mode.OPEN) {
			return false;
		}
		if (mode == Mode.DOWNHILL) {
			return entirelyDownhill(box, pos, downhill);
		}
		if (entirelyUphill(box, pos, downhill)) {
			return false;
		}
		boolean over = box.maxX > pos.getX() + EPSILON && box.minX < pos.getX() + 1 - EPSILON && box.maxZ > pos.getZ() + EPSILON
				&& box.minZ < pos.getZ() + 1 - EPSILON;
		return !over;
	}

	/** True when {@code box} lies wholly before the block at {@code pos}, seen in the {@code downhill} direction. */
	public static boolean entirelyUphill(AABB box, BlockPos pos, Direction downhill) {
		return entirelyDownhill(box, pos, downhill.getOpposite());
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
