package dev.kantobridge.client.ui;

import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * A how-many box (docs/PLAN.md §2.4 flow 3; PROTOCOL.md {@code ui.open{kind:quantity}}): Gen 1's small box at the right
 * above the text box, {@code ×NN} and, with a unit price, the total in ¥. Up and down step through 1..max and wrap
 * ({@link QuantityModel}), left and right by ten, the wheel steps too; A confirms ({@code ui.qty}), B cancels
 * ({@code ui.back}); a stray click does nothing (it could buy). The list it counts for stays drawn beneath.
 */
public final class QuantityScreen extends BridgeScreen {
	static final int BOX_Y = 9 * Gen1Draw.TILE;
	static final int BOX_H = 3 * Gen1Draw.TILE;

	public QuantityScreen(UiRuntime runtime, UiRouter.View view) {
		super(runtime, view, Component.translatable("kantobridge.ui.quantity"));
	}

	@Override
	protected void drawView(GuiGraphics graphics, Gen1Canvas canvas, long now) {
		drawQuantity(graphics, this.font, this.view.main().quantity());
	}

	@Override
	protected void onButton(Keybinds.Button button, boolean repeat, long now) {
		UiSession session = this.view.main();
		QuantityModel quantity = session.quantity();
		if (quantity == null || !this.live(session, now)) {
			return;
		}
		switch (button) {
			case UP -> quantity.step(1);
			case DOWN -> quantity.step(-1);
			case RIGHT -> quantity.step(10);
			case LEFT -> quantity.step(-10);
			case A -> this.runtime.router().quantity(session, now);
			case B -> this.runtime.router().back(session, now);
			default -> {
			}
		}
	}

	static void drawQuantity(GuiGraphics graphics, Font font, QuantityModel quantity) {
		String count = "×" + String.format(Locale.ROOT, quantity.max() >= 100 ? "%03d" : "%02d", quantity.qty());
		Long total = quantity.total();
		String price = total == null ? null : "¥" + total;
		int w = 5 * Gen1Draw.TILE;
		if (price != null) {
			w = MenuScreen.tiles(Math.max(13 * Gen1Draw.TILE, Gen1Draw.width(font, count) + Gen1Draw.width(font, price) + 4 * Gen1Draw.TILE));
		}
		w = Math.min(Gen1Canvas.WIDTH, w);
		int x = Gen1Canvas.WIDTH - w;
		new Gen1Box(x, BOX_Y, w, BOX_H).draw(graphics);
		Gen1Draw.text(graphics, font, count, x + Gen1Draw.TILE, BOX_Y + Gen1Draw.TILE, Gen1Draw.BLACK);
		if (price != null) {
			Gen1Draw.textRight(graphics, font, price, Gen1Canvas.WIDTH - Gen1Draw.TILE, BOX_Y + Gen1Draw.TILE, Gen1Draw.BLACK);
		}
	}
}
