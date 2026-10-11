package dev.kantobridge.client.move;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.TestData;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.Place;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.WorldIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The movement sync model against the synthetic world's WorldIndex (TEST_TOWN on the surface, TEST_HOUSE one layer
 * down): steps on crossings, edge pushes, facing, answers, snap-back, drift, settling and the gates on all of it.
 */
class MovementSyncTest {
	private static WorldIndex world;

	@BeforeAll
	static void readIndex() throws Exception {
		world = WorldIndex.fromJson(Files.readString(TestData.goldenIndex(), StandardCharsets.UTF_8));
	}

	/** Records what the model asks for, as short strings. */
	static final class FakePort implements MovementSync.Port {
		final WorldIndex index;
		final List<String> calls = new ArrayList<>();
		boolean warping;
		boolean forced;

		FakePort(WorldIndex index) {
			this.index = index;
		}

		@Override
		public void step(Dir dir, long n, boolean push) {
			this.calls.add("step " + dir.id() + " " + n + (push ? " push" : ""));
		}

		@Override
		public void face(Dir dir) {
			this.calls.add("face " + dir.id());
		}

		@Override
		public void walkTo(Spot target, int frames) {
			CellPos cell = target.cell(this.index);
			this.calls.add("walk " + (cell == null ? "?" : cell.key()) + " " + frames);
		}

		@Override
		public void teleport(Place to, long now) {
			this.calls.add("teleport " + to);
		}

		@Override
		public boolean warping() {
			return this.warping;
		}

		@Override
		public boolean forcedMoving() {
			return this.forced;
		}

		List<String> take() {
			List<String> out = List.copyOf(this.calls);
			this.calls.clear();
			return out;
		}
	}

	/** A player in a world: position, camera and keys, one world tick (50 ms) at a time. */
	static final class Rig {
		final WorldIndex index;
		final FakePort port;
		final MovementSync sync;
		long now = 10_000;
		double x;
		double y;
		double z;
		float yaw;
		@Nullable Dir intent;

		Rig(WorldIndex index) {
			this.index = index;
			this.port = new FakePort(index);
			this.sync = new MovementSync(this.port);
			this.sync.setIndex(index);
		}

		/** Linked, idle, standing on the hidden player's cell and looking the way it faces. */
		static Rig at(WorldIndex index, String map, int cx, int cy, Dir facing) {
			Rig rig = new Rig(index);
			rig.moveTo(map, cx, cy);
			rig.yaw = facing.yaw();
			rig.sync.onSnapshot(true, new Place(map, cx, cy, facing), "idle");
			rig.sync.setLinked(true);
			rig.tick();
			assertEquals(List.of(), rig.port.take(), "standing on the hidden cell needs nothing");
			return rig;
		}

		void moveTo(String map, int cx, int cy) {
			Vec3 centre = this.index.toBlock(map, cx, cy);
			this.x = centre.x;
			this.y = centre.y;
			this.z = centre.z;
		}

		List<String> tick() {
			this.now += 50;
			this.sync.tick(this.now, this.x, this.y, this.z, this.intent, this.yaw);
			return this.port.take();
		}

		List<String> ticks(int count) {
			List<String> all = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				all.addAll(this.tick());
			}
			return all;
		}

		void pos(String map, int cx, int cy, Dir facing, String kind, int frames, @Nullable Long n) {
			this.sync.onPosition(new Place(map, cx, cy, facing), kind, frames, n, this.now);
		}
	}

	// ---- Wild Routes zones (docs/specs/WILD_ROUTES_PLAN.md §4.5) ----

	/** The synthetic world with a made-up zone: its route map's 2x2 cells at the trailhead, inside a 400-block box. */
	private static WorldIndex zoned() {
		List<MapPlacement> maps = new ArrayList<>(world.maps().values());
		maps.add(new MapPlacement("KB_ROUTE_TEST_ZONE", 3_200_100, 64, 3_210_100, 2, 2, 0, MapPlacement.Kind.ROUTE, "TEST_PALETTE",
				"kantobridge:wild_forest", false, null));
		return new WorldIndex(world.game(), world.exportHash(), maps, world.slotMapHash(), world.storyCells(), world.habitats(),
				List.of(new WorldIndex.Zone("test_zone", "KB_ROUTE_TEST_ZONE", "forest", 3_200_000, 64, 3_210_000, 400, 400,
						"kantobridge:wild_forest")));
	}

	@Test
	void outInAZoneNothingIsSentAndNothingPullsYouBack() {
		WorldIndex zoned = zoned();
		Rig rig = Rig.at(zoned, "KB_ROUTE_TEST_ZONE", 1, 0, Dir.RIGHT);
		rig.x += 30;
		rig.z += 17;
		assertEquals(List.of(), rig.tick(), "off the trailhead: no step");
		rig.yaw = Dir.UP.yaw();
		assertEquals(List.of(), rig.ticks((int) (MovementSync.DRIFT_MS / 50) * 4), "no drift fix and no faces out in the zone");
		assertEquals(0, rig.sync.stats().drifts);
		assertTrue(rig.sync.stats().zoneTicks > 0);
		// back onto x1: nothing; then on to A, an ordinary step west (A's script takes the hidden player home)
		rig.moveTo("KB_ROUTE_TEST_ZONE", 1, 0);
		rig.yaw = Dir.RIGHT.yaw();
		assertEquals(List.of(), rig.tick());
		rig.moveTo("KB_ROUTE_TEST_ZONE", 0, 0);
		assertEquals(List.of("step left 1"), rig.tick());
	}

	@Test
	void joiningOutInAZoneLeavesYouWhereYouAre() {
		WorldIndex zoned = zoned();
		Rig rig = new Rig(zoned);
		rig.moveTo("KB_ROUTE_TEST_ZONE", 1, 0);
		rig.x += 120;
		rig.sync.onSnapshot(true, new Place("KB_ROUTE_TEST_ZONE", 1, 0, Dir.RIGHT), "idle");
		rig.sync.setLinked(true);
		assertEquals(List.of(), rig.ticks(40), "the hidden player waits on x1; you stay out in the zone");
	}

	@Test
	void aZoneDoesntHideAHiddenPlayerSomewhereElse() {
		WorldIndex zoned = zoned();
		Rig rig = Rig.at(zoned, "TEST_TOWN", 5, 8, Dir.UP);
		rig.x = 3_200_150;
		rig.z = 3_210_150;
		List<String> calls = rig.ticks((int) (MovementSync.DRIFT_MS / 50) + 4);
		assertTrue(calls.stream().anyMatch(c -> c.startsWith("teleport")), "in a zone's box but the hidden player is in town: back to town " + calls);
	}

	@Test
	void crossingsInAllFourDirectionsSendNumberedSteps() {
		Rig rig = Rig.at(world, "TEST_TOWN", 5, 8, Dir.UP);
		rig.x += 0.6;
		assertEquals(List.of(), rig.tick(), "moving inside a cell sends nothing");
		rig.moveTo("TEST_TOWN", 5, 7);
		assertEquals(List.of("step up 1"), rig.tick());
		rig.pos("TEST_TOWN", 5, 7, Dir.UP, "walk", 16, 1L);
		rig.moveTo("TEST_TOWN", 6, 7);
		assertEquals(List.of("step right 2"), rig.tick());
		rig.pos("TEST_TOWN", 6, 7, Dir.RIGHT, "walk", 16, 2L);
		rig.moveTo("TEST_TOWN", 6, 8);
		assertEquals(List.of("step down 3"), rig.tick());
		rig.pos("TEST_TOWN", 6, 8, Dir.DOWN, "walk", 16, 3L);
		rig.moveTo("TEST_TOWN", 5, 8);
		assertEquals(List.of("step left 4"), rig.tick());
		rig.pos("TEST_TOWN", 5, 8, Dir.LEFT, "walk", 16, 4L);
		assertEquals(0, rig.sync.pendingSteps(), "every step was answered");
		assertEquals(4, rig.sync.stats().steps);
		assertEquals(0, rig.sync.stats().forced, "answers for steps Minecraft made move nothing");
		assertEquals(5, rig.sync.nextStep());
	}

	@Test
	void aCornerCutBecomesTwoStepsInCrossingOrder() {
		Rig rig = Rig.at(world, "TEST_TOWN", 5, 8, Dir.UP);
		double startX = rig.x;
		double startZ = rig.z;
		// From the centre of (5,8) to just inside (6,7): the east boundary (1 block away) comes before the north one.
		rig.x = startX + 1.5;
		rig.z = startZ - 1.1;
		assertEquals(List.of("step right 1", "step up 2"), rig.tick());
		rig.pos("TEST_TOWN", 6, 8, Dir.RIGHT, "walk", 16, 1L);
		rig.pos("TEST_TOWN", 6, 7, Dir.UP, "walk", 16, 2L);
		assertEquals(0, rig.sync.stats().forced, "the intermediate cell is the one the first step expects");

		// The other way round: north first.
		Rig other = Rig.at(world, "TEST_TOWN", 5, 8, Dir.UP);
		other.x = startX + 1.1;
		other.z = startZ - 1.5;
		assertEquals(List.of("step up 1", "step right 2"), other.tick());
	}

	@Test
	void largerJumpsAreLeftToDriftCorrection() {
		Rig rig = Rig.at(world, "TEST_TOWN", 5, 8, Dir.UP);
		rig.moveTo("TEST_TOWN", 5, 5);
		assertEquals(List.of(), rig.tick());
		assertEquals(1, rig.sync.stats().jumps);
	}

	@Test
	void pushingAgainstAnEdgeSendsPushStepsOnTheirSchedule() {
		Rig rig = Rig.at(world, "TEST_TOWN", 5, 8, Dir.DOWN);
		rig.intent = Dir.DOWN;
		assertEquals(List.of(), rig.ticks(MovementSync.PUSH_TICKS - 1), "not before 8 ticks");
		assertEquals(List.of("step down 1 push"), rig.tick());
		// gen1-bridge refuses it (a wall): the player is already on the hidden cell, so nothing snaps.
		rig.sync.onBlocked(new Place("TEST_TOWN", 5, 8, Dir.DOWN), 1L, rig.now);
		assertEquals(List.of(), rig.ticks(MovementSync.PUSH_REPEAT_TICKS - 1));
		assertEquals(List.of("step down 2 push"), rig.tick(), "repeats while the push lasts");
		rig.sync.onBlocked(new Place("TEST_TOWN", 5, 8, Dir.DOWN), 2L, rig.now);

		// Turning to another direction starts the count again.
		rig.intent = Dir.LEFT;
		assertEquals(List.of(), rig.ticks(MovementSync.PUSH_TICKS - 1));
		assertEquals(List.of("step left 3 push"), rig.tick());
		rig.sync.onBlocked(new Place("TEST_TOWN", 5, 8, Dir.LEFT), 3L, rig.now);

		// Moving along the pushed direction is no push.
		rig.intent = Dir.UP;
		for (int i = 0; i < 12; i++) {
			rig.z -= 0.05;
			assertEquals(List.of(), rig.tick(), "tick " + i);
		}
		// Letting go resets too.
		rig.intent = null;
		rig.tick();
		rig.intent = Dir.UP;
		assertEquals(List.of(), rig.ticks(MovementSync.PUSH_TICKS - 1));
		assertEquals(List.of("step up 4 push"), rig.tick());
	}

	@Test
	void aPushWaitsForStepsInFlight() {
		Rig rig = Rig.at(world, "TEST_TOWN", 5, 8, Dir.DOWN);
		rig.moveTo("TEST_TOWN", 5, 9);
		assertEquals(List.of("step down 1"), rig.tick());
		rig.intent = Dir.DOWN;
		assertEquals(List.of(), rig.ticks(MovementSync.PUSH_TICKS + 2), "step 1 isn't answered yet");
		rig.pos("TEST_TOWN", 5, 9, Dir.DOWN, "walk", 16, 1L);
		assertEquals(List.of("step down 2 push"), rig.tick(), "overdue: sent as soon as nothing is in flight");
	}

	@Test
	void standingStillTurnsTheHiddenPlayerToTheCameraQuadrant() {
		Rig rig = Rig.at(world, "TEST_TOWN", 5, 8, Dir.DOWN);
		rig.yaw = 170.0F;
		assertEquals(List.of(), rig.ticks(MovementSync.FACE_STABLE_TICKS - 1), "the quadrant must hold for 4 ticks");
		assertEquals(List.of("face up"), rig.tick());
		assertEquals(List.of(), rig.ticks(5), "not repeated while gen1-bridge turns");
		rig.pos("TEST_TOWN", 5, 8, Dir.UP, "sync", 0, null);
		assertEquals(List.of(), rig.ticks(10), "facing agrees");

		// Glancing across a quadrant boundary for less than 4 ticks does nothing.
		rig.yaw = 95.0F;
		rig.ticks(2);
		rig.yaw = 175.0F;
		assertEquals(List.of(), rig.ticks(10));

		// No turning while keys are held or the player still moves.
		rig.yaw = -80.0F;
		rig.intent = Dir.RIGHT;
		assertEquals(List.of(), rig.ticks(MovementSync.PUSH_TICKS - 1));
		rig.intent = null;
		for (int i = 0; i < 6; i++) {
			rig.x += 0.05;
			assertEquals(List.of(), rig.tick(), "moving, tick " + i);
		}
		assertEquals(List.of(), rig.ticks(MovementSync.FACE_STABLE_TICKS - 1));
		assertEquals(List.of("face right"), rig.tick());
	}

	@Test
	void aRefusedStepSnapsBackToTheHiddenCell() {
		Rig rig = Rig.at(world, "TEST_TOWN", 5, 8, Dir.UP);
		rig.moveTo("TEST_TOWN", 5, 7);
		assertEquals(List.of("step up 1"), rig.tick());
		rig.sync.onBlocked(new Place("TEST_TOWN", 5, 8, Dir.UP), 1L, rig.now);
		assertEquals(List.of("walk TEST_TOWN:5:8 " + MovementSync.SNAP_FRAMES), rig.port.take());
		assertEquals(1, rig.sync.stats().snaps);
	}

	@Test
	void aRefusedStepWaitsForNewerStepsBeforeSnapping() {
		Rig rig = Rig.at(world, "TEST_TOWN", 5, 8, Dir.UP);
		rig.moveTo("TEST_TOWN", 5, 7);
		rig.tick();
		rig.moveTo("TEST_TOWN", 5, 6);
		assertEquals(List.of("step up 2"), rig.tick());
		rig.sync.onBlocked(new Place("TEST_TOWN", 5, 8, Dir.UP), 1L, rig.now);
		assertEquals(List.of(), rig.port.take(), "step 2 is still on its way");
		rig.sync.onBlocked(new Place("TEST_TOWN", 5, 8, Dir.UP), 2L, rig.now);
		assertEquals(List.of("walk TEST_TOWN:5:8 " + 2 * MovementSync.SNAP_FRAMES), rig.port.take(), "two cells back");
	}

	@Test
	void driftIsSteeredBackAfterHalfASecond() {
		Rig rig = Rig.at(world, "TEST_TOWN", 5, 8, Dir.UP);
		rig.moveTo("TEST_TOWN", 7, 8);
		assertEquals(List.of(), rig.tick(), "two cells in one tick: no steps");
		assertEquals(List.of(), rig.ticks((int) (MovementSync.DRIFT_MS / 50)), "not within 500 ms");
		assertEquals(List.of("walk TEST_TOWN:5:8 " + 2 * MovementSync.WALK_FRAMES), rig.tick());
		assertEquals(1, rig.sync.stats().drifts);
	}

	@Test
	void farOrCrossLayerDriftTeleports() {
		Rig rig = Rig.at(world, "TEST_TOWN", 5, 8, Dir.UP);
		rig.moveTo("TEST_TOWN", 9, 8);
		rig.tick();
		rig.ticks((int) (MovementSync.DRIFT_MS / 50));
		assertEquals(List.of("teleport TEST_TOWN:5:8:up"), rig.tick(), "more than two cells away");

		Rig inside = Rig.at(world, "TEST_TOWN", 5, 8, Dir.UP);
		inside.moveTo("TEST_HOUSE", 5, 8);
		inside.tick();
		inside.ticks((int) (MovementSync.DRIFT_MS / 50));
		assertEquals(List.of("teleport TEST_TOWN:5:8:up"), inside.tick(), "another layer");
	}

	@Test
	void driftWaitsForStepsInFlight() {
		Rig rig = Rig.at(world, "TEST_TOWN", 5, 8, Dir.UP);
		rig.moveTo("TEST_TOWN", 5, 7);
		assertEquals(List.of("step up 1"), rig.tick());
		assertEquals(List.of(), rig.ticks(20), "an unanswered step explains the difference");
		// Unanswered for over 1.5 s: forgotten (lost), then drift steers back after another half second.
		assertEquals(List.of(), rig.ticks((int) ((MovementSync.STEP_TIMEOUT_MS - 1000) / 50)));
		assertEquals(0, rig.sync.stats().lost, "1.5 s exactly is still in time");
		assertEquals(List.of(), rig.tick());
		assertEquals(1, rig.sync.stats().lost);
		List<String> later = rig.ticks((int) (MovementSync.DRIFT_MS / 50) + 2);
		assertEquals(List.of("walk TEST_TOWN:5:8 " + MovementSync.WALK_FRAMES), later);
	}

	@Test
	void nothingIsSentUnlessLinkedIdleAndFree() {
		Rig rig = Rig.at(world, "TEST_TOWN", 5, 8, Dir.UP);
		rig.sync.setLinked(false);
		rig.moveTo("TEST_TOWN", 5, 7);
		assertEquals(List.of(), rig.tick(), "not linked");
		rig.pos("TEST_TOWN", 5, 9, Dir.DOWN, "walk", 16, null);
		assertEquals(List.of(), rig.port.take(), "not followed: the hidden player's moves are only noted");
		rig.sync.setLinked(true);
		assertEquals(List.of("teleport TEST_TOWN:5:9:down"), rig.tick(), "linking again puts the player on the hidden cell");

		Rig busy = Rig.at(world, "TEST_TOWN", 5, 8, Dir.UP);
		busy.sync.onMode("cutscene");
		busy.moveTo("TEST_TOWN", 5, 7);
		busy.intent = Dir.UP;
		assertEquals(List.of(), busy.ticks(30), "not idle: no steps, pushes, faces or drift fixes");
		busy.sync.onMode("idle");
		busy.moveTo("TEST_TOWN", 5, 6);
		busy.intent = null;
		assertEquals(List.of("step up 1"), busy.tick(), "idle again: the next crossing counts");

		Rig warping = Rig.at(world, "TEST_TOWN", 5, 8, Dir.UP);
		warping.port.warping = true;
		warping.moveTo("TEST_TOWN", 5, 7);
		assertEquals(List.of(), warping.ticks(30), "warping");

		Rig forced = Rig.at(world, "TEST_TOWN", 5, 8, Dir.UP);
		forced.port.forced = true;
		forced.moveTo("TEST_TOWN", 5, 7);
		assertEquals(List.of(), forced.ticks(30), "a forced move is running");
		forced.port.forced = false;
		forced.moveTo("TEST_TOWN", 5, 6);
		assertEquals(List.of("step up 1"), forced.tick());
	}

	@Test
	void answersTellOwnStepsFromForcedMoves() {
		Rig rig = Rig.at(world, "TEST_TOWN", 5, 8, Dir.UP);
		rig.moveTo("TEST_TOWN", 5, 7);
		assertEquals(List.of("step up 1"), rig.tick());
		// No n (a gen1-bridge that doesn't echo it): the oldest step's expected cell claims it.
		rig.pos("TEST_TOWN", 5, 7, Dir.UP, "walk", 16, null);
		assertEquals(List.of(), rig.port.take());
		assertEquals(0, rig.sync.pendingSteps());

		// A walk nobody asked for (the user walks in Gen1Recomp's window): the player follows it.
		rig.pos("TEST_TOWN", 5, 6, Dir.UP, "walk", 16, null);
		assertEquals(List.of("walk TEST_TOWN:5:6 16"), rig.port.take());

		// An echoed step that lands elsewhere than Minecraft went is walked too.
		Rig other = Rig.at(world, "TEST_TOWN", 5, 8, Dir.UP);
		other.moveTo("TEST_TOWN", 5, 7);
		other.tick();
		other.pos("TEST_TOWN", 6, 8, Dir.RIGHT, "walk", 16, 1L);
		assertEquals(List.of("walk TEST_TOWN:6:8 16"), other.port.take());

		// The forced mover takes the player there (no steps meanwhile); a late answer for an unknown step that matches
		// where the player now stands needs nothing.
		other.port.forced = true;
		other.moveTo("TEST_TOWN", 6, 8);
		assertEquals(List.of(), other.tick());
		other.port.forced = false;
		other.pos("TEST_TOWN", 6, 8, Dir.RIGHT, "walk", 16, 99L);
		assertEquals(List.of(), other.port.take());
	}

	@Test
	void anExitMatsStepIsAnsweredBySyncAndTheWalkOutIsWalked() {
		// gen1-bridge's order: warp start, warp arrive (the town's door), the step's answer as a sync with its n, then
		// the door walk-out as a script move without n.
		Rig rig = Rig.at(world, "TEST_HOUSE", 4, 8, Dir.DOWN);
		rig.intent = Dir.DOWN;
		rig.ticks(MovementSync.PUSH_TICKS - 1);
		assertEquals(List.of("step down 1 push"), rig.tick());
		rig.sync.onWarpStart();
		rig.port.warping = true;
		rig.sync.onWarpArrive(new Place("TEST_TOWN", 5, 5, Dir.DOWN));
		rig.pos("TEST_TOWN", 5, 5, Dir.DOWN, "sync", 0, 1L);
		assertEquals(0, rig.sync.pendingSteps(), "the sync answered the push");
		rig.pos("TEST_TOWN", 5, 6, Dir.DOWN, "script", 16, null);
		rig.intent = null;
		assertEquals(List.of(), rig.ticks(3), "nothing while the warp runs");
		// The server's teleport put the player on the door cell; the fade opens.
		rig.moveTo("TEST_TOWN", 5, 5);
		rig.port.warping = false;
		rig.sync.onWarpEnded();
		assertEquals(List.of("walk TEST_TOWN:5:6 " + MovementSync.WALK_FRAMES), rig.tick(), "the walk-out, at walking speed");
		assertEquals(0, rig.sync.stats().lost);
	}

	@Test
	void aSyncAnswerWithoutAVisibleStepSnapsThePlayer() {
		Rig rig = Rig.at(world, "TEST_TOWN", 5, 8, Dir.UP);
		rig.intent = Dir.UP;
		rig.ticks(MovementSync.PUSH_TICKS - 1);
		assertEquals(List.of("step up 1 push"), rig.tick());
		rig.pos("TEST_TOWN", 6, 8, Dir.UP, "sync", 0, 1L);
		assertEquals(List.of("walk TEST_TOWN:6:8 " + MovementSync.SNAP_FRAMES), rig.port.take());
		assertEquals(0, rig.sync.pendingSteps());
	}

	@Test
	void aStrengthPushLeavesThePlayerWhereItIs() {
		Rig rig = Rig.at(world, "TEST_TOWN", 5, 8, Dir.UP);
		rig.intent = Dir.UP;
		rig.ticks(MovementSync.PUSH_TICKS - 1);
		assertEquals(List.of("step up 1 push"), rig.tick());
		rig.pos("TEST_TOWN", 5, 8, Dir.UP, "push", 0, 1L);
		assertEquals(List.of(), rig.port.take(), "the boulder moved, not the player");
		assertEquals(0, rig.sync.pendingSteps());
		assertEquals(0, rig.sync.stats().forced);
	}

	@Test
	void walkingIntoABoulderTwiceBumpsThenPushesIt() {
		// M8 wave 2: with Strength on, Gen 1 needs two steps into the boulder; holding the key against it sends both.
		Rig rig = Rig.at(world, "TEST_TOWN", 5, 8, Dir.UP);
		rig.intent = Dir.UP;
		rig.ticks(MovementSync.PUSH_TICKS - 1);
		assertEquals(List.of("step up 1 push"), rig.tick());
		// The first step only bumps it: refused, the player stays where both stand.
		rig.sync.onBlocked(new Place("TEST_TOWN", 5, 8, Dir.UP), 1L, rig.now);
		assertEquals(List.of(), rig.port.take(), "nothing to snap back");
		assertEquals(List.of(), rig.ticks(MovementSync.PUSH_REPEAT_TICKS - 1));
		assertEquals(List.of("step up 2 push"), rig.tick(), "still pushing: the second step");
		rig.pos("TEST_TOWN", 5, 8, Dir.UP, "push", 0, 2L);
		assertEquals(List.of(), rig.port.take(), "the boulder moved, not the player");
		assertEquals(0, rig.sync.pendingSteps());
		// The way is open now: the player's own walk into the cell is an ordinary step.
		rig.moveTo("TEST_TOWN", 5, 7);
		assertEquals(List.of("step up 3"), rig.tick());
	}

	@Test
	void slopeStepsAreClaimedLikeWalksAndTheRollIsWalked() {
		Rig rig = Rig.at(world, "TEST_TOWN", 5, 8, Dir.DOWN);
		rig.moveTo("TEST_TOWN", 5, 9);
		assertEquals(List.of("step down 1"), rig.tick());
		rig.pos("TEST_TOWN", 5, 9, Dir.DOWN, "slope", 8, 1L);
		assertEquals(List.of(), rig.port.take(), "the player's own step downhill");
		rig.pos("TEST_TOWN", 5, 10, Dir.DOWN, "slope", 8, null);
		assertEquals(List.of("walk TEST_TOWN:5:10 8"), rig.port.take(), "the bike rolls on its own");
	}

	@Test
	void eachAnswerRemovesOnlyItsOwnStep() {
		Rig rig = Rig.at(world, "TEST_TOWN", 5, 8, Dir.UP);
		rig.moveTo("TEST_TOWN", 5, 7);
		rig.tick();
		rig.moveTo("TEST_TOWN", 5, 6);
		assertEquals(List.of("step up 2"), rig.tick());
		// Step 2 was dropped from gen1-bridge's queue; step 1 is still on its way and gets its own answer.
		rig.sync.onBlocked(new Place("TEST_TOWN", 5, 8, Dir.UP), 2L, rig.now);
		assertEquals(1, rig.sync.pendingSteps());
		assertEquals(List.of(), rig.port.take(), "step 1 is still waiting: no snap yet");
		rig.pos("TEST_TOWN", 5, 7, Dir.UP, "walk", 16, 1L);
		assertEquals(0, rig.sync.pendingSteps());
		assertEquals(List.of(), rig.port.take(), "step 1 was Minecraft's own");
	}

	@Test
	void movesTheGameMakesAreWalkedOrTeleported() {
		Rig rig = Rig.at(world, "TEST_TOWN", 5, 8, Dir.DOWN);
		rig.pos("TEST_TOWN", 5, 9, Dir.DOWN, "script", 16, null);
		assertEquals(List.of("walk TEST_TOWN:5:9 16"), rig.port.take());
		rig.port.forced = true;
		// A path continues from where the last forced move ends, not from where the player is now.
		rig.pos("TEST_TOWN", 5, 10, Dir.DOWN, "script", 16, null);
		rig.pos("TEST_TOWN", 5, 11, Dir.DOWN, "script", 16, null);
		assertEquals(List.of("walk TEST_TOWN:5:10 16", "walk TEST_TOWN:5:11 16"), rig.port.take());
		rig.pos("TEST_TOWN", 5, 13, Dir.DOWN, "ledge", 32, null);
		assertEquals(List.of("walk TEST_TOWN:5:13 32"), rig.port.take(), "a ledge hop: two cells in 32 frames");
		rig.pos("TEST_TOWN", 15, 13, Dir.DOWN, "spin", 48, null);
		assertEquals(List.of("teleport TEST_TOWN:15:13:down"), rig.port.take(), "too far to walk");
		assertEquals(5, rig.sync.stats().forced);
	}

	@Test
	void syncPositionsSnapOnlyWhenTheCellDiffers() {
		Rig rig = Rig.at(world, "TEST_TOWN", 5, 8, Dir.DOWN);
		rig.pos("TEST_TOWN", 5, 8, Dir.LEFT, "sync", 0, null);
		assertEquals(List.of(), rig.port.take(), "a turn");
		rig.pos("TEST_TOWN", 6, 8, Dir.LEFT, "sync", 0, null);
		assertEquals(List.of("walk TEST_TOWN:6:8 " + MovementSync.SNAP_FRAMES), rig.port.take());

		Rig stepping = Rig.at(world, "TEST_TOWN", 5, 8, Dir.DOWN);
		stepping.moveTo("TEST_TOWN", 6, 8);
		assertEquals(List.of("step right 1"), stepping.tick());
		stepping.pos("TEST_TOWN", 5, 8, Dir.RIGHT, "sync", 0, null);
		assertEquals(List.of(), stepping.port.take(), "the hidden player turned before stepping");
	}

	@Test
	void positionsAreSettledOnJoinAndAfterWarps() {
		Rig rig = new Rig(world);
		rig.moveTo("TEST_TOWN", 2, 2);
		rig.tick();
		rig.sync.onSnapshot(true, new Place("TEST_HOUSE", 4, 8, Dir.UP), "screen");
		rig.sync.setLinked(true);
		assertEquals(List.of("teleport TEST_HOUSE:4:8:up"), rig.tick(), "settled in any mode");
		assertEquals(1, rig.sync.stats().syncs);

		// The warp's teleport put the player on the door; the hidden player has walked out one cell since.
		Rig door = Rig.at(world, "TEST_TOWN", 5, 5, Dir.DOWN);
		door.sync.onWarpStart();
		door.port.warping = true;
		door.sync.onWarpArrive(new Place("TEST_TOWN", 5, 5, Dir.DOWN));
		door.pos("TEST_TOWN", 5, 6, Dir.DOWN, "script", 16, null);
		assertEquals(List.of(), door.ticks(3), "nothing while the warp runs");
		door.port.warping = false;
		door.sync.onWarpEnded();
		assertEquals(List.of("walk TEST_TOWN:5:6 " + MovementSync.WALK_FRAMES), door.tick());

		// Joining a world, or a resync's snapshot, teleports even when the player is close.
		Rig joined = Rig.at(world, "TEST_TOWN", 5, 8, Dir.DOWN);
		joined.moveTo("TEST_TOWN", 15, 2);
		joined.sync.onWorldJoin();
		assertEquals(List.of("teleport TEST_TOWN:5:8:down"), joined.tick());
		Rig resynced = Rig.at(world, "TEST_TOWN", 5, 8, Dir.DOWN);
		resynced.moveTo("TEST_TOWN", 5, 9);
		resynced.sync.onSnapshot(true, new Place("TEST_TOWN", 5, 8, Dir.DOWN), "idle");
		assertEquals(List.of("teleport TEST_TOWN:5:8:down"), resynced.tick());
		// A snapshot from the title screen (not in game) moves nothing.
		Rig title = Rig.at(world, "TEST_TOWN", 5, 8, Dir.DOWN);
		title.moveTo("TEST_TOWN", 5, 9);
		title.sync.onSnapshot(false, new Place("TEST_TOWN", 1, 1, Dir.DOWN), "screen");
		assertEquals(List.of(), title.tick());
	}

	@Test
	void teleportsAreRateLimited() {
		Rig rig = Rig.at(world, "TEST_TOWN", 5, 8, Dir.DOWN);
		rig.moveTo("TEST_TOWN", 15, 2);
		rig.sync.onWorldJoin();
		assertEquals(List.of("teleport TEST_TOWN:5:8:down"), rig.tick());
		rig.sync.onWorldJoin();
		assertEquals(List.of(), rig.tick(), "within 3 s of the last one");
		// The server refused it: nothing more for 10 s, though drift keeps finding the player in the wrong place.
		rig.sync.onTeleportResult(false, rig.now);
		long refusedAt = rig.now;
		List<String> during = new ArrayList<>();
		while (rig.now + 50 < refusedAt + MovementSync.REFUSED_TELEPORT_COOLDOWN_MS) {
			during.addAll(rig.tick());
		}
		assertEquals(List.of(), during);
		assertTrue(rig.sync.stats().drifts > 10, "drift kept trying");
		List<String> later = rig.ticks((int) (MovementSync.DRIFT_MS / 50) + 2);
		assertEquals(List.of("teleport TEST_TOWN:5:8:down"), later, "drift tries again once the wait is over");
	}

	@Test
	void stuckForcedMovesTeleport() {
		Rig rig = Rig.at(world, "TEST_TOWN", 5, 8, Dir.DOWN);
		rig.pos("TEST_TOWN", 5, 9, Dir.DOWN, "script", 16, null);
		rig.port.take();
		rig.sync.onStuck(rig.now);
		assertEquals(List.of("teleport TEST_TOWN:5:9:down"), rig.port.take());
	}

	@Test
	void mapsTheWorldDoesntHaveAreReportedNotChased() {
		Rig rig = Rig.at(world, "TEST_TOWN", 5, 8, Dir.DOWN);
		rig.pos("ROUTE_99", 3, 3, Dir.DOWN, "walk", 16, null);
		assertTrue(rig.sync.hiddenUnbuilt());
		assertEquals(List.of(), rig.ticks(30), "nothing to steer or teleport to");
	}

	@Test
	void cellsAcrossAMapSeamAreAdjacent() {
		// Two outdoor maps on one grid: NORTH_MAP directly above SOUTH_MAP.
		String hash = "c".repeat(64);
		WorldIndex seam = new WorldIndex("red", hash, List.of(
				new MapPlacement("SOUTH_MAP", 0, 64, 0, 10, 10, 0, MapPlacement.Kind.SURFACE, "P", "kantobridge:kanto_outdoor", false, null),
				new MapPlacement("NORTH_MAP", 0, 64, -20, 10, 10, 0, MapPlacement.Kind.SURFACE, "P", "kantobridge:kanto_outdoor", false, null)),
				hash, StoryCellTable.of(List.of()), List.of());
		Rig rig = Rig.at(seam, "SOUTH_MAP", 3, 0, Dir.UP);
		rig.moveTo("NORTH_MAP", 3, 9);
		assertEquals(List.of("step up 1"), rig.tick());
		// gen1-bridge may still name the cell from the map being left.
		rig.pos("SOUTH_MAP", 3, -1, Dir.UP, "walk", 16, 1L);
		assertEquals(List.of(), rig.port.take());
		assertEquals(0, rig.sync.stats().forced);
		assertEquals(List.of(), rig.ticks(20), "no drift: the same cell");
	}
}
