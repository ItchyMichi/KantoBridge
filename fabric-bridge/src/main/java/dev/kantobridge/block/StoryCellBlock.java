package dev.kantobridge.block;

import com.mojang.serialization.MapCodec;
import dev.kantobridge.story.StoryStates;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * An invisible, per-player blockade (docs/PLAN.md P3, §4.2). The world builder puts one on every cell whose
 * passability depends on story state: STAY objects, Cut trees and other swap sites, water, boulder areas.
 *
 * <p>It collides only for players who have its cell closed in their {@code StoryState}, so one shared world can block
 * each player differently with no movement mixin: client prediction and the server's re-simulation both ask the
 * block with the moving player's own collision context (refs/_research/P4_mc_runtime.md §3). With no entity (the
 * empty context: pathfinding, shape caches) it is a full cube.
 *
 * <p>It has no outline (never catches the crosshair), no occlusion, no light blocking, and renders nothing.
 */
public class StoryCellBlock extends BaseEntityBlock {
	public static final MapCodec<StoryCellBlock> CODEC = simpleCodec(StoryCellBlock::new);

	public StoryCellBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new StoryCellBlockEntity(pos, state);
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
		return closedFor(level, pos, context) ? Shapes.block() : Shapes.empty();
	}

	/** The collision rule; also used by tests. */
	public static boolean closedFor(BlockGetter level, BlockPos pos, CollisionContext context) {
		Entity entity = context instanceof EntityCollisionContext entityContext ? entityContext.getEntity() : null;
		if (entity == null) {
			return true;
		}
		if (StoryStates.governingPlayer(entity) == null) {
			return false;
		}
		// Block entity data arrives with the chunk; until it does (or if it is missing) stay closed.
		if (!(level.getBlockEntity(pos) instanceof StoryCellBlockEntity cell) || cell.cellKey() == null) {
			return true;
		}
		return StoryStates.blocks(entity, cell.cellKey(), cell.type());
	}

	@Override
	protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
		// Full brightness: the cached (empty-context) collision is a full cube, which would darken neighbours' AO.
		return 1.0F;
	}

	@Override
	protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
		return true;
	}
}
