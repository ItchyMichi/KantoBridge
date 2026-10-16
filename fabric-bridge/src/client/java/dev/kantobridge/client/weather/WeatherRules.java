package dev.kantobridge.client.weather;

import org.jetbrains.annotations.Nullable;

/**
 * When the hidden game's weather shows (SE6): only for the followed link's game, while it is connected and its player is
 * in the field, only where it sends a weather (Gen 3; Gen 1 and 2 send none), only while the local player stands on a map
 * of the followed region (never out in a Wild Routes zone or the dungeon area, in another region, or off every map), and
 * only with the client's {@code weather} setting on. Standing off every map for a moment (a seam, a doorway, the void
 * ring) keeps it for {@link #GRACE_TICKS}, so it doesn't flicker. Pure Java.
 */
public final class WeatherRules {
	/** Three seconds off the followed region's maps before the weather starts to fade out. */
	public static final int GRACE_TICKS = 60;

	private int offMapTicks = GRACE_TICKS;

	/**
	 * One client tick's verdict.
	 *
	 * @param setting   the client's {@code weather} setting
	 * @param connected the followed link is connected
	 * @param ingame    its hidden player is in the field (not the title screen)
	 * @param weather   the game's weather name, or null when it sends none
	 * @param onMap     the local player stands on a cell of the followed region's maps
	 */
	public boolean applies(boolean setting, boolean connected, boolean ingame, @Nullable String weather, boolean onMap) {
		this.offMapTicks = onMap ? 0 : Math.min(GRACE_TICKS, this.offMapTicks + 1);
		return setting && connected && ingame && weather != null && Weather.byId(weather) != null && this.offMapTicks < GRACE_TICKS;
	}

	/** Forgets the grace (a new world, a disconnect). */
	public void reset() {
		this.offMapTicks = GRACE_TICKS;
	}
}
