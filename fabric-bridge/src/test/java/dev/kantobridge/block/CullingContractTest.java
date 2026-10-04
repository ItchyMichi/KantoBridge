package dev.kantobridge.block;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.McBootstrap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * What Minecraft culls between our carriers (docs/specs/VOXEL_BLOCKS_PLAN.md §3.4, §6.1), through
 * {@code Block.shouldRenderFace}: opaque tiles hide each other; a shaped tile's face on an opaque tile is hidden; an
 * opaque tile's face on a shaped tile is drawn (the art doesn't fill the cube); decoration hides nothing and is hidden by
 * nothing. Between two shaped blocks the client's face masks decide.
 */
class CullingContractTest {
	private static final BlockPos A = BlockPos.ZERO;
	private static final BlockPos B = A.east();

	@BeforeAll
	static void setUp() {
		McBootstrap.ensure();
		KbBlocks.register();
	}

	/** True when {@code a}'s east face is drawn with {@code b} east of it. */
	private static boolean drawn(BlockState a, BlockState b) {
		return Block.shouldRenderFace(a, new ShapeBlockTest.Blocks2(Map.of(A, a, B, b)), A, Direction.EAST, B);
	}

	@Test
	void theContract() {
		BlockState tile = KbBlocks.tileFull().get(0).withSlot(1);
		BlockState other = KbBlocks.tileFull().get(3).withSlot(200);
		BlockState shape = KbBlocks.shapeFull().get(0).withSlot(1);
		BlockState decor = KbBlocks.DECOR.defaultBlockState();
		BlockState leaves = KbBlocks.DECOR_LEAVES.defaultBlockState();
		assertFalse(drawn(tile, other), "tile_full against tile_full: culled");
		assertFalse(drawn(shape, tile), "shape_full against tile_full: culled");
		assertTrue(drawn(tile, shape), "tile_full against shape_full: drawn");
		assertTrue(drawn(shape, shape), "shape_full against shape_full: Minecraft draws it; the face masks decide");
		for (BlockState any : new BlockState[] {tile, shape, decor, leaves}) {
			assertTrue(drawn(decor, any), "decor against anything: drawn");
			assertTrue(drawn(any, decor), "anything against decor: drawn");
		}
	}
}
