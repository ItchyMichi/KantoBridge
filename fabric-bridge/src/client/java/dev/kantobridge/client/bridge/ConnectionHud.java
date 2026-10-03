package dev.kantobridge.client.bridge;

import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import org.jetbrains.annotations.Nullable;

/**
 * The connection status in the top-left corner, in game and on the title screen: small, and hidden once connected
 * (after a few seconds), with F1, and while the F3 screen is open. Other parts of the client add lines under it (why the
 * player is held, the save pairing, textures). The pause overlay ({@link LinkPauseScreen}) is its big sibling.
 *
 * <p>M10: the lines are worked out once per client tick ({@link #refresh}) and formatted only when they change, so
 * drawing them each frame allocates nothing of ours.
 */
final class ConnectionHud {
	static final int X = 2;
	static final int Y = 2;
	private static final int NORMAL = 0xFFE0E0E0;
	private static final int WARNING = 0xFFFFB050;
	private static final int BACKGROUND = 0x90000000;
	/** Lines shown at most (the status line and the others under it). */
	private static final int MAX = 12;

	private final ConnectionStatus.@Nullable Line[] lines = new ConnectionStatus.Line[MAX];
	private final String[] texts = new String[MAX];
	private final int[] widths = new int[MAX];
	private int count;
	/** 1 when the first line is the connection status, 0 when there is none now. */
	private int statusCount;
	private @Nullable Language language;

	/** Once per client tick: the status line and the extra lines, each formatted again only when it changed. */
	void refresh(Font font, ConnectionStatus.@Nullable Line status, List<Supplier<ConnectionStatus.@Nullable Line>> extras) {
		Language current = Language.getInstance();
		if (current != this.language) {
			// A language change: every text is made again.
			this.language = current;
			java.util.Arrays.fill(this.lines, null);
		}
		int n = this.put(0, status, font);
		this.statusCount = n;
		for (Supplier<ConnectionStatus.@Nullable Line> extra : extras) {
			n = this.put(n, extra.get(), font);
		}
		for (int i = n; i < this.count; i++) {
			this.lines[i] = null;
			this.texts[i] = null;
		}
		this.count = n;
	}

	private int put(int index, ConnectionStatus.@Nullable Line line, Font font) {
		if (line == null || index >= MAX) {
			return index;
		}
		if (!line.equals(this.lines[index])) {
			this.lines[index] = line;
			this.texts[index] = I18n.get(line.key(), line.args().toArray());
			this.widths[index] = font.width(this.texts[index]);
		}
		return index + 1;
	}

	/**
	 * Draws the status line and, in game ({@code extras}), the lines under it; returns the y below them, for the debug
	 * overlay. Nothing with F1 or the F3 screen.
	 */
	int render(GuiGraphics graphics, Minecraft minecraft, boolean extras) {
		if (minecraft.options.hideGui || minecraft.getDebugOverlay().showDebugScreen()) {
			return Y;
		}
		int shown = extras ? this.count : this.statusCount;
		int y = Y;
		int height = minecraft.font.lineHeight;
		for (int i = 0; i < shown; i++) {
			String text = this.texts[i];
			ConnectionStatus.Line line = this.lines[i];
			if (text == null || line == null) {
				continue;
			}
			graphics.fill(X, y, X + this.widths[i] + 4, y + height + 2, BACKGROUND);
			graphics.drawString(minecraft.font, text, X + 2, y + 2, line.warning() ? WARNING : NORMAL, false);
			y += height + 4;
		}
		return y;
	}
}
