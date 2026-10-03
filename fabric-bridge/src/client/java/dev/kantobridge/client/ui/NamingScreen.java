package dev.kantobridge.client.ui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

/**
 * The naming screen (docs/PLAN.md §2.4 flow 4; PROTOCOL.md {@code ui.open{kind:naming}}), Gen 1's: first the NEW NAME /
 * presets menu when there are presets, then a full-screen box with the title, the name so far over {@code max}
 * underscores, the letter grid with ED and the case switch, and the ▶ cursor ({@link NamingModel}). A types, B deletes,
 * SELECT flips the case, START or ED sends {@code ui.name}; a click types the cell under the pointer.
 */
public final class NamingScreen extends BridgeScreen {
	static final int NAME_Y = 3 * Gen1Draw.TILE;
	static final int GRID_TOP = 5 * Gen1Draw.TILE;
	static final int GRID_BOTTOM = 17 * Gen1Draw.TILE;
	static final int GRID_LEFT = 2 * Gen1Draw.TILE;

	public NamingScreen(UiRuntime runtime, UiRouter.View view) {
		super(runtime, view, Component.translatable("kantobridge.ui.naming"));
	}

	@Override
	protected void drawView(GuiGraphics graphics, Gen1Canvas canvas, long now) {
		drawNaming(graphics, this.font, this.view.main().naming(), this.live(this.view.main(), now), now);
	}

	@Override
	protected void onButton(Keybinds.Button button, boolean repeat, long now) {
		UiSession session = this.view.main();
		NamingModel naming = session.naming();
		if (naming == null || !this.live(session, now)) {
			return;
		}
		List<String> send = null;
		if (naming.phase() == NamingModel.Phase.PRESETS) {
			switch (button) {
				case UP -> naming.movePreset(-1);
				case DOWN -> naming.movePreset(1);
				case A -> send = naming.pressPreset();
				default -> {
				}
			}
		} else {
			switch (button) {
				case UP -> naming.moveCursor(0, -1);
				case DOWN -> naming.moveCursor(0, 1);
				case LEFT -> naming.moveCursor(-1, 0);
				case RIGHT -> naming.moveCursor(1, 0);
				case A -> send = naming.pressCell();
				case B -> naming.delete();
				case SELECT -> naming.flipCase();
				case START -> send = naming.confirm();
				default -> {
				}
			}
		}
		if (send != null) {
			this.runtime.router().name(session, send, now);
		}
	}

	@Override
	protected void onClick(double x, double y, long now) {
		UiSession session = this.view.main();
		NamingModel naming = session.naming();
		if (naming == null || !this.live(session, now)) {
			return;
		}
		List<String> send = null;
		if (naming.phase() == NamingModel.Phase.PRESETS) {
			MenuScreen.Layout layout = MenuScreen.menuLayout(this.font, presetMenu(naming));
			int row = layout.rowAt(x, y, naming.presets().size() + 1);
			if (row >= 0) {
				naming.selectPreset(row);
				send = naming.pressPreset();
			}
		} else {
			Grid grid = Grid.of(naming);
			int row = (int) Math.floor((y - GRID_TOP + Gen1Draw.TILE / 2.0) / grid.rowStep());
			int col = (int) Math.floor((x - GRID_LEFT + grid.colStep() / 2.0) / grid.colStep());
			if (row >= 0 && row < naming.rows().size()) {
				if (naming.rows().get(row).size() == 1) {
					// A row of one control (ED on its own, the case switch) takes the whole width.
					col = 0;
				}
				if (col >= 0 && col < naming.rows().get(row).size()) {
					naming.selectCell(row, col);
					send = naming.pressCell();
				}
			}
		}
		if (send != null) {
			this.runtime.router().name(session, send, now);
		}
	}

	/** The grid's spacing: two tiles a cell as in Gen 1, tighter for an engine grid too big for that. */
	record Grid(int colStep, int rowStep) {
		static Grid of(NamingModel naming) {
			int columns = 1;
			for (List<NamingModel.Cell> row : naming.rows()) {
				columns = Math.max(columns, row.size());
			}
			int colStep = Math.max(6, Math.min(Gen1Draw.ROW, (Gen1Canvas.WIDTH - 2 * GRID_LEFT) / Math.max(1, columns - 1)));
			int rowStep = Math.max(9, Math.min(Gen1Draw.ROW, (GRID_BOTTOM - GRID_TOP) / Math.max(1, naming.rows().size())));
			return new Grid(colStep, rowStep);
		}
	}

	static void drawNaming(GuiGraphics graphics, Font font, NamingModel naming, boolean active, long now) {
		if (naming.phase() == NamingModel.Phase.PRESETS) {
			MenuModel menu = presetMenu(naming);
			MenuScreen.drawMenu(graphics, font, menu, active, now);
			return;
		}
		new Gen1Box(0, 0, Gen1Canvas.WIDTH, Gen1Canvas.HEIGHT).draw(graphics);
		Gen1Draw.text(graphics, font, naming.title(), Gen1Draw.TILE, Gen1Draw.TILE, Gen1Draw.BLACK);
		// The name: one glyph per 8-pixel slot over underscores, centred.
		List<String> typed = naming.typed();
		int left = (Gen1Canvas.WIDTH - naming.max() * Gen1Draw.TILE) / 2;
		for (int i = 0; i < naming.max(); i++) {
			int x = left + i * Gen1Draw.TILE;
			if (i < typed.size()) {
				Gen1Draw.text(graphics, font, typed.get(i), x, NAME_Y, Gen1Draw.BLACK);
			}
			graphics.fill(x, NAME_Y + 9, x + Gen1Draw.TILE - 2, NAME_Y + 10, i == typed.size() ? Gen1Draw.BLACK : Gen1Draw.GREY);
		}
		Grid grid = Grid.of(naming);
		List<List<NamingModel.Cell>> rows = naming.rows();
		for (int r = 0; r < rows.size(); r++) {
			int y = GRID_TOP + r * grid.rowStep();
			List<NamingModel.Cell> cells = rows.get(r);
			for (int c = 0; c < cells.size(); c++) {
				NamingModel.Cell cell = cells.get(c);
				int x = GRID_LEFT + c * grid.colStep();
				String label = switch (cell.control()) {
					case END -> I18n.get("kantobridge.ui.naming.end");
					case CASE -> I18n.get(naming.pageCount() > 2 ? "kantobridge.ui.naming.next" : naming.page() == 0
							? "kantobridge.ui.naming.lower" : "kantobridge.ui.naming.upper");
					default -> cell.glyph();
				};
				Gen1Draw.text(graphics, font, label, x, y, Gen1Draw.BLACK);
				if (r == naming.row() && c == naming.col()) {
					Gen1Draw.cursor(graphics, font, x - Gen1Draw.TILE, y, active);
				}
			}
		}
	}

	/** The preset menu: NEW NAME, then each preset, as a menu at the left. */
	static MenuModel presetMenu(NamingModel naming) {
		List<MenuModel.Item> items = new ArrayList<>();
		items.add(new MenuModel.Item(I18n.get("kantobridge.ui.naming.new_name"), null));
		for (String preset : naming.presets()) {
			items.add(new MenuModel.Item(preset, null));
		}
		return new MenuModel(false, items, naming.presetCursor(), 0, items.size(), null, null, null, null);
	}
}
