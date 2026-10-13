package dev.kantobridge.gametest;

import dev.kantobridge.KantoBridge;
import dev.kantobridge.block.KbBlocks;
import dev.kantobridge.block.TileBlock;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Spike S2 in a running server: every tile family (full and half; 32 per shape since MG3) exists with 256 states, and states survive placement. */
public class TileBlockTests implements FabricGameTest {
	@GameTest(template = EMPTY_STRUCTURE)
	public void familiesRegistered(GameTestHelper helper) {
		int states = 0;
		for (String shape : new String[] {"full", "half"}) {
			for (int family = 0; family < KbBlocks.FAMILIES_PER_SHAPE; family++) {
				ResourceLocation id = ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, KbBlocks.tileId(shape, family));
				Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
				helper.assertTrue(block instanceof TileBlock, "missing tile block " + id);
				int count = block.getStateDefinition().getPossibleStates().size();
				helper.assertTrue(count == 256, id + " has " + count + " states, not 256");
				states += count;
			}
		}
		int expected = 2 * KbBlocks.FAMILIES_PER_SHAPE * 256;
		helper.assertTrue(states == expected, "expected " + expected + " tile states, got " + states);
		KantoBridge.LOGGER.info("S2: {} tile blocks, {} tile states registered", KbBlocks.allTiles().size(), states);
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void slotsSurvivePlacement(GameTestHelper helper) {
		TileBlock full = KbBlocks.tileFull().get(3);
		TileBlock half = KbBlocks.tileHalf().get(15);
		BlockPos a = new BlockPos(1, 1, 1);
		BlockPos b = new BlockPos(3, 1, 1);
		helper.setBlock(a, full.withSlot(0x7c));
		helper.setBlock(b, half.withSlot(255));
		BlockState placedFull = helper.getBlockState(a);
		BlockState placedHalf = helper.getBlockState(b);
		helper.assertTrue(placedFull.is(full) && TileBlock.slot(placedFull) == 0x7c, "full tile slot changed on placement");
		helper.assertTrue(placedHalf.is(half) && TileBlock.slot(placedHalf) == 255, "half tile slot changed on placement");
		double fullTop = placedFull.getCollisionShape(helper.getLevel(), helper.absolutePos(a), CollisionContext.empty()).max(Direction.Axis.Y);
		double halfTop = placedHalf.getCollisionShape(helper.getLevel(), helper.absolutePos(b), CollisionContext.empty()).max(Direction.Axis.Y);
		helper.assertTrue(fullTop == 1.0, "full tile is not a full block");
		helper.assertTrue(halfTop == 0.5, "half tile is not half a block high");
		helper.succeed();
	}
}
