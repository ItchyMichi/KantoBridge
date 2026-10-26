package dev.kantobridge.gametest;

import dev.kantobridge.block.KbBlocks;
import dev.kantobridge.block.LedgeColliderBlock;
import dev.kantobridge.entity.BikeEntity;
import dev.kantobridge.entity.KbEntities;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.server.Bikes;
import dev.kantobridge.server.WarpService;
import dev.kantobridge.story.StoryStates;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.Place;
import dev.kantobridge.world.WorldIndex;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The ridden bike on a real server (docs/specs/BIKE_PLAN.md B3, §2.5): a relayed {@code story.state} seats its player on a
 * {@link BikeEntity} of its kind and takes it away again; the bike is stopped by a bike wall and rides down a ledge as its
 * rider would; a warp keeps the rider on a bike; sneak never dismounts; a bike without a rider goes; two riders' bikes are
 * both in the world for each other. A batch of its own (the warp test publishes the synthetic index).
 */
public class RideGameTests implements FabricGameTest {
	private static final String BATCH = "kantobridge_bikes";

	private static String state(long seq, String data) {
		return "{\"type\":\"story.state\",\"seq\":" + seq + ",\"data\":" + data + "}";
	}

	private static final String FULL_MACH = "{\"full\":true,\"active\":[],\"surf\":false,\"bike\":true,\"bikeKind\":\"mach\",\"lit\":[]}";

	/** A mock player standing on stone in the test's area. */
	private static ServerPlayer standing(GameTestHelper helper, BlockPos rel) {
		helper.setBlock(rel.below(), Blocks.STONE);
		ServerPlayer player = MockPlayers.join(helper);
		BlockPos abs = helper.absolutePos(rel);
		player.teleportTo(helper.getLevel(), abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, java.util.Set.of(), 0.0F, 0.0F);
		return player;
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void aRelayedBikeStateSeatsThePlayerAndTakesTheBikeAway(GameTestHelper helper) {
		ServerPlayer player = standing(helper, new BlockPos(2, 2, 2));
		try {
			RelayService.get().receive(player, state(1, FULL_MACH));
			BikeEntity bike = Bikes.bikeOf(player);
			helper.assertTrue(bike != null, "the relayed bike state seated no one");
			helper.assertTrue(bike.kind() == BikeEntity.Kind.MACH, "the bike's kind isn't the state's");
			helper.assertTrue(bike.getControllingPassenger() == player, "the rider doesn't control the bike");
			RelayService.get().receive(player, state(2, "{\"full\":false,\"bikeKind\":\"acro\",\"trick\":\"wheelie\"}"));
			helper.assertTrue(bike.kind() == BikeEntity.Kind.ACRO && bike.trick() == BikeEntity.Trick.WHEELIE, "the kind and pose didn't follow");
			RelayService.get().receive(player, state(3, "{\"full\":false,\"bike\":false}"));
			helper.assertTrue(player.getVehicle() == null, "off the bike the player still rides");
			helper.assertTrue(bike.isRemoved(), "the bike stayed");
		} finally {
			StoryStates.of(player).clear();
			MockPlayers.leave(helper, player);
		}
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void theBikeStopsAtABikeWallAndRidesDownALedgeAsItsRider(GameTestHelper helper) {
		BlockPos wall = new BlockPos(4, 2, 2);
		BlockPos ledge = new BlockPos(2, 2, 5);
		for (int x = 1; x <= 5; x++) {
			for (int z = 1; z <= 7; z++) {
				helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
			}
		}
		helper.setBlock(wall.above(), KbBlocks.BIKE_WALL.defaultBlockState());
		helper.setBlock(ledge, KbBlocks.LEDGE_COLLIDER.defaultBlockState().setValue(LedgeColliderBlock.FACING, Direction.SOUTH));
		ServerPlayer player = standing(helper, new BlockPos(2, 2, 2));
		try {
			RelayService.get().receive(player, state(1, FULL_MACH));
			BikeEntity bike = Bikes.bikeOf(player);
			helper.assertTrue(bike != null, "not seated");
			BlockPos w = helper.absolutePos(wall);
			bike.setPos(w.getX() - 1.5, w.getY(), w.getZ() + 0.5);
			bike.move(MoverType.SELF, new Vec3(1.5, 0, 0));
			helper.assertTrue(bike.getX() < w.getX(), "a bike rode through a bike wall");
			BlockPos l = helper.absolutePos(ledge);
			bike.setPos(l.getX() + 0.5, l.getY(), l.getZ() - 0.41);
			bike.move(MoverType.SELF, new Vec3(0, 0, 0.6));
			bike.move(MoverType.SELF, new Vec3(0, 0, 0.6));
			helper.assertTrue(bike.getZ() > l.getZ() + 0.6, "the ledge stopped a bike going downhill");
			bike.setPos(l.getX() + 0.5, l.getY(), l.getZ() + 1.41);
			bike.move(MoverType.SELF, new Vec3(0, 0, -0.6));
			helper.assertTrue(bike.getZ() > l.getZ() + 1.3, "a bike climbed back up the ledge");
		} finally {
			StoryStates.of(player).clear();
			Bikes.dismount(player);
			MockPlayers.leave(helper, player);
		}
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void sneakNeverDismounts(GameTestHelper helper) {
		ServerPlayer player = standing(helper, new BlockPos(2, 2, 2));
		try {
			RelayService.get().receive(player, state(1, FULL_MACH));
			helper.assertTrue(Bikes.bikeOf(player) != null, "not seated");
			player.setShiftKeyDown(true);
			player.rideTick();
			helper.assertTrue(Bikes.bikeOf(player) != null, "sneaking got the player off the bike");
		} finally {
			player.setShiftKeyDown(false);
			StoryStates.of(player).clear();
			Bikes.dismount(player);
			MockPlayers.leave(helper, player);
		}
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 100)
	public void aBikeWithoutARiderGoes(GameTestHelper helper) {
		helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE);
		BikeEntity bike = KbEntities.BIKE.create(helper.getLevel());
		BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
		bike.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
		helper.getLevel().addFreshEntity(bike);
		helper.runAfterDelay(BikeEntity.RIDERLESS_TICKS + 5, () -> {
			helper.assertTrue(bike.isRemoved(), "a riderless bike stayed");
			helper.succeed();
		});
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 100)
	public void aWarpKeepsTheRiderOnABike(GameTestHelper helper) {
		WorldIndex index = WarpGameTests.publishSyntheticIndex(helper);
		ServerPlayer player = standing(helper, new BlockPos(2, 2, 2));
		RelayService.get().receive(player, state(1, FULL_MACH));
		BikeEntity before = Bikes.bikeOf(player);
		helper.assertTrue(before != null, "not seated");
		WarpService.Result result = WarpService.teleport(player, index, new Place("TEST_TOWN", 5, 6, Dir.DOWN));
		helper.assertTrue(result.refusal() == null, "the warp was refused");
		helper.runAfterDelay(3, () -> {
			try {
				BikeEntity after = Bikes.bikeOf(player);
				helper.assertTrue(after != null, "the warp left the rider walking");
				helper.assertTrue(after.position().distanceTo(index.toBlock("TEST_TOWN", 5, 6)) < 0.75, "the bike isn't at the destination");
				helper.assertTrue(after.kind() == BikeEntity.Kind.MACH, "the new bike lost its kind");
				helper.assertTrue(before.isRemoved(), "the old bike stayed behind");
			} finally {
				StoryStates.of(player).clear();
				Bikes.dismount(player);
				MockPlayers.leave(helper, player);
			}
			helper.succeed();
		});
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void twoRidersBikesAreBothInTheWorldForEachOther(GameTestHelper helper) {
		ServerPlayer one = standing(helper, new BlockPos(1, 2, 1));
		ServerPlayer two = standing(helper, new BlockPos(4, 2, 4));
		try {
			RelayService.get().receive(one, state(1, FULL_MACH));
			RelayService.get().receive(two, state(1, FULL_MACH.replace("mach", "acro")));
			BikeEntity a = Bikes.bikeOf(one);
			BikeEntity b = Bikes.bikeOf(two);
			helper.assertTrue(a != null && b != null, "both riders must be seated");
			helper.assertTrue(a.kind() == BikeEntity.Kind.MACH && b.kind() == BikeEntity.Kind.ACRO, "each bike has its own kind");
			helper.assertTrue(a.broadcastToPlayer(two) && b.broadcastToPlayer(one), "a bike hidden from the other player");
			helper.assertTrue(!a.isInvisibleTo(two) && !b.isInvisibleTo(one), "a bike invisible to the other player");
		} finally {
			StoryStates.of(one).clear();
			StoryStates.of(two).clear();
			Bikes.dismount(one);
			Bikes.dismount(two);
			MockPlayers.leave(helper, one);
			MockPlayers.leave(helper, two);
		}
		helper.succeed();
	}
}
