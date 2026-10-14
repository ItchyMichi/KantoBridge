package dev.kantobridge.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The Gen 1 canvas: whole window pixels per Gen 1 pixel, inside the window, the dialog at the bottom. */
class Gen1CanvasTest {
	static void checkCrisp(Gen1Canvas canvas, int windowWidth, int windowHeight, int gui) {
		float windowPixels = canvas.scale() * gui;
		assertEquals(Math.round(windowPixels), windowPixels, 1e-4, "a whole number of window pixels per Gen 1 pixel");
		float left = canvas.x() * gui;
		float top = canvas.y() * gui;
		assertEquals(Math.round(left), left, 1e-3, "the left edge on a window pixel");
		assertEquals(Math.round(top), top, 1e-3, "the top edge on a window pixel");
		assertTrue(left >= 0 && left + Gen1Canvas.WIDTH * windowPixels <= windowWidth, "inside the window horizontally");
		assertTrue(top >= 0 && top + Gen1Canvas.HEIGHT * windowPixels <= windowHeight, "inside the window vertically");
	}

	@Test
	void theDialogIsAboutHalfTheWindowWideAndBottomCentred() {
		Gen1Canvas canvas = Gen1Canvas.dialog(1920, 1080, 4);
		assertEquals(6, canvas.windowPixels(4), "1920 / 2 / 160");
		checkCrisp(canvas, 1920, 1080, 4);
		assertEquals(120.0F, canvas.x(), 1e-4, "centred: (1920 - 960) / 2 window pixels");
		assertEquals((1080 - 864 - 12) / 4.0F, canvas.y(), 1e-4, "two Gen 1 pixels above the bottom");
	}

	@Test
	void theDialogFollowsTheGuiScaleWithinLimits() {
		// A small GUI scale keeps text at most twice its size.
		assertEquals(4, Gen1Canvas.dialog(1920, 1080, 2).windowPixels(2));
		// Never smaller than the GUI scale makes text, while it fits.
		assertTrue(Gen1Canvas.dialog(1280, 720, 3).windowPixels(3) >= 3);
		for (int[] size : new int[][] {{854, 480}, {1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}, {640, 360}}) {
			for (int gui = 1; gui <= 4; gui++) {
				checkCrisp(Gen1Canvas.dialog(size[0], size[1], gui), size[0], size[1], gui);
			}
		}
	}

	@Test
	void theMirrorIsAsLargeAsFitsAboveTheHints() {
		Gen1Canvas canvas = Gen1Canvas.mirror(1920, 1080, 4, 14);
		assertEquals(7, canvas.windowPixels(4), "(1080 - 56) / 144");
		checkCrisp(canvas, 1920, 1080, 4);
		for (int[] size : new int[][] {{854, 480}, {1280, 720}, {1920, 1080}, {2560, 1440}}) {
			for (int gui = 1; gui <= 4; gui++) {
				checkCrisp(Gen1Canvas.mirror(size[0], size[1], gui, 14), size[0], size[1], gui);
			}
		}
	}

	@Test
	void gen3sMirrorKeepsWholePixelsFor240By160() {
		Gen1Canvas canvas = Gen1Canvas.mirror(1920, 1080, 4, 14, 240, 160);
		assertEquals(6, canvas.windowPixels(4), "(1080 - 56) / 160");
		assertEquals((1920 - 240 * 6) / 2 / 4.0F, canvas.x(), 1e-4, "centred");
		for (int[] size : new int[][] {{854, 480}, {1280, 720}, {1920, 1080}, {2560, 1440}}) {
			for (int gui = 1; gui <= 4; gui++) {
				Gen1Canvas c = Gen1Canvas.mirror(size[0], size[1], gui, 14, 240, 160);
				float px = c.scale() * gui;
				assertEquals(Math.round(px), px, 1e-4);
				assertTrue(c.x() * gui >= 0 && c.x() * gui + 240 * px <= size[0], "inside the window");
			}
		}
	}

	@Test
	void boxesSitOnTheTileGrid() {
		Gen1Box textBox = Gen1Box.tiles(0, 12, 20, 6);
		assertEquals(new Gen1Box(0, 96, 160, 48), textBox, "Gen 1's text box: the bottom six tiles");
		assertTrue(textBox.contains(0, 96) && textBox.contains(159.9, 143.9));
		assertTrue(!textBox.contains(160, 100) && !textBox.contains(10, 95.9));
	}

	@Test
	void mouseCoordinatesMapToCanvasPixels() {
		Gen1Canvas canvas = Gen1Canvas.dialog(1920, 1080, 4);
		assertEquals(0.0, canvas.toCanvasX(canvas.x()), 1e-6);
		assertEquals(160.0, canvas.toCanvasX(canvas.x() + 160 * canvas.scale()), 1e-4);
		assertEquals(144.0, canvas.toCanvasY(canvas.y() + 144 * canvas.scale()), 1e-4);
	}
}
