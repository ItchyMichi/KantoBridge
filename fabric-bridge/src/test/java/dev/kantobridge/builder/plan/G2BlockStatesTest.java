package dev.kantobridge.builder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportSwaps;
import dev.kantobridge.builder.export.G2Map;
import dev.kantobridge.builder.export.G2Tiles;
import dev.kantobridge.scene.gen3.QuarterPages;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Gen 2 blocks with several states (docs/specs/STORY_EVENTS_PLAN.md §5.5: the bedroom's decorations) on the synthetic
 * Crystal export's house (tools/fake_gen1/synth2.py: its floor block 1, wall 2, counter 3, mat 4): every state kept, each
 * cell built in its most walkable one, and a {@code cell} story cell on every walkable cell with the other states' passes,
 * heights and art.
 */
class G2BlockStatesTest {
	private static final String HOUSE = "G2_TEST_HOUSE";
	private static G2Map house;
	private static G2Tiles tiles;

	@BeforeAll
	static void load() throws Exception {
		ExportBundle bundle = ExportBundle.open(TestData.syntheticGen2Bundle());
		house = bundle.g2map(HOUSE);
		tiles = bundle.g2tiles(house.variant());
	}

	/**
	 * The floor block (0, 1) a script turns into a counter, the mat or a wall (a bed, a carpet, a plant), and the wall block
	 * (0, 0) into a counter or block 0 (a poster).
	 */
	private static List<ExportSwaps.Site> sites() {
		return List.of(
				new ExportSwaps.Site(HOUSE, 0, 1, "script", 1, 3),
				new ExportSwaps.Site(HOUSE, 0, 1, "script", 1, 4),
				new ExportSwaps.Site(HOUSE, 0, 1, "script", 1, 2),
				new ExportSwaps.Site(HOUSE, 0, 0, "script", 2, 3),
				new ExportSwaps.Site(HOUSE, 0, 0, "script", 2, 0));
	}

	@Test
	void everyStateIsKeptAndTheMostWalkableBuilt() {
		Warnings warnings = new Warnings();
		MapModel model = G2Cells.model(house, tiles, sites(), true, warnings);
		assertEquals(3, warnings.noteCount("g2_block_several_states"));
		for (int[] c : new int[][] {{0, 2}, {1, 2}, {0, 3}, {1, 3}}) {
			assertEquals(List.of(1, 3, 4, 2), model.blockStates(c[0], c[1]), "the map data's block first, then the sites' in order");
			assertEquals(1, model.builtBlock(c[0], c[1]), "the floor: walkable in every cell");
			assertEquals(MapModel.Kind.GROUND, model.kind(c[0], c[1]));
			assertFalse(model.changes(c[0], c[1]), "no closed state: its story cell draws the others");
		}
		// the wall: no state is walkable, so the first site's, as a lone site builds it (its after on a tie)
		assertEquals(List.of(2, 3, 0), model.blockStates(0, 0));
		assertEquals(3, model.builtBlock(0, 0));
		assertEquals(MapModel.Kind.COUNTER, model.kind(1, 1));
		assertEquals(List.of(), model.blockStates(2, 2), "a block no script turns into several others");
	}

	@Test
	void theWalkableCellsGetCellStoryCells() {
		Warnings warnings = new Warnings();
		MapModel model = G2Cells.model(house, tiles, sites(), true, warnings);
		PlacedMap placed = new PlacedMap(new MapPlacement(HOUSE, 0, 42, 0, house.cellWidth(), house.cellHeight(), 1, MapPlacement.Kind.INTERIOR,
				QuarterPages.PALETTE, WorldPlan.BIOME_INTERIOR, false, null), model);
		StoryCellTable table = G2StoryCells.plan(List.of(placed), Map.of(HOUSE, house), warnings,
				(atlas, palette, tile) -> tile < 0 ? -1 : tile + 1000, id -> tiles);
		// the house's lass stands on (1, 2): her object cell comes first
		assertEquals(StoryCellType.OBJECT, table.get(HOUSE + ":1:2").type());
		assertEquals(1, warnings.noteCount("g2_states_under_object"));
		for (String key : List.of(HOUSE + ":0:2", HOUSE + ":0:3", HOUSE + ":1:3")) {
			StoryCellTable.Entry entry = table.get(key);
			assertEquals(StoryCellType.CELL, entry.type(), key);
			assertEquals(1, entry.start(), "the map data's block");
			assertEquals(new StoryCellTable.Built(1, StoryCellTable.Pass.GROUND), entry.built());
			assertEquals(List.of(3, 4, 2), entry.states().stream().map(StoryCellTable.CellState::metatile).toList(), "block ids");
			assertEquals(List.of(StoryCellTable.Pass.SOLID, StoryCellTable.Pass.GROUND, StoryCellTable.Pass.SOLID),
					entry.states().stream().map(StoryCellTable.CellState::pass).toList());
			assertEquals(List.of(HeightRules.COUNTER, 0, HeightRules.INTERIOR_WALL),
					entry.states().stream().map(StoryCellTable.CellState::height).toList());
		}
		// a state's art: its block's four tiles at the cell (here the south-east one), north-west first, as full-shape slots
		StoryCellTable.CellState mat = table.get(HOUSE + ":1:3").states().get(1);
		assertEquals(List.of(tiles.tile(4, 2, 2) + 1000, tiles.tile(4, 3, 2) + 1000, tiles.tile(4, 2, 3) + 1000, tiles.tile(4, 3, 3) + 1000),
				mat.art());
		// the wall's cells are built solid: no story cell stands there, its built art stays
		assertNull(table.get(HOUSE + ":0:0"));
		assertEquals(4, warnings.noteCount("g2_states_solid"));
		assertEquals(0, warnings.total(), warnings.warnings().toString());
	}
}
