package dev.kantobridge.client.zone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.client.puppet.PuppetManager;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.story.RouteProgress;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.WorldIndex;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the client shows and reaches in a Wild Routes zone (WR5): its puppets for a player's progress, cells and balls. */
class ZoneViewTest {
	private static final String HASH = "0".repeat(64);
	/** The route map's A at block (1000, 2000); the zone's box around it. */
	private static final WorldIndex.Zone ZONE = new WorldIndex.Zone("z_zone", "KB_ROUTE_Z", "forest", 990, 64, 1950, 200, 200,
			"kantobridge:wild_forest",
			List.of(new WorldIndex.ZoneTrainer("a1.t1", 12, -3, Dir.LEFT, 4, "OPP_TEST", "SPRITE_GUARD", List.of(new WorldIndex.PartyMember("MON", 5)))),
			List.of(new WorldIndex.ZoneBall("a1.i1", 20, 5, "POTION"), new WorldIndex.ZoneBall("a2.i1", -2, 7, "POKE_BALL")), "SPRITE_BALL");
	private static final WorldIndex INDEX = new WorldIndex("red", HASH,
			List.of(new MapPlacement("KB_ROUTE_Z", 1000, 64, 2000, 2, 2, 0, MapPlacement.Kind.ROUTE, "PAL", "kantobridge:wild_forest", false, null)),
			HASH, StoryCellTable.empty(), List.of(), List.of(ZONE));

	@Test
	void puppetsAreEveryTrainerAndTheBallsNotTaken() throws Exception {
		RouteProgress progress = new RouteProgress();
		List<PuppetManager.ZoneSpec> all = ZoneView.specs(ZONE, progress);
		assertEquals(List.of(
				new PuppetManager.ZoneSpec("zone:z_zone:a1.t1", "KB_ROUTE_Z", "SPRITE_GUARD", "trainer", 12, -3, Dir.LEFT),
				new PuppetManager.ZoneSpec("zone:z_zone:a1.i1", "KB_ROUTE_Z", "SPRITE_BALL", "item", 20, 5, Dir.DOWN),
				new PuppetManager.ZoneSpec("zone:z_zone:a2.i1", "KB_ROUTE_Z", "SPRITE_BALL", "item", -2, 7, Dir.DOWN)), all);
		progress.replaceAll(ProtoJson.parseObject("{\"z_zone\":{\"taken\":[\"a1.i1\"],\"wins\":{\"a1.t1\":1}}}"));
		List<PuppetManager.ZoneSpec> later = ZoneView.specs(ZONE, progress);
		assertEquals(2, later.size(), "the taken ball's puppet goes; a beaten trainer stays at its post");
		assertEquals("a2.i1", ZoneView.objectOf(ZONE, later.get(1).id()));
		assertNull(ZoneView.objectOf(ZONE, "zone:other:a2.i1"));
		WorldIndex.Zone spriteless = new WorldIndex.Zone("z_zone", "KB_ROUTE_Z", "forest", 990, 64, 1950, 200, 200, "kantobridge:wild_forest",
				ZONE.trainers(), ZONE.balls(), null);
		assertEquals(1, ZoneView.specs(spriteless, new RouteProgress()).size(), "no ball sprite: the balls only collide");
	}

	@Test
	void cellsAndTheBallInFront() throws Exception {
		assertEquals(ZONE, ZoneView.zoneAt(INDEX, 1001.5, 2001.5));
		assertNull(ZoneView.zoneAt(INDEX, 0, 0));
		// cell (19, 5) of the grid is blocks 1038-1039, 2010-2011; the ball is east of it
		int[] cell = ZoneView.cellAt(INDEX, ZONE, 1039.4, 2010.6);
		assertEquals(19, cell[0]);
		assertEquals(5, cell[1]);
		assertEquals("a1.i1", ZoneView.facedBall(INDEX, ZONE, new RouteProgress(), 1039.4, 2010.6, Dir.RIGHT).id());
		assertNull(ZoneView.facedBall(INDEX, ZONE, new RouteProgress(), 1039.4, 2010.6, Dir.UP));
		// negative cells: (-2, 7) is blocks 996-997, 2014-2015; from (-2, 6) facing down
		assertEquals("a2.i1", ZoneView.facedBall(INDEX, ZONE, new RouteProgress(), 996.5, 2012.5, Dir.DOWN).id());
		assertTrue(ZoneView.nextTo(INDEX, ZONE, 996.5, 2012.5, -2, 7));
		assertFalse(ZoneView.nextTo(INDEX, ZONE, 996.5, 2010.5, -2, 7), "two cells away");
		RouteProgress taken = new RouteProgress();
		taken.replaceAll(ProtoJson.parseObject("{\"z_zone\":{\"taken\":[\"a2.i1\"],\"wins\":{}}}"));
		assertNull(ZoneView.facedBall(INDEX, ZONE, taken, 996.5, 2012.5, Dir.DOWN), "already taken");
		assertEquals("{\"route\":\"z_zone\",\"id\":\"a1.i1\",\"item\":\"POTION\"}", ZoneView.give(ZONE, ZONE.balls().get(0)).toString());
	}
}
