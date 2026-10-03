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
}
