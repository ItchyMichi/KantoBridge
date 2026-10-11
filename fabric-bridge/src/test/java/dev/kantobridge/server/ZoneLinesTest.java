package dev.kantobridge.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The trainers' lines (WR5): our own text, one text-box line each, a list for every class Viridian's zone uses. */
class ZoneLinesTest {
	@Test
	void theBundledLinesFitAndCoverTheZonesClasses() {
		ZoneLines lines = ZoneLines.bundled();
		for (String cls : List.of("OPP_YOUNGSTER", "OPP_BUG_CATCHER", "OPP_LASS", "OPP_JR_TRAINER_M", "OPP_JR_TRAINER_F")) {
			assertTrue(lines.byClass().containsKey(cls), cls);
		}
		lines.byClass().values().forEach(list -> list.forEach(line -> assertTrue(line.length() <= ZoneLines.MAX_LENGTH, line)));
		assertEquals(lines.lineFor("OPP_LASS", "a2.t1"), lines.lineFor("OPP_LASS", "a2.t1"), "a trainer always says the same");
		assertTrue(lines.byClass().get("default").contains(lines.lineFor("OPP_NOBODY", "a1.t1")), "any other class: the defaults");
	}

	@Test
	void linesThatDontFitAreRefused() {
		assertThrows(IllegalArgumentException.class, () -> new ZoneLines(Map.of("default", List.of("This line is far too long for a box"))));
		assertThrows(IllegalArgumentException.class, () -> new ZoneLines(Map.of("OPP_X", List.of("Hi!"))), "no default");
		assertThrows(IllegalArgumentException.class, () -> new ZoneLines(Map.of("default", List.of())));
	}
}
