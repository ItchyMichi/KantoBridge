package dev.kantobridge.client.weather;

import org.jetbrains.annotations.Nullable;

/**
 * The weather on screen, fading (SE6): a new weather blends in from whatever was showing over {@link #FADE_TICKS} (a
 * change mid-fade starts from the blend of the moment), and the whole effect fades in and out over the same time as it
 * starts or stops applying ({@link WeatherRules}). One instance per client, ticked 20 times a second. Pure Java.
 */
public final class WeatherFade {
	/** Two seconds. */
	public static final int FADE_TICKS = 40;

	private @Nullable Weather target;
	private WeatherLook from = WeatherLook.NONE;
	private WeatherLook to = WeatherLook.NONE;
	private int age = FADE_TICKS;
	/** Ticks of fade-in reached, 0..FADE_TICKS (whole ticks, so a full fade-out lands on exactly nothing). */
	private int level;
	private boolean applies;

	/**
	 * What should show: {@code weather} (null: nothing) while {@code applies}; when it doesn't apply, the last weather fades
	 * out where it stands.
	 */
	public void want(@Nullable Weather weather, boolean applies) {
		this.applies = applies;
		if (!applies) {
			return;
		}
		Weather next = weather == null ? Weather.NONE : weather;
		if (next == this.target) {
			return;
		}
		// start from the blend of the moment, so a change mid-fade doesn't jump
		this.from = this.level > 0 ? this.blend() : next.look();
		this.to = next.look();
		this.target = next;
		this.age = this.level > 0 ? 0 : FADE_TICKS;
	}

	/** One client tick. */
	public void tick() {
		if (this.age < FADE_TICKS) {
			this.age++;
		}
		this.level = this.applies ? Math.min(FADE_TICKS, this.level + 1) : Math.max(0, this.level - 1);
		if (this.level == 0 && !this.applies) {
			this.target = null;
			this.from = this.to = WeatherLook.NONE;
			this.age = FADE_TICKS;
		}
	}

	/** The look to draw now. */
	public WeatherLook current() {
		return this.level <= 0 ? WeatherLook.NONE : this.blend().scaled(this.strength());
	}

	/** The weather fading in or showing, or null. */
	public @Nullable Weather target() {
		return this.target;
	}

	/** How strongly the effect shows, 0..1 (its fade in or out). */
	public float strength() {
		return (float) this.level / FADE_TICKS;
	}

	private WeatherLook blend() {
		return this.age >= FADE_TICKS ? this.to : WeatherLook.blend(this.from, this.to, (float) this.age / FADE_TICKS);
	}
}
