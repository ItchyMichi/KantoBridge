package dev.kantobridge.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;

/**
 * {@code shape_full_NN}: a tile whose art doesn't fill its cube (a roof, later objects and hulls; docs/specs/
 * VOXEL_BLOCKS_PLAN.md §3.3). It collides and is picked like {@code tile_full} (a full cube, so walkability never
 * depends on art), keeps the same slot numbers (its fallback cube and the colour distant-terrain mods take), but never
 * occludes, never suffocates or blocks the view, lets sky light through and casts no ambient occlusion: its neighbours'
 * faces stay drawn around the art (§3.4, rule 3).
 */
public class ShapeBlock extends TileBlock {
	public ShapeBlock(Properties properties) {
		super(properties, Shapes.block());
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
