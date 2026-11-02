package dev.kantobridge.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * A hidden-game list (docs/PLAN.md §2.4 flow 3; PROTOCOL.md {@code ui.open{kind:list}}): the bag, the Mart's items, the
 * PC's lists. Gen 1's list box (from four tiles in to the right edge: 16 tiles wide on the Game Boy's canvas, 26 on the
 * GBA's; two tiles from the top), {@code rows} rows of the items from
 * {@code top}, each with its right column (a count, a price), the ▼ when more follow, the money box at the top right
 * and the footer in the text box below. The cursor stops at the ends and left/right turn a page; everything else is
 * {@link MenuScreen}'s.
 */
public final class ListScreen extends MenuScreen {
	static final int LIST_X = 4 * Gen1Draw.TILE;

	public ListScreen(UiRuntime runtime, UiRouter.View view) {
		super(runtime, view, Component.translatable("kantobridge.ui.list"));
	}

	@Override
	protected void draw(GuiGraphics graphics, CanvasShape shape, MenuModel menu, boolean active, long now) {
		drawList(graphics, this.font, shape, menu, active, now);
	}

	@Override
	protected Layout layout(CanvasShape shape, MenuModel menu) {
		return listLayout(shape, menu);
	}

	/** The list box, from four tiles in to the canvas's right edge. */
	static Layout listLayout(CanvasShape shape, MenuModel menu) {
		int top = menu.money() != null || menu.title() != null ? 3 * Gen1Draw.TILE : 2 * Gen1Draw.TILE;
		int h = Gen1Draw.ROW * menu.rows() + 3 * Gen1Draw.TILE;
		int y = Math.max(0, Math.min(top, shape.height() - h));
		int w = shape.width() - LIST_X;
		return new Layout(LIST_X, y, w, Math.min(h, shape.height()), y + Gen1Draw.ROW, LIST_X + 2 * Gen1Draw.TILE,
				LIST_X + Gen1Draw.TILE, LIST_X + w - Gen1Draw.TILE);
	}

	static void drawList(GuiGraphics graphics, Font font, CanvasShape shape, MenuModel menu, boolean active, long now) {
		if (menu.title() != null) {
			int w = Math.min(shape.width(), tiles(Gen1Draw.width(font, menu.title()) + 2 * Gen1Draw.TILE));
			new Gen1Box(0, 0, w, 3 * Gen1Draw.TILE).draw(graphics);
			Gen1Draw.text(graphics, font, menu.title(), Gen1Draw.TILE, Gen1Draw.TILE, Gen1Draw.BLACK);
		}
		// The title has its own box here, above the list.
		drawRows(graphics, font, menu, listLayout(shape, menu), false, active, now);
		if (menu.money() != null) {
			String money = "¥" + menu.money();
			int w = tiles(Gen1Draw.width(font, money) + 2 * Gen1Draw.TILE);
			new Gen1Box(shape.width() - w, 0, w, 3 * Gen1Draw.TILE).draw(graphics);
			Gen1Draw.textRight(graphics, font, money, shape.width() - Gen1Draw.TILE, Gen1Draw.TILE, Gen1Draw.BLACK);
		}
		if (menu.footer() != null) {
			shape.textBox().draw(graphics);
			TextBoxScreen.drawWrapped(graphics, font, shape, menu.footer(), false);
		}
	}
}
