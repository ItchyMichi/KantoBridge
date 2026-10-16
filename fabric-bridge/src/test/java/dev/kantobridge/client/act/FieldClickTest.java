package dev.kantobridge.client.act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import dev.kantobridge.world.WorldIndex;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Field moves from a press (docs/PLAN.md §6 M8 item 4): what A or a right-click on a cell sends, on a made-up town with
 * a Cut tree and a pond, a map north of it across a seam, and a dock whose ship has sailed or not.
 */
class FieldClickTest {
	private static final String HASH = "0".repeat(64);
	private static final String TREE = "TOWN:10:14";
	private static final String POND = "TOWN:12:12";
	private static final String NORTH_POND = "NORTH:5:9";
	private static final String DECK = "DOCK:2:0";
	private static final String HULL = "DOCK:2:1";
	private static final List<Integer> ART = List.of(1, 1, 1, 1);

	private static final WorldIndex WORLD = new WorldIndex("red", HASH, List.of(
			new MapPlacement("TOWN", 0, 64, 0, 20, 18, 0, MapPlacement.Kind.SURFACE, "PAL", "kantobridge:kanto_outdoor", false, null),
			// Directly north of the town on the same grid: its row 9 is the town's row -1.
			new MapPlacement("NORTH", 0, 64, -20, 20, 10, 0, MapPlacement.Kind.SURFACE, "PAL", "kantobridge:kanto_outdoor", false, null),
			new MapPlacement("DOCK", 100, 42, 0, 8, 6, 1, MapPlacement.Kind.INTERIOR, "PAL", "kantobridge:kanto_interior", false, "TOWN")),
			HASH, StoryCellTable.of(List.of(
					new StoryCellTable.Entry(CellPos.parse(TREE), StoryCellType.TILE, null, new StoryCellTable.Swap(5, 7, "cut", 14, 15), false, 14,
							new StoryCellTable.Closed(14, StoryCellTable.Pass.SOLID, 2, ART), false),
					new StoryCellTable.Entry(CellPos.parse(POND), StoryCellType.WATER, null, null),
					new StoryCellTable.Entry(CellPos.parse(NORTH_POND), StoryCellType.WATER, null, null),
					new StoryCellTable.Entry(CellPos.parse(DECK), StoryCellType.TILE, null, new StoryCellTable.Swap(1, 0, "script", 1, 2), false, 1,
							new StoryCellTable.Closed(2, StoryCellTable.Pass.WATER, 0, ART), false),
					new StoryCellTable.Entry(CellPos.parse(HULL), StoryCellType.TILE, null, new StoryCellTable.Swap(1, 0, "script", 1, 2), false, 1,
							new StoryCellTable.Closed(1, StoryCellTable.Pass.SOLID, 2, ART), true))),
			List.of());

	private static Predicate<String> drawn(String... keys) {
		Set<String> set = Set.of(keys);
		return set::contains;
	}

	private static FieldClick.Decision press(String map, int hx, int hy, int tx, int ty, boolean surfing, Predicate<String> drawn, boolean land) {
		return FieldClick.decide(WORLD, map, hx, hy, new CellPos(map, tx, ty), surfing, drawn, land);
	}

	@Test
	void aGen3CellIsWaterWhileItsStateIsWater() {
		// SE3: a cell built as land whose other state is water (a tide floods it), and one built as water that a story
		// turns to a wall
		String flooded = "EM_CAVE:3:3";
		String pool = "EM_CAVE:5:3";
		List<Integer> art = List.of(1, 1, 1, 1);
		WorldIndex world = new WorldIndex("emerald", "0".repeat(64), List.of(
				new MapPlacement("EM_CAVE", 0, 64, 0, 10, 10, 0, MapPlacement.Kind.SURFACE, "BAKED", "kantobridge:kanto_outdoor", false, null)),
				"0".repeat(64), StoryCellTable.of(List.of(
						new StoryCellTable.Entry(CellPos.parse(flooded), 0, new StoryCellTable.Built(0, StoryCellTable.Pass.GROUND),
								List.of(new StoryCellTable.CellState(2, StoryCellTable.Pass.WATER, 0, art))),
						new StoryCellTable.Entry(CellPos.parse(pool), 2, new StoryCellTable.Built(2, StoryCellTable.Pass.WATER),
								List.of(new StoryCellTable.CellState(3, StoryCellTable.Pass.SOLID, 2, art))))),
				List.of());
		java.util.function.ToIntFunction<String> states = key -> 0;
		FieldClick.Decision dry = FieldClick.decide(world, "EM_CAVE", 3, 2, new CellPos("EM_CAVE", 3, 3), false, drawn(), states, false);
		assertEquals(FieldClick.Kind.INTERACT, dry.kind(), "built land: the ordinary A");
		FieldClick.Decision wet = FieldClick.decide(world, "EM_CAVE", 3, 2, new CellPos("EM_CAVE", 3, 3), false, drawn(flooded), states, false);
		assertEquals("surf", wet.action(), "flooded: Surf");
		FieldClick.Decision pond = FieldClick.decide(world, "EM_CAVE", 5, 2, new CellPos("EM_CAVE", 5, 3), false, drawn(), states, false);
		assertEquals("surf", pond.action(), "built as water: Surf");
		FieldClick.Decision walled = FieldClick.decide(world, "EM_CAVE", 5, 2, new CellPos("EM_CAVE", 5, 3), true, drawn(pool), states, true);
		assertEquals(FieldClick.Kind.INTERACT, walled.kind(), "a surfer can't land on the wall the pool became");
	}

	@Test
	void aStandingCutTreeIsCutFromNextToIt() {
		FieldClick.Decision cut = press("TOWN", 10, 13, 10, 14, false, drawn(TREE), false);
		assertEquals(FieldClick.Kind.FIELD, cut.kind());
		assertEquals("cut", cut.action());
		assertEquals("{\"action\":\"cut\",\"x\":10,\"y\":14}", ProtoJson.write(cut.fieldData()));
		assertEquals(FieldClick.Kind.FIELD, press("TOWN", 10, 13, 10, 14, false, drawn(TREE), true).kind(), "a click on its floor too");
		// Already cut for this player: the ordinary A on open ground.
		assertEquals(FieldClick.Kind.INTERACT, press("TOWN", 10, 13, 10, 14, false, drawn(), false).kind());
		// Two cells away, or diagonal: refused here, nothing sent.
		FieldClick.Decision far = press("TOWN", 10, 12, 10, 14, false, drawn(TREE), true);
		assertEquals(FieldClick.Kind.REFUSED, far.kind());
		assertEquals("not_adjacent", far.reason());
		assertEquals("cut", far.action());
		assertEquals(FieldClick.Kind.REFUSED, press("TOWN", 11, 13, 10, 14, false, drawn(TREE), true).kind(), "diagonal");
	}

	@Test
	void waterMeansSurfOnlyWhileWalking() {
		FieldClick.Decision surf = press("TOWN", 12, 11, 12, 12, false, drawn(), true);
		assertEquals(FieldClick.Kind.FIELD, surf.kind());
		assertEquals("{\"action\":\"surf\",\"x\":12,\"y\":12}", ProtoJson.write(surf.fieldData()));
		assertEquals(FieldClick.Kind.FIELD, press("TOWN", 12, 11, 12, 12, false, drawn(), false).kind(), "A at the water too");
		// Already surfing: A on water is Gen 1's nothing.
		assertEquals(FieldClick.Kind.INTERACT, press("TOWN", 12, 11, 12, 12, true, drawn(), true).kind());
		// Plain ground while walking: the ordinary A.
		FieldClick.Decision ground = press("TOWN", 5, 5, 5, 6, false, drawn(), true);
		assertEquals(FieldClick.Kind.INTERACT, ground.kind());
		assertNull(ground.action());
	}

	@Test
	void aSurferClickingTheGroundOfLandStepsAshore() {
		FieldClick.Decision ashore = press("TOWN", 12, 12, 12, 11, true, drawn(), true);
		assertEquals(FieldClick.Kind.FIELD, ashore.kind());
		assertEquals("{\"action\":\"surf\",\"x\":12,\"y\":11}", ProtoJson.write(ashore.fieldData()), "gen1-bridge's dismount");
		// A has no ground to aim at (it may be a sign on the shore): the ordinary A.
		assertEquals(FieldClick.Kind.INTERACT, press("TOWN", 12, 12, 12, 11, true, drawn(), false).kind());
		// A moored hull is no shore.
		assertEquals(FieldClick.Kind.INTERACT, press("DOCK", 2, 2, 2, 1, true, drawn(HULL), true).kind());
	}

	@Test
	void acrossASeamTheCellIsTheNeighboursButNamedInThePlayersMap() {
		FieldClick.Decision surf = press("TOWN", 5, 0, 5, -1, false, drawn(), true);
		assertEquals(FieldClick.Kind.FIELD, surf.kind(), "the north map's pond");
		assertEquals("{\"action\":\"surf\",\"x\":5,\"y\":-1}", ProtoJson.write(surf.fieldData()));
		assertEquals(new CellPos("NORTH", 5, 9), InteractTarget.own(WORLD, new CellPos("TOWN", 5, -1)));
		assertEquals(new CellPos("TOWN", 5, 0), InteractTarget.own(WORLD, new CellPos("TOWN", 5, 0)));
		assertNull(InteractTarget.own(WORLD, new CellPos("TOWN", 50, -40)), "off every map");
	}

	@Test
	void aShipsCellsAreWaterOnlyOnceSheHasSailed() {
		// Moored: her hull is drawn and bars the way; her deck is walkable ground.
		Predicate<String> moored = drawn(HULL);
		assertEquals(FieldClick.Kind.INTERACT, press("DOCK", 2, 2, 2, 1, false, moored, true).kind(), "the hull");
		assertEquals(FieldClick.Kind.INTERACT, press("DOCK", 1, 0, 2, 0, false, moored, true).kind(), "the deck");
		assertEquals(FieldClick.Kind.FIELD, press("DOCK", 1, 0, 2, 0, true, moored, true).kind(), "a surfer climbs onto her deck");
		// Sailed: her deck draws the water it became, her hull's cell is built as water.
		Predicate<String> sailed = drawn(DECK);
		assertEquals("surf", press("DOCK", 2, 2, 2, 1, false, sailed, true).action(), "where her hull lay");
		assertEquals("surf", press("DOCK", 1, 0, 2, 0, false, sailed, true).action(), "where her deck was");
		assertEquals(FieldClick.Kind.INTERACT, press("DOCK", 1, 0, 2, 0, true, sailed, true).kind(), "a surfer on it does nothing");
	}
}
