package dev.kantobridge.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.world.Dir;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Crossings (MG5, MULTI_GAME_PLAN §4.8): every refusal of {@code pass.cross}, the accepted crossing, the teleport into the
 * far gate house when {@code region.state} names the far region (both ways), the start path, abort and the timer, on a
 * made-up world of two regions joined by one connecting route.
 */
class PassCrossingsTest {
	private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-000000000001");
	private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-000000000002");
	/** The kanto half: route map at (1000, 64, 2000), the gate house's far doorway at cell (40, 0..1), facing right. */
	static final PassCrossings.Half KANTO = new PassCrossings.Half("kanto", "eastern_pass", "eastern_pass", "from", "hoenn", 1000, 64, 2000,
			List.of(new int[] {40, 0}, new int[] {40, 1}), Dir.RIGHT);
	/** The hoenn half, 8192 blocks east, its pass at cell (40, 0..1) too, the house facing right as well. */
	static final PassCrossings.Half HOENN = new PassCrossings.Half("hoenn", "eastern_pass", "eastern_pass", "to", "kanto", 9192, 64, 2000,
			List.of(new int[] {40, 0}, new int[] {40, 1}), Dir.RIGHT);
	/** A free-roam zone's half-less position: no pass anywhere near. */
	static final double[] KANTO_PASS = {1000 + 80 + 1.0, 64, 2000 + 1.0};
	static final double[] HOENN_PASS = {9192 + 80 + 1.0, 64, 2000 + 1.0};

	/** The world as the service would answer it: two regions, halves by (route, region), links and progress per player. */
	static final class World implements PassCrossings.Lookup {
		final Set<String> regions = new HashSet<>(Set.of("kanto", "hoenn"));
		final Set<String> built = new HashSet<>(Set.of("kanto", "hoenn"));
		final Map<String, PassCrossings.Half> halves = new HashMap<>();
		final Map<UUID, Set<String>> links = new HashMap<>();
		final Map<UUID, Map<String, String>> progress = new HashMap<>();

		World() {
			this.halves.put("eastern_pass/kanto", KANTO);
			this.halves.put("eastern_pass/hoenn", HOENN);
			this.links.put(ALICE, Set.of("kanto", "hoenn"));
			this.links.put(BOB, Set.of("kanto"));
		}

		@Override
		public PassCrossings.@Nullable Half standing(UUID player, double x, double y, double z) {
			// the zone boxes: 200 blocks around each route map
			for (PassCrossings.Half half : this.halves.values()) {
				if (x >= half.routeMapX() - 20 && x < half.routeMapX() + 200 && z >= half.routeMapZ() - 20 && z < half.routeMapZ() + 200) {
					return half;
				}
			}
			return null;
		}

		@Override
		public boolean regionExists(String region) {
			return this.regions.contains(region);
		}

		@Override
		public boolean regionBuilt(String region) {
			return this.built.contains(region);
		}

		@Override
		public PassCrossings.@Nullable Half half(String route, String region) {
			return this.halves.get(route + "/" + region);
		}

		@Override
		public boolean hasLink(UUID player, String region) {
			return this.links.getOrDefault(player, Set.of()).contains(region);
		}

		@Override
		public @Nullable String playthrough(UUID player, String region) {
			return this.progress.getOrDefault(player, Map.of()).get(region);
		}
	}

	private static String code(PassCrossings.Decision decision) {
		return assertInstanceOf(PassCrossings.Refused.class, decision).code();
	}

	@Test
	void thePlayerMustStandOnThatPassHeadingForTheOtherEnd() {
		World world = new World();
		PassCrossings crossings = new PassCrossings(world);
		// away from any half
		assertEquals("bad_message", code(crossings.cross(ALICE, "eastern_pass", "to", 500, 64, 500, 1)));
		// in the half but not on the pass
		assertEquals("bad_message", code(crossings.cross(ALICE, "eastern_pass", "to", 1000 + 10, 64, 2000 + 1, 1)));
		// on the pass, but another route named
		assertEquals("bad_message", code(crossings.cross(ALICE, "western_pass", "to", KANTO_PASS[0], KANTO_PASS[1], KANTO_PASS[2], 1)));
		// heading back into the half they stand in
		assertEquals("bad_message", code(crossings.cross(ALICE, "eastern_pass", "from", KANTO_PASS[0], KANTO_PASS[1], KANTO_PASS[2], 1)));
		// a floor above the pass
		assertEquals("bad_message", code(crossings.cross(ALICE, "eastern_pass", "to", KANTO_PASS[0], 70, KANTO_PASS[2], 1)));
		assertTrue(crossings.crossings().isEmpty());
	}

	@Test
	void theFarRegionMustExistBeBuiltAndHaveALink() {
		World world = new World();
		PassCrossings crossings = new PassCrossings(world);
		world.regions.remove("hoenn");
		assertEquals("pass.no_region", code(crossings.cross(ALICE, "eastern_pass", "to", KANTO_PASS[0], 64, KANTO_PASS[2], 1)));
		world.regions.add("hoenn");
		world.built.remove("hoenn");
		assertEquals("pass.not_built", code(crossings.cross(ALICE, "eastern_pass", "to", KANTO_PASS[0], 64, KANTO_PASS[2], 1)));
		world.built.add("hoenn");
		world.halves.remove("eastern_pass/hoenn");
		assertEquals("pass.not_built", code(crossings.cross(ALICE, "eastern_pass", "to", KANTO_PASS[0], 64, KANTO_PASS[2], 1)),
				"a far region without this route's half isn't built for it");
		world.halves.put("eastern_pass/hoenn", HOENN);
		assertEquals("pass.no_link", code(crossings.cross(BOB, "eastern_pass", "to", KANTO_PASS[0], 64, KANTO_PASS[2], 1)));
		assertTrue(crossings.crossings().isEmpty());
	}

	@Test
	void anAcceptedCrossingEndsWithTheTeleportIntoTheFarGateHouse() {
		World world = new World();
		PassCrossings crossings = new PassCrossings(world);
		world.progress.put(ALICE, Map.of("hoenn", "saved"));
		PassCrossings.Decision decision = crossings.cross(ALICE, "eastern_pass", "to", KANTO_PASS[0], 64, KANTO_PASS[2], 100);
		PassCrossings.Accepted accepted = assertInstanceOf(PassCrossings.Accepted.class, decision);
		assertEquals(new PassCrossings.Accepted("eastern_pass", "kanto", "hoenn", false), accepted);
		assertNotNull(crossings.crossing(ALICE));
		assertEquals("pass.busy", code(crossings.cross(ALICE, "eastern_pass", "to", KANTO_PASS[0], 64, KANTO_PASS[2], 101)), "one at a time");
		// a region.state that doesn't name the far region: nothing yet
		assertInstanceOf(PassCrossings.None.class, crossings.onRegionState(ALICE, "kanto"));
		assertInstanceOf(PassCrossings.None.class, crossings.onRegionState(ALICE, null));
		assertInstanceOf(PassCrossings.None.class, crossings.onRegionState(BOB, "hoenn"), "another player's state");
		assertNotNull(crossings.crossing(ALICE));
		// the far region followed: one cell inside hoenn's far doorway (the house faces right, so inside is one cell left)
		PassCrossings.Teleport teleport = assertInstanceOf(PassCrossings.Teleport.class, crossings.onRegionState(ALICE, "hoenn"));
		assertEquals(9192 + 78 + 1.0, teleport.x(), 1e-9);
		assertEquals(64, teleport.y(), 1e-9);
		assertEquals(2000 + 0 + 1.0, teleport.z(), 1e-9);
		assertEquals(Dir.LEFT.yaw(), teleport.yaw(), "looking into the house, away from the pass");
		assertNull(crossings.crossing(ALICE), "the crossing is over");
		assertInstanceOf(PassCrossings.None.class, crossings.onRegionState(ALICE, "hoenn"), "a later state isn't a crossing's");
	}

	@Test
	void theWayBackWorksFromTheOtherHalf() {
		World world = new World();
		PassCrossings crossings = new PassCrossings(world);
		world.progress.put(ALICE, Map.of("kanto", "saved", "hoenn", "saved"));
		PassCrossings.Accepted accepted = assertInstanceOf(PassCrossings.Accepted.class,
				crossings.cross(ALICE, "eastern_pass", "from", HOENN_PASS[0], 64, HOENN_PASS[2], 5));
		assertEquals(new PassCrossings.Accepted("eastern_pass", "hoenn", "kanto", false), accepted);
		PassCrossings.Teleport teleport = assertInstanceOf(PassCrossings.Teleport.class, crossings.onRegionState(ALICE, "kanto"));
		assertEquals(1000 + 78 + 1.0, teleport.x(), 1e-9);
		assertEquals(2000 + 1.0, teleport.z(), 1e-9);
	}

	@Test
	void aFarGameAtItsTitleIsStartedAndTheClientPlacesThePlayer() {
		World world = new World();
		PassCrossings crossings = new PassCrossings(world);
		world.progress.put(ALICE, Map.of("hoenn", "title"));
		PassCrossings.Accepted accepted = assertInstanceOf(PassCrossings.Accepted.class,
				crossings.cross(ALICE, "eastern_pass", "to", KANTO_PASS[0], 64, KANTO_PASS[2], 7));
		assertTrue(accepted.start());
		assertInstanceOf(PassCrossings.Relink.class, crossings.onRegionState(ALICE, "hoenn"));
		assertNull(crossings.crossing(ALICE));
		// no record at all: the far game is resumed (the client's own relink after a start wins anyway)
		world.progress.remove(ALICE);
		assertFalse(assertInstanceOf(PassCrossings.Accepted.class, crossings.cross(ALICE, "eastern_pass", "to", KANTO_PASS[0], 64, KANTO_PASS[2], 8))
				.start());
	}

	@Test
	void abortAndTheTimerEndACrossingWhereThePlayerStands() {
		World world = new World();
		PassCrossings crossings = new PassCrossings(world);
		assertFalse(crossings.abort(ALICE), "nothing to abort");
		assertInstanceOf(PassCrossings.Accepted.class, crossings.cross(ALICE, "eastern_pass", "to", KANTO_PASS[0], 64, KANTO_PASS[2], 1000));
		assertTrue(crossings.abort(ALICE));
		assertNull(crossings.crossing(ALICE));
		assertInstanceOf(PassCrossings.None.class, crossings.onRegionState(ALICE, "hoenn"), "an aborted crossing never teleports");

		assertInstanceOf(PassCrossings.Accepted.class, crossings.cross(ALICE, "eastern_pass", "to", KANTO_PASS[0], 64, KANTO_PASS[2], 2000));
		assertInstanceOf(PassCrossings.Accepted.class, crossings.cross(BOB, "eastern_pass", "from", HOENN_PASS[0], 64, HOENN_PASS[2], 2100));
		assertTrue(crossings.tick(2000 + PassCrossings.TIMEOUT_TICKS - 1).isEmpty(), "not yet");
		assertEquals(List.of(ALICE), crossings.tick(2000 + PassCrossings.TIMEOUT_TICKS), "15 s: over");
		assertNull(crossings.crossing(ALICE));
		assertNotNull(crossings.crossing(BOB), "a younger crossing goes on");
		crossings.forget(BOB);
		assertTrue(crossings.crossings().isEmpty(), "a player who left takes their crossing with them");
	}

	@Test
	void theHalfKnowsItsPassAndItsArrival() {
		assertTrue(KANTO.onPass(KANTO_PASS[0], 64, KANTO_PASS[2]));
		assertTrue(KANTO.onPass(1000 + 80 + 1.5, 65.5, 2000 + 2 + 0.2), "the second pass cell, a little above the floor");
		assertFalse(KANTO.onPass(1000 + 78 + 1.0, 64, 2000 + 1.0), "the cell inside the house");
		assertFalse(KANTO.onPass(KANTO_PASS[0], 66, KANTO_PASS[2]), "two blocks up");
		assertEquals("to", KANTO.otherEnd());
		assertEquals("from", HOENN.otherEnd());
		PassCrossings.Teleport arrival = KANTO.arrival();
		assertEquals(1000 + 78 + 1.0, arrival.x(), 1e-9);
		assertEquals(Dir.LEFT.yaw(), arrival.yaw());
	}
}
