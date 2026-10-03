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
 *
 * <p>M10: its lines are made a few times a second in the client tick, only while it is shown, and a frame only draws
 * them, so the overlay costs nothing per frame while hidden and allocates nothing of ours while shown.
 */
final class DebugOverlay {
	private static final int TEXT = 0xFFFFFFFF;
	private static final int BACKGROUND = 0xA0000000;
	private static final long RATE_WINDOW_MS = 1000;
	/** Ticks between two refreshes of the lines (4 a second). */
	private static final int REFRESH_TICKS = 5;

	private final List<Supplier<String>> extraLines = new CopyOnWriteArrayList<>();
	private final List<String> lines = new ArrayList<>();
	private int width;
	private int sinceRefresh = REFRESH_TICKS;
	private boolean visible;
	private long sampleAtMs = -1;
	private long sampleIn;
	private long sampleOut;
	private double inPerSecond;
	private double outPerSecond;

	void toggle() {
		this.visible = !this.visible;
		this.sinceRefresh = REFRESH_TICKS;
	}

	/** Adds a line other parts of the client fill in (the movement sync's counters, …). */
	void addLine(Supplier<String> line) {
		this.extraLines.add(line);
	}

	/** Once per client tick: the message rates, and the lines while shown. */
	void tick(Minecraft minecraft, BridgeClient client, ClientBridgeState state, long nowMs) {
		BridgeStats stats = client.stats();
		this.sampleRates(stats.messagesIn.get(), stats.messagesOut.get(), nowMs);
		if (!this.visible) {
			this.lines.clear();
			return;
		}
		if (++this.sinceRefresh < REFRESH_TICKS) {
			return;
		}
		this.sinceRefresh = 0;
		this.lines.clear();
		BridgeStats.Snapshot snapshot = stats.snapshot();
		HelloOk hello = state.handshake();
		this.lines.add(I18n.get("kantobridge.debug.title"));
		this.lines.add(I18n.get("kantobridge.debug.link", client.state().name().toLowerCase(Locale.ROOT), orDash(client.detail()),
				hello == null ? "-" : hello.game(), hello == null ? "-" : hello.engine(), hello == null ? "-" : orDash(hello.tested()),
				hello == null ? "-" : hello.bridge()));
		this.lines.add(I18n.get("kantobridge.debug.session", hello == null ? "-" : hello.session(), snapshot.connects(), snapshot.restarts(),
				snapshot.reconnects()));
		if (state.hasPosition()) {
			this.lines.add(I18n.get("kantobridge.debug.place", state.map(), state.x(), state.y(), orDash(state.facing()),
					orDash(state.moveKind())));
		} else {
			this.lines.add(I18n.get("kantobridge.debug.no_place"));
		}
		this.lines.add(I18n.get("kantobridge.debug.mode", state.mode(), yesNo(state.bike()), yesNo(state.surf()), yesNo(state.slope()),
				yesNo(state.dark()), yesNo(state.lit())));
		this.lines.add(I18n.get("kantobridge.debug.traffic", snapshot.messagesIn(), String.format(Locale.ROOT, "%.1f", this.inPerSecond),
				snapshot.messagesOut(), String.format(Locale.ROOT, "%.1f", this.outPerSecond),
				snapshot.lastRttMs() < 0 ? "-" : Long.toString(snapshot.lastRttMs())));
		this.lines.add(I18n.get("kantobridge.debug.errors", snapshot.protocolErrors(), snapshot.drops(), state.snapshots(), state.positions()));
		for (Supplier<String> extra : this.extraLines) {
			this.lines.add(extra.get());
		}
		int widest = 0;
		for (String line : this.lines) {
			widest = Math.max(widest, minecraft.font.width(line));
		}
		this.width = widest;
	}

	void render(GuiGraphics graphics, Minecraft minecraft, int top) {
		if (!this.visible || this.lines.isEmpty() || minecraft.options.hideGui || minecraft.getDebugOverlay().showDebugScreen()) {
			return;
		}
		int lineHeight = minecraft.font.lineHeight + 1;
		int x = ConnectionHud.X;
		graphics.fill(x, top, x + this.width + 6, top + this.lines.size() * lineHeight + 4, BACKGROUND);
		int y = top + 3;
		for (int i = 0; i < this.lines.size(); i++) {
			graphics.drawString(minecraft.font, this.lines.get(i), x + 3, y, TEXT, false);
			y += lineHeight;
		}
	}

	private void sampleRates(long in, long out, long nowMs) {
		if (this.sampleAtMs < 0) {
			this.sampleAtMs = nowMs;
			this.sampleIn = in;
			this.sampleOut = out;
			return;
		}
		long elapsed = nowMs - this.sampleAtMs;
		if (elapsed >= RATE_WINDOW_MS) {
			this.inPerSecond = (in - this.sampleIn) * 1000.0 / elapsed;
			this.outPerSecond = (out - this.sampleOut) * 1000.0 / elapsed;
			this.sampleAtMs = nowMs;
			this.sampleIn = in;
			this.sampleOut = out;
		}
	}

	private static String orDash(@Nullable String value) {
		return value == null ? "-" : value;
	}

	private static String yesNo(boolean value) {
		return I18n.get(value ? "kantobridge.debug.yes" : "kantobridge.debug.no");
	}
}
