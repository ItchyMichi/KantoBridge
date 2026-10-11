package dev.kantobridge.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.WorldIndex;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Wild Routes trainers' rules (WR5, docs/specs/WILD_ROUTES_PLAN.md §4.6): Gen 1 sight, the walk-up, visits and rematches. */
class ZoneEncountersTest {
	private static final String HASH = "0".repeat(64);
	private static final UUID PLAYER = UUID.randomUUID();
	/** The route map's A at block (1000, 2000); a trainer at cell (5, 0) looking right 3 cells, another at (5, 4) looking up 4. */
	static final WorldIndex.ZoneTrainer EAST = new WorldIndex.ZoneTrainer("a1.t1", 5, 0, Dir.RIGHT, 3, "OPP_TEST", "SPRITE",
			List.of(new WorldIndex.PartyMember("MON", 7)));
	static final WorldIndex.ZoneTrainer NORTH = new WorldIndex.ZoneTrainer("a1.t2", 7, 4, Dir.UP, 4, "OPP_TEST", "SPRITE",
			List.of(new WorldIndex.PartyMember("MON", 8)));
	static final WorldIndex INDEX = new WorldIndex("red", HASH,
			List.of(new MapPlacement("KB_ROUTE_Z", 1000, 64, 2000, 2, 2, 0, MapPlacement.Kind.ROUTE, "PAL", "kantobridge:wild_forest", false, null)),
			HASH, StoryCellTable.empty(), List.of(), List.of(new WorldIndex.Zone("z_zone", "KB_ROUTE_Z", "forest", 990, 64, 1950, 200, 200,
					"kantobridge:wild_forest", List.of(EAST, NORTH), List.of(), null)));

	/** The middle of cell (cx, cy) of the route map's grid. */
	static double bx(int cx) {
		return 1000 + cx * 2 + 1.0;
	}

	static double bz(int cy) {
		return 2000 + cy * 2 + 1.0;
	}

	@Test
	void aTrainerSeesStraightAheadWalksUpThenBattles() {
		ZoneEncounters encounters = new ZoneEncounters();
		assertNull(encounters.tick(PLAYER, INDEX, bx(9), bz(0), true, 100), "4 cells ahead: past its sight");
		assertNull(encounters.tick(PLAYER, INDEX, bx(6), bz(1), true, 101), "beside its lane");
		assertNull(encounters.tick(PLAYER, INDEX, bx(4), bz(0), true, 102), "behind it");
		assertNull(encounters.tick(PLAYER, INDEX, bx(8), bz(0), false, 103), "not free (a menu, a battle)");
		ZoneEncounters.Action sight = encounters.tick(PLAYER, INDEX, bx(8), bz(0), true, 104);
		assertEquals(new ZoneEncounters.Sight("z_zone", "a1.t1", 7, 0, 32), sight, "3 ahead: two steps to the cell in front");
		// the "!" (60 frames) and the walk (32) take 31 ticks; 4 more for the last step
		for (long t = 105; t < 104 + 31 + 4; t++) {
			assertNull(encounters.tick(PLAYER, INDEX, bx(8), bz(0), true, t), "tick " + t);
		}
		ZoneEncounters.Action battle = encounters.tick(PLAYER, INDEX, bx(8), bz(0), true, 104 + 31 + 4);
		assertInstanceOf(ZoneEncounters.Battle.class, battle);
		assertEquals(EAST, ((ZoneEncounters.Battle) battle).trainer());
		assertEquals("a1.t1", encounters.encounter(PLAYER));
	}

	@Test
	void aWinLastsTheVisitAndTheNextVisitIsARematch() {
		ZoneEncounters encounters = new ZoneEncounters();
		long t = startBattle(encounters, EAST, 6, 0, 10);
		encounters.onResult(PLAYER, "battle", true, "z_zone", "a1.t1", "win", t);
		assertEquals(Set.of("a1.t1"), encounters.beaten(PLAYER));
		assertNull(encounters.encounter(PLAYER));
		assertNull(encounters.tick(PLAYER, INDEX, bx(6), bz(0), true, t + 1), "beaten on this visit");
		// leaving the zone's box ends the visit; back in, it challenges again
		assertNull(encounters.tick(PLAYER, INDEX, 0, 0, true, t + 2));
		assertNull(encounters.zone(PLAYER));
		assertEquals(new ZoneEncounters.Sight("z_zone", "a1.t1", 5, 0, 0), encounters.tick(PLAYER, INDEX, bx(6), bz(0), true, t + 3),
				"right in front: no walk");
	}

	@Test
	void anythingButAWinLetsTheTrainerLookAgainLater() {
		ZoneEncounters encounters = new ZoneEncounters();
		long t = startBattle(encounters, EAST, 6, 0, 10);
		encounters.onResult(PLAYER, "battle", false, "z_zone", "a1.t1", null, t);
		assertTrue(encounters.beaten(PLAYER).isEmpty());
		assertNull(encounters.tick(PLAYER, INDEX, bx(6), bz(0), true, t + 1), "resting");
		assertInstanceOf(ZoneEncounters.Sight.class, encounters.tick(PLAYER, INDEX, bx(6), bz(0), true, t + ZoneEncounters.RETRY_TICKS + 1));
		// answers for another zone, another op or another trainer change nothing
		encounters.onResult(PLAYER, "battle", true, "other_zone", "a1.t1", "win", t);
		encounters.onResult(PLAYER, "give", true, "z_zone", "a1.t1", null, t);
		assertTrue(encounters.beaten(PLAYER).isEmpty());
	}

	@Test
	void aLostAnswerTimesOutOnlyWhileThePlayerIsFree() {
		ZoneEncounters encounters = new ZoneEncounters();
		long t = startBattle(encounters, EAST, 6, 0, 10);
		// a long battle: not free, so no timeout
		for (long k = 1; k <= ZoneEncounters.RESULT_TIMEOUT_TICKS * 3; k += 50) {
			assertNull(encounters.tick(PLAYER, INDEX, bx(6), bz(0), false, t + k));
		}
		assertEquals("a1.t1", encounters.encounter(PLAYER));
		long free = t + ZoneEncounters.RESULT_TIMEOUT_TICKS * 3;
		encounters.tick(PLAYER, INDEX, bx(6), bz(0), true, free + ZoneEncounters.RESULT_TIMEOUT_TICKS + 1);
		assertNull(encounters.encounter(PLAYER), "free with no answer: given up");
	}

	@Test
	void oneEncounterAtATime() {
		ZoneEncounters encounters = new ZoneEncounters();
		// cell (7, 0): EAST sees it (2 ahead) and NORTH sees it (4 ahead)
		assertEquals("a1.t1", ((ZoneEncounters.Sight) encounters.tick(PLAYER, INDEX, bx(7), bz(0), true, 10)).id());
		for (long t = 11; t < 200; t++) {
			ZoneEncounters.Action a = encounters.tick(PLAYER, INDEX, bx(7), bz(0), true, t);
			assertTrue(a == null || a instanceof ZoneEncounters.Battle, "no second sighting while the first runs");
		}
		encounters.onResult(PLAYER, "battle", true, "z_zone", "a1.t1", "win", 200);
		assertEquals(new ZoneEncounters.Sight("z_zone", "a1.t2", 7, 1, 48), encounters.tick(PLAYER, INDEX, bx(7), bz(0), true, 201));
	}

	/** Sight then battle for {@code trainer} with the player at cell (cx, cy) from tick {@code t}; returns the battle's tick. */
	private static long startBattle(ZoneEncounters encounters, WorldIndex.ZoneTrainer trainer, int cx, int cy, long t) {
		assertInstanceOf(ZoneEncounters.Sight.class, encounters.tick(PLAYER, INDEX, bx(cx), bz(cy), true, t));
		for (long k = t + 1; k < t + 400; k++) {
			ZoneEncounters.Action a = encounters.tick(PLAYER, INDEX, bx(cx), bz(cy), true, k);
			if (a instanceof ZoneEncounters.Battle battle) {
				assertEquals(trainer, battle.trainer());
				return k;
			}
		}
		throw new AssertionError("no battle");
	}
}
