package dev.kantobridge.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The YES/NO box over a text box (docs/PLAN.md §2.4 flow 3): Gen 1 puts it at the right, standing on the text box, one
 * label every two tiles. Up and down move the cursor round, A picks ({@code ui.pick}), B answers with
 * {@code ui.back} (gen1-bridge answers NO), a click picks the label under the pointer. Drawn and driven by
 * {@link TextBoxScreen}, which also shows a choice that has no text box under it (with its question).
 */
public final class ChoiceOverlay {
	private ChoiceOverlay() {
	}

	/** The box, standing on the text box at the right. */
	static Gen1Box bounds(Font font, ChoiceModel choice) {
		int labels = choice.labels().size();
		int widest = 0;
		for (String label : choice.labels()) {
			widest = Math.max(widest, Gen1Draw.width(font, label));
		}
		int w = Math.min(Gen1Canvas.WIDTH, tiles(Math.max(6 * Gen1Draw.TILE, widest + 3 * Gen1Draw.TILE)));
		int h = (2 * labels + 1) * Gen1Draw.TILE;
		int y = Math.max(0, TextBoxScreen.BOX_Y - h);
		return new Gen1Box(Gen1Canvas.WIDTH - w, y, w, h);
	}

	public static void draw(GuiGraphics graphics, Font font, ChoiceModel choice, boolean active) {
		Gen1Box box = bounds(font, choice);
		box.draw(graphics);
		for (int i = 0; i < choice.labels().size(); i++) {
			int y = box.y() + Gen1Draw.TILE + i * Gen1Draw.ROW;
			Gen1Draw.text(graphics, font, choice.labels().get(i), box.x() + 2 * Gen1Draw.TILE, y, Gen1Draw.BLACK);
			if (i == choice.index()) {
				Gen1Draw.cursor(graphics, font, box.x() + Gen1Draw.TILE, y, active);
			}
		}
	}

	/** A button on the choice. */
	static void button(UiRouter router, UiSession session, Keybinds.Button button, long now) {
		ChoiceModel choice = session.choice();
		if (choice == null || !router.isTop(session) || session.answered(now)) {
			return;
		}
		switch (button) {
			case UP -> choice.move(-1);
			case DOWN -> choice.move(1);
			case A -> router.pick(session, choice.index(), now);
			case B -> router.back(session, now);
			default -> {
			}
		}
	}

	/** A click: picks the label under the pointer; true when the click was on the box. */
	static boolean click(UiRouter router, UiSession session, Font font, double x, double y, long now) {
		ChoiceModel choice = session.choice();
		if (choice == null) {
			return false;
		}
		Gen1Box box = bounds(font, choice);
		if (!box.contains(x, y)) {
			return false;
		}
		int row = (int) Math.floor((y - box.y() - Gen1Draw.TILE / 2.0) / Gen1Draw.ROW);
		if (row >= 0 && row < choice.labels().size() && router.isTop(session) && !session.answered(now)) {
			choice.select(row);
			router.pick(session, row, now);
		}
		return true;
	}

	private static int tiles(int pixels) {
		return (pixels + Gen1Draw.TILE - 1) / Gen1Draw.TILE * Gen1Draw.TILE;
	}
}
