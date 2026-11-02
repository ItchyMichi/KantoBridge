package dev.kantobridge.client.ui;

/**
 * Where our screens draw the hidden game's screen in the GUI (docs/PLAN.md §4.3 "UI"): the layout is in game pixels on
 * a canvas of the game's own shape ({@link CanvasShape}: the Game Boy's 160×144, the GBA's 240×160), and every game
 * pixel covers a whole number of window pixels, so boxes, Minecraft's font and the mirror picture stay crisp at any GUI
 * scale.
 *
 * @param scale GUI units per canvas pixel
 * @param x     the canvas's left edge, in GUI units
 * @param y     the canvas's top edge, in GUI units
 * @param shape the canvas's size in its own pixels, and how its text box lays text out
 */
public record Gen1Canvas(float scale, float x, float y, CanvasShape shape) {
	/** The Game Boy's screen. */
	public static final int WIDTH = 160;
	public static final int HEIGHT = 144;

	/** The Game Boy's dialog canvas (Blue, Crystal). */
	public static Gen1Canvas dialog(int windowWidth, int windowHeight, double guiScale) {
		return dialog(windowWidth, windowHeight, guiScale, CanvasShape.GAME_BOY);
	}

	/**
	 * The dialog canvas (text boxes, menus, naming) of {@code shape}: about half the window wide, never smaller than the
	 * user's GUI scale makes text nor more than twice that, and bottom-centred, so the text box sits at the bottom of the
	 * window as in the game and the world stays in view above it.
	 */
	public static Gen1Canvas dialog(int windowWidth, int windowHeight, double guiScale, CanvasShape shape) {
		int gui = guiScale(guiScale);
		int fit = Math.min(windowWidth / (2 * shape.width()), (int) (windowHeight * 0.8 / shape.height()));
		int pixels = Math.max(gui, Math.min(fit, 2 * gui));
		pixels = Math.min(pixels, Math.max(1, Math.min(windowWidth / shape.width(), windowHeight / shape.height())));
		int left = (windowWidth - shape.width() * pixels) / 2;
		int top = Math.max(0, windowHeight - shape.height() * pixels - 2 * pixels);
		return new Gen1Canvas((float) pixels / gui, left / (float) gui, top / (float) gui, shape);
	}

	/**
	 * The mirror canvas: as large as fits with {@code reservedBottom} GUI units left free under it (the key hints), centred
	 * in the space above.
	 */
	public static Gen1Canvas mirror(int windowWidth, int windowHeight, double guiScale, int reservedBottom) {
		return mirror(windowWidth, windowHeight, guiScale, reservedBottom, WIDTH, HEIGHT);
	}

	/**
	 * The mirror canvas for a picture of {@code width}×{@code height} pixels (MG7: Gen 3's 240×160), with whole window
	 * pixels per picture pixel.
	 */
	public static Gen1Canvas mirror(int windowWidth, int windowHeight, double guiScale, int reservedBottom, int width, int height) {
		int gui = guiScale(guiScale);
		int usable = Math.max(height, windowHeight - reservedBottom * gui);
		int pixels = Math.max(1, Math.min(windowWidth / width, usable / height));
		int left = (windowWidth - width * pixels) / 2;
		int top = Math.max(0, (usable - height * pixels) / 2);
		return new Gen1Canvas((float) pixels / gui, left / (float) gui, top / (float) gui, CanvasShape.ofSize(width, height));
	}

	/** Window pixels per canvas pixel at GUI scale {@code guiScale}. */
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
