package dev.kantobridge.client.ui;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * A hidden-game list (docs/PLAN.md §2.4 flow 3; PROTOCOL.md {@code ui.open{kind:list}}): the bag, the Mart's items, the
 * PC's lists. Gen 1's list box (16 tiles wide, two tiles from the top), {@code rows} rows of the items from
 * {@code top}, each with its right column (a count, a price), the ▼ when more follow, the money box at the top right
 * and the footer in the text box below. The cursor stops at the ends and left/right turn a page; everything else is
 * {@link MenuScreen}'s.
 */
public final class ListScreen extends MenuScreen {
	static final int LIST_X = 4 * Gen1Draw.TILE;
	static final int LIST_W = Gen1Canvas.WIDTH - LIST_X;

	public ListScreen(UiRuntime runtime, UiRouter.View view) {
		super(runtime, view, Component.translatable("kantobridge.ui.list"));
	}

	@Override
	protected void draw(GuiGraphics graphics, MenuModel menu, boolean active, long now) {
		drawList(graphics, this.font, menu, active, now);
	}

	@Override
	protected Layout layout(MenuModel menu) {
		return listLayout(menu);
	}

	static Layout listLayout(MenuModel menu) {
		int top = menu.money() != null || menu.title() != null ? 3 * Gen1Draw.TILE : 2 * Gen1Draw.TILE;
		int h = Gen1Draw.ROW * menu.rows() + 3 * Gen1Draw.TILE;
		int y = Math.max(0, Math.min(top, Gen1Canvas.HEIGHT - h));
		return new Layout(LIST_X, y, LIST_W, Math.min(h, Gen1Canvas.HEIGHT), y + Gen1Draw.ROW, LIST_X + 2 * Gen1Draw.TILE,
				LIST_X + Gen1Draw.TILE, LIST_X + LIST_W - Gen1Draw.TILE);
	}

	static void drawList(GuiGraphics graphics, Font font, MenuModel menu, boolean active, long now) {
		if (menu.title() != null) {
			int w = Math.min(Gen1Canvas.WIDTH, tiles(Gen1Draw.width(font, menu.title()) + 2 * Gen1Draw.TILE));
			new Gen1Box(0, 0, w, 3 * Gen1Draw.TILE).draw(graphics);
			Gen1Draw.text(graphics, font, menu.title(), Gen1Draw.TILE, Gen1Draw.TILE, Gen1Draw.BLACK);
		}
		// The title has its own box here, above the list.
		drawRows(graphics, font, menu, listLayout(menu), false, active, now);
		if (menu.money() != null) {
			String money = "¥" + menu.money();
			int w = tiles(Gen1Draw.width(font, money) + 2 * Gen1Draw.TILE);
			new Gen1Box(Gen1Canvas.WIDTH - w, 0, w, 3 * Gen1Draw.TILE).draw(graphics);
			Gen1Draw.textRight(graphics, font, money, Gen1Canvas.WIDTH - Gen1Draw.TILE, Gen1Draw.TILE, Gen1Draw.BLACK);
		}
		if (menu.footer() != null) {
			TextBoxScreen.BOX.draw(graphics);
			List<String> lines = Gen1Text.wrap(menu.footer(), Gen1Text.LINE_GLYPHS);
			for (int i = 0; i < lines.size() && i < 2; i++) {
				Gen1Draw.text(graphics, font, lines.get(i), TextBoxScreen.TEXT_X, TextBoxScreen.FIRST_LINE_Y + i * Gen1Draw.ROW, Gen1Draw.BLACK);
			}
		}
	}
}
