package dev.kantobridge.client.ui;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * A hidden-game menu (docs/PLAN.md §2.4 flow 3; PROTOCOL.md {@code ui.open{kind:menu}}): a Gen 1 box of labels, one
 * every two tiles, with the ▶ cursor and an optional right column and title. The start menu stands at the right like
 * Gen 1's; other menus at the left. Up and down move the cursor here (wrapping round), A picks ({@code ui.pick}), B
 * backs out ({@code ui.back}), START closes the start menu as in Gen 1, a click picks the row under the pointer and the
 * wheel moves the cursor. {@link ListScreen} draws scrolling lists the same way.
 */
public class MenuScreen extends BridgeScreen {
	public static final String START_MENU = "StartMenu";

	/** Where a menu's rows are drawn, in canvas pixels. */
	record Layout(int x, int y, int w, int h, int firstRow, int textX, int cursorX, int rightEdge) {
		Gen1Box box() {
			return new Gen1Box(this.x, this.y, this.w, this.h);
		}

		/** The window row at canvas {@code px, py}, or -1. */
		int rowAt(double px, double py, int rows) {
			if (!this.box().contains(px, py)) {
				return -1;
			}
			int row = (int) Math.floor((py - this.firstRow + Gen1Draw.TILE / 2.0) / Gen1Draw.ROW);
			return row >= 0 && row < rows ? row : -1;
		}
	}

	public MenuScreen(UiRuntime runtime, UiRouter.View view) {
		this(runtime, view, Component.translatable("kantobridge.ui.menu"));
	}

	protected MenuScreen(UiRuntime runtime, UiRouter.View view, Component title) {
		super(runtime, view, title);
	}

	@Override
	protected void drawView(GuiGraphics graphics, Gen1Canvas canvas, long now) {
		this.draw(graphics, this.view.main().menu(), this.live(this.view.main(), now), now);
	}

	/** Draws the model in this screen's style. */
	protected void draw(GuiGraphics graphics, MenuModel menu, boolean active, long now) {
		drawMenu(graphics, this.font, menu, active, now);
	}

	/** This screen's layout of the model. */
	protected Layout layout(MenuModel menu) {
		return menuLayout(this.font, menu);
	}

	@Override
	protected void onButton(Keybinds.Button button, boolean repeat, long now) {
		UiSession session = this.view.main();
		MenuModel menu = session.menu();
		if (menu == null || !this.live(session, now)) {
			return;
		}
		switch (button) {
			case UP -> menu.move(-1);
			case DOWN -> menu.move(1);
			case LEFT -> menu.page(-1);
			case RIGHT -> menu.page(1);
			case A -> this.runtime.router().pick(session, menu.index(), now);
			case B -> this.runtime.router().back(session, now);
			case START -> {
				if (START_MENU.equals(menu.screen())) {
					this.runtime.router().back(session, now);
				}
			}
			default -> {
			}
		}
	}

	@Override
	protected void onClick(double x, double y, long now) {
		UiSession session = this.view.main();
		MenuModel menu = session.menu();
		if (menu == null || !this.live(session, now)) {
			return;
		}
		int row = this.layout(menu).rowAt(x, y, menu.window().size());
		if (row >= 0) {
			menu.select(menu.top() + row);
			this.runtime.router().pick(session, menu.index(), now);
		}
	}

	/** A menu box at the left (the start menu at the right), sized to its widest row. */
	static Layout menuLayout(Font font, MenuModel menu) {
		int rows = menu.window().size();
		int titleRows = menu.title() != null ? 1 : 0;
		int widest = menu.title() != null ? Gen1Draw.width(font, menu.title()) : 0;
		for (MenuModel.Item item : menu.items()) {
			int width = Gen1Draw.width(font, item.label()) + (item.right() != null ? Gen1Draw.width(font, item.right()) + Gen1Draw.TILE : 0);
			widest = Math.max(widest, width);
		}
		int w = Math.min(Gen1Canvas.WIDTH, tiles(Math.max(5 * Gen1Draw.TILE, widest + 3 * Gen1Draw.TILE)));
		int h = Math.min(Gen1Canvas.HEIGHT, Gen1Draw.ROW * (Math.max(1, rows) + titleRows) + Gen1Draw.ROW);
		int x = START_MENU.equals(menu.screen()) ? Gen1Canvas.WIDTH - w : 0;
		int y = 0;
		return new Layout(x, y, w, h, y + Gen1Draw.ROW * (1 + titleRows), x + 2 * Gen1Draw.TILE, x + Gen1Draw.TILE, x + w - Gen1Draw.TILE);
	}

	static void drawMenu(GuiGraphics graphics, Font font, MenuModel menu, boolean active, long now) {
		drawRows(graphics, font, menu, menuLayout(font, menu), true, active, now);
	}

	/** The box, the title (when {@code withTitle}), the window's rows with their right column, the cursor, the ▼. */
	static void drawRows(GuiGraphics graphics, Font font, MenuModel menu, Layout layout, boolean withTitle, boolean active, long now) {
		layout.box().draw(graphics);
		if (withTitle && menu.title() != null) {
			Gen1Draw.text(graphics, font, menu.title(), layout.textX() - Gen1Draw.TILE, layout.y() + Gen1Draw.TILE, Gen1Draw.BLACK);
		}
		List<MenuModel.Item> window = menu.window();
		for (int i = 0; i < window.size(); i++) {
			MenuModel.Item item = window.get(i);
			int y = layout.firstRow() + i * Gen1Draw.ROW;
			Gen1Draw.text(graphics, font, item.label(), layout.textX(), y, Gen1Draw.BLACK);
			if (item.right() != null) {
				Gen1Draw.textRight(graphics, font, item.right(), layout.rightEdge(), y, Gen1Draw.BLACK);
			}
			if (menu.top() + i == menu.index() && !menu.empty()) {
				Gen1Draw.cursor(graphics, font, layout.cursorX(), y, active);
			}
		}
		if (menu.moreBelow() && active) {
			Gen1Draw.downArrow(graphics, font, layout.rightEdge() - Gen1Draw.TILE, layout.y() + layout.h() - 11, now);
		}
	}

	static int tiles(int pixels) {
		return (pixels + Gen1Draw.TILE - 1) / Gen1Draw.TILE * Gen1Draw.TILE;
	}
}
