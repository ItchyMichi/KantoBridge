package dev.kantobridge.builder.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The follow-up's item 8 (docs/SEVERAL_GAMES_FOLLOWUPS.md): a Gen 3 export's animated quarters as strips with their loop
 * in ticks, and its object-event sheets in the generated layout. Made-up pixels.
 */
class BakedTexturesTest {
	private static final int MARK = 0xFFFF00FF;

	private static int[] filled(int argb) {
		int[] out = new int[64];
		java.util.Arrays.fill(out, argb);
		return out;
	}

	@Test
	void aStripIsItsFramesOneBelowTheOther() {
		Png.Image strip = BakedTextures.strip(List.of(filled(0xFFFF0000), filled(0xFF0000FF)));
		assertEquals(16, strip.width());
		assertEquals(32, strip.height());
		assertEquals(0xFFFF0000, strip.get(15, 15));
		assertEquals(0xFF0000FF, strip.get(0, 16));
	}

	@Test
	void aLoopKeepsItsLengthInTicks() {
		// 24 GBA frames: a whole 8 ticks, one loop
		assertEquals("{\"animation\":{\"frames\":[{\"index\":0,\"time\":4},{\"index\":1,\"time\":4}]}}",
				BakedTextures.loopMeta(new int[][] {{0, 12}, {1, 12}}));
		// 32 GBA frames are no whole number of ticks: three loops in 32 ticks, each step ending on the tick nearest its time
		assertEquals("{\"animation\":{\"frames\":[{\"index\":0,\"time\":5},{\"index\":1,\"time\":6},{\"index\":0,\"time\":5},"
				+ "{\"index\":1,\"time\":5},{\"index\":0,\"time\":6},{\"index\":1,\"time\":5}]}}",
				BakedTextures.loopMeta(new int[][] {{0, 16}, {1, 16}}));
		// a step too short for a tick of its own (30 changes a second) is left out; the loop keeps its 2 ticks
		assertEquals("{\"animation\":{\"frames\":[{\"index\":0,\"time\":1},{\"index\":2,\"time\":1}]}}",
				BakedTextures.loopMeta(new int[][] {{0, 2}, {1, 2}, {2, 2}}));
	}

	/** A strip of {@code frames} frames of w x h: frame f all (f + 1) blue, its left column marked. */
	private static Png.Image strip(int w, int h, int frames) {
		Png.Image out = new Png.Image(w, h * frames);
		for (int f = 0; f < frames; f++) {
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					out.set(x, f * h + y, x == 0 ? MARK : 0xFF000000 | (f + 1));
				}
			}
		}
		return out;
	}

	@Test
	void aWalkerStandsOnFramesZeroToTwoAndWalksOnThreeFiveSeven() {
		Png.Image sheet = BakedTextures.sheet(strip(16, 32, 9), 16, 32, 9);
		assertEquals(128, sheet.width());
		assertEquals(64, sheet.height());
		// standing: down, up, left in 32-pixel cells, each frame at the cell's bottom middle (x 8..23)
		assertEquals(0, sheet.get(7, 0), "clear around the frame");
		assertEquals(MARK, sheet.get(8, 0));
		assertEquals(0xFF000001, sheet.get(9, 0));
		assertEquals(0xFF000002, sheet.get(32 + 9, 0));
		assertEquals(0xFF000003, sheet.get(64 + 9, 0));
		// right: left mirrored, the mark on the frame's right edge
		assertEquals(MARK, sheet.get(96 + 8 + 15, 0));
		assertEquals(0xFF000003, sheet.get(96 + 8, 0));
		// walking: frames 3, 5, 7 (one step a facing)
		assertEquals(0xFF000004, sheet.get(9, 32));
		assertEquals(0xFF000006, sheet.get(32 + 9, 32));
		assertEquals(0xFF000008, sheet.get(64 + 9, 32));
		assertEquals(0xFF000008, sheet.get(96 + 9, 32));
	}

	@Test
	void anObjectOfOneLookKeepsItEverywhereUnmirrored() {
		Png.Image sheet = BakedTextures.sheet(strip(16, 16, 4), 16, 16, 4);
		assertEquals(128, sheet.width());
		for (int column = 0; column < 4; column++) {
			for (int row = 0; row < 2; row++) {
				assertEquals(0, sheet.get(column * 32 + 9, row * 32 + 15), "above the frame: clear");
				assertEquals(MARK, sheet.get(column * 32 + 8, row * 32 + 16), "frame 0 at the bottom, never mirrored");
				assertEquals(0xFF000001, sheet.get(column * 32 + 9, row * 32 + 31));
			}
		}
	}

	@Test
	void aSheetOfThreeFacesButNeverWalksAndBigFramesTakeBigCells() {
		Png.Image blob = BakedTextures.sheet(strip(32, 32, 3), 32, 32, 3);
		assertEquals(128, blob.width());
		for (int x = 0; x < 128; x++) {
			assertEquals(blob.get(x, 5), blob.get(x, 32 + 5), "the walking row is the standing one");
		}
		assertEquals(0xFF000002, blob.get(32 + 5, 5));
		Png.Image big = BakedTextures.sheet(strip(64, 48, 1), 64, 48, 1);
		assertEquals(256, big.width());
		assertEquals(128, big.height());
		assertEquals(0, big.get(5, 15), "a 64 x 48 frame at the bottom of its 64-pixel cell");
		assertEquals(MARK, big.get(0, 16));
		assertNull(BakedTextures.sheet(strip(16, 32, 2), 16, 32, 9), "a strip shorter than its frames");
	}
}
