package dev.kantobridge.client.act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.TestData;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.WorldIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The cell an A press aims at: in front of the hidden player by the camera's quadrant, or the clicked block's or
 * puppet's cell, always named in the player's own map (across an outdoor seam too); and what gen1-bridge can face.
 */
class InteractTargetTest {
	private static WorldIndex world;
	private static WorldIndex seam;

	@BeforeAll
	static void readIndex() throws Exception {
		world = WorldIndex.fromJson(Files.readString(TestData.goldenIndex(), StandardCharsets.UTF_8));
		String hash = "c".repeat(64);
		// Two outdoor maps on one grid: NORTH_MAP directly above SOUTH_MAP, and an interior under it.
		seam = new WorldIndex("red", hash, List.of(
				new MapPlacement("SOUTH_MAP", 0, 64, 0, 10, 10, 0, MapPlacement.Kind.SURFACE, "P", "kantobridge:kanto_outdoor", false, null),
				new MapPlacement("NORTH_MAP", 0, 64, -20, 10, 10, 0, MapPlacement.Kind.SURFACE, "P", "kantobridge:kanto_outdoor", false, null),
				new MapPlacement("SOUTH_HOUSE", 0, 42, 0, 5, 5, 1, MapPlacement.Kind.INTERIOR, "P", "kantobridge:kanto_interior", false,
						"SOUTH_MAP")),
				hash, StoryCellTable.of(List.of()), List.of());
	}

	@Test
	void theCellInFrontFollowsTheCameraQuadrant() {
		assertEquals(new CellPos("TEST_TOWN", 10, 8), InteractTarget.faced("TEST_TOWN", 9, 8, Dir.fromYaw(-90.0F)), "looking east");
		assertEquals(new CellPos("TEST_TOWN", 9, 7), InteractTarget.faced("TEST_TOWN", 9, 8, Dir.fromYaw(170.0F)), "looking north");
		assertEquals(new CellPos("TEST_TOWN", 9, 9), InteractTarget.faced("TEST_TOWN", 9, 8, Dir.fromYaw(10.0F)));
		assertEquals(new CellPos("TEST_TOWN", 8, 8), InteractTarget.faced("TEST_TOWN", 9, 8, Dir.fromYaw(80.0F)));
		assertEquals(new CellPos("SOUTH_MAP", 3, -1), InteractTarget.faced("SOUTH_MAP", 3, 0, Dir.UP), "just outside the map at a seam");
	}

	@Test
	void theAKeyAimsFromWhereBothPlayersStand() {
		MapPlacement town = world.map("TEST_TOWN");
		double x = town.x() + 9 * 2 + 1.0;
		double z = town.z() + 8 * 2 + 1.0;
		assertEquals(new CellPos("TEST_TOWN", 10, 8), InteractTarget.inFront(world, "TEST_TOWN", 9, 8, x, 64.0, z, Dir.RIGHT));
		assertEquals(new CellPos("TEST_TOWN", 9, 7), InteractTarget.inFront(world, "TEST_TOWN", 9, 8, x + 0.9, 64.0, z - 0.9, Dir.UP),
				"anywhere on the cell's 2×2 blocks");
		assertNull(InteractTarget.inFront(world, "TEST_TOWN", 9, 8, x + 2.0, 64.0, z, Dir.RIGHT), "a cell apart: out of step, no press");
		assertNull(InteractTarget.inFront(world, "TEST_TOWN", 9, 8, town.x() - 30.0, 64.0, z, Dir.RIGHT), "off the map");
		// Across a seam the feet's cell is named in the hidden player's map.
		assertEquals(new CellPos("SOUTH_MAP", 3, -2), InteractTarget.inFront(seam, "SOUTH_MAP", 3, -1, 7.0, 64.0, -1.5, Dir.UP));
	}

	@Test
	void aClickedBlocksCellIsItsColumnsCell() {
		MapPlacement town = world.map("TEST_TOWN");
		// The sign at 11,5: its column's lower block and the block above both name the cell.
		int x = town.x() + 11 * 2 + 1;
		int z = town.z() + 5 * 2;
		assertEquals(new CellPos("TEST_TOWN", 11, 5), InteractTarget.clicked(world, "TEST_TOWN", x, 64, z));
		assertEquals(new CellPos("TEST_TOWN", 11, 5), InteractTarget.clicked(world, "TEST_TOWN", x, 65, z));
		assertEquals(new CellPos("TEST_TOWN", 11, 5), InteractTarget.clicked(world, "TEST_TOWN", x, 63, z), "the floor block too");
		// The interior's own cells, from inside it.
		MapPlacement house = world.map("TEST_HOUSE");
		assertEquals(new CellPos("TEST_HOUSE", 6, 4),
				InteractTarget.clicked(world, "TEST_HOUSE", house.x() + 12, house.y(), house.z() + 8));
		// Off every map, or a surface block from inside an interior: nothing.
		assertNull(InteractTarget.clicked(world, "TEST_TOWN", town.x() - 50, 64, town.z() - 50));
		assertNull(InteractTarget.clicked(world, "TEST_HOUSE", x, 64, z));
	}

	@Test
	void acrossAnOutdoorSeamTheCellIsNamedInThePlayersMap() {
		// NORTH_MAP's bottom row (y 9) is SOUTH_MAP's row -1.
		assertEquals(new CellPos("SOUTH_MAP", 3, -1), InteractTarget.clicked(seam, "SOUTH_MAP", 7, 64, -1));
		assertEquals(new CellPos("SOUTH_MAP", 3, -1), InteractTarget.cellOf(seam, "SOUTH_MAP", "NORTH_MAP", 3, 9));
		assertEquals(new CellPos("SOUTH_MAP", 3, 4), InteractTarget.cellOf(seam, "SOUTH_MAP", "SOUTH_MAP", 3, 4));
		// Not across levels.
		assertNull(InteractTarget.cellOf(seam, "SOUTH_HOUSE", "SOUTH_MAP", 3, 4));
		assertNull(InteractTarget.cellOf(seam, "SOUTH_MAP", "NO_SUCH_MAP", 3, 4));
	}

	@Test
	void gen1bridgeCanFaceStraightLinesUpToACounterAway() {
		assertTrue(InteractTarget.reachable("TEST_TOWN", 9, 8, new CellPos("TEST_TOWN", 10, 8)), "adjacent");
		assertTrue(InteractTarget.reachable("TEST_TOWN", 9, 8, new CellPos("TEST_TOWN", 9, 6)), "behind a counter");
		assertFalse(InteractTarget.reachable("TEST_TOWN", 9, 8, new CellPos("TEST_TOWN", 9, 5)), "too far");
		assertFalse(InteractTarget.reachable("TEST_TOWN", 9, 8, new CellPos("TEST_TOWN", 10, 9)), "diagonal");
		assertFalse(InteractTarget.reachable("TEST_TOWN", 9, 8, new CellPos("TEST_TOWN", 9, 8)), "its own cell");
		assertFalse(InteractTarget.reachable("TEST_TOWN", 9, 8, new CellPos("TEST_HOUSE", 10, 8)), "another map");
		assertEquals(Dir.UP, InteractTarget.towards(9, 8, new CellPos("TEST_TOWN", 9, 6)));
		assertEquals(Dir.RIGHT, InteractTarget.towards(9, 8, new CellPos("TEST_TOWN", 10, 8)));
	}

	@Test
	void theGateLetsOnePressThroughAndOnlyWhenIdle() {
		assertTrue(ActionGate.idle(true, true, true, "idle", false, false));
		assertFalse(ActionGate.idle(false, true, true, "idle", false, false), "creative, spectator or not a Kanto world");
		assertFalse(ActionGate.idle(true, false, true, "idle", false, false), "not connected");
		assertFalse(ActionGate.idle(true, true, false, "idle", false, false), "at the title screen");
		assertFalse(ActionGate.idle(true, true, true, "screen", false, false), "the hidden game is busy");
		assertFalse(ActionGate.idle(true, true, true, "idle", true, false), "held (a warp, a forced move)");
		assertFalse(ActionGate.idle(true, true, true, "idle", false, true), "one of our screens is open");
		ActionGate gate = new ActionGate();
		assertTrue(gate.tryInteract(1000));
		assertFalse(gate.tryInteract(1200), "right-click repeats every 200 ms while held");
		assertTrue(gate.tryInteract(1000 + ActionGate.INTERACT_COOLDOWN_MS));
		assertTrue(gate.tryButton(0));
		assertFalse(gate.tryButton(100));
		assertEquals(2, gate.interacts());
		assertEquals(1, gate.buttons());
		assertEquals(2, gate.refused());
	}
}
