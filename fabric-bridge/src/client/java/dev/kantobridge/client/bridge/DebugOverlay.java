package dev.kantobridge.client.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.Nullable;

/**
 * The bridge's debug overlay (key F7 by default): the hidden game's map, cell, facing and mode, plus the link's
 * session, counters and message rates. Map ids and modes are engine symbols, never game text.
 */
final class DebugOverlay {
	private static final int TEXT = 0xFFFFFFFF;
	private static final int BACKGROUND = 0xA0000000;
	private static final long RATE_WINDOW_MS = 1000;

	private final List<Supplier<String>> extraLines = new CopyOnWriteArrayList<>();
	private boolean visible;
	private long sampleAtMs = -1;
	private long sampleIn;
	private long sampleOut;
	private double inPerSecond;
	private double outPerSecond;

	void toggle() {
		this.visible = !this.visible;
	}

	/** Adds a line other parts of the client fill in (the movement sync's counters, …). */
	void addLine(Supplier<String> line) {
		this.extraLines.add(line);
	}

	void render(GuiGraphics graphics, Minecraft minecraft, BridgeClient client, ClientBridgeState state, long nowMs, int top) {
		BridgeStats.Snapshot stats = client.stats().snapshot();
		this.sampleRates(stats, nowMs);
		if (!this.visible || minecraft.options.hideGui || minecraft.getDebugOverlay().showDebugScreen()) {
			return;
		}
		List<String> lines = new ArrayList<>();
		HelloOk hello = state.handshake();
		lines.add(I18n.get("kantobridge.debug.title"));
		lines.add(I18n.get("kantobridge.debug.link", client.state().name().toLowerCase(Locale.ROOT), orDash(client.detail()),
				hello == null ? "-" : hello.game(), hello == null ? "-" : hello.engine(), hello == null ? "-" : hello.bridge()));
		lines.add(I18n.get("kantobridge.debug.session", hello == null ? "-" : hello.session(), stats.connects(), stats.restarts(),
				stats.reconnects()));
		if (state.hasPosition()) {
			lines.add(I18n.get("kantobridge.debug.place", state.map(), state.x(), state.y(), orDash(state.facing()),
					orDash(state.moveKind())));
		} else {
			lines.add(I18n.get("kantobridge.debug.no_place"));
		}
		lines.add(I18n.get("kantobridge.debug.mode", state.mode(), yesNo(state.bike()), yesNo(state.surf()), yesNo(state.slope()),
				yesNo(state.dark()), yesNo(state.lit())));
		lines.add(I18n.get("kantobridge.debug.traffic", stats.messagesIn(), String.format(Locale.ROOT, "%.1f", this.inPerSecond),
				stats.messagesOut(), String.format(Locale.ROOT, "%.1f", this.outPerSecond),
				stats.lastRttMs() < 0 ? "-" : Long.toString(stats.lastRttMs())));
		lines.add(I18n.get("kantobridge.debug.errors", stats.protocolErrors(), stats.drops(), state.snapshots(), state.positions()));
		for (Supplier<String> extra : this.extraLines) {
			lines.add(extra.get());
		}

		int width = 0;
		for (String line : lines) {
			width = Math.max(width, minecraft.font.width(line));
		}
		int lineHeight = minecraft.font.lineHeight + 1;
		int x = ConnectionHud.X;
		graphics.fill(x, top, x + width + 6, top + lines.size() * lineHeight + 4, BACKGROUND);
		int y = top + 3;
		for (String line : lines) {
			graphics.drawString(minecraft.font, line, x + 3, y, TEXT, false);
			y += lineHeight;
		}
	}

	private void sampleRates(BridgeStats.Snapshot stats, long nowMs) {
		if (this.sampleAtMs < 0) {
			this.sampleAtMs = nowMs;
			this.sampleIn = stats.messagesIn();
			this.sampleOut = stats.messagesOut();
			return;
		}
		long elapsed = nowMs - this.sampleAtMs;
		if (elapsed >= RATE_WINDOW_MS) {
			this.inPerSecond = (stats.messagesIn() - this.sampleIn) * 1000.0 / elapsed;
			this.outPerSecond = (stats.messagesOut() - this.sampleOut) * 1000.0 / elapsed;
			this.sampleAtMs = nowMs;
			this.sampleIn = stats.messagesIn();
			this.sampleOut = stats.messagesOut();
		}
	}

	private static String orDash(@Nullable String value) {
		return value == null ? "-" : value;
	}

	private static String yesNo(boolean value) {
		return I18n.get(value ? "kantobridge.debug.yes" : "kantobridge.debug.no");
	}
}
