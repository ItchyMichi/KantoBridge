package dev.kantobridge.pokemon;

import org.jetbrains.annotations.Nullable;

/**
 * When a state message ({@code dex.sync}, …) goes out: after something marked it changed, at most once every
 * {@code everyTicks} ticks, and only when its text differs from the last one sent. Plain Java; times in server ticks.
 */
public final class SyncDebounce {
	private final int everyTicks;
	private boolean dirty = true;
	private long lastSent = Long.MIN_VALUE / 2;
	private @Nullable String lastText;

	public SyncDebounce(int everyTicks) {
		if (everyTicks < 1) {
			throw new IllegalArgumentException("at least one tick");
		}
		this.everyTicks = everyTicks;
	}

	/** Something changed: build the message at the next chance. */
	public void mark() {
		this.dirty = true;
	}

	/** Send the next message even if it equals the last one (a new link, a restart). */
	public void forget() {
		this.dirty = true;
		this.lastText = null;
	}

	/** True when a message should be built now. */
	public boolean due(long now) {
		return this.dirty && now - this.lastSent >= this.everyTicks;
	}

	/**
	 * The message built for {@link #due}: true when it should be sent (it differs from the last one sent). Either way the
	 * change is consumed; call {@link #sent} once it went out.
	 */
	public boolean offer(String text) {
		this.dirty = false;
		return !text.equals(this.lastText);
	}

	/** The message went out. */
	public void sent(String text, long now) {
		this.lastText = text;
		this.lastSent = now;
	}
}
