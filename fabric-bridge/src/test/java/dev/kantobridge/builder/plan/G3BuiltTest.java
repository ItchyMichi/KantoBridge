package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.G3Map;
import dev.kantobridge.builder.export.G3Sites;
import dev.kantobridge.world.StoryCellTable;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SE3 (docs/specs/STORY_EVENTS_PLAN.md §4.1 S3): the state a changeable Gen 3 cell is built in, and the maps with it. */
class G3BuiltTest {
	private static G3Sites.State state(int metatile, int coll) {
		return new G3Sites.State(metatile, coll, 3, "script");
	}

	@Test
	void passesFromTheCollisionVocabulary() {
		assertEquals(StoryCellTable.Pass.GROUND, G3Built.pass(G3Cells.WALK));
		assertEquals(StoryCellTable.Pass.GROUND, G3Built.pass(G3Cells.TALL_GRASS));
		assertEquals(StoryCellTable.Pass.GROUND, G3Built.pass(G3Cells.DOOR));
		assertEquals(StoryCellTable.Pass.GROUND, G3Built.pass(G3Cells.LEDGE_SOUTH), "the hidden game makes the hop");
		assertEquals(StoryCellTable.Pass.WATER, G3Built.pass(G3Cells.WATER));
		assertEquals(StoryCellTable.Pass.SOLID, G3Built.pass(G3Cells.BLOCKED));
		assertEquals(StoryCellTable.Pass.SOLID, G3Built.pass(G3Cells.COUNTER));
		assertEquals(StoryCellTable.Pass.SOLID, G3Built.pass(G3Cells.NOTHING));
		assertEquals(StoryCellTable.Pass.SOLID, G3Built.pass(0x55), "a byte the vocabulary lacks is built solid");
	}

	@Test
	void theMostWalkableStateIsBuiltTheOwnOneOnATie() {
		G3Sites.State wall = new G3Sites.State(3, G3Cells.BLOCKED, 3, null);
		G3Sites.State water = state(2, G3Cells.WATER);
		G3Sites.State ground = state(0, G3Cells.WALK);
		assertSame(ground, G3Built.built(new G3Sites.Site("EM_A", 0, 0, wall, List.of(water, ground))), "ground before water before solid");
		assertSame(water, G3Built.built(new G3Sites.Site("EM_A", 0, 0, wall, List.of(water))));
		G3Sites.State own = new G3Sites.State(0, G3Cells.WALK, 3, null);
		assertSame(own, G3Built.built(new G3Sites.Site("EM_A", 0, 0, own, List.of(state(7, G3Cells.WALK), wall))), "art alone: the own state");
		G3Sites.State tv = new G3Sites.State(9, G3Cells.BLOCKED, 3, null);
		assertSame(tv, G3Built.built(new G3Sites.Site("EM_A", 0, 0, tv, List.of(state(10, G3Cells.BLOCKED)))), "all solid: the own state");
	}

	@Test
	void theMapsCarryTheBuiltStates() throws Exception {
		ExportBundle bundle = ExportBundle.open(TestData.syntheticGen3Bundle());
		Map<String, G3Map> maps = G3Built.maps(bundle);
		G3Map route = maps.get("EM_TEST_ROUTE");
		assertEquals(0, route.mid(4, 0), "the water a layout turns to land is built as land");
		assertEquals(G3Cells.WALK, route.coll(4, 0));
		assertEquals(2, route.mid(5, 0), "its neighbour keeps its water");
		assertEquals(2, bundle.g3map("EM_TEST_ROUTE").mid(4, 0), "the export itself is untouched");
		G3Map town = maps.get("EM_TEST_TOWN");
		assertEquals(0, town.mid(7, 2), "the tree a layout clears is built cleared");
		assertEquals(0, town.mid(6, 2), "the ground a script walls off keeps its ground");
		assertSame(bundle.g3map("EM_TEST_TOWN").objects(), town.objects());
		assertEquals(town.mid(7, 2), G3Built.map(bundle, "EM_TEST_TOWN").mid(7, 2));
		// a map none of whose sites changes is the export's own
		G3Map house = bundle.g3map("EM_TEST_HOUSE");
		assertSame(house, G3Built.apply(house, List.of(new G3Sites.Site("EM_TEST_HOUSE", 2, 1, new G3Sites.State(512, G3Cells.WALK, 3, null),
				List.of(state(514, G3Cells.WALK))))));
		assertSame(house, G3Built.apply(house, List.of(new G3Sites.Site("EM_TEST_HOUSE", 40, 1, new G3Sites.State(512, G3Cells.WALK, 3, null),
				List.of(state(0, G3Cells.WALK))))), "a site off the grid is left alone");
	}
}
