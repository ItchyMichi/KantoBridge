package dev.kantobridge.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Naming rules: presets first and the NEW NAME flow, the grid (the engine's or Gen 1's own), typing up to the limit
 * with multi-character tokens counting as one glyph, deleting, the case switch, confirming and the empty-name
 * fallbacks, and the checks on what goes out.
 */
class NamingModelTest {
	static NamingModel plain(int max) {
		return new NamingModel("YOUR NAME?", max, List.of(), "", null, null);
	}

	/** Moves the cursor onto the glyph and presses A. */
	static void type(NamingModel naming, String glyph) {
		for (int r = 0; r < naming.rows().size(); r++) {
			for (int c = 0; c < naming.rows().get(r).size(); c++) {
				NamingModel.Cell cell = naming.rows().get(r).get(c);
				if (cell.control() == NamingModel.Control.GLYPH && cell.glyph().equals(glyph)) {
					naming.selectCell(r, c);
					assertNull(naming.pressCell());
					return;
				}
			}
		}
		throw new AssertionError("glyph not on this page: " + glyph);
	}

	@Test
	void withoutAGridItIsGen1sUpperAndLowerLayout() {
		NamingModel naming = plain(7);
		assertFalse(naming.engineGrid());
		assertEquals(2, naming.pageCount());
		List<List<NamingModel.Cell>> rows = naming.rows();
		assertEquals(6, rows.size(), "five glyph rows and the case row");
		assertEquals("A", rows.get(0).get(0).glyph());
		assertEquals("I", rows.get(0).get(8).glyph());
		assertEquals(" ", rows.get(2).get(8).glyph(), "space ends the S-Z row");
		assertEquals("<PK>", rows.get(3).get(7).glyph());
		assertEquals("<MN>", rows.get(3).get(8).glyph());
		assertEquals(NamingModel.Control.END, rows.get(4).get(8).control(), "ED ends the last glyph row");
		assertEquals(NamingModel.Control.CASE, rows.get(5).get(0).control());
		assertTrue(naming.charset().containsAll(List.of("A", "z", "♂", "♀", "×", "<PK>", " ")));
		assertFalse(naming.charset().contains("1"), "Gen 1 names have no digits");
		naming.flipCase();
		assertEquals("a", naming.rows().get(0).get(0).glyph());
	}

	@Test
	void anEngineGridKeepsOnlyItsGlyphCells() {
		List<List<List<String>>> grid = List.of(
				List.of(List.of("A", "B", "C"), List.of("<PK>", "<MN>", "é"), List.of("ED", "lower case")),
				List.of(List.of("a", "b", "c"), List.of("?", "!", " ")));
		NamingModel naming = new NamingModel("NAME?", 10, List.of(), "", 25, grid);
		assertTrue(naming.engineGrid());
		List<List<NamingModel.Cell>> rows = naming.rows();
		assertEquals(4, rows.size(), "two glyph rows (the engine's own control row dropped), ED on a row of its own, the case row");
		assertEquals(List.of("<PK>", "<MN>", "é"), rows.get(1).stream().map(NamingModel.Cell::glyph).toList());
		assertEquals(NamingModel.Control.END, rows.get(2).get(0).control(), "a full last row puts ED on a row of its own");
		assertEquals(NamingModel.Control.CASE, rows.get(3).get(0).control());
		assertEquals(Integer.valueOf(25), naming.mon());
		assertTrue(naming.charset().containsAll(List.of("A", "<PK>", "é", "a", "?", " ")));
		assertFalse(naming.charset().contains("ED"));
		// A grid with a page of no glyphs at all falls back to Gen 1's.
		NamingModel broken = new NamingModel("NAME?", 7, List.of(), "", null, List.of(List.of(List.of("ED"))));
		assertFalse(broken.engineGrid());
		assertEquals(2, broken.pageCount());
	}

	@Test
	void typingStopsAtTheLimitThenTheCursorJumpsToEd() {
		NamingModel naming = plain(7);
		for (String glyph : List.of("A", "S", "H", "<PK>", "<MN>", "♂", "Z")) {
			type(naming, glyph);
		}
		assertEquals(7, naming.typed().size(), "<PK> and <MN> count as one glyph each");
		assertEquals(NamingModel.Control.END, naming.cell().control(), "full: the cursor is on ED");
		type(naming, "B");
		assertEquals(7, naming.typed().size(), "no eighth glyph");
		naming.delete();
		naming.delete();
		assertEquals(List.of("A", "S", "H", "<PK>", "<MN>"), naming.typed());
		List<String> sent = naming.confirm();
		assertEquals(List.of("A", "S", "H", "<PK>", "<MN>"), sent);
		assertEquals(NamingModel.Phase.SENT, naming.phase());
		assertNull(naming.confirm(), "only once");
	}

	@Test
	void edConfirmsAndTheCaseCellFlips() {
		NamingModel naming = plain(10);
		type(naming, "R");
		naming.flipCase();
		type(naming, "e");
		naming.selectCell(5, 0);
		assertEquals(NamingModel.Control.CASE, naming.cell().control());
		assertNull(naming.pressCell());
		assertEquals(0, naming.page(), "back to upper case");
		naming.selectCell(4, 8);
		assertEquals(List.of("R", "e"), naming.pressCell(), "ED sends");
	}

	@Test
	void theCursorWrapsRoundRowsAndColumns() {
		NamingModel naming = plain(7);
		naming.moveCursor(-1, 0);
		assertEquals(8, naming.col());
		naming.moveCursor(1, 0);
		assertEquals(0, naming.col());
		naming.moveCursor(0, -1);
		assertEquals(5, naming.row(), "up from the top is the case row");
		assertEquals(0, naming.col());
		naming.moveCursor(0, 1);
		assertEquals(0, naming.row());
		naming.selectCell(0, 8);
		naming.moveCursor(0, 5);
		assertEquals(5, naming.row());
		assertEquals(0, naming.col(), "the one-cell case row clamps the column");
	}

	@Test
	void anEmptyNameFallsBackToTheDefaultThenTheFirstPreset() {
		NamingModel withDefault = new NamingModel("NICKNAME?", 10, List.of(), "SPARKY", 25, null);
		assertEquals(List.of("S", "P", "A", "R", "K", "Y"), withDefault.confirm());
		NamingModel presetsOnly = new NamingModel("YOUR NAME?", 7, List.of("RED", "ASH"), "", null, null);
		assertNull(presetsOnly.pressPreset(), "NEW NAME opens the grid");
		assertEquals(NamingModel.Phase.GRID, presetsOnly.phase());
		assertEquals(List.of("R", "E", "D"), presetsOnly.confirm());
		NamingModel nothing = plain(7);
		assertNull(nothing.confirm(), "no name, no default, no preset: nothing to send");
		assertEquals(NamingModel.Phase.GRID, nothing.phase());
		// A default longer than the limit is cut to it.
		NamingModel longDefault = new NamingModel("?", 7, List.of(), "ABCDEFGHIJ", null, null);
		assertEquals(7, longDefault.confirm().size());
	}

	@Test
	void presetsComeFirstAndAPresetIsAnsweredAtOnce() {
		NamingModel naming = new NamingModel("YOUR NAME?", 7, List.of("RED", "ASH", "JACK"), "", null, null);
		assertEquals(NamingModel.Phase.PRESETS, naming.phase());
		naming.moveCursor(1, 0);
		naming.delete();
		assertNull(naming.confirm(), "the grid takes nothing until NEW NAME");
		naming.movePreset(-1);
		assertEquals(3, naming.presetCursor(), "the preset menu wraps round");
		naming.movePreset(-1);
		assertEquals(List.of("A", "S", "H"), naming.pressPreset());
		assertEquals(NamingModel.Phase.SENT, naming.phase());
	}

	@Test
	void outgoingNamesAreChecked() {
		assertTrue(NamingModel.valid(List.of("A"), 7));
		assertTrue(NamingModel.valid(List.of("<PK>", "<MN>"), 7));
		assertFalse(NamingModel.valid(List.of(), 7), "at least one glyph");
		assertFalse(NamingModel.valid(List.of("A", "B", "C", "D", "E", "F", "G", "H"), 7), "at most max");
		assertFalse(NamingModel.valid(List.of("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K"), 99), "never more than 10");
		assertFalse(NamingModel.valid(List.of("AB"), 7), "one glyph per entry");
		assertFalse(NamingModel.valid(List.of("ED"), 7));
		assertEquals(7, plain(7).max());
		assertEquals(10, new NamingModel("?", 999, List.of(), "", null, null).max(), "the limit is capped at 10");
	}

	@Test
	void prefillTypesWhatTheGridCanUpToTheLimitAndWaitsOnEd() {
		NamingModel naming = new NamingModel("YOUR NAME?", 7, List.of("ASH", "GARY"), "", null, null);
		assertEquals(NamingModel.Phase.PRESETS, naming.phase());
		naming.prefill(List.of("R", "E", "<BAD>", "D"));
		assertEquals(NamingModel.Phase.GRID, naming.phase());
		assertEquals(List.of("R", "E", "D"), naming.typed(), "a token the grid can't type is skipped");
		assertTrue(naming.cell().control() != NamingModel.Control.END, "room left: the cursor stays where it was");
		naming.prefill(List.of("A", "B", "C", "D", "E", "F", "G", "H"));
		assertEquals(List.of("A", "B", "C", "D", "E", "F", "G"), naming.typed());
		assertEquals(NamingModel.Control.END, naming.cell().control());
		assertEquals(List.of("A", "B", "C", "D", "E", "F", "G"), naming.confirm());
		naming.prefill(List.of("X"));
		assertEquals(NamingModel.Phase.SENT, naming.phase(), "nothing changes once the name went out");
	}
}
