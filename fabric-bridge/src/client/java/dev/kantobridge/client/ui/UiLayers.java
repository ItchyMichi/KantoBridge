package dev.kantobridge.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws a UI that lies under the shown one, inactive (hollow cursor, no ▼), as Gen 1 leaves the windows under a submenu
 * on screen: the bag under its USE/TOSS menu, the Mart list under its quantity box, the party picture under a text box
 * (its last frame; the picture only stays live while its own screen is shown).
 */
final class UiLayers {
	private UiLayers() {
	}

	static void draw(GuiGraphics graphics, Font font, CanvasShape shape, UiSession session, long now) {
		switch (session.kind()) {
			case TEXT -> TextBoxScreen.drawText(graphics, font, shape, session.text(), false, now);
			case CHOICE -> ChoiceOverlay.draw(graphics, font, shape, session.choice(), false);
			case MENU -> MenuScreen.drawMenu(graphics, font, shape, session.menu(), false, now);
			case LIST -> ListScreen.drawList(graphics, font, shape, session.menu(), false, now);
			case QUANTITY -> QuantityScreen.drawQuantity(graphics, font, shape, session.quantity());
			case NAMING -> NamingScreen.drawNaming(graphics, font, shape, session.naming(), false, now);
			case MIRROR -> MirrorScreen.Picture.drawUnder(graphics, shape, session.id());
		}
	}
}
