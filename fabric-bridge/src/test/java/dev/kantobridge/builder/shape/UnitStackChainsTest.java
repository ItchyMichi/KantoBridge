package dev.kantobridge.builder.shape;

import static dev.kantobridge.builder.shape.ShapeFixtures.tile;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.kantobridge.scene.SceneMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Explicit source rows form one validated object, never a partly consumed pair. */
class UnitStackChainsTest {
	@AfterEach
	void reset() {
		FittedShapes.useForTest(null);
		FittedShapes.useTablesForTest(null);
		FittedShapes.setEnabled(true);
	}

	private static String key(char c) {
		int id = tile(c);
		return FittedShapes.key(ShapeFixtures.ATLAS, new int[] {id, id, id, id});
	}

	private static Map<String, FittedShapes.Entry> entries(String symbols) {
		Map<String, FittedShapes.Entry> result = new HashMap<>();
		for (char c : symbols.toCharArray()) {
			byte[] bits = new byte[FittedShapes.VOXEL_BYTES];
			bits[0] = 1;
			result.put(key(c), new FittedShapes.Entry("voxels", List.of(tile(c)), bits));
		}
		return result;
	}

	private static Map<String, String> links(String symbols) {
		Map<String, String> result = new HashMap<>();
		for (int n = 1; n < symbols.length(); n++) {
			result.put(key(symbols.charAt(n - 1)), key(symbols.charAt(n)));
		}
		return result;
	}

	private static SceneMap drawing(String symbols, boolean interior) {
		List<String> rows = new ArrayList<>();
		for (char c : symbols.toCharArray()) {
			rows.add(".." + c + c + "..");
			rows.add(".." + c + c + "..");
		}
		return ShapeFixtures.map("CHAIN", !interior, rows.toArray(String[]::new));
	}

	private static ShapePlanner.MapShapes plan(SceneMap map) {
		return ShapePlanner.planMap(map, ShapeFixtures.TIER_B, 0, ShapeParams.v4());
	}

	private static int index(SceneMap map, int row) {
		return row * map.perCell() * map.artCols() + map.perCell();
	}

	@Test
	void threeSourceRowsMoveTogetherAndKeepBottomToTopPaintKeys() {
		SceneMap map = drawing("ABC", false);
		FittedShapes.useStacksForTest(entries("ABC"), links("ABC"));
		ShapePlanner.MapShapes result = plan(map);
		assertEquals(1, result.units().size());
		assertEquals(2, result.units().get(0).cy());
		int base = index(map, 2);
		assertEquals(6, result.blocks()[base]);
		assertEquals(0b111111, result.shapeMask()[base]);
		ShapeKey[] column = result.open()[base];
		for (int layer = 0; layer < 3; layer++) {
			for (int y = 0; y < 2; y++) {
				assertEquals(tile("CBA".charAt(layer)), column[layer * 2 + y].number(0));
			}
		}
		for (int row = 0; row < 2; row++) {
			assertNull(result.open()[index(map, row)]);
			assertEquals(0, result.blocks()[index(map, row)]);
			assertEquals(0, result.shapeMask()[index(map, row)]);
		}
	}

	@Test
	void walkableProjectionRowsMoveOnlyTheirKeysAndUnrelatedCellsAreUnchanged() {
		SceneMap map = ShapeFixtures.surface("WALK_CHAIN", "..aaDD..", "..aaDD..", "..bb....", "..bb....", "..CC....", "..CC....");
		byte[] kinds = map.kinds().clone();
		int[] tags = map.tags().clone();
		FittedShapes.useStacksForTest(entries("abCD"), Map.of());
		ShapePlanner.MapShapes before = plan(map);
		FittedShapes.useStacksForTest(entries("abCD"), links("abC"));
		ShapePlanner.MapShapes after = plan(map);
		assertEquals(6, after.blocks()[index(map, 2)]);
		assertNull(after.open()[index(map, 0)]);
		assertNull(after.open()[index(map, 1)]);
		assertArrayEquals(kinds, map.kinds());
		assertArrayEquals(tags, map.tags());
		assertArrayEquals(before.decor(), after.decor());
		assertArrayEquals(before.floor(), after.floor());
		for (int z = 0; z < map.artRows(); z++) {
			for (int x = 0; x < map.artCols(); x++) {
				if (x >= 2 && x < 4) {
					continue;
				}
				int i = z * map.artCols() + x;
				assertArrayEquals(before.open()[i], after.open()[i]);
				assertEquals(before.blocks()[i], after.blocks()[i]);
				assertEquals(before.shapeMask()[i], after.shapeMask()[i]);
			}
		}
	}

	@Test
	void orphanWalkTopDropsItsTemporaryKeysWithoutChangingFloorOrCollision() {
		SceneMap map = drawing("aC", false);
		FittedShapes.useStacksForTest(entries("aBC"), Map.of(key('a'), key('B')));
		ShapePlanner.MapShapes result = plan(map);
		assertNull(result.open()[index(map, 0)]);
		assertEquals(0, result.blocks()[index(map, 0)]);
		assertEquals(0, result.decor()[index(map, 0)]);
		assertNotNull(result.open()[index(map, 1)]);
		assertEquals(1, result.units().size());
	}

	@Test
	void cyclicSourceNamesCannotLoopAndDistinctMapPositionsStillFormOneChain() {
		SceneMap map = drawing("ABA", false);
		FittedShapes.useStacksForTest(entries("AB"), Map.of(key('A'), key('B'), key('B'), key('A')));
		ShapePlanner.MapShapes after = plan(map);
		assertEquals(1, after.units().size());
		assertEquals(6, after.blocks()[index(map, 2)]);
		assertNull(after.open()[index(map, 0)]);
		assertNull(after.open()[index(map, 1)]);
		assertEquals(tile('A'), after.open()[index(map, 2)][0].number(0));
		assertEquals(tile('B'), after.open()[index(map, 2)][2].number(0));
		assertEquals(tile('A'), after.open()[index(map, 2)][4].number(0));
	}

	@Test
	void explicitAlternateBaseMatchesOnlyTheActualAdjacentSourceAndKeepsPrimaryPair() {
		FittedShapes.useStacksForTest(entries("ABCD"), Map.of(key('A'), key('B')),
				Map.of(key('A'), java.util.Set.of(key('C'))));
		for (String drawing : List.of("AB", "AC")) {
			SceneMap map = drawing(drawing, false);
			assertEquals(1, plan(map).units().size());
			assertEquals(4, plan(map).blocks()[index(map, 1)]);
		}
		assertEquals(2, plan(drawing("AD", false)).units().size());
	}

	@Test
	void identicalMiddleSectionsUsePhysicalPositionsAndTheirExplicitTerminalAlias() {
		FittedShapes.useStacksForTest(entries("ABC"), Map.of(key('A'), key('B'), key('B'), key('B')),
				Map.of(key('B'), java.util.Set.of(key('C'))));
		SceneMap map = drawing("ABBC", true).withHeadroom(8);
		ShapePlanner.MapShapes result = plan(map);
		assertEquals(1, result.units().size());
		assertEquals(8, result.blocks()[index(map, 3)]);
		ShapeKey[] column = result.open()[index(map, 3)];
		for (int layer = 0; layer < 4; layer++) assertEquals(tile("CBBA".charAt(layer)), column[layer * 2].number(0));
		assertEquals(4, plan(map.withHeadroom(7)).units().size());
	}

	@Test
	void selfReferencingSourceStillHonorsCarrierLimitWithoutConsumingAPrefix() {
		FittedShapes.useStacksForTest(entries("A"), Map.of(key('A'), key('A')));
		SceneMap oversized = drawing("AAAAAAAAAAAAAAAA", false);
		assertEquals(16, plan(oversized).units().size());
		SceneMap limit = drawing("AAAAAAAAAAAAAAA", false);
		assertEquals(1, plan(limit).units().size());
		assertEquals(30, plan(limit).blocks()[index(limit, 14)]);
	}

	@Test
	void anAbsentAlternativeBasePreservesTheExistingPairAndUnrelatedUnit() {
		SceneMap map = drawing("ABD", false);
		FittedShapes.useStacksForTest(entries("ABCD"), links("ABC"));
		ShapePlanner.MapShapes result = plan(map);
		assertEquals(2, result.units().size());
		assertNull(result.open()[index(map, 0)]);
		assertEquals(4, result.blocks()[index(map, 1)]);
		assertEquals(2, result.blocks()[index(map, 2)]);
	}

	@Test
	void headroomRejectsAWholeChainAndPreservesAnOrdinaryPairWhenItFits() {
		FittedShapes.useStacksForTest(entries("ABC"), links("ABC"));
		SceneMap shortRoom = drawing("ABC", true).withHeadroom(5);
		assertEquals(3, plan(shortRoom).units().size());
		SceneMap tallRoom = shortRoom.withHeadroom(6);
		assertEquals(1, plan(tallRoom).units().size());
		FittedShapes.useStacksForTest(entries("AB"), links("AB"));
		SceneMap pair = drawing("AB", true).withHeadroom(4);
		assertEquals(4, plan(pair).blocks()[index(pair, 1)]);
		SceneMap historicalFallback = drawing("AB", true);
		assertEquals(4, plan(historicalFallback).blocks()[index(historicalFallback, 1)]);
	}

	@Test
	void thirtyCarrierLimitDoesNotSplitAnOversizedChain() {
		String symbols = "ABCDEFGHIJKLMNOP";
		FittedShapes.useStacksForTest(entries(symbols), links(symbols));
		SceneMap map = drawing(symbols, false);
		assertEquals(16, plan(map).units().size());
		String valid = symbols.substring(0, 15);
		FittedShapes.useStacksForTest(entries(valid), links(valid));
		SceneMap max = drawing(valid, false);
		ShapePlanner.MapShapes result = plan(max);
		assertEquals(1, result.units().size());
		assertEquals(30, result.blocks()[index(max, 14)]);
		assertEquals(0x3fffffff, result.shapeMask()[index(max, 14)]);
	}
}
