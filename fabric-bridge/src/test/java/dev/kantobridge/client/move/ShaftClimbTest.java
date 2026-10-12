package dev.kantobridge.client.move;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.TestData;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.Place;
import dev.kantobridge.world.WorldIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Climbing a cave's shafts (docs/specs/CAVES_3D_PLAN.md §3.6) against the synthetic world's index: TEST_CAVE_1F's
 * ladders down to B1F at (3, 5) and (9, 3), and its hole at (5, 7). The hidden player waits beside the shaft while the
 * player climbs; the far end takes the warp.
 */
class ShaftClimbTest {
	private static WorldIndex world;

	@BeforeAll
	static void readIndex() throws Exception {
		world = WorldIndex.fromJson(Files.readString(TestData.goldenIndex(), StandardCharsets.UTF_8));
	}

	private static WorldIndex.Shaft shaft(String map, int x, int y) {
		return world.shaftEnd(new CellPos(map, x, y));
	}

	/** Linked and idle beside the ladder at 1F (3, 5), stepped onto its top: climbing, nothing sent. */
	private static MovementSyncTest.Rig atTheTop() {
		MovementSyncTest.Rig rig = MovementSyncTest.Rig.at(world, "TEST_CAVE_1F", 2, 5, Dir.RIGHT);
		rig.moveTo("TEST_CAVE_1F", 3, 5);
		assertEquals(List.of(), rig.tick(), "no step onto a shaft's top");
		assertTrue(rig.sync.climbing());
		return rig;
	}

	/** {@link #atTheTop}, then down the shaft onto B1F's floor: the take is sent. */
	private static MovementSyncTest.Rig atTheBottom() {
		MovementSyncTest.Rig rig = atTheTop();
		rig.y = shaft("TEST_CAVE_1F", 3, 5).y0() + 5;
		assertEquals(List.of(), rig.ticks(30), "in the shaft: nothing sent, no drift fix");
		rig.moveTo("TEST_CAVE_B1F", 3, 3);
		assertEquals(List.of("take TEST_CAVE_1F:3:5 TEST_CAVE_B1F:3:3:right ladder #100"), rig.tick(), "the take at the far end");
		assertTrue(rig.sync.takeHolding());
		return rig;
	}

	@Test
	void downALadderTheWarpIsTakenAtTheBottom() {
		MovementSyncTest.Rig rig = atTheBottom();
		rig.sync.onTaken(100L, true, null, rig.now);
		assertTrue(rig.sync.takes(100L), "its warp needs no fade");
		rig.sync.onTakenWarp("start", null);
		rig.sync.onTakenWarp("arrive", new Place("TEST_CAVE_B1F", 3, 3, Dir.RIGHT));
		assertFalse(rig.sync.climbing() || rig.sync.takeHolding());
		assertEquals(List.of(), rig.tick(), "standing where the hidden player now is");
		rig.moveTo("TEST_CAVE_B1F", 3, 4);
		assertEquals(List.of("step down 1"), rig.tick(), "off the ladder: an ordinary step");
		assertEquals(1, rig.sync.stats().takes);
	}

	@Test
	void upALadderTheWarpIsTakenAtTheTopsFeet() {
		MovementSyncTest.Rig rig = MovementSyncTest.Rig.at(world, "TEST_CAVE_B1F", 3, 4, Dir.UP);
		rig.moveTo("TEST_CAVE_B1F", 3, 3);
		assertEquals(List.of(), rig.tick(), "no step onto the bottom of a ladder climbed up too");
		rig.y = shaft("TEST_CAVE_B1F", 3, 3).y0() + 6;
		assertEquals(List.of(), rig.ticks(10));
		rig.moveTo("TEST_CAVE_1F", 3, 5);
		assertEquals(List.of("take TEST_CAVE_B1F:3:3 TEST_CAVE_1F:3:5:up ladder #100"), rig.tick());
	}

	@Test
	void throughAHoleTheWarpIsTakenOnTheFloorBelow() {
		MovementSyncTest.Rig rig = MovementSyncTest.Rig.at(world, "TEST_CAVE_1F", 4, 7, Dir.RIGHT);
		rig.moveTo("TEST_CAVE_1F", 5, 7);
		assertEquals(List.of(), rig.tick());
		rig.y = shaft("TEST_CAVE_1F", 5, 7).y0() + 3;
		assertEquals(List.of(), rig.tick(), "falling");
		rig.moveTo("TEST_CAVE_B1F", 5, 5);
		assertEquals(List.of("take TEST_CAVE_1F:5:7 TEST_CAVE_B1F:5:5:right hole #100"), rig.tick());
		// A hole's bottom is an ordinary cell: walked onto, it is a step.
		MovementSyncTest.Rig below = MovementSyncTest.Rig.at(world, "TEST_CAVE_B1F", 4, 5, Dir.RIGHT);
		below.moveTo("TEST_CAVE_B1F", 5, 5);
		assertEquals(List.of("step right 1"), below.tick());
	}

	@Test
	void aRefusedTakeSendsThePlayerBack() {
		MovementSyncTest.Rig rig = atTheBottom();
		rig.sync.onTaken(100L, false, "far", rig.now);
		assertEquals(List.of("teleport TEST_CAVE_1F:2:5:right"), rig.port.take(), "back to the hidden cell, through the fade");
		assertFalse(rig.sync.climbing() || rig.sync.takeHolding());
		assertEquals(1, rig.sync.stats().takesFailed);
	}

	@Test
	void aBusyGameIsAskedAgainForASecond() {
		MovementSyncTest.Rig rig = atTheBottom();
		rig.sync.onTaken(100L, false, "busy", rig.now);
		assertTrue(rig.sync.takeHolding(), "the controls stay held");
		assertEquals(List.of("take TEST_CAVE_1F:3:5 TEST_CAVE_B1F:3:3:right ladder #101"), rig.ticks(4), "asked again 200 ms on");
		List<String> after = new ArrayList<>();
		for (long seq = 101; seq < 120 && after.stream().noneMatch(c -> c.startsWith("teleport")); seq++) {
			rig.sync.onTaken(seq, false, "busy", rig.now);
			after.addAll(rig.port.take());
			after.addAll(rig.ticks(4));
		}
		assertTrue(after.contains("teleport TEST_CAVE_1F:2:5:right"), "a second of busy answers: back to the hidden cell " + after);
	}

	@Test
	void aTakeNoWarpFollowsFailsAfterThreeSeconds() {
		MovementSyncTest.Rig rig = atTheBottom();
		rig.sync.onTaken(100L, true, null, rig.now);
		List<String> calls = rig.ticks((int) (MovementSync.TAKE_TIMEOUT_MS / 50) + 2);
		assertEquals(List.of("teleport TEST_CAVE_1F:2:5:right"), calls);
	}

	@Test
	void aBattleMidShaftPutsThePlayerBackWithTheHiddenOne() {
		MovementSyncTest.Rig rig = atTheTop();
		rig.y = shaft("TEST_CAVE_1F", 3, 5).y0() + 5;
		rig.tick();
		rig.sync.setLinked(false);
		assertFalse(rig.sync.climbing());
		rig.sync.setLinked(true);
		assertEquals(List.of("teleport TEST_CAVE_1F:2:5:right"), rig.tick(), "relinked: onto the hidden cell");
	}

	@Test
	void steppingBackOffTheTopEndsTheClimb() {
		MovementSyncTest.Rig rig = atTheTop();
		rig.moveTo("TEST_CAVE_1F", 2, 5);
		assertEquals(List.of(), rig.tick(), "back where the hidden player waits: nothing to send");
		assertFalse(rig.sync.climbing());
	}

	@Test
	void steppingOffToAnotherNeighbourWalksTheHiddenPlayerRound() {
		MovementSyncTest.Rig rig = atTheTop();
		rig.moveTo("TEST_CAVE_1F", 3, 4);
		assertEquals(List.of("step up 1", "step right 2"), rig.tick(), "round the corner, never onto the shaft's top (HD12)");
		rig.pos("TEST_CAVE_1F", 2, 4, Dir.UP, "walk", 16, 1L);
		rig.pos("TEST_CAVE_1F", 3, 4, Dir.RIGHT, "walk", 16, 2L);
		assertEquals(List.of(), rig.tick(), "both answered as steps Minecraft made: nothing to steer");
	}
}
