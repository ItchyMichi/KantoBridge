package dev.kantobridge.block;

import com.mojang.serialization.MapCodec;
import dev.kantobridge.story.StoryState;
import dev.kantobridge.story.StoryStates;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * An invisible wall at a waterfall's foot (docs/specs/WATER_AND_BRIDGES_PLAN.md §2.1 item 4, D4): the game lets a surfer up
 * a waterfall only with the Waterfall move, a forced walk up its cells, and pushes a surfer who enters it from above down
 * the same way. The cascade's steps climb from the foot, so this wall stands over the foot cell's first steps: solid for a
 * player whose story state says no forced move runs, open during one (and to everything that is no player). A box
 * already inside it is let out.
 *
 * <p>No outline, no occlusion; it renders nothing.
 */
public class FallWallBlock extends Block {
	public static final MapCodec<FallWallBlock> CODEC = simpleCodec(FallWallBlock::new);
	private static final double EPSILON = 1.0E-7;

	public FallWallBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
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
			return Shapes.empty();
		}
		Player player = StoryStates.governingPlayer(entity);
		StoryState story = player == null ? null : StoryStates.peek(player);
		return player != null && walls(story != null && story.forced(), entity.getBoundingBox(), pos) ? Shapes.block() : Shapes.empty();
	}

	/** The rule: a wall for a player in no forced move whose box isn't already over the block. */
	public static boolean walls(boolean forced, AABB box, BlockPos pos) {
		if (forced) {
			return false;
		}
		boolean over = box.maxX > pos.getX() + EPSILON && box.minX < pos.getX() + 1 - EPSILON && box.maxZ > pos.getZ() + EPSILON
				&& box.minZ < pos.getZ() + 1 - EPSILON;
		return !over;
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
