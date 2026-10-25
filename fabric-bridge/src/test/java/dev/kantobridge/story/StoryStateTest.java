package dev.kantobridge.story;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.McBootstrap;
import dev.kantobridge.block.LedgeColliderBlock;
import dev.kantobridge.world.StoryCellType;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The pure collision rules: which story cells close for a player, and which side of a ledge is solid. */
class StoryStateTest {
	@BeforeAll
	static void setUp() {
		McBootstrap.ensure();
	}

	@Test
	void objectAndTileCellsCloseOnlyWhileActive() {
		StoryState state = new StoryState();
		String key = "PALLET_TOWN:3:4";
		assertFalse(state.blocks(key, StoryCellType.OBJECT));
		assertFalse(state.blocks(key, StoryCellType.TILE));
		state.activate(key);
		assertTrue(state.blocks(key, StoryCellType.OBJECT));
		assertTrue(state.blocks(key, StoryCellType.TILE));
		assertFalse(state.blocks("PALLET_TOWN:3:5", StoryCellType.OBJECT));
		state.deactivate(key);
		assertFalse(state.blocks(key, StoryCellType.OBJECT));
		state.setActive(List.of(key, "ROUTE_1:1:1"));
		assertTrue(state.isActive("ROUTE_1:1:1"));
		state.clear();
		assertFalse(state.isActive(key));
	}

	@Test
	void routeBallsAndPassesCloseOnlyWhileActive() {
		// WR5's balls and MG5's passes (a connecting route's far doorway) follow the active set like objects
		StoryState state = new StoryState();
		String ball = "KB_ROUTE_Z:12:-3";
		String pass = "KB_ROUTE_EASTERN_PASS:40:0";
		assertFalse(state.blocks(ball, StoryCellType.ROUTE));
		assertFalse(state.blocks(pass, StoryCellType.PASS));
		state.setActive(List.of(ball, pass));
		assertTrue(state.blocks(ball, StoryCellType.ROUTE));
		assertTrue(state.blocks(pass, StoryCellType.PASS));
		assertFalse(StoryState.blocksWithoutState(StoryCellType.PASS), "a player without state passes (the client decides)");
		state.clear();
		assertFalse(state.blocks(pass, StoryCellType.PASS));
	}

	@Test
	void waterClosesUnlessSurfing() {
		StoryState state = new StoryState();
		String key = "PALLET_TOWN:8:16";
		assertTrue(state.blocks(key, StoryCellType.WATER));
		state.setSurf(true);
		assertFalse(state.blocks(key, StoryCellType.WATER));
		state.activate(key);
		assertTrue(state.blocks(key, StoryCellType.WATER), "an active water cell stays closed even when surfing");
		assertTrue(StoryState.blocksWithoutState(StoryCellType.WATER));
		assertFalse(StoryState.blocksWithoutState(StoryCellType.OBJECT));
	}

	@Test
	void boulderAreasCloseWhereABoulderStands() {
		StoryState state = new StoryState();
		String key = "SEAFOAM_ISLANDS_B1F:4:4";
		assertFalse(state.blocks(key, StoryCellType.BOULDER_AREA));
		state.setBoulders(List.of(key));
		assertTrue(state.blocks(key, StoryCellType.BOULDER_AREA));
		assertFalse(state.blocks("SEAFOAM_ISLANDS_B1F:4:5", StoryCellType.BOULDER_AREA));
	}

	@Test
	void ledgesAreSolidOnlyFromTheDownhillSide() {
		BlockPos pos = new BlockPos(10, 64, 20);
		// A player-sized box south of the block (downhill for a south-facing ledge).
		AABB south = new AABB(10.2, 64, 21.0, 10.8, 65.8, 21.6);
		AABB north = new AABB(10.2, 64, 19.4, 10.8, 65.8, 20.0);
		AABB inside = new AABB(10.2, 64, 20.5, 10.8, 65.8, 21.1);
		assertTrue(LedgeColliderBlock.entirelyDownhill(south, pos, Direction.SOUTH));
		assertFalse(LedgeColliderBlock.entirelyDownhill(north, pos, Direction.SOUTH));
		assertFalse(LedgeColliderBlock.entirelyDownhill(inside, pos, Direction.SOUTH), "crossing it: the drop is allowed");
		assertTrue(LedgeColliderBlock.entirelyDownhill(north, pos, Direction.NORTH));
		AABB east = new AABB(11.0, 64, 20.2, 11.6, 65.8, 20.8);
		AABB west = new AABB(9.4, 64, 20.2, 10.0, 65.8, 20.8);
		assertTrue(LedgeColliderBlock.entirelyDownhill(east, pos, Direction.EAST));
		assertFalse(LedgeColliderBlock.entirelyDownhill(west, pos, Direction.EAST));
		assertTrue(LedgeColliderBlock.entirelyDownhill(west, pos, Direction.WEST));
		assertTrue(LedgeColliderBlock.WALL.max(Direction.Axis.Y) == 1.5, "24 px: above step height and any jump");
	}

	@Test
	void bikeWallsStopOnlyBikers() {
		// FA8 item 10: Emerald refuses a bike onto long grass and the like, and lets a walker on
		BlockPos pos = new BlockPos(10, 65, 20);
		AABB beside = new AABB(9.4, 64, 20.2, 10.0, 65.8, 20.8);
		AABB inside = new AABB(10.2, 64, 20.2, 10.8, 65.8, 20.8);
		assertTrue(dev.kantobridge.block.BikeWallBlock.walls(true, beside, pos), "a biker is stopped");
		assertFalse(dev.kantobridge.block.BikeWallBlock.walls(false, beside, pos), "a walker isn't");
		assertFalse(dev.kantobridge.block.BikeWallBlock.walls(true, inside, pos), "a biker already there is let out");
	}

	@Test
	void fallWallsOpenOnlyForAForcedMove() {
		// WATER_AND_BRIDGES_PLAN D4: a waterfall's foot
		BlockPos pos = new BlockPos(10, 65, 20);
		AABB beside = new AABB(9.4, 64, 20.2, 10.0, 65.8, 20.8);
		AABB inside = new AABB(10.2, 64, 20.2, 10.8, 65.8, 20.8);
		assertTrue(dev.kantobridge.block.FallWallBlock.walls(false, beside, pos), "a surfer is stopped");
		assertFalse(dev.kantobridge.block.FallWallBlock.walls(true, beside, pos), "a forced move isn't");
		assertFalse(dev.kantobridge.block.FallWallBlock.walls(false, inside, pos), "a box already there is let out");
	}

	@Test
	void theForcedFlagTravelsInAStoryState() throws Exception {
		StoryState state = new StoryState();
		StoryStateMessages.apply(state, StoryStateMessages.delta(null, null, null, null, null, true, null));
		assertTrue(state.forced());
		StoryStateMessages.apply(state, StoryStateMessages.full(java.util.List.of(), false, false, false, java.util.List.of()));
		assertFalse(state.forced(), "a full state without it: no forced move");
	}

	@Test
	void ledgesWallEveryoneButTheHopper() {
		BlockPos pos = new BlockPos(10, 64, 20);
		AABB uphill = new AABB(10.2, 64, 19.4, 10.8, 65.8, 20.0);
		AABB downhill = new AABB(10.2, 64, 21.0, 10.8, 65.8, 21.6);
		AABB inside = new AABB(10.2, 64, 20.5, 10.8, 65.8, 21.1);
		AABB west = new AABB(9.4, 64, 20.2, 10.0, 65.8, 20.8);
		AABB east = new AABB(11.0, 64, 20.2, 11.6, 65.8, 20.8);
		assertFalse(LedgeColliderBlock.walls(west, pos, Direction.SOUTH, LedgeColliderBlock.Mode.DOWNHILL), "the old rule: open from the side");
		assertTrue(LedgeColliderBlock.walls(downhill, pos, Direction.SOUTH, LedgeColliderBlock.Mode.DOWNHILL), "a wall from downhill");
		assertFalse(LedgeColliderBlock.walls(downhill, pos, Direction.SOUTH, LedgeColliderBlock.Mode.OPEN), "Gen 2's: never a wall");
		assertFalse(LedgeColliderBlock.walls(uphill, pos, Direction.SOUTH, LedgeColliderBlock.Mode.SIDES), "the hop: open from uphill");
		assertFalse(LedgeColliderBlock.walls(inside, pos, Direction.SOUTH, LedgeColliderBlock.Mode.SIDES), "a hop under way isn't trapped");
		assertTrue(LedgeColliderBlock.walls(downhill, pos, Direction.SOUTH, LedgeColliderBlock.Mode.SIDES), "nobody climbs back");
		assertTrue(LedgeColliderBlock.walls(west, pos, Direction.SOUTH, LedgeColliderBlock.Mode.SIDES), "nobody walks on from the side");
		assertTrue(LedgeColliderBlock.walls(east, pos, Direction.SOUTH, LedgeColliderBlock.Mode.SIDES));
		assertFalse(LedgeColliderBlock.walls(west, pos, Direction.EAST, LedgeColliderBlock.Mode.SIDES), "west is uphill of an east-facing ledge");
		assertTrue(LedgeColliderBlock.walls(uphill, pos, Direction.EAST, LedgeColliderBlock.Mode.SIDES), "and north is its side");
	}
}
