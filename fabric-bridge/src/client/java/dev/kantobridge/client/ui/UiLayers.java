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

	static void draw(GuiGraphics graphics, Font font, UiSession session, long now) {
		switch (session.kind()) {
			case TEXT -> TextBoxScreen.drawText(graphics, font, session.text(), false, now);
			case CHOICE -> ChoiceOverlay.draw(graphics, font, session.choice(), false);
			case MENU -> MenuScreen.drawMenu(graphics, font, session.menu(), false, now);
			case LIST -> ListScreen.drawList(graphics, font, session.menu(), false, now);
			case QUANTITY -> QuantityScreen.drawQuantity(graphics, font, session.quantity());
			case NAMING -> NamingScreen.drawNaming(graphics, font, session.naming(), false, now);
			case MIRROR -> MirrorScreen.Picture.drawUnder(graphics, session.id());
		}
	}
}
