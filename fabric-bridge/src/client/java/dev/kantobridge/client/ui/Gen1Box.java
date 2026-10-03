package dev.kantobridge.client.ui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Gen 1's window (docs/PLAN.md §4.3 "UI"): a white box with a black frame and rounded corners, laid out in Gen 1 pixels
 * on the 8-pixel tile grid (the text box is 20×6 tiles, the YES/NO box 6×5). Our screens draw every window with it and
 * test clicks against it.
 *
 * @param x the left edge, in canvas pixels
 * @param y the top edge
 * @param w the width
 * @param h the height
 */
public record Gen1Box(int x, int y, int w, int h) {
	/** A box of whole tiles. */
	public static Gen1Box tiles(int tileX, int tileY, int tilesWide, int tilesHigh) {
		return new Gen1Box(tileX * Gen1Draw.TILE, tileY * Gen1Draw.TILE, tilesWide * Gen1Draw.TILE, tilesHigh * Gen1Draw.TILE);
	}

	/** Draws it: white, with a 2-pixel black frame one pixel in from the edge, the frame's corner pixels left white. */
	public void draw(GuiGraphics graphics) {
		int right = this.x + this.w;
		int bottom = this.y + this.h;
		graphics.fill(this.x, this.y, right, bottom, Gen1Draw.WHITE);
		graphics.fill(this.x + 2, this.y + 1, right - 2, this.y + 3, Gen1Draw.BLACK);
		graphics.fill(this.x + 2, bottom - 3, right - 2, bottom - 1, Gen1Draw.BLACK);
		graphics.fill(this.x + 1, this.y + 2, this.x + 3, bottom - 2, Gen1Draw.BLACK);
		graphics.fill(right - 3, this.y + 2, right - 1, bottom - 2, Gen1Draw.BLACK);
	}

	/** True when canvas point {@code px, py} lies inside. */
	public boolean contains(double px, double py) {
		return px >= this.x && px < this.x + this.w && py >= this.y && py < this.y + this.h;
	}
}
