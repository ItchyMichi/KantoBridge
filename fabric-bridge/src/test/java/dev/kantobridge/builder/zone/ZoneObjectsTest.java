package dev.kantobridge.builder.zone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportEncounters;
import dev.kantobridge.builder.export.ExportMap;
import dev.kantobridge.builder.export.ExportRoutes;
import dev.kantobridge.routes.gen.ForestTheme;
import dev.kantobridge.routes.gen.RouteGenerator;
import dev.kantobridge.routes.gen.RouteLayout;
import dev.kantobridge.routes.gen.RouteSpec;
import dev.kantobridge.world.WorldIndex;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Wild Routes' trainers and balls (WR5, docs/specs/WILD_ROUTES_PLAN.md §4.6): what the export gives them
 * ({@link ZoneContent}, on the synthetic bundle) and how {@link ZoneObjects} dresses the layout's cells with it.
 */
class ZoneObjectsTest {
	/** Two neighbours' slots (levels 3 and 5), two classes of which only one has a sprite, three items. */
	static final ZoneContent CONTENT = new ZoneContent(
			Map.of("TEST_TOWN", List.of(new ZoneContent.Slot("TESTMON_A", 16, 3, 60), new ZoneContent.Slot("TESTMON_B", 19, 5, 40))),
			Map.of("OPP_TEST", "SPRITE_TEST_GUARD"), "SPRITE_TEST_BALL", Set.of("POTION", "POKE_BALL", "SUPER_POTION"));
	static final ZoneObjects.ItemTiers TIERS = ZoneObjects.ItemTiers.bundled();

	static ExportRoutes.Zone zone(String town, List<String> classes) {
		return new ExportRoutes.Zone("test_zone", "TEST WILDS", "free_roam", "forest", town, "KB_ROUTE_TEST_ZONE", 3, 4, 0.5, 1, 3, classes, 1,
				3, 0.6);
	}

	static RouteLayout layout(long seed) {
		return RouteGenerator.generate(RouteSpec.freeRoam(ForestTheme.INSTANCE).withChances(1, 1), seed);
	}

	@Test
	void trainersGetPartiesFromTheNeighboursAtTheirAreasLevels() {
		for (long seed = 1; seed <= 12; seed++) {
			RouteLayout layout = layout(seed);
			ZoneObjects objects = ZoneObjects.make(zone("TEST_TOWN", List.of("OPP_TEST", "OPP_NO_SPRITE")), layout, CONTENT, TIERS, seed);
			assertEquals(layout.trainers().size(), objects.trainers().size(), "every trainer cell gets a trainer");
			Set<String> ids = new HashSet<>();
			for (int i = 0; i < objects.trainers().size(); i++) {
				ZoneObjects.Trainer t = objects.trainers().get(i);
				RouteLayout.Trainer cell = layout.trainers().get(i);
				assertEquals(cell.cell(), t.cell());
				assertEquals(cell.sight(), t.sight());
				assertEquals(ZoneObjects.dir(cell.facing()), t.facing());
				assertEquals("OPP_TEST", t.cls(), "only a class with a sprite");
				assertEquals("SPRITE_TEST_GUARD", t.sprite());
				assertTrue(t.id().matches("a" + (cell.area() + 1) + "\\.t[1-9]"), t.id());
				assertTrue(ids.add(t.id()));
				int size = t.party().size();
				assertTrue(cell.area() == 0 ? size >= 1 && size <= 2 : size >= 2 && size <= 3, "party size " + size);
				int top = 5 + layout.areas().get(cell.area()).levelOffset();
				for (WorldIndex.PartyMember m : t.party()) {
					assertTrue(m.species().equals("TESTMON_A") || m.species().equals("TESTMON_B"));
					assertTrue(m.level() >= top && m.level() <= top + 3, "level " + m.level() + " for top " + top);
				}
			}
		}
	}

	@Test
	void ballsHoldTheGamesOwnItemsFromTheirAreasTiers() {
		int balls = 0;
		for (long seed = 1; seed <= 12; seed++) {
			RouteLayout layout = layout(seed);
			ZoneObjects objects = ZoneObjects.make(zone("TEST_TOWN", List.of("OPP_TEST")), layout, CONTENT, TIERS, seed);
			assertEquals(layout.items().size(), objects.balls().size());
			Set<String> ids = new HashSet<>();
			for (ZoneObjects.Ball b : objects.balls()) {
				int area = layout.areaOf(b.cell());
				assertTrue(area >= 0);
				assertTrue(b.id().matches("a" + (area + 1) + "\\.i[1-9]"), b.id());
				assertTrue(ids.add(b.id()));
				assertTrue(CONTENT.items().contains(b.item()), "the game has it");
				assertTrue(TIERS.forLevelOffset(layout.areas().get(area).levelOffset()).contains(b.item()), "its area's tiers");
			}
			assertEquals(objects.balls().isEmpty() ? null : "SPRITE_TEST_BALL", objects.ballSprite());
			balls += objects.balls().size();
		}
		assertTrue(balls > 20, "balls " + balls);
	}

	@Test
	void withoutAPoolOrAClassWithASpriteThereAreNoTrainers() {
		RouteLayout layout = layout(3);
		ZoneObjects noPool = ZoneObjects.make(zone("OTHER_TOWN", List.of("OPP_TEST")), layout, CONTENT, TIERS, 3);
		assertTrue(noPool.trainers().isEmpty());
		assertFalse(noPool.balls().isEmpty(), "balls don't need a pool");
		assertTrue(ZoneObjects.make(zone("TEST_TOWN", List.of("OPP_NO_SPRITE")), layout, CONTENT, TIERS, 3).trainers().isEmpty());
		ZoneObjects nothing = ZoneObjects.make(zone("TEST_TOWN", List.of("OPP_TEST")), layout, ZoneContent.EMPTY, TIERS, 3);
		assertTrue(nothing.trainers().isEmpty() && nothing.balls().isEmpty());
		assertNull(nothing.ballSprite());
	}

	@Test
	void theSameWorldGetsTheSameObjects() {
		RouteLayout layout = layout(5);
		ExportRoutes.Zone zone = zone("TEST_TOWN", List.of("OPP_TEST"));
		assertEquals(ZoneObjects.make(zone, layout, CONTENT, TIERS, 77), ZoneObjects.make(zone, layout, CONTENT, TIERS, 77));
		boolean differs = false;
		for (long other = 78; other < 90 && !differs; other++) {
			differs = !ZoneObjects.make(zone, layout, CONTENT, TIERS, 77).equals(ZoneObjects.make(zone, layout, CONTENT, TIERS, other));
		}
		assertTrue(differs, "another world seed picks differently");
	}

	@Test
	void theBundledTiersAreGenOneItemsByLevelOffset() {
		assertTrue(TIERS.forLevelOffset(0).contains("POTION"));
		assertFalse(TIERS.forLevelOffset(0).contains("SUPER_POTION"));
		assertTrue(TIERS.forLevelOffset(1).containsAll(List.of("POTION", "SUPER_POTION")));
		assertEquals(TIERS.forLevelOffset(3), TIERS.forLevelOffset(9), "the last entry serves any higher offset");
	}

	@Test
	void theSyntheticExportGivesPoolsSpritesAndItems() throws Exception {
		ExportBundle bundle = ExportBundle.open(TestData.syntheticBundle());
		assertEquals(Set.of("POKE_BALL", "POTION"), bundle.itemIds(), "key items are left out");
		ExportEncounters encounters = bundle.encounters();
		assertNotNull(encounters);
		assertEquals("TESTMON_A", encounters.grass().get("TEST_TOWN").slots().get(0).species());
		ExportMap.MapObject guard = bundle.map("TEST_TOWN").objects().stream().filter(ExportMap.MapObject::trainer).findFirst().orElseThrow();
		assertEquals("OPP_TEST", guard.trainerClass());
		// TEST_HILL's only neighbour is TEST_TOWN, whose grass table becomes its pool; TEST_TOWN's neighbour has none
		ZoneContent content = ZoneContent.of(bundle, List.of("TEST_HILL", "TEST_TOWN"));
		List<ZoneContent.Slot> pool = content.pool("TEST_HILL");
		assertEquals(encounters.grass().get("TEST_TOWN").slots().size(), pool.size());
		assertEquals(100.0, pool.stream().mapToDouble(ZoneContent.Slot::weight).sum(), 1e-9, "the slots' shares add up");
		assertTrue(content.pool("TEST_TOWN").isEmpty());
		assertEquals(Map.of("OPP_TEST", "SPRITE_TEST_GUARD"), content.classSprites());
		assertEquals("SPRITE_TEST_BALL", content.ballSprite());
		assertEquals(Set.of("POKE_BALL", "POTION"), content.items());
	}
}
