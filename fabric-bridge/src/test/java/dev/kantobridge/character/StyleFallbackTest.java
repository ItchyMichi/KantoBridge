package dev.kantobridge.character;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The style chain (docs/specs/VOXEL_BLOCKS_PLAN.md §5.11; report B5.5): rigged, else carved, else thick, else flat, decided
 * once per sprite, with what failed first.
 */
class StyleFallbackTest {
	@Test
	void eachStyleWhenItWorks() {
		SpriteFrames person = SpriteFixtures.same(SpriteFixtures.PERSON, 6);
		assertEquals(StyleFallback.Style.RIGGED, StyleFallback.decide(person, StyleFallback.Style.RIGGED, false).style());
		StyleFallback.Decision box = StyleFallback.decide(SpriteFixtures.same(SpriteFixtures.BOX, 6), StyleFallback.Style.CARVED, false);
		assertEquals(StyleFallback.Style.CARVED, box.style());
		assertNotNull(box.carved());
		StyleFallback.Decision thick = StyleFallback.decide(person, StyleFallback.Style.THICK, false);
		assertEquals(StyleFallback.Style.THICK, thick.style());
		assertNull(thick.reason());
		assertEquals(StyleFallback.Style.FLAT, StyleFallback.decide(person, StyleFallback.Style.FLAT, false).style());
	}

	@Test
	void eachFallbackSaysWhy() {
		// A box isn't a person: rigged falls back to carved.
		StyleFallback.Decision box = StyleFallback.decide(SpriteFixtures.same(SpriteFixtures.BOX, 6), StyleFallback.Style.RIGGED, false);
		assertEquals(StyleFallback.Style.CARVED, box.style());
		assertEquals("rig_not_humanoid", box.reason());
		// Views that disagree: carved falls back to thick.
		SpriteFrames rows = SpriteFixtures.sheet(SpriteFixtures.BOX, SpriteFixtures.BOX, SpriteFixtures.box(4, 7, 12, 12), SpriteFixtures.BOX, 6);
		StyleFallback.Decision carved = StyleFallback.decide(rows, StyleFallback.Style.CARVED, false);
		assertEquals(StyleFallback.Style.THICK, carved.style());
		assertEquals("carve_rows", carved.reason());
		// An empty frame: no card at all, the flat one.
		StyleFallback.Decision empty = StyleFallback.decide(SpriteFixtures.same(SpriteFixtures.empty(), 6), StyleFallback.Style.THICK, false);
		assertEquals(StyleFallback.Style.FLAT, empty.style());
		assertEquals("thick", empty.reason());
	}

	@Test
	void aSheetTellsItsFrameCount() {
		// A client reads its resolved sheet: the frame count comes from the masks.
		assertEquals(1, frames(SpriteFixtures.same(SpriteFixtures.BALL, 6)), "every frame the same");
		assertEquals(3, frames(SpriteFixtures.sheet(SpriteFixtures.PERSON, SpriteFixtures.BOX, SpriteFixtures.BALL, SpriteFixtures.PERSON, 6)),
				"the walking row repeats the standing one");
		assertEquals(6, frames(SpriteFixtures.sheet(SpriteFixtures.PERSON, SpriteFixtures.BOX, SpriteFixtures.BALL, SpriteFixtures.BOX, 6)));
	}

	/** The frame count {@link SpriteFrames#ofSheet} infers from a sheet built from these frames. */
	private static int frames(SpriteFrames sheet) {
		int n = sheet.size();
		boolean[] opaque = new boolean[4 * n * 2 * n];
		for (int row = 0; row < 2; row++) {
			for (int column = 0; column < 4; column++) {
				java.util.BitSet mask = sheet.mask(row, column);
				for (int i = mask.nextSetBit(0); i >= 0; i = mask.nextSetBit(i + 1)) {
					opaque[(row * n + i / n) * 4 * n + column * n + i % n] = true;
				}
			}
		}
		return SpriteFrames.ofSheet(opaque, 4 * n, 2 * n).frames();
	}

	@Test
	void theReportCountsTheSyntheticSprites() throws Exception {
		CharacterReport report = CharacterReport.of(dev.kantobridge.builder.export.ExportBundle.open(dev.kantobridge.builder.TestData.syntheticBundle()));
		assertEquals(java.util.Map.of("1", 1L, "3", 1L, "6", 2L), report.spritesByFrames(), "a ball; a mother; a girl and a guard");
		assertEquals(java.util.List.of(CharacterReport.FALLBACKS), java.util.List.copyOf(report.fallbacks().keySet()));
		long sprites = report.spritesByFrames().values().stream().mapToLong(Long::longValue).sum();
		assertEquals(4, sprites);
		for (long n : report.fallbacks().values()) {
			org.junit.jupiter.api.Assertions.assertTrue(n >= 0 && n <= sprites);
		}
	}

	@Test
	void aSingleFrameItemRevolves() {
		StyleFallback.Decision ball = StyleFallback.decide(SpriteFixtures.same(SpriteFixtures.BALL, 1), StyleFallback.Style.CARVED, true);
		assertEquals(StyleFallback.Style.CARVED, ball.style());
		StyleFallback.Decision person = StyleFallback.decide(SpriteFixtures.same(SpriteFixtures.BALL, 1), StyleFallback.Style.CARVED, false);
		assertEquals(StyleFallback.Style.THICK, person.style(), "one frame, not an item: no views to carve");
		assertEquals("carve_frames", person.reason());
	}
}
