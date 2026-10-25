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
 * An invisible wall for players on a bike only (docs/specs/FIELD_MECHANICS_AUDIT_PLAN.md §13.8 item 10): Emerald refuses
 * a bike onto long grass, hot springs, a no-running floor and a warp door ({@code Rse.collision}, bike/rse.lua:136-146,
 * 182-221) and lets a walker through, so the cell's block above feet level is solid for a player whose story state
 * says bike, and open to everyone else (walkers, wild Pokémon, pathfinding). A box already inside it is let out.
 *
 * <p>No outline, no occlusion; it renders nothing.
 */
public class BikeWallBlock extends Block {
	public static final MapCodec<BikeWallBlock> CODEC = simpleCodec(BikeWallBlock::new);
	private static final double EPSILON = 1.0E-7;

	public BikeWallBlock(Properties properties) {
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
		return walls(story != null && story.bike(), entity.getBoundingBox(), pos) ? Shapes.block() : Shapes.empty();
	}

	/** The rule: a wall for a biker whose box isn't already over the block. */
	public static boolean walls(boolean bike, AABB box, BlockPos pos) {
		if (!bike) {
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
