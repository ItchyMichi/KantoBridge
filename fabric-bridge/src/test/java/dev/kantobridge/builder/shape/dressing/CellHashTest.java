package dev.kantobridge.builder.shape.dressing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The dressing's position hash (docs/specs/CAVES_AND_WOODS_PLAN.md §3): the fract-of-sine mixer, evaluated in the
 * order Kanto in First Person evaluates it. The expected values come from another runtime's libm (CPython's
 * {@code math.sin}), so the same cells come out wherever the formula runs.
 */
class CellHashTest {
	@Test
	void valuesMatchAnotherRuntime() {
		assertEquals(0.7307337045858731, CellHash.hash01(0, 0, 241), 1e-12);
		assertEquals(0.12769784209376667, CellHash.hash01(3, 7, 269), 1e-12);
		assertEquals(0.3170970157134434, CellHash.hash01(7, 3, 269), 1e-12);
		assertEquals(0.4421811086431262, CellHash.hash01(-4, 12, 5), 1e-12, "negative coordinates, as in a ring");
		assertEquals(0.9218199460592587, CellHash.hash01(25, 30, 163), 1e-12);
		assertEquals(0.9206812207703479, CellHash.hash01(12, -2, 191), 1e-12);
	}

	@Test
	void argumentOrderMatters() {
		assertNotEquals(CellHash.hash01(3, 7, 269), CellHash.hash01(7, 3, 269), "the mod swaps them on purpose for some decisions");
	}

	@Test
	void oneInFiveIsAboutAFifth() {
		int n = 0;
		for (int cy = 0; cy < 100; cy++) {
			for (int cx = 0; cx < 100; cx++) {
				double h = CellHash.hash01(cx, cy, 269);
				assertTrue(h >= 0 && h < 1);
				n += CellHash.oneIn(cx, cy, 269, 5) ? 1 : 0;
			}
		}
		assertEquals(2040, n, "the same count another runtime gets");
	}
}
