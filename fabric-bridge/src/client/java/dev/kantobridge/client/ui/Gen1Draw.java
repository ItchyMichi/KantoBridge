package dev.kantobridge.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Drawing in Gen 1 pixels on a {@link Gen1Canvas} (docs/PLAN.md §4.3 "UI"): text in the protocol's markup with
 * Minecraft's font ({@code <PK>}/{@code <MN>} as small capitals in one glyph's room), the cursor and arrow glyphs, and the
 * colours and tile grid that {@link Gen1Box} windows use.
 */
public final class Gen1Draw {
	public static final int WHITE = 0xFFFFFFFF;
	public static final int BLACK = 0xFF000000;
	public static final int GREY = 0xFF8C8C8C;
	/** A Gen 1 tile: boxes are laid out on this grid. */
	public static final int TILE = 8;
	/** Text rows are two tiles apart, as in Gen 1. */
	public static final int ROW = 16;
	public static final String CURSOR = "▶";
	public static final String HOLLOW_CURSOR = "▷";
	public static final String ARROW_DOWN = "▼";
	private static final float SMALL = 0.6F;

	private Gen1Draw() {
	}

	/** Puts the pose on the canvas: everything drawn until {@link #end} is in Gen 1 pixels. */
	public static void begin(GuiGraphics graphics, Gen1Canvas canvas) {
		graphics.pose().pushPose();
		graphics.pose().translate(canvas.x(), canvas.y(), 0.0F);
		graphics.pose().scale(canvas.scale(), canvas.scale(), 1.0F);
	}

	public static void end(GuiGraphics graphics) {
		graphics.pose().popPose();
	}

	/** Draws markup text at {@code x, y} (the top of the glyphs); returns where it ends. */
	public static int text(GuiGraphics graphics, Font font, String markup, int x, int y, int colour) {
		StringBuilder plain = new StringBuilder();
		float cursor = x;
		for (String glyph : Gen1Text.glyphs(markup)) {
			if (Gen1Text.isSmallCaps(glyph)) {
				cursor = flush(graphics, font, plain, cursor, y, colour);
				cursor += smallCaps(graphics, font, glyph.equals(Gen1Text.PK) ? "PK" : "MN", cursor, y, colour);
			} else {
				// Minecraft reads § as a formatting code; Gen 1 has no such glyph, so it can only be noise.
				plain.append(glyph.equals("§") ? "?" : glyph);
			}
		}
		cursor = flush(graphics, font, plain, cursor, y, colour);
		return Math.round(cursor);
	}

	/** Markup text right-aligned so it ends at {@code right}. */
	public static void textRight(GuiGraphics graphics, Font font, String markup, int right, int y, int colour) {
		text(graphics, font, markup, right - width(font, markup), y, colour);
	}

	/** The width of markup text in Gen 1 pixels. */
	public static int width(Font font, String markup) {
		float width = 0;
		StringBuilder plain = new StringBuilder();
		for (String glyph : Gen1Text.glyphs(markup)) {
			if (Gen1Text.isSmallCaps(glyph)) {
				width += font.width(plain.toString());
				plain.setLength(0);
				width += smallCapsWidth(font, glyph.equals(Gen1Text.PK) ? "PK" : "MN");
			} else {
				plain.append(glyph);
			}
		}
		width += font.width(plain.toString());
		return Math.round(width);
	}

	/** The menu cursor ▶ (▷ when its menu is not the one taking input). */
	public static void cursor(GuiGraphics graphics, Font font, int x, int y, boolean active) {
		graphics.drawString(font, active ? CURSOR : HOLLOW_CURSOR, x, y, BLACK, false);
	}

	/** The ▼ that waits for a press, blinking like Gen 1's. */
	public static void downArrow(GuiGraphics graphics, Font font, int x, int y, long now) {
		if ((now / 400) % 2 == 0) {
			graphics.drawString(font, ARROW_DOWN, x, y, BLACK, false);
		}
	}

	private static float flush(GuiGraphics graphics, Font font, StringBuilder plain, float x, int y, int colour) {
		if (plain.isEmpty()) {
			return x;
		}
		String run = plain.toString();
		graphics.pose().pushPose();
		graphics.pose().translate(x, y, 0.0F);
		graphics.drawString(font, run, 0, 0, colour, false);
		graphics.pose().popPose();
		plain.setLength(0);
		return x + font.width(run);
	}

	private static float smallCaps(GuiGraphics graphics, Font font, String letters, float x, int y, int colour) {
		graphics.pose().pushPose();
		// Bottom-aligned with the capitals around it (7 pixels tall).
		graphics.pose().translate(x, y + 7.0F * (1.0F - SMALL), 0.0F);
		graphics.pose().scale(SMALL, SMALL, 1.0F);
		graphics.drawString(font, letters, 0, 0, colour, false);
		graphics.pose().popPose();
		return smallCapsWidth(font, letters);
	}

	private static float smallCapsWidth(Font font, String letters) {
		return font.width(letters) * SMALL + 1.0F;
	}
}
