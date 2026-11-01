package dev.kantobridge.builder.shape;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.kantobridge.scene.SceneAtlas;
import dev.kantobridge.scene.SceneMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The exported Shipyard 1F supports at (10,9..12) and (14,10..12). */
class EmeraldShipyardStackTest {
	private static final int[] CAP = {5096, 5097, 5098, 5099};
	private static final int[] REPEAT = {4838, 4839, 4840, 4841};
	private static final String CAP_KEY = "g3q/5096-5097-5098-5099";
	private static final String REPEAT_KEY = "g3q/4838-4839-4840-4841";
	private static final int[] BEAM_TOP = {4700, 4700, 4701, 4701};
	private static final int[] BEAM_MIDDLE = {4701, 4701, 4701, 4701};
	private static final int[] BEAM_BASE = {4701, 4701, 4724, 4724};
	private static final String BEAM_TOP_KEY = "g3q/4700-4700-4701-4701";
	private static final String BEAM_MIDDLE_KEY = "g3q/4701-4701-4701-4701";
	private static final String BEAM_BASE_KEY = "g3q/4701-4701-4724-4724";
	private static final SceneAtlas ATLAS = new SceneAtlas("g3q", 32, 6000, 8, List.of(), Set.of(), Map.of());

	@AfterEach
	void reset() {
		FittedShapes.useForTest(null);
		FittedShapes.useTablesForTest(null);
		FittedShapes.setEnabled(true);
	}

	private static Map<String, FittedShapes.Entry> entries() {
		byte[] occupied = new byte[FittedShapes.VOXEL_BYTES];
		occupied[0] = 1;
		return Map.of(CAP_KEY, new FittedShapes.Entry("voxels", List.of(), occupied),
				REPEAT_KEY, new FittedShapes.Entry("voxels", List.of(), occupied));
	}

	private static SceneMap sourceColumn(int height) {
		List<String> rows = new ArrayList<>();
		for (int y = 0; y < height; y++) {
			// Exported collision 0 maps to WALK; only terminal collision 7 is SOLID.
			String line = y == height - 1 ? "..BB.." : "..bb..";
			rows.add(line);
			rows.add(line);
		}
		SceneMap fixture = ShapeFixtures.interior("EM_SHIPYARD_SUPPORT_" + height, rows.toArray(String[]::new));
		int[] tiles = fixture.tiles().clone();
		for (int y = 0; y < height; y++) {
			int[] source = y == 0 ? CAP : REPEAT;
			for (int z = 0; z < 2; z++) {
				for (int x = 0; x < 2; x++) tiles[(y * 2 + z) * fixture.artCols() + 2 + x] = source[z * 2 + x];
			}
		}
		return new SceneMap(fixture.id(), 0, "EMERALD", "g3q", "BAKED", fixture.w(), fixture.h(), 2, false,
				fixture.env(), tiles, null, fixture.kinds(), fixture.tags(), fixture.dirs(), null, fixture.ownBorder(), List.of(), List.of())
				.withHeadroom(9);
	}

	private static ShapePlanner.MapShapes plan(SceneMap map) {
		return ShapePlanner.planMap(map, ATLAS, 0, ShapeParams.v4());
	}

	private static SceneMap beamColumn(String roles, boolean walkTop) {
		List<String> rows = new ArrayList<>();
		for (int y = 0; y < roles.length(); y++) {
			String line = y == 0 && walkTop ? "..bb.." : "..BB..";
			rows.add(line);
			rows.add(line);
		}
		SceneMap fixture = ShapeFixtures.interior("EM_SHIP_BEAM_" + roles, rows.toArray(String[]::new));
		int[] tiles = fixture.tiles().clone();
		for (int y = 0; y < roles.length(); y++) {
			int[] source = switch (roles.charAt(y)) { case 'T' -> BEAM_TOP; case 'M' -> BEAM_MIDDLE; default -> BEAM_BASE; };
			for (int z = 0; z < 2; z++) {
				for (int x = 0; x < 2; x++) tiles[(y * 2 + z) * fixture.artCols() + 2 + x] = source[z * 2 + x];
			}
		}
		return new SceneMap(fixture.id(), 0, "EMERALD", "g3q", "BAKED", fixture.w(), fixture.h(), 2, false,
				fixture.env(), tiles, null, fixture.kinds(), fixture.tags(), fixture.dirs(), null, fixture.ownBorder(), List.of(), List.of())
				.withHeadroom(9);
	}

	private static void beamTable(boolean aliases) {
		byte[] occupied = new byte[FittedShapes.VOXEL_BYTES];
		occupied[0] = 1;
		FittedShapes.Entry entry = new FittedShapes.Entry("voxels", List.of(), occupied);
		FittedShapes.useStacksForTest(Map.of(BEAM_TOP_KEY, entry, BEAM_MIDDLE_KEY, entry, BEAM_BASE_KEY, entry),
				Map.of(BEAM_TOP_KEY, BEAM_BASE_KEY, BEAM_MIDDLE_KEY, BEAM_BASE_KEY),
				aliases ? Map.of(BEAM_TOP_KEY, Set.of(BEAM_MIDDLE_KEY), BEAM_MIDDLE_KEY, Set.of(BEAM_MIDDLE_KEY)) : Map.of());
	}

	@Test
	void shipBeamAliasesJoinRepeatedMiddlePositionsButNeverCrossAnUnlinkedBase() {
		for (String roles : List.of("TMMB", "TM", "TMBMB")) {
			SceneMap map = beamColumn(roles, false);
			beamTable(true);
			ShapePlanner.MapShapes result = plan(map);
			int[] bases = roles.equals("TMBMB") ? new int[] {2, 4} : new int[] {roles.length() - 1};
			assertEquals(bases.length, result.units().size());
			int first = 0;
			for (int baseRow : bases) {
				int base = baseRow * 2 * map.artCols() + 2;
				int rows = baseRow - first + 1;
				assertEquals(rows * 2, result.blocks()[base]);
				for (int layer = 0; layer < rows; layer++) {
					int expected = roles.charAt(baseRow - layer) == 'T' ? 4700 : 4701;
					assertEquals(expected, result.open()[base][layer * 2].number(0));
				}
				for (int y = first; y < baseRow; y++) assertNull(result.open()[y * 2 * map.artCols() + 2]);
				first = baseRow + 1;
			}
		}
	}

	@Test
	void shipyardWalkBeamTopIsAbsentBeforeAliasAndMovesOntoTheTwoSolidMiddleSectionsAfter() {
		SceneMap map = beamColumn("TMM", true);
		byte[] kinds = map.kinds().clone();
		int[] tags = map.tags().clone();
		beamTable(false);
		ShapePlanner.MapShapes before = plan(map);
		assertEquals(2, before.units().size());
		assertNull(before.open()[2]);
		assertEquals(2, before.blocks()[2 * map.artCols() + 2]);
		assertEquals(2, before.blocks()[4 * map.artCols() + 2]);
		beamTable(true);
		ShapePlanner.MapShapes after = plan(map);
		assertEquals(1, after.units().size());
		assertNull(after.open()[2]);
		assertNull(after.open()[2 * map.artCols() + 2]);
		int base = 4 * map.artCols() + 2;
		assertEquals(6, after.blocks()[base]);
		assertEquals(4701, after.open()[base][0].number(0));
		assertEquals(4701, after.open()[base][2].number(0));
		assertEquals(4700, after.open()[base][4].number(0));
		assertArrayEquals(before.floor(), after.floor());
		assertArrayEquals(before.decor(), after.decor());
		assertArrayEquals(kinds, map.kinds());
		assertArrayEquals(tags, map.tags());
	}

	@Test
	void baselineEmitsOnlyTheTerminalSolidRepeatAndCurrentEmitsTheWholePhysicalChain() {
		for (int height : List.of(3, 4)) {
			SceneMap map = sourceColumn(height);
			byte[] kinds = map.kinds().clone();
			int[] tags = map.tags().clone();
			// Pre-pass metadata: cap has on=repeat; repeat has no on/walk/seat.
			FittedShapes.useStacksForTest(entries(), Map.of(CAP_KEY, REPEAT_KEY));
			ShapePlanner.MapShapes before = plan(map);
			assertEquals(1, before.units().size());
			assertEquals(height - 1, before.units().getFirst().cy());
			// Current metadata adds only the explicit repeat continuation.
			FittedShapes.useStacksForTest(entries(), Map.of(CAP_KEY, REPEAT_KEY, REPEAT_KEY, REPEAT_KEY));
			ShapePlanner.MapShapes after = plan(map);
			assertEquals(1, after.units().size());
			assertEquals(height - 1, after.units().getFirst().cy());
			for (int z = 0; z < 2; z++) {
				for (int x = 0; x < 2; x++) {
					for (int row = 0; row < height - 1; row++) {
						int i = (row * 2 + z) * map.artCols() + 2 + x;
						assertNull(before.open()[i]);
						assertNull(after.open()[i]);
						assertEquals(0, before.blocks()[i]);
						assertEquals(0, after.blocks()[i]);
					}
					int base = ((height - 1) * 2 + z) * map.artCols() + 2 + x;
					assertEquals(2, before.blocks()[base]);
					assertEquals(3, before.shapeMask()[base]);
					assertEquals(height * 2, after.blocks()[base]);
					assertEquals((1 << height * 2) - 1, after.shapeMask()[base]);
					for (int layer = 0; layer < height; layer++) {
						int[] source = layer == height - 1 ? CAP : REPEAT;
						for (int y = 0; y < 2; y++) {
							for (int quadrant = 0; quadrant < 4; quadrant++) {
								assertEquals(source[quadrant], after.open()[base][layer * 2 + y].number(quadrant));
							}
						}
					}
				}
			}
			assertArrayEquals(before.floor(), after.floor());
			assertArrayEquals(before.decor(), after.decor());
			assertArrayEquals(kinds, map.kinds());
			assertArrayEquals(tags, map.tags());
		}
	}
}
