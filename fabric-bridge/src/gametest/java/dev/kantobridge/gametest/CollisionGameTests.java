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

	/** A story block of a given cell key at {@code rel} (a tile cell gets the tile block, as the builder places it). */
	private static BlockPos storyCell(GameTestHelper helper, BlockPos rel, String key, StoryCellType type) {
		helper.setBlock(rel.below(), Blocks.STONE);
		helper.setBlock(rel, type == StoryCellType.TILE ? KbBlocks.STORY_TILE : KbBlocks.STORY_CELL);
		BlockPos abs = helper.absolutePos(rel);
		StoryCellBlockEntity cell = (StoryCellBlockEntity) helper.getLevel().getBlockEntity(abs);
		helper.assertTrue(cell != null, "story cell has no block entity");
		cell.set(key, type);
		return abs;
	}

	private static boolean stopped(ServerPlayer player, BlockPos abs) {
		return Math.abs(walkSouthInto(player, abs) + 0.3) < 1.0E-6;
	}

	/**
	 * M9: two players walk into the same story cells, each with their own hidden game's story (DESIGN §8 "the golden
	 * rule"): every blockade type closes for the player whose story has it closed and stays open for the other, and a
	 * player's relayed {@code story.state} changes their collision only.
	 */
	@GameTest(template = EMPTY_STRUCTURE)
	public void twoPlayersWalkTheSameCellsWithTheirOwnStories(GameTestHelper helper) {
		String guard = "TEST_MAP:1:1";
		String tree = "TEST_MAP:2:1";
		String boulder = "TEST_MAP:3:1";
		String water = "TEST_MAP:4:1";
		BlockPos guardAt = storyCell(helper, new BlockPos(1, 2, 4), guard, StoryCellType.OBJECT);
		BlockPos treeAt = storyCell(helper, new BlockPos(3, 2, 4), tree, StoryCellType.TILE);
		BlockPos boulderAt = storyCell(helper, new BlockPos(5, 2, 4), boulder, StoryCellType.BOULDER_AREA);
		BlockPos waterAt = storyCell(helper, new BlockPos(7, 2, 4), water, StoryCellType.WATER);
		dev.kantobridge.net.RelayService relay = dev.kantobridge.net.RelayService.get();
		MockPlayers.with(helper, walker -> MockPlayers.with(helper, surfer -> {
			// The walker's hidden game: the guard stands, the tree is uncut, a boulder sits there, no Surf. The surfer's: all
			// of that is behind them, and they are surfing.
			relay.receive(walker, PartyGameTests.envelope(1, "story.state", "{\"full\":true,\"active\":[\"" + guard + "\",\"" + tree + "\",\""
					+ boulder + "\"],\"surf\":false,\"bike\":false,\"lit\":[]}"));
			relay.receive(surfer, PartyGameTests.envelope(1, "story.state", "{\"full\":true,\"active\":[],\"surf\":true,\"bike\":false,\"lit\":[]}"));
			for (BlockPos at : new BlockPos[] {guardAt, treeAt, boulderAt, waterAt}) {
				helper.assertTrue(stopped(walker, at), "the walker went through a cell their story has closed");
				helper.assertFalse(stopped(surfer, at), "the surfer was stopped by another player's blockade");
			}

			// The surfer's own client closes the guard for them (a delta): only their collision changes.
			relay.receive(surfer, PartyGameTests.envelope(2, "story.state", "{\"full\":false,\"add\":[\"" + guard + "\"]}"));
			helper.assertTrue(stopped(surfer, guardAt), "the surfer's own delta didn't close their cell");
			helper.assertTrue(stopped(walker, guardAt) && stopped(walker, treeAt), "the walker's cells changed with the surfer's delta");
			// The walker's client clears everything and surfs: open for them; the surfer's guard stays closed.
			relay.receive(walker, PartyGameTests.envelope(2, "story.state", "{\"full\":true,\"active\":[],\"surf\":true,\"bike\":false,\"lit\":[]}"));
			for (BlockPos at : new BlockPos[] {guardAt, treeAt, boulderAt, waterAt}) {
				helper.assertFalse(stopped(walker, at), "the walker's cleared story still blocks");
			}
			helper.assertTrue(stopped(surfer, guardAt), "the walker's full state reached the surfer");
			StoryStates.of(walker).clear();
			StoryStates.of(surfer).clear();
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
