package dev.kantobridge.client.ui;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * A hidden-game text box (docs/PLAN.md §2.4 flow 2): the game's own box across the bottom of the canvas
 * ({@link CanvasShape#textBox}: Gen 1's 20×6 tiles, the GBA's 30×6), two lines typed glyph by glyph, the ▼ while it
 * waits ({@link TextBoxModel}). A line too wide for the box is squeezed into it. A, B or a click is {@code ui.advance}
 * at a stop and finishes the typing otherwise. A choice that opens over the box ({@link ChoiceOverlay}) is shown and
 * answered here too; a choice with no text box under it is shown with its question in the box.
 */
public final class TextBoxScreen extends BridgeScreen {
	public TextBoxScreen(UiRuntime runtime, UiRouter.View view) {
		super(runtime, view, Component.translatable("kantobridge.ui.text"));
	}

	@Override
	protected void drawView(GuiGraphics graphics, Gen1Canvas canvas, long now) {
		UiSession main = this.view.main();
		UiSession choice = this.choice();
		TextBoxModel text = main.text();
		if (text != null) {
			drawText(graphics, this.font, canvas.shape(), text, choice == null && this.live(main, now), now);
		} else if (choice != null) {
			drawQuestion(graphics, this.font, canvas.shape(), choice.choice());
		}
		if (choice != null) {
			ChoiceOverlay.draw(graphics, this.font, canvas.shape(), choice.choice(), this.live(choice, now));
		}
	}

	@Override
	protected void onButton(Keybinds.Button button, boolean repeat, long now) {
		UiSession choice = this.choice();
		if (choice != null) {
			ChoiceOverlay.button(this.runtime.router(), choice, button, now);
			return;
		}
		if ((button == Keybinds.Button.A || button == Keybinds.Button.B) && !repeat) {
			this.runtime.router().press(this.view.main(), now);
		}
	}

	@Override
	protected void onClick(double x, double y, long now) {
		UiSession choice = this.choice();
		if (choice != null) {
			ChoiceOverlay.click(this.runtime.router(), choice, this.font, this.canvas().shape(), x, y, now);
			return;
		}
		this.runtime.router().press(this.view.main(), now);
	}

	/** The choice shown: the overlay over the text box, or the main UI itself when it is a choice. */
	private @Nullable UiSession choice() {
		if (this.view.overlay() != null) {
			return this.view.overlay();
		}
		return this.view.main().choice() != null ? this.view.main() : null;
	}

	/** The box with its visible lines; the ▼ only while the box takes input. */
	public static void drawText(GuiGraphics graphics, Font font, CanvasShape shape, TextBoxModel text, boolean active, long now) {
		shape.textBox().draw(graphics);
		drawLines(graphics, font, shape, text.visible(), text.visibleWhole());
		if (active && text.arrow()) {
			Gen1Draw.downArrow(graphics, font, shape.width() - 2 * Gen1Draw.TILE, shape.firstLineY() + Gen1Draw.ROW + 1, now);
		}
	}

	/** A lone choice's question (its last two lines) in the text box; an empty box when it has none. */
	static void drawQuestion(GuiGraphics graphics, Font font, CanvasShape shape, ChoiceModel choice) {
		shape.textBox().draw(graphics);
		if (choice.question() != null) {
			drawWrapped(graphics, font, shape, choice.question(), true);
		}
	}

	/**
	 * Text with no layout of its own (a question, a list's footer) wrapped into the text box: its first two lines, or
	 * with {@code last} its last two.
	 */
	static void drawWrapped(GuiGraphics graphics, Font font, CanvasShape shape, String markup, boolean last) {
		List<String> lines = shape.wrap(markup, s -> Gen1Draw.width(font, s));
		if (lines.size() > 2) {
			lines = last ? lines.subList(lines.size() - 2, lines.size()) : lines.subList(0, 2);
		}
		drawLines(graphics, font, shape, lines, lines);
	}

	/** Up to two lines, each squeezed by its whole line's width when that is too wide for the box. */
	private static void drawLines(GuiGraphics graphics, Font font, CanvasShape shape, List<String> shown, List<String> whole) {
		for (int i = 0; i < shown.size() && i < 2; i++) {
			float squeeze = shape.squeeze(Gen1Draw.width(font, i < whole.size() ? whole.get(i) : shown.get(i)));
			Gen1Draw.text(graphics, font, shown.get(i), CanvasShape.TEXT_X, shape.firstLineY() + i * Gen1Draw.ROW, Gen1Draw.BLACK, squeeze);
		}
	}
}
