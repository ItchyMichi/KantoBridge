package dev.kantobridge.block;

import com.mojang.serialization.MapCodec;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * {@code prop}: a Wild Routes object drawn from its block state (docs/specs/WILD_ROUTES_PLAN.md §4.4, D8): which
 * {@link Kind} it is and which quarter of its 2×2-block cell this block shows ({@link #PART}: 0 north-west, 1 north-east,
 * 2 south-west, 3 south-east), so one model per kind and quarter draws a whole cell. Built-in models of our own over
 * vanilla textures; texture packs can replace them. A floor-level piece: no collision (you step onto it; the server
 * sees where you stand) and a thin outline.
 */
public class PropBlock extends Block {
	public static final MapCodec<PropBlock> CODEC = simpleCodec(PropBlock::new);
	public static final EnumProperty<Kind> KIND = EnumProperty.create("kind", Kind.class);
	public static final IntegerProperty PART = IntegerProperty.create("part", 0, 3);
	private static final VoxelShape OUTLINE = Block.box(0, 0, 0, 16, 2, 16);

	/** What a prop is. New kinds only ever go at the end (block codes carry the ordinal). */
	public enum Kind implements StringRepresentable {
		/** A zone's way into its dungeon (WR7). */
		DUNGEON_ENTRANCE,
		/** A dungeon floor's way down. */
		STAIRS,
		/** The last floor's way out, back to the entrance. */
		EXIT;

		@Override
		public String getSerializedName() {
			return this.name().toLowerCase(Locale.ROOT);
		}
	}

	public PropBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(KIND, Kind.DUNGEON_ENTRANCE).setValue(PART, 0));
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(KIND, PART);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return OUTLINE;
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return Shapes.empty();
	}

	@Override
	protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
		return true;
	}
}
