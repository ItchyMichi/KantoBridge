package dev.kantobridge.client.ui;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * A hidden-game text box (docs/PLAN.md §2.4 flow 2): Gen 1's box across the bottom of the canvas, two lines typed glyph
 * by glyph, the ▼ while it waits ({@link TextBoxModel}). A, B or a click is {@code ui.advance} at a stop and finishes
 * the typing otherwise. A choice that opens over the box ({@link ChoiceOverlay}) is shown and answered here too; a
 * choice with no text box under it is shown with its question in the box.
 */
public final class TextBoxScreen extends BridgeScreen {
	/** Gen 1's text box: the bottom six tiles of the screen. */
	public static final Gen1Box BOX = Gen1Box.tiles(0, 12, 20, 6);
	public static final int BOX_Y = BOX.y();
	public static final int BOX_HEIGHT = BOX.h();
	static final int TEXT_X = 8;
	static final int FIRST_LINE_Y = 112;

	public TextBoxScreen(UiRuntime runtime, UiRouter.View view) {
		super(runtime, view, Component.translatable("kantobridge.ui.text"));
	}

	@Override
	protected void drawView(GuiGraphics graphics, Gen1Canvas canvas, long now) {
		UiSession main = this.view.main();
		UiSession choice = this.choice();
		TextBoxModel text = main.text();
		if (text != null) {
			drawText(graphics, this.font, text, choice == null && this.live(main, now), now);
		} else if (choice != null) {
			drawQuestion(graphics, this.font, choice.choice());
		}
		if (choice != null) {
			ChoiceOverlay.draw(graphics, this.font, choice.choice(), this.live(choice, now));
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
			ChoiceOverlay.click(this.runtime.router(), choice, this.font, x, y, now);
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
	public static void drawText(GuiGraphics graphics, Font font, TextBoxModel text, boolean active, long now) {
		BOX.draw(graphics);
		drawLines(graphics, font, text.visible());
		if (active && text.arrow()) {
			Gen1Draw.downArrow(graphics, font, Gen1Canvas.WIDTH - 2 * Gen1Draw.TILE, FIRST_LINE_Y + Gen1Draw.ROW + 1, now);
		}
	}

	/** A lone choice's question (its last two lines) in the text box; an empty box when it has none. */
	static void drawQuestion(GuiGraphics graphics, Font font, ChoiceModel choice) {
		BOX.draw(graphics);
		if (choice.question() != null) {
			List<String> lines = Gen1Text.wrap(choice.question(), Gen1Text.LINE_GLYPHS);
			drawLines(graphics, font, lines.size() <= 2 ? lines : lines.subList(lines.size() - 2, lines.size()));
		}
	}

	private static void drawLines(GuiGraphics graphics, Font font, List<String> lines) {
		for (int i = 0; i < lines.size() && i < 2; i++) {
			Gen1Draw.text(graphics, font, lines.get(i), TEXT_X, FIRST_LINE_Y + i * Gen1Draw.ROW, Gen1Draw.BLACK);
		}
	}
}
