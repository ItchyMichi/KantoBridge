package dev.kantobridge.client.move;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.TestData;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.WorldIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * BIKE_PLAN B2 through {@link MoveController}: on a bike the hidden game drives: {@code move.ride} on each change, the empty
 * ride on leaving, no steps or faces, the engine's positions walked as legs, a hop flagged, a bump counted.
 */
class RideControllerTest {
	private static final MoveController.RideKeys UP = new MoveController.RideKeys(true, false, false, false, 180.0F, false);
	private static WorldIndex world;

	@BeforeAll
	static void readIndex() throws Exception {
		world = WorldIndex.fromJson(Files.readString(TestData.goldenIndex(), StandardCharsets.UTF_8));
	}

	private static MoveControllerTest.Rig riding(boolean bike) throws Exception {
		MoveControllerTest.Rig rig = new MoveControllerTest.Rig();
		rig.at("TEST_TOWN", 5, 8);
		rig.bridge("snapshot", "{\"ingame\":true,\"player\":{\"map\":\"TEST_TOWN\",\"x\":5,\"y\":8,\"facing\":\"up\"},\"state\":{\"mode\":\"idle\","
				+ "\"bike\":" + bike + ",\"surf\":false,\"slope\":false,\"hidden\":false,\"dark\":false,\"lit\":false}}");
		rig.tick(null, 180.0F);
		return rig;
	}

	/** One tick with the ride: the controller's beforeMove, the ride, the mover's velocity applied, afterMove. */
	private static void tick(MoveControllerTest.Rig rig, boolean bike, MoveController.RideKeys keys) {
		rig.now += 50;
		ForcedMover.Velocity v = rig.controller.beforeMove(rig.now,
				new MoveController.Frame(rig.index, false, true, true, rig.mode, false, false, rig.x, rig.z));
		rig.controller.rideTick(bike, keys);
		if (v != null) {
			rig.x += v.x();
			rig.z += v.z();
		}
		rig.controller.afterMove(rig.now, rig.x, rig.y, rig.z, null, 180.0F);
	}

	private static List<String> rides(MoveControllerTest.Rig rig) {
		return rig.port.bridge.stream().filter(s -> s.startsWith("move.ride")).toList();
	}

	@Test
	void theRideIsSentOnChangeAndEmptiedOnLeavingTheBike() throws Exception {
		MoveControllerTest.Rig rig = riding(true);
		tick(rig, true, UP);
		tick(rig, true, UP);
		assertEquals(List.of("move.ride {\"dir\":\"up\",\"b\":false}"), rides(rig));
		tick(rig, false, UP);
		tick(rig, false, UP);
		assertEquals(List.of("move.ride {\"dir\":\"up\",\"b\":false}", "move.ride {\"dir\":null,\"b\":false}"), rides(rig));
		assertFalse(rig.controller.rideOn());
	}

	@Test
	void aModeOtherThanIdleEndsTheRide() throws Exception {
		MoveControllerTest.Rig rig = riding(true);
		tick(rig, true, UP);
		rig.bridge("player.state", "{\"mode\":\"screen\",\"bike\":true,\"surf\":false,\"slope\":false,\"hidden\":false,\"dark\":false,\"lit\":false}");
		tick(rig, true, UP);
		assertEquals("move.ride {\"dir\":null,\"b\":false}", rides(rig).get(rides(rig).size() - 1));
		rig.bridge("player.state", "{\"mode\":\"idle\",\"bike\":true,\"surf\":false,\"slope\":false,\"hidden\":false,\"dark\":false,\"lit\":false}");
		tick(rig, true, UP);
		assertEquals("move.ride {\"dir\":\"up\",\"b\":false}", rides(rig).get(rides(rig).size() - 1)); // pressed again
	}

	@Test
	void noStepsWhileRidingAndTheEnginesStepsAreLegs() throws Exception {
		MoveControllerTest.Rig rig = riding(true);
		tick(rig, true, UP);
		// a stream of 4-frame steps the engine took by itself
		for (int y = 7; y >= 4; y--) {
			rig.bridge("player.pos", "{\"map\":\"TEST_TOWN\",\"x\":5,\"y\":" + y + ",\"facing\":\"up\",\"kind\":\"bike\",\"frames\":4}");
			tick(rig, true, UP);
		}
		for (int i = 0; i < 20; i++) {
			tick(rig, true, UP);
		}
		assertEquals(4, rig.controller.sync().stats().forced);
		assertEquals(0, rig.controller.sync().stats().steps);   // the crossings sent nothing
		assertEquals(0, rig.controller.sync().stats().teleports);
		assertEquals(world.toBlock("TEST_TOWN", 5, 4).z, rig.z, 0.25);
	}

	@Test
	void aHopIsFlaggedOnceAndABumpIsCounted() throws Exception {
		MoveControllerTest.Rig rig = riding(true);
		tick(rig, true, UP);
		rig.bridge("player.pos", "{\"map\":\"TEST_TOWN\",\"x\":5,\"y\":7,\"facing\":\"up\",\"kind\":\"bike\",\"frames\":16,\"jump\":\"low\"}");
		assertEquals("low", rig.controller.takeHop());
		assertNull(rig.controller.takeHop());
		rig.bridge("player.blocked", "{\"map\":\"TEST_TOWN\",\"x\":5,\"y\":7,\"dir\":\"up\",\"reason\":\"tile\"}");
		assertEquals(1, rig.controller.bumps());
	}

	@Test
	void offTheBikeStepsResumeOnTheNextCrossing() throws Exception {
		MoveControllerTest.Rig rig = riding(true);
		tick(rig, true, UP);
		tick(rig, false, MoveController.RideKeys.NONE);
		rig.port.bridge.clear();
		rig.at("TEST_TOWN", 5, 7);
		tick(rig, false, MoveController.RideKeys.NONE);
		assertTrue(rig.port.bridge.contains("move.step {\"dir\":\"up\",\"n\":1}"), rig.port.bridge.toString());
	}

	@Test
	void walkingNeverSendsARide() throws Exception {
		MoveControllerTest.Rig rig = riding(false);
		tick(rig, false, UP);
		assertTrue(rides(rig).isEmpty());
		assertFalse(rig.controller.sync().riding());
	}

	@Test
	void theLockSaysRidingWhileTheHiddenGameDrives() throws Exception {
		MoveControllerTest.Rig rig = riding(true);
		tick(rig, true, UP);
		tick(rig, true, UP);
		assertEquals(InputLock.Reason.RIDING, rig.controller.reason());
		assertEquals(Dir.UP, rig.controller.ride().sent().dir());
	}
}
