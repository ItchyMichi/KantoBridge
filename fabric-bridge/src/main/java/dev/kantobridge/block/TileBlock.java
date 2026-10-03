package dev.kantobridge.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A generic Game Boy tile block. Its 256 states are texture slots chosen by the world builder's slot map; the art
 * comes from each player's own generated resource pack (docs/PLAN.md §4.2, §4.5).
 *
 * <p>The slot is split into two 16-value properties, {@code hi} and {@code lo}. One 0..255 property would cost about
 * 95 MB of heap for all families instead of about 5 MB, because every state builds a neighbour table over every
 * value of every property (refs/_research/P5_mc_content_testing.md §1.2).
 */
public class TileBlock extends Block {
	public static final IntegerProperty HI = IntegerProperty.create("hi", 0, 15);
	public static final IntegerProperty LO = IntegerProperty.create("lo", 0, 15);

	private final VoxelShape shape;

	public TileBlock(Properties properties, VoxelShape shape) {
		super(properties);
		this.shape = shape;
		this.registerDefaultState(this.stateDefinition.any().setValue(HI, 0).setValue(LO, 0));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(HI, LO);
	}

	/** One shape per family; collision and occlusion follow it. */
	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return this.shape;
	}

	/** The texture slot (0..255) of a state. */
	public static int slot(BlockState state) {
		return state.getValue(HI) * 16 + state.getValue(LO);
	}

	/** The state of this block for a texture slot (0..255). */
	public BlockState withSlot(int slot) {
		if (slot < 0 || slot > 255) {
			throw new IllegalArgumentException("tile slot out of range: " + slot);
		}
		return this.defaultBlockState().setValue(HI, slot >> 4).setValue(LO, slot & 15);
	}
}
