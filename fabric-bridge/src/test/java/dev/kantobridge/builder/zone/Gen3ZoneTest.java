package dev.kantobridge.builder.zone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.BuildTask;
import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportRoutes;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.builder.plan.WorldPlanner;
import dev.kantobridge.world.WorldIndex;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * MG8 (docs/specs/HOENN_PASS_PLAN.md HP5): the Hoenn half of a connecting route from a Gen 3 export (the synthetic
 * export's pass variant, tools/fake_gen1/synth3.py: its town with two tree rows, the clearing the adapter opens baked
 * in, a trainer by it, the routes resource): Gen 3 zone content (the town's neighbour pool by the names resource and the
 * engine's slot chances, sprites by role else the trainers' graphic, the ball's, the game's items) and the half planned
 * into the world plan with its gate house and pass, its trainers' parties from the pool. Everything is made up.
 */
class Gen3ZoneTest {
	private static ExportBundle bundle;
	private static ExportRoutes routes;
	private static ZoneContent content;

	@BeforeAll
	static void load() throws Exception {
		bundle = ExportBundle.open(TestData.syntheticGen3PassBundle());
		routes = bundle.routes();
		content = ZoneContent.ofGen3(bundle, List.of("EM_TEST_TOWN"), List.of("TRAINER_CLASS_TEST", "TRAINER_CLASS_NOBODY_WEARS"));
	}

	@Test
	void theRouteIsTheHoennHalfOfAConnectingRoute() {
		assertEquals(1, routes.zones().size());
		ExportRoutes.Zone zone = routes.zones().get(0);
		assertEquals("test_pass", zone.id());
		assertTrue(zone.connecting());
		assertEquals("to", zone.end());
		assertEquals("kanto", zone.toRegion(), "the far half is Kanto's");
		assertEquals("EM_TEST_TOWN", zone.townMap());
		assertEquals("KB_ROUTE_TEST_PASS_TO", zone.routeMap());
	}

	@Test
	void theTownsPoolIsItsNeighboursLandSlotsByName() {
		List<ZoneContent.Slot> pool = content.pool("EM_TEST_TOWN");
		assertEquals(12, pool.size(), "the route north of the town: 12 land slots");
		assertEquals(new ZoneContent.Slot("TEST_TREECKO", 252, 4, 20), pool.get(0), "named without SPECIES_, the range's top");
		assertEquals(new ZoneContent.Slot("TEST_WURMPLE", 265, 4, 1), pool.get(11), "the engine's slot chance");
		assertTrue(content.pool("EM_TEST_ROUTE").isEmpty(), "only the towns asked for");
	}

	@Test
	void spritesItemsAndTheBall() {
		// no graphic named like the role: the one the trainers wear
		assertEquals("OBJ_EVENT_GFX_TEST_TRAINER", content.classSprites().get("TRAINER_CLASS_TEST"));
		assertEquals("OBJ_EVENT_GFX_TEST_TRAINER", content.classSprites().get("TRAINER_CLASS_NOBODY_WEARS"));
		assertEquals("OBJ_EVENT_GFX_TEST_ITEM_BALL", content.ballSprite());
		assertEquals(Set.of("TEST_POTION", "TEST_POKE_BALL", "POTION", "POKE_BALL"), content.items(), "the names resource's, ITEM_ off");
	}

	@Test
	void theHalfIsPlannedWithItsGateHouseAndPass() throws Exception {
		WorldPlan base = WorldPlanner.plan(bundle,
				new WorldPlanner.Options(null, TestData.G3_ORIGIN_X, TestData.G3_ORIGIN_Z, TestData.MIN_BUILD_HEIGHT, false));
		WorldPlan plan = BuildTask.withZones(base, routes, 1234L, () -> TerrainSampler.flat(70), TestData.G3_ORIGIN_X,
				TestData.G3_ORIGIN_Z, content);
		assertEquals(1, plan.zones().size());
		WorldIndex index = plan.index();
		WorldIndex.Zone zone = index.zones().get(0);
		assertEquals("test_pass", zone.id());
		assertEquals("KB_ROUTE_TEST_PASS_TO", zone.routeMap());
		assertNotNull(zone.pass(), "the far doorway is the pass");
		assertNotNull(zone.gateHouse());
		assertEquals("kanto", zone.pass().toRegion());
		assertEquals("to", zone.pass().end());
		assertFalse(zone.trainers().isEmpty(), "trainers from the town's pool");
		Set<String> species = Set.of("TEST_TREECKO", "TEST_TORCHIC", "TEST_MUDKIP", "TEST_POOCHYENA", "TEST_ZIGZAGOON",
				"TEST_WURMPLE");
		for (WorldIndex.ZoneTrainer trainer : zone.trainers()) {
			assertEquals("OBJ_EVENT_GFX_TEST_TRAINER", trainer.sprite());
			for (WorldIndex.PartyMember member : trainer.party()) {
				assertTrue(species.contains(member.species()), member.species());
			}
		}
		assertFalse(zone.balls().isEmpty(), "item balls from the tiers the game has");
		for (WorldIndex.ZoneBall ball : zone.balls()) {
			assertTrue(Set.of("POTION", "POKE_BALL").contains(ball.item()), ball.item());
		}
		assertNotNull(index.map("KB_ROUTE_TEST_PASS_TO"), "the route map's placement at the trailhead");
	}

	@Test
	void theClearingIsBuiltOpen() throws Exception {
		WorldPlan plan = WorldPlanner.plan(bundle,
				new WorldPlanner.Options(null, TestData.G3_ORIGIN_X, TestData.G3_ORIGIN_Z, TestData.MIN_BUILD_HEIGHT, false));
		var town = plan.maps().stream().filter(m -> m.id().equals("EM_TEST_TOWN")).findFirst().orElseThrow().model();
		assertTrue(walkable(town, 4, 7) && walkable(town, 5, 6), "the cleared block");
		assertFalse(walkable(town, 3, 7) || walkable(town, 6, 6), "the trees beside it");
	}

	private static boolean walkable(dev.kantobridge.builder.plan.MapModel model, int cx, int cy) {
		return (model.flags(cx, cy) & dev.kantobridge.builder.export.CellFlags.WALKABLE) != 0;
	}
}
