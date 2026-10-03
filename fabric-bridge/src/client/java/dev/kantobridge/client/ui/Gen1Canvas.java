package dev.kantobridge.client.ui;

/**
 * Where our screens draw Gen 1's 160×144 screen in the GUI (docs/PLAN.md §4.3 "UI"): the layout is in Gen 1 pixels,
 * and every Gen 1 pixel covers a whole number of window pixels, so boxes, Minecraft's font and the mirror picture stay
 * crisp at any GUI scale.
 *
 * @param scale GUI units per Gen 1 pixel
 * @param x     the canvas's left edge, in GUI units
 * @param y     the canvas's top edge, in GUI units
 */
public record Gen1Canvas(float scale, float x, float y) {
	public static final int WIDTH = 160;
	public static final int HEIGHT = 144;

	/**
	 * The dialog canvas (text boxes, menus, naming): about half the window wide, never smaller than the user's GUI scale
	 * makes text nor more than twice that, and bottom-centred, so the text box sits at the bottom of the window as in
	 * Gen 1 and the world stays in view above it.
	 */
	public static Gen1Canvas dialog(int windowWidth, int windowHeight, double guiScale) {
		int gui = guiScale(guiScale);
		int fit = Math.min(windowWidth / (2 * WIDTH), (int) (windowHeight * 0.8 / HEIGHT));
		int pixels = Math.max(gui, Math.min(fit, 2 * gui));
		pixels = Math.min(pixels, Math.max(1, Math.min(windowWidth / WIDTH, windowHeight / HEIGHT)));
		int left = (windowWidth - WIDTH * pixels) / 2;
		int top = Math.max(0, windowHeight - HEIGHT * pixels - 2 * pixels);
		return new Gen1Canvas((float) pixels / gui, left / (float) gui, top / (float) gui);
	}

	/**
	 * The mirror canvas: as large as fits with {@code reservedBottom} GUI units left free under it (the key hints), centred
	 * in the space above.
	 */
	public static Gen1Canvas mirror(int windowWidth, int windowHeight, double guiScale, int reservedBottom) {
		int gui = guiScale(guiScale);
		int usable = Math.max(HEIGHT, windowHeight - reservedBottom * gui);
		int pixels = Math.max(1, Math.min(windowWidth / WIDTH, usable / HEIGHT));
		int left = (windowWidth - WIDTH * pixels) / 2;
		int top = Math.max(0, (usable - HEIGHT * pixels) / 2);
		return new Gen1Canvas((float) pixels / gui, left / (float) gui, top / (float) gui);
	}

	/** Window pixels per Gen 1 pixel at GUI scale {@code guiScale}. */
	public int windowPixels(double guiScale) {
		return Math.round(this.scale * guiScale(guiScale));
	}

	/** A GUI x coordinate (a mouse position) in canvas pixels. */
	public double toCanvasX(double guiX) {
		return (guiX - this.x) / this.scale;
	}

	public double toCanvasY(double guiY) {
		return (guiY - this.y) / this.scale;
	}

	private static int guiScale(double guiScale) {
		return Math.max(1, (int) Math.round(guiScale));
	}
}
