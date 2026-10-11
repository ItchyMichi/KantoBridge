package dev.kantobridge.dungeon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.routes.gen.PmdParams;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.WorldIndex;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Dungeon visits (WR7): the party at the entrance, floors built as you go, stairs, the exit, clearing, full slots. */
class DungeonVisitsTest {
	private static final String HASH = "0".repeat(64);
	/** The route map's A at (1000, 64, 2000); the entrance at cell (30, 4), its way out to the left. */
	static final WorldIndex INDEX = new WorldIndex("red", HASH,
			List.of(new MapPlacement("KB_ROUTE_Z", 1000, 64, 2000, 2, 2, 0, MapPlacement.Kind.ROUTE, "PAL", "kantobridge:wild_forest", false, null)),
			HASH, StoryCellTable.empty(), List.of(), List.of(new WorldIndex.Zone("z_zone", "KB_ROUTE_Z", "forest", 990, 64, 1950, 200, 200,
					"kantobridge:wild_forest", List.of(), List.of(), null, new WorldIndex.ZoneDungeon(30, 4, Dir.LEFT, "kantobridge:zone_z_zone_a1", 8))),
			new WorldIndex.Dungeons(0, 64, -4000, DungeonSlots.PER_ROW * DungeonSlots.SLOT_W, DungeonSlots.ROWS * DungeonSlots.SLOT_D));
	static final double[] ENTRANCE = {1000 + 60 + 1.0, 64, 2000 + 8 + 1.0};

	/** Players and their positions, ticked; built floors are reported back at once. */
	static final class Run {
		final DungeonVisits visits = new DungeonVisits(1234L, PmdParams.standard());
		final Map<UUID, double[]> at = new HashMap<>();
		final List<DungeonVisits.Action> log = new ArrayList<>();
		long tick = 1000;

		List<DungeonVisits.Action> tick() {
			List<DungeonVisits.Where> where = new ArrayList<>();
			this.at.forEach((id, p) -> where.add(new DungeonVisits.Where(id, p[0], p[1], p[2])));
			List<DungeonVisits.Action> out = this.visits.tick(this.tick++, INDEX, where);
			for (DungeonVisits.Action a : out) {
				if (a instanceof DungeonVisits.Build b) {
					this.visits.built(b.floor());
				} else if (a instanceof DungeonVisits.Enter e) {
					this.at.put(e.player(), new double[] {e.x(), e.y(), e.z()});
				} else if (a instanceof DungeonVisits.Leave l) {
					this.at.put(l.player(), new double[] {l.x(), l.y(), l.z()});
				} else if (a instanceof DungeonVisits.Clear c) {
					this.visits.cleared(c.slot());
				}
			}
			this.log.addAll(out);
			return out;
		}

		void ticks(int n) {
			for (int i = 0; i < n; i++) {
				this.tick();
			}
		}

		/** Puts the player on the stairs of the floor it stands on. */
		void toStairs(UUID player) {
			DungeonFloorPlan plan = this.visits.plan(player, this.visits.floorOf(player));
			double[] c = plan.centre(plan.floor().stairs());
			this.at.put(player, c);
		}
	}

	@Test
	void inDownAndOutWithFloorsBuiltAhead() {
		Run run = new Run();
		run.visits.forceFloors(2);
		UUID me = UUID.randomUUID();
		run.at.put(me, ENTRANCE.clone());
		List<DungeonVisits.Action> first = run.tick();
		assertEquals(List.of(new DungeonVisits.Fade(me, 100)), first, "faded at once, gathering for 5 s");
		run.ticks((int) DungeonVisits.GATHER_TICKS + 1);
		assertEquals(1, run.visits.floorOf(me), "in floor 1 once it stood (the tick after the window closed)");
		assertTrue(run.log.stream().filter(a -> a instanceof DungeonVisits.Build).count() == 2, "floor 2 built as soon as floor 1 was entered");
		DungeonFloorPlan floor1 = run.visits.plan(me, 1);
		assertEquals(64, floor1.feet());
		// down the stairs: a fade, then floor 2 (the last: its stairs are the exit)
		run.toStairs(me);
		assertInstanceOf(DungeonVisits.Fade.class, run.tick().get(0));
		run.ticks(DungeonVisits.FADE_TICKS + 1);
		assertEquals(2, run.visits.floorOf(me));
		assertTrue(run.visits.plan(me, 2).last());
		assertEquals(64 + DungeonSlots.FLOOR_STEP, run.visits.plan(me, 2).feet());
		run.toStairs(me);
		run.ticks(DungeonVisits.FADE_TICKS + 2);
		assertEquals(-1, run.visits.floorOf(me), "out");
		assertEquals(1000 + 58 + 1.0, run.at.get(me)[0], 1e-9, "beside the entrance, on its open side");
		run.ticks(2);
		assertTrue(run.log.stream().anyMatch(a -> a instanceof DungeonVisits.Clear c && c.volumes().size() == 2), "the slot is cleared");
		assertEquals(0, run.visits.visits());
	}

	@Test
	void aPartyIsWhoeverStepsInWithinFiveSecondsAndTwoPartiesTakeTwoSlots() {
		Run run = new Run();
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		UUID c = UUID.randomUUID();
		run.at.put(a, ENTRANCE.clone());
		run.ticks(50);
		run.at.put(b, ENTRANCE.clone());
		run.ticks(60);
		assertEquals(run.visits.slotOf(a), run.visits.slotOf(b), "one copy for the party");
		run.at.put(c, ENTRANCE.clone());
		run.ticks((int) DungeonVisits.GATHER_TICKS + 2);
		assertNotEquals(run.visits.slotOf(a), run.visits.slotOf(c), "a later player gets their own");
		assertEquals(2, run.visits.visits());
		// one of the party going offline leaves the other inside; the last one gone clears it
		run.at.remove(a);
		run.ticks(2);
		assertEquals(2, run.visits.visits());
		run.at.remove(b);
		run.ticks(2);
		assertEquals(1, run.visits.visits());
	}

	@Test
	void aFloorIsFilledOnceTheFirstTimeAnyoneEntersIt() {
		Run run = new Run();
		run.visits.forceFloors(2);
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		run.at.put(a, ENTRANCE.clone());
		run.at.put(b, ENTRANCE.clone());
		run.ticks((int) DungeonVisits.GATHER_TICKS + 2);
		assertEquals(1, run.log.stream().filter(x -> x instanceof DungeonVisits.Populate p && p.floor() == 1).count(), "once for the party");
		run.toStairs(a);
		run.ticks(DungeonVisits.FADE_TICKS + 2);
		run.toStairs(b);
		run.ticks(DungeonVisits.FADE_TICKS + 2);
		assertEquals(1, run.log.stream().filter(x -> x instanceof DungeonVisits.Populate p && p.floor() == 2).count());
		DungeonVisits.Populate p = (DungeonVisits.Populate) run.log.stream().filter(x -> x instanceof DungeonVisits.Populate).findFirst().get();
		assertEquals("z_zone", p.zone());
		assertTrue(p.visit() >= 1);
	}

	@Test
	void withEverySlotTakenAPartyIsPutBackOutside() {
		Run run = new Run();
		for (int i = 0; i < DungeonSlots.SLOTS; i++) {
			UUID p = UUID.randomUUID();
			run.at.put(p, ENTRANCE.clone());
			run.ticks((int) DungeonVisits.GATHER_TICKS + 2);
		}
		assertEquals(DungeonSlots.SLOTS, run.visits.visits());
		UUID late = UUID.randomUUID();
		run.at.put(late, ENTRANCE.clone());
		run.ticks((int) DungeonVisits.GATHER_TICKS + 2);
		assertEquals(-1, run.visits.slotOf(late));
		assertTrue(run.log.stream().anyMatch(a -> a instanceof DungeonVisits.Leave l && l.player().equals(late)));
	}

	@Test
	void leavingTheSlotAnotherWayLeavesTheVisitAndAStrayIsPutOutside() {
		Run run = new Run();
		UUID me = UUID.randomUUID();
		run.at.put(me, ENTRANCE.clone());
		run.ticks((int) DungeonVisits.GATHER_TICKS + 2);
		assertEquals(1, run.visits.floorOf(me));
		run.at.put(me, new double[] {0, 64, 0});
		run.ticks(2);
		assertEquals(-1, run.visits.floorOf(me), "a blackout's warp or Fly took them out");
		// someone in the dungeon region with no visit (after a restart)
		UUID stray = UUID.randomUUID();
		run.at.put(stray, new double[] {300, 64, -3950});
		run.tick();
		assertTrue(run.log.stream().anyMatch(a -> a instanceof DungeonVisits.Leave l && l.player().equals(stray)));
	}
}
