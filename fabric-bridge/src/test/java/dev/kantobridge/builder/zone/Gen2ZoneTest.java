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
 * MG9 G2H (docs/specs/GEN2_BRIDGE_PLAN.md §8): the Johto half of a connecting route from a Gen 2 export (the synthetic
 * export's pass variant, tools/fake_gen1/synth2.py: its town with two rows of trees below it, the clearing the adapter
 * opens baked in, a trainer of the zone's class, the routes resource): Gen 2 zone content (the town's neighbour pool by
 * the names resource and the engine's slot chances, sprites by class else the trainers' sprite, the ball's, the game's
 * items) and the half planned into the world plan with its gate house and pass, its trainers' parties from the pool.
 * Everything is made up.
 */
class Gen2ZoneTest {
	private static ExportBundle bundle;
	private static ExportRoutes routes;
	private static ZoneContent content;

	@BeforeAll
	static void load() throws Exception {
		bundle = ExportBundle.open(TestData.syntheticGen2PassBundle());
		routes = bundle.routes();
		content = ZoneContent.ofGen2(bundle, List.of("G2_TEST_TOWN"), List.of("TEST_YOUNGSTER", "TEST_NOBODY_WEARS"));
	}

	@Test
	void theRouteIsTheJohtoHalfOfAConnectingRoute() {
		assertEquals(1, routes.zones().size());
		ExportRoutes.Zone zone = routes.zones().get(0);
		assertEquals("test_pass", zone.id());
		assertTrue(zone.connecting());
		assertEquals("to", zone.end());
		assertEquals("kanto", zone.toRegion(), "the far half is Kanto's");
		assertEquals("G2_TEST_TOWN", zone.townMap());
		assertEquals("KB_ROUTE_TEST_PASS_TO", zone.routeMap());
	}

	@Test
	void theTownsPoolIsItsNeighboursDaytimeGrassByName() {
		List<ZoneContent.Slot> pool = content.pool("G2_TEST_TOWN");
		assertEquals(List.of(new ZoneContent.Slot("TEST_PIDGEY", 16, 4, 30), new ZoneContent.Slot("TEST_RATTATA", 19, 4, 30),
				new ZoneContent.Slot("TEST_RATTATA", 19, 4, 20), new ZoneContent.Slot("TEST_SENTRET", 161, 4, 10),
				new ZoneContent.Slot("TEST_SENTRET", 161, 4, 5), new ZoneContent.Slot("TEST_HOOTHOOT", 163, 4, 4),
				new ZoneContent.Slot("TEST_HOOTHOOT", 163, 4, 1)), pool, "the route north of the town: its DAY slots by the engine's chances");
		assertTrue(content.pool("G2_TEST_ROUTE").isEmpty(), "only the towns asked for");
	}

	@Test
	void spritesItemsAndTheBall() {
		assertEquals("SPRITE_TEST_YOUNGSTER", content.classSprites().get("TEST_YOUNGSTER"), "the class's own sprite, placed on a map");
		assertEquals("SPRITE_TEST_LASS", content.classSprites().get("TEST_NOBODY_WEARS"), "else the trainers' (a tie: the first)");
		assertEquals("SPRITE_TEST_BALL", content.ballSprite());
		assertEquals(Set.of("TEST_POTION", "TEST_POKE_BALL", "POTION", "POKE_BALL"), content.items(), "the names resource's");
	}

	@Test
	void theHalfIsPlannedWithItsGateHouseAndPass() throws Exception {
		WorldPlan base = WorldPlanner.plan(bundle,
				new WorldPlanner.Options(null, TestData.G2_ORIGIN_X, TestData.G2_ORIGIN_Z, TestData.MIN_BUILD_HEIGHT, false));
		WorldPlan plan = BuildTask.withZones(base, routes, 1234L, () -> TerrainSampler.flat(70), TestData.G2_ORIGIN_X,
				TestData.G2_ORIGIN_Z, content);
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
		Set<String> species = Set.of("TEST_PIDGEY", "TEST_RATTATA", "TEST_SENTRET", "TEST_HOOTHOOT");
		for (WorldIndex.ZoneTrainer trainer : zone.trainers()) {
			assertEquals("SPRITE_TEST_YOUNGSTER", trainer.sprite());
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
				new WorldPlanner.Options(null, TestData.G2_ORIGIN_X, TestData.G2_ORIGIN_Z, TestData.MIN_BUILD_HEIGHT, false));
		var town = plan.maps().stream().filter(m -> m.id().equals("G2_TEST_TOWN")).findFirst().orElseThrow().model();
		assertTrue(walkable(town, 4, 8) && walkable(town, 5, 9) && walkable(town, 4, 10) && walkable(town, 5, 11), "the cleared blocks");
		assertFalse(walkable(town, 3, 9) || walkable(town, 6, 10), "the trees beside them");
	}

	private static boolean walkable(dev.kantobridge.builder.plan.MapModel model, int cx, int cy) {
		return (model.flags(cx, cy) & dev.kantobridge.builder.export.CellFlags.WALKABLE) != 0;
	}
}
