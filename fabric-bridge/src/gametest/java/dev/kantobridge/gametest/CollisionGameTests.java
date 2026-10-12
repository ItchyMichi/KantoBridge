package dev.kantobridge.gametest;

import dev.kantobridge.block.EdgeWallBlock;
import dev.kantobridge.block.KbBlocks;
import dev.kantobridge.block.LedgeColliderBlock;
import dev.kantobridge.block.StepColliderBlock;
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
import net.minecraft.world.level.block.RenderShape;
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

	/**
	 * A swap site's cell as the voxel plan's V5 builds it: its story tile is a model block (it carries the bottom of the
	 * cell's closed art, drawn per player) with a decor carrier above; collision is still the story tile's, per player, and
	 * the carrier above never collides.
	 */
	@GameTest(template = EMPTY_STRUCTURE)
	public void aMeshedStoryTileCollidesAsBefore(GameTestHelper helper) {
		BlockPos abs = storyCell(helper, new BlockPos(3, 2, 3), KEY, StoryCellType.TILE);
		helper.setBlock(new BlockPos(3, 3, 3), KbBlocks.DECOR_LEAVES);
		helper.assertTrue(helper.getLevel().getBlockState(abs).getRenderShape() == RenderShape.MODEL, "the story tile is a model block");
		MockPlayers.with(helper, player -> {
			StoryState state = StoryStates.of(player);
			state.clear();
			helper.assertTrue(walkSouthInto(player, abs) > 0.1, "an open cell was in the way");
			BlockState above = helper.getLevel().getBlockState(abs.above());
			helper.assertTrue(above.getCollisionShape(helper.getLevel(), abs.above(), CollisionContext.of(player)).isEmpty(), "the carrier collides");
			state.activate(KEY);
			helper.assertTrue(Math.abs(walkSouthInto(player, abs) + 0.3) < 1.0E-6, "a closed cell didn't stop the player at its face");
			state.clear();
		});
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
	public void edgeWallsStopPlayersAtFeetAndFromTheBlockAbove(GameTestHelper helper) {
		// CAVES_3D_PLAN §3.2: a wall on the east side of a block, at feet level, then one in the block above feet level.
		BlockPos rel = new BlockPos(3, 2, 3);
		helper.setBlock(rel.below(), Blocks.STONE);
		helper.setBlock(rel.below().east(), Blocks.STONE);
		helper.setBlock(rel, KbBlocks.EDGE_WALL.withSides(EdgeWallBlock.SIDE_EAST));
		BlockPos abs = helper.absolutePos(rel);
		ServerLevel level = helper.getLevel();
		BlockState state = level.getBlockState(abs);
		helper.assertTrue(state.getShape(level, abs).isEmpty(), "an edge wall must have no outline");
		helper.assertTrue(state.getCollisionShape(level, abs, CollisionContext.empty()).max(Direction.Axis.Y) == 1.5,
				"its plate is 24 px high");
		// Cobblemon's spawner stands wild Pokémon only on blocks that are isSolid (CAVES_3D_PLAN §3.3): none of the invisible
		// helpers is, so nothing appears on top of a wall, a ledge or a story cell.
		helper.assertTrue(!state.isSolid(), "an edge wall must not count as ground to spawn on");
		helper.assertTrue(!KbBlocks.LEDGE_COLLIDER.defaultBlockState().isSolid(), "nor a ledge collider");
		helper.assertTrue(!KbBlocks.STORY_CELL.defaultBlockState().isSolid() && !KbBlocks.STORY_TILE.defaultBlockState().isSolid(),
				"nor a story cell");
		helper.assertTrue(KbBlocks.DECOR.defaultBlockState().setValue(dev.kantobridge.block.DecorBlock.SOLID, true)
				.getCollisionShape(level, abs, CollisionContext.empty()).equals(net.minecraft.world.phys.shapes.Shapes.block()),
				"the cave area's solid carrier collides as a full block");
		MockPlayers.with(helper, player -> {
			// Walking east across the block's east face: stopped by the plate.
			player.setPos(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
			player.move(MoverType.SELF, new Vec3(1.0, 0, 0));
			helper.assertTrue(player.getX() < abs.getX() + 1.0, "a player walked through an edge wall");
			// Walking west into it from the other side: stopped too.
			player.setPos(abs.getX() + 1.6, abs.getY(), abs.getZ() + 0.5);
			player.move(MoverType.SELF, new Vec3(-1.0, 0, 0));
			helper.assertTrue(player.getX() > abs.getX() + 1.0, "a player walked through an edge wall from the far side");
			// Walking south along it: free.
			player.setPos(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
			player.move(MoverType.SELF, new Vec3(0, 0, 0.4));
			helper.assertTrue(player.getZ() > abs.getZ() + 0.85, "the wall stopped a player walking along it");
			// The same wall in the block above feet level still stops a player 1.8 tall.
			level.setBlockAndUpdate(abs, Blocks.AIR.defaultBlockState());
			level.setBlockAndUpdate(abs.above(), KbBlocks.EDGE_WALL.withSides(EdgeWallBlock.SIDE_EAST));
			player.setPos(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
			player.move(MoverType.SELF, new Vec3(1.0, 0, 0));
			helper.assertTrue(player.getX() < abs.getX() + 1.0, "a player walked under an edge wall a block up");
		});
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void caveStepsClimbHalfABlockAtATime(GameTestHelper helper) {
		// CAVES_3D_PLAN §3.4: stairs climbing east as the builder lays them (two half steps on the floor, a block with two
		// more on it), then a plateau two blocks up; beside them, the plateau's bare face.
		for (int x = 0; x < 8; x++) {
			for (int z = 1; z < 7; z++) {
				helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
			}
		}
		BlockState steps = KbBlocks.STEP_COLLIDER.defaultBlockState().setValue(StepColliderBlock.FACING, Direction.EAST);
		for (int z = 2; z < 4; z++) {
			helper.setBlock(new BlockPos(3, 2, z), steps);
			helper.setBlock(new BlockPos(4, 2, z), Blocks.STONE);
			helper.setBlock(new BlockPos(4, 3, z), steps);
		}
		for (int x = 5; x < 8; x++) {
			for (int z = 2; z < 6; z++) {
				helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE);
				helper.setBlock(new BlockPos(x, 3, z), Blocks.STONE);
			}
		}
		ServerLevel level = helper.getLevel();
		BlockPos low = helper.absolutePos(new BlockPos(3, 2, 2));
		BlockState state = level.getBlockState(low);
		helper.assertTrue(state.getShape(level, low).isEmpty(), "a step collider must have no outline");
		helper.assertTrue(!state.isSolid(), "nor count as ground to spawn on");
		helper.assertTrue(state.getCollisionShape(level, low, CollisionContext.empty()).max(Direction.Axis.Y) == 1.0, "its back half is a block high");
		helper.assertTrue(StepColliderBlock.shape(Direction.EAST).bounds().equals(new net.minecraft.world.phys.AABB(0, 0, 0, 1, 1, 1))
				&& StepColliderBlock.shape(Direction.NORTH).toAabbs().size() == 2, "two half steps filling the block's footprint");
		BlockPos origin = helper.absolutePos(BlockPos.ZERO);
		MockPlayers.with(helper, player -> {
			// Up the stairs onto the plateau, half a block at a time.
			player.setPos(origin.getX() + 1.5, origin.getY() + 2, origin.getZ() + 3.0);
			walk(player, 0.25, 0, 20);
			helper.assertTrue(player.getX() > origin.getX() + 5.5 && Math.abs(player.getY() - (origin.getY() + 4)) < 1e-6,
					"the stairs didn't lead onto the plateau: " + player.position());
			// The bare face beside them: two blocks, never climbed.
			player.setPos(origin.getX() + 1.5, origin.getY() + 2, origin.getZ() + 5.0);
			walk(player, 0.25, 0, 20);
			helper.assertTrue(player.getX() < origin.getX() + 5.0 && player.getY() < origin.getY() + 2.1, "a player climbed a two-block face");
			// And down them again.
			player.setPos(origin.getX() + 6.5, origin.getY() + 4, origin.getZ() + 3.0);
			walk(player, -0.25, 0, 24);
			helper.assertTrue(player.getX() < origin.getX() + 3.0 && Math.abs(player.getY() - (origin.getY() + 2)) < 1e-6,
					"the stairs didn't lead down: " + player.position());
		});
		helper.succeed();
	}

	/** Walks a player {@code steps} times by (dx, dz) as Minecraft moves it (0.6-block steps), falling onto what is under it. */
	static void walk(ServerPlayer player, double dx, double dz, int steps) {
		player.move(MoverType.SELF, new Vec3(0, -1.0, 0));
		for (int i = 0; i < steps; i++) {
			player.move(MoverType.SELF, new Vec3(dx, 0, dz));
			player.move(MoverType.SELF, new Vec3(0, -1.0, 0));
		}
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
