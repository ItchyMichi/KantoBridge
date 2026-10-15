package dev.kantobridge.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.McBootstrap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The voxel plan's carriers (docs/specs/VOXEL_BLOCKS_PLAN.md §3.3, §3.4, §6.1): {@code shape_full} collides and is
 * picked exactly like {@code tile_full} but never occludes, suffocates or blocks sky light; {@code decor} has neither
 * collision nor outline; the ledge collider is a model block now. Collision never depends on the art.
 */
class ShapeBlockTest {
	@BeforeAll
	static void setUp() {
		McBootstrap.ensure();
		KbBlocks.register();
	}

	/** A world of a few blocks, air elsewhere. */
	record Blocks2(Map<BlockPos, BlockState> states) implements BlockGetter {
		@Override
		public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
			return null;
		}

		@Override
		public BlockState getBlockState(BlockPos pos) {
			return this.states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
		}

		@Override
		public FluidState getFluidState(BlockPos pos) {
			return Fluids.EMPTY.defaultFluidState();
		}

		@Override
		public int getHeight() {
			return 384;
		}

		@Override
		public int getMinBuildHeight() {
			return -64;
		}
	}

	@Test
	void shapeTilesCollideLikeTilesButNeverOcclude() {
		BlockState tile = KbBlocks.tileFull().get(4).withSlot(9);
		BlockState shape = KbBlocks.shapeFull().get(4).withSlot(9);
		BlockGetter level = new Blocks2(Map.of(BlockPos.ZERO, shape));
		assertEquals(tile.getCollisionShape(level, BlockPos.ZERO), shape.getCollisionShape(level, BlockPos.ZERO), "the same collision");
		assertEquals(tile.getShape(level, BlockPos.ZERO), shape.getShape(level, BlockPos.ZERO), "and the same outline: the crosshair lands on it");
		assertTrue(shape.isCollisionShapeFullBlock(level, BlockPos.ZERO));
		assertEquals(9, TileBlock.slot(shape), "the same slot numbers");
		assertTrue(tile.canOcclude() && tile.isSolidRender(level, BlockPos.ZERO));
		assertFalse(shape.canOcclude(), "neighbours keep their faces around shaped art");
		assertFalse(shape.isSolidRender(level, BlockPos.ZERO));
		assertEquals(0, shape.getLightBlock(level, BlockPos.ZERO), "sky light passes");
		assertEquals(15, tile.getLightBlock(level, BlockPos.ZERO));
		assertFalse(shape.isSuffocating(level, BlockPos.ZERO));
		assertFalse(shape.isViewBlocking(level, BlockPos.ZERO));
		assertEquals(1.0F, shape.getShadeBrightness(level, BlockPos.ZERO), "no ambient occlusion from a roof's empty corners");
		assertEquals(RenderShape.MODEL, shape.getRenderShape());
		assertEquals(256, KbBlocks.shapeFull().get(0).getStateDefinition().getPossibleStates().size());
	}

	@Test
	void decorationHasNothingToCollideWithOrToPick() {
		for (DecorBlock decor : new DecorBlock[] {KbBlocks.DECOR, KbBlocks.DECOR_LEAVES}) {
			BlockState state = decor.defaultBlockState();
			BlockGetter level = new Blocks2(Map.of(BlockPos.ZERO, state));
			assertTrue(state.getCollisionShape(level, BlockPos.ZERO, CollisionContext.empty()).isEmpty());
			assertTrue(state.getShape(level, BlockPos.ZERO).isEmpty(), "no outline: the crosshair passes through");
			assertFalse(state.canOcclude());
			assertEquals(0, state.getLightBlock(level, BlockPos.ZERO));
			assertEquals(RenderShape.MODEL, state.getRenderShape());
			assertEquals(0, state.getLightEmission(), "dark unless it stands in for a light (CW1)");
			BlockState lit = state.setValue(DecorBlock.LIT, true);
			assertEquals(DecorBlock.LIGHT, lit.getLightEmission());
			assertTrue(lit.getCollisionShape(level, BlockPos.ZERO, CollisionContext.empty()).isEmpty(), "lit, still nothing to collide with");
			assertEquals(4, decor.getStateDefinition().getPossibleStates().size(), "lit or not, solid or not");
			// The cave area's rock fill (CAVES_3D_PLAN §3.3): a solid carrier collides as a full block, still with no outline.
			BlockState solid = state.setValue(DecorBlock.SOLID, true);
			assertEquals(net.minecraft.world.phys.shapes.Shapes.block(), solid.getCollisionShape(level, BlockPos.ZERO, CollisionContext.empty()));
			assertTrue(solid.getShape(level, BlockPos.ZERO).isEmpty());
		}
		// the first carrier after the three tile/shape blocks of every family (112 families since MG9)
		assertSame(KbBlocks.DECOR, KbBlocks.carriers().get(3 * KbBlocks.FAMILIES_PER_SHAPE).block());
	}

	@Test
	void theLedgeColliderIsAModelBlockThatStillCollidesOneWay() {
		BlockState ledge = KbBlocks.LEDGE_COLLIDER.defaultBlockState().setValue(LedgeColliderBlock.FACING, Direction.SOUTH);
		assertEquals(RenderShape.MODEL, ledge.getRenderShape(), "it carries the ledge lip");
		BlockGetter level = new Blocks2(Map.of(BlockPos.ZERO, ledge));
		assertTrue(ledge.getShape(level, BlockPos.ZERO).isEmpty(), "still no outline");
		assertEquals(LedgeColliderBlock.WALL, ledge.getCollisionShape(level, BlockPos.ZERO, CollisionContext.empty()));
		assertFalse(ledge.canOcclude());
	}
}
