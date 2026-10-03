package dev.kantobridge.client.bridge;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * The connection's one-line status for the HUD and title screen, as a translation key plus arguments. Updated on the
 * client thread from {@link Dispatcher} state events.
 *
 * <p>While Gen1Recomp is closed the client cycles connecting/disconnected every few seconds, so the line remembers
 * the last failure until the next handshake instead of flickering. Once connected, the line shows briefly and then
 * gets out of the way.
 */
public final class ConnectionStatus {
	/** How long "connected" stays on screen. */
	public static final long SHOW_CONNECTED_MS = 5000;

	/**
	 * @param key     a translation key under {@code kantobridge.hud.}
	 * @param args    its arguments (short codes and symbols only)
	 * @param warning draw it as a problem
	 */
	public record Line(String key, List<String> args, boolean warning) {
	}

	private ConnectionState state = ConnectionState.IDLE;
	private @Nullable String detail;
	private long sinceMs;
	private boolean failedSinceHandshake;
	private @Nullable String rejectReason;

	public void update(ConnectionState next, @Nullable String why, long nowMs) {
		switch (next) {
			case CONNECTED -> {
				this.failedSinceHandshake = false;
				this.rejectReason = null;
			}
			case REJECTED -> {
				this.failedSinceHandshake = true;
				this.rejectReason = why;
			}
			case DISCONNECTED -> this.failedSinceHandshake = true;
			default -> {
			}
		}
		this.state = next;
		this.detail = why;
		this.sinceMs = nowMs;
	}

	public ConnectionState state() {
		return this.state;
	}

	/** The line to show now, or null when nothing needs showing. */
	public @Nullable Line line(long nowMs) {
		return switch (this.state) {
			case CONNECTED -> nowMs - this.sinceMs < SHOW_CONNECTED_MS
					? new Line("kantobridge.hud.connected", List.of(this.detail == null ? "?" : this.detail), false)
					: null;
			case STOPPED -> new Line("kantobridge.hud.stopped", List.of(), false);
			case PAUSED -> new Line("kantobridge.hud.paused", List.of(), false);
			default -> {
				if (this.rejectReason != null) {
					yield new Line("kantobridge.hud.rejected", List.of(this.rejectReason), true);
				}
				if (this.failedSinceHandshake) {
					yield new Line("kantobridge.hud.disconnected", List.of(), true);
				}
				yield new Line("kantobridge.hud.connecting", List.of(), false);
			}
		};
	}
}
