package dev.kantobridge.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.client.ui.TextBoxModel.Press;
import dev.kantobridge.client.ui.TextBoxModel.Stop;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Text paging and the typewriter: FAST typing, two lines in view, scrolling on by itself or after a press before
 * {@code cont} lines, page and end stops, one advance per stop, and following the hidden box's {@code ui.update}.
 */
class TextBoxModelTest {
	/** 12 glyphs: 200 ms at one glyph per 60 Hz frame. */
	static final String TWELVE = "Hello there!";

	static TextBoxModel box(List<List<String>> pages, List<List<Boolean>> cont) {
		return new TextBoxModel(pages, cont, false, false, false, 0);
	}

	@Test
	void typesOneGlyphPerFrameAtFastSpeed() {
		TextBoxModel text = box(List.of(List.of(TWELVE)), List.of(List.of(false)));
		assertEquals(List.of(""), text.visible());
		text.tick(100);
		assertEquals(6, text.typed());
		assertEquals(List.of("Hello "), text.visible());
		assertEquals(Stop.NONE, text.stop());
		assertFalse(text.arrow());
		text.tick(200);
		assertEquals(List.of(TWELVE), text.visible());
		assertEquals(Stop.END, text.stop());
		assertTrue(text.arrow(), "the ▼ waits at the end");
	}

	@Test
	void linesWithoutContScrollOnByThemselvesTwoInView() {
		TextBoxModel text = box(List.of(List.of("AAAA", "BBBB", "CCCC")), List.of(List.of(false, false, false)));
		text.tick(100); // 6 glyphs: line 0 done, 2 of line 1
		assertEquals(List.of("AAAA", "BB"), text.visible());
		text.tick(200); // 12: everything typed; line 2 scrolled in under line 1
		assertEquals(List.of("BBBB", "CCCC"), text.visible());
		assertEquals(Stop.END, text.stop());
	}

	@Test
	void aContLineWaitsForAPressThenScrollsIn() {
		TextBoxModel text = box(List.of(List.of("AAAA", "BBBB", "CCCC")), List.of(List.of(false, false, true)));
		text.tick(1000);
		assertEquals(Stop.SCROLL, text.stop());
		assertEquals(List.of("AAAA", "BBBB"), text.visible());
		assertTrue(text.arrow());
		assertEquals(Press.ADVANCE, text.press(1000));
		assertEquals(2, text.line());
		assertEquals(List.of("BBBB", ""), text.visible(), "line 2 scrolled in and types");
		text.tick(2000);
		assertEquals(Stop.END, text.stop());
		assertEquals(List.of("BBBB", "CCCC"), text.visible());
	}

	@Test
	void pagesStopThenStartAfresh() {
		TextBoxModel text = box(List.of(List.of("AAAA", "BBBB"), List.of("CCCC")), List.of());
		text.tick(1000);
		assertEquals(Stop.PAGE, text.stop());
		assertTrue(text.arrow());
		assertEquals(Press.ADVANCE, text.press(1000));
		assertEquals(1, text.page());
		assertEquals(List.of(""), text.visible(), "a new page starts with an empty box");
		text.tick(2000);
		assertEquals(List.of("CCCC"), text.visible());
		assertEquals(Stop.END, text.stop());
		assertEquals(2, text.pageCount());
	}

	@Test
	void aPressWhileTypingOnlyFinishesTheTextUpToTheNextStop() {
		TextBoxModel text = box(List.of(List.of("AAAA", "BBBB", "CCCC", "DDDD")), List.of(List.of(false, false, false, true)));
		text.tick(10);
		assertEquals(Press.NONE, text.press(10));
		assertEquals(Stop.SCROLL, text.stop(), "typed through the auto-scrolling lines to the cont stop");
		assertEquals(List.of("BBBB", "CCCC"), text.visible());
	}

	@Test
	void theFinalPressAsksOnceThenAgainOnlyAfterAWhile() {
		TextBoxModel text = box(List.of(List.of(TWELVE)), List.of());
		text.tick(1000);
		assertEquals(Press.ADVANCE, text.press(1000));
		assertEquals(Press.NONE, text.press(1100), "no second advance while the first is on its way");
		assertEquals(Press.ADVANCE, text.press(1000 + TextBoxModel.RESEND_FINAL_MS), "asked again once the close didn't come");
	}

	@Test
	void autoAndStayBoxesNeverAskAtTheirEnd() {
		for (boolean auto : new boolean[] {true, false}) {
			TextBoxModel text = new TextBoxModel(List.of(List.of(TWELVE)), List.of(), auto, !auto, false, 0);
			text.tick(1000);
			assertEquals(Stop.END, text.stop());
			assertFalse(text.arrow());
			assertEquals(Press.NONE, text.press(1000));
			assertEquals(Press.NONE, text.press(5000));
		}
		// Their earlier stops still take presses.
		TextBoxModel paged = new TextBoxModel(List.of(List.of("A"), List.of("B")), List.of(), true, false, false, 0);
		paged.tick(100);
		assertEquals(Stop.PAGE, paged.stop());
		assertEquals(Press.ADVANCE, paged.press(100));
	}

	@Test
	void aBoxWithAChoiceAfterItNeverAsksAtItsEnd() {
		TextBoxModel text = new TextBoxModel(List.of(List.of("Hello!"), List.of("Want it?")), List.of(), false, false, false, true, 0);
		assertTrue(text.choice());
		text.tick(1000);
		assertEquals(Stop.PAGE, text.stop());
		assertTrue(text.arrow(), "its earlier stops still wait for a press");
		assertEquals(Press.ADVANCE, text.press(1000));
		text.tick(2000);
		assertEquals(Stop.END, text.stop());
		assertFalse(text.arrow(), "no ▼ at the end: the YES/NO box comes instead");
		assertEquals(Press.NONE, text.press(2000));
		assertEquals(Press.NONE, text.press(2000 + 2 * TextBoxModel.RESEND_FINAL_MS), "never, not even later");
		assertFalse(box(List.of(List.of("Hello!")), List.of()).choice(), "without the field: no choice");
	}

	@Test
	void anInstantBoxShowsItsLastPageTyped() {
		TextBoxModel text = new TextBoxModel(List.of(List.of("AAAA", "BBBB")), List.of(), false, false, true, 0);
		assertEquals(Stop.END, text.stop());
		assertEquals(List.of("AAAA", "BBBB"), text.visible());
		// With several pages, only the last appears at once.
		TextBoxModel paged = new TextBoxModel(List.of(List.of("AAAA"), List.of("BBBB")), List.of(), false, false, true, 0);
		assertEquals(Stop.NONE, paged.stop());
		paged.tick(1000);
		paged.press(1000);
		assertEquals(Stop.END, paged.stop());
		assertEquals(List.of("BBBB"), paged.visible());
	}

	@Test
	void hiddenUpdatesMoveTheBoxForwardOnly() {
		TextBoxModel text = box(List.of(List.of("AAAA", "BBBB", "CCCC"), List.of("DDDD"), List.of("EEEE")),
				List.of(List.of(false, false, true)));
		// The hidden box is a page ahead (someone pressed A in Gen1Recomp's window): jump there.
		text.apply(1, 0, null, null, 0);
		assertEquals(1, text.page());
		assertEquals(0, text.line());
		assertEquals(List.of(""), text.visible());
		// An older position (sent before our own press arrived) changes nothing.
		text.apply(0, 2, null, null, 0);
		assertEquals(1, text.page());
		// waiting at this line: the hidden box has typed it, so it is shown typed.
		text.apply(1, 0, true, null, 0);
		assertEquals(Stop.PAGE, text.stop());
		assertEquals(List.of("DDDD"), text.visible());
		// done: everything is shown.
		text.apply(null, null, null, true, 0);
		assertEquals(2, text.page());
		assertEquals(Stop.END, text.stop());
		assertEquals(List.of("EEEE"), text.visible());
	}

	@Test
	void aHiddenLineAheadOnThePageScrollsThere() {
		TextBoxModel text = box(List.of(List.of("AAAA", "BBBB", "CCCC")), List.of(List.of(false, false, true)));
		text.tick(1000);
		assertEquals(Stop.SCROLL, text.stop());
		text.apply(0, 2, false, false, 1000);
		assertEquals(2, text.line());
		assertEquals(Stop.NONE, text.stop());
		assertEquals(List.of("BBBB", ""), text.visible());
		// Positions out of range are clamped, never thrown.
		text.apply(0, 99, null, null, 1000);
		assertEquals(2, text.line());
		text.apply(99, null, null, null, 1000);
		assertEquals(0, text.page());
	}

	@Test
	void ourPressRunsAheadAndTheHiddenEchoChangesNothing() {
		TextBoxModel text = box(List.of(List.of("AAAA"), List.of("BBBB")), List.of());
		text.tick(1000);
		assertEquals(Press.ADVANCE, text.press(1000));
		text.tick(1010);
		int typed = text.typed();
		// gen1-bridge applies our advance and reports the page we are already on.
		text.apply(1, 0, false, null, 1010);
		assertEquals(1, text.page());
		assertEquals(typed, text.typed(), "the typing goes on undisturbed");
	}

	@Test
	void emptyInputIsOneEmptyLine() {
		TextBoxModel text = box(List.of(), List.of());
		assertEquals(List.of(""), text.visible());
		assertEquals(Stop.END, text.stop());
		TextBoxModel blankPage = box(List.of(List.of()), List.of());
		assertEquals(List.of(""), blankPage.visible());
	}

	@Test
	void tokensTypeAsSingleGlyphs() {
		TextBoxModel text = box(List.of(List.of("<PK><MN>!")), List.of());
		text.tick(17); // one frame: one glyph
		assertEquals(List.of("<PK>"), text.visible());
		text.tick(34);
		assertEquals(List.of("<PK><MN>"), text.visible());
	}
}
