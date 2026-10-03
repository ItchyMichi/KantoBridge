package dev.kantobridge.gametest;

import dev.kantobridge.block.KbBlocks;
import dev.kantobridge.block.LedgeColliderBlock;
import dev.kantobridge.block.StoryCellBlockEntity;
import dev.kantobridge.story.StoryState;
import dev.kantobridge.story.StoryStates;
import dev.kantobridge.world.StoryCellType;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Story-cell and ledge collision with real (mock) players (docs/PLAN.md §4.2): a story cell closes only for players
 * who have it active, ledges are walls only from the downhill side, and both are solid for an empty context.
 */
public class CollisionGameTests implements FabricGameTest {
	private static final String KEY = "TEST_MAP:3:4";

	private static BlockPos storyCell(GameTestHelper helper, BlockPos rel, StoryCellType type) {
		helper.setBlock(rel.below(), Blocks.STONE);
		helper.setBlock(rel, KbBlocks.STORY_CELL);
		BlockPos abs = helper.absolutePos(rel);
		StoryCellBlockEntity cell = (StoryCellBlockEntity) helper.getLevel().getBlockEntity(abs);
		helper.assertTrue(cell != null, "story cell has no block entity");
		cell.set(KEY, type);
		return abs;
	}

	private static boolean solidFor(GameTestHelper helper, BlockPos abs, CollisionContext context) {
		BlockState state = helper.getLevel().getBlockState(abs);
		return !state.getCollisionShape(helper.getLevel(), abs, context).isEmpty();
	}

	/** Puts the player on the block's north side, touching nothing, and walks it half a block south. */
	private static double walkSouthInto(ServerPlayer player, BlockPos abs) {
		player.setPos(abs.getX() + 0.5, abs.getY(), abs.getZ() - 0.31);
		player.move(MoverType.SELF, new Vec3(0, 0, 0.5));
		return player.getZ() - abs.getZ();
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void storyCellClosesOnlyWhileActive(GameTestHelper helper) {
		BlockPos abs = storyCell(helper, new BlockPos(3, 2, 3), StoryCellType.OBJECT);
		helper.assertTrue(solidFor(helper, abs, CollisionContext.empty()), "empty context must see a full cube");
		MockPlayers.with(helper, player -> {
			StoryState state = StoryStates.of(player);
			state.clear();
			helper.assertTrue(!solidFor(helper, abs, CollisionContext.of(player)), "an inactive cell collides");
			double free = walkSouthInto(player, abs);
			helper.assertTrue(free > 0.1, "walking through an inactive cell was stopped");

			state.activate(KEY);
			helper.assertTrue(solidFor(helper, abs, CollisionContext.of(player)), "an active cell doesn't collide");
			double blocked = walkSouthInto(player, abs);
			helper.assertTrue(Math.abs(blocked + 0.3) < 1.0E-6, "an active cell didn't stop the player at its face");

			state.deactivate(KEY);
			helper.assertTrue(!solidFor(helper, abs, CollisionContext.of(player)), "a deactivated cell still collides");
		});
		ArmorStand stand = new ArmorStand(helper.getLevel(), abs.getX() + 0.5, abs.getY(), abs.getZ() - 1.0);
		helper.assertTrue(!solidFor(helper, abs, CollisionContext.of(stand)), "entities no player governs pass story cells");
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void waterCellsOpenWhenSurfing(GameTestHelper helper) {
		BlockPos abs = storyCell(helper, new BlockPos(3, 2, 3), StoryCellType.WATER);
		MockPlayers.with(helper, player -> {
			StoryState state = StoryStates.of(player);
			state.clear();
			helper.assertTrue(solidFor(helper, abs, CollisionContext.of(player)), "water must close for a walking player");
			state.setSurf(true);
			helper.assertTrue(!solidFor(helper, abs, CollisionContext.of(player)), "water must open for a surfing player");
			state.clear();
		});
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void storyStatesArePerPlayer(GameTestHelper helper) {
		BlockPos abs = storyCell(helper, new BlockPos(3, 2, 3), StoryCellType.OBJECT);
		MockPlayers.with(helper, first -> MockPlayers.with(helper, second -> {
			StoryStates.of(first).clear();
			StoryStates.of(second).clear();
			StoryStates.of(first).activate(KEY);
			helper.assertTrue(solidFor(helper, abs, CollisionContext.of(first)), "closed for the first player");
			helper.assertTrue(!solidFor(helper, abs, CollisionContext.of(second)), "the second player's state leaked");
		}));
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void ledgesBlockOnlyFromBelow(GameTestHelper helper) {
		BlockPos rel = new BlockPos(3, 2, 3);
		helper.setBlock(rel.below(), Blocks.STONE);
		helper.setBlock(rel, KbBlocks.LEDGE_COLLIDER.defaultBlockState().setValue(LedgeColliderBlock.FACING, Direction.SOUTH));
		BlockPos abs = helper.absolutePos(rel);
		BlockState state = helper.getLevel().getBlockState(abs);
		ServerLevel level = helper.getLevel();
		helper.assertTrue(state.getCollisionShape(level, abs, CollisionContext.empty()).max(Direction.Axis.Y) == 1.5,
				"empty context must see the 24-px wall");
		helper.assertTrue(state.getShape(level, abs).isEmpty(), "a ledge collider must have no outline");
		MockPlayers.with(helper, player -> {
			// From the uphill (north) side the player walks down across it.
			player.setPos(abs.getX() + 0.5, abs.getY(), abs.getZ() - 0.31);
			player.move(MoverType.SELF, new Vec3(0, 0, 0.5));
			helper.assertTrue(player.getZ() > abs.getZ() + 0.1, "the ledge stopped a player going downhill");
			player.move(MoverType.SELF, new Vec3(0, 0, 0.5));
			helper.assertTrue(player.getZ() > abs.getZ() + 0.6, "the ledge stopped a player crossing it");

			// From the downhill (south) side it is a wall.
			player.setPos(abs.getX() + 0.5, abs.getY(), abs.getZ() + 1.31);
			helper.assertTrue(!state.getCollisionShape(level, abs, CollisionContext.of(player)).isEmpty(), "no wall from below");
			player.move(MoverType.SELF, new Vec3(0, 0, -0.5));
			helper.assertTrue(Math.abs(player.getZ() - (abs.getZ() + 1.3)) < 1.0E-6, "a player climbed back up the ledge");
		});
		helper.succeed();
	}
}
