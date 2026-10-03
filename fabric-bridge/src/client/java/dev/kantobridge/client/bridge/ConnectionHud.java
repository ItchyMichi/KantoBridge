package dev.kantobridge.client.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;

/**
 * The connection status in the top-left corner, in game and on the title screen: small, and hidden once connected
 * (after a few seconds), with F1, and while the F3 screen is open.
 */
final class ConnectionHud {
	static final int X = 2;
	static final int Y = 2;
	private static final int NORMAL = 0xFFE0E0E0;
	private static final int WARNING = 0xFFFFB050;
	private static final int BACKGROUND = 0x90000000;

	private ConnectionHud() {
	}

	/** Draws the status line if there is one; returns the y below it, for the debug overlay. */
	static int render(GuiGraphics graphics, Minecraft minecraft, ConnectionStatus status, long nowMs) {
		if (minecraft.options.hideGui || minecraft.getDebugOverlay().showDebugScreen()) {
			return Y;
		}
		ConnectionStatus.Line line = status.line(nowMs);
		if (line == null) {
			return Y;
		}
		String text = I18n.get(line.key(), line.args().toArray());
		int width = minecraft.font.width(text);
		int height = minecraft.font.lineHeight;
		graphics.fill(X, Y, X + width + 4, Y + height + 2, BACKGROUND);
		graphics.drawString(minecraft.font, text, X + 2, Y + 2, line.warning() ? WARNING : NORMAL, false);
		return Y + height + 4;
	}
}
