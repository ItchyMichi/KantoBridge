package dev.kantobridge.client.weather;

/**
 * How one weather looks in Minecraft (SE6, docs/specs/STORY_EVENTS_PLAN.md §4.1 S8), as the numbers the client's effects
 * read: the rain level Minecraft's own precipitation renderer draws with (and whether it falls as snow), the thunder level
 * that darkens the sky, the game's lightning flashes, fog (how strongly the view closes in towards {@code fogEnd} blocks),
 * a tint for fog and sky ({@code tintRgb} at {@code tint} strength), a translucent screen tint ({@code screenArgb}, alpha
 * its strength) and particles per tick (falling ash, blowing sand, rising bubbles). Pure Java.
 *
 * @param rain      rain level 0..1 (0: no precipitation drawn)
 * @param snow      precipitation falls as snow
 * @param thunder   thunder level 0..1
 * @param lightning flashes every few seconds
 * @param fog       fog strength 0..1
 * @param fogEnd    the fog's far end at full strength, in blocks
 * @param tintRgb   fog and sky tint colour, 0xRRGGBB
 * @param tint      tint strength 0..1
 * @param screenArgb screen tint, 0xAARRGGBB (alpha 0: none)
 * @param ash       ash particles per tick
 * @param sand      sand particles per tick
 * @param bubbles   bubble particles per tick
 */
public record WeatherLook(float rain, boolean snow, float thunder, boolean lightning, float fog, float fogEnd, int tintRgb, float tint,
		int screenArgb, float ash, float sand, float bubbles) {
	/** Nothing drawn. */
	public static final WeatherLook NONE = new WeatherLook(0, false, 0, false, 0, 0, 0, 0, 0, 0, 0, 0);

	public WeatherLook {
		rain = clamp01(rain);
		thunder = clamp01(thunder);
		fog = clamp01(fog);
		tint = clamp01(tint);
		fogEnd = Math.max(0, fogEnd);
		ash = Math.max(0, ash);
		sand = Math.max(0, sand);
		bubbles = Math.max(0, bubbles);
	}

	/** True when it draws nothing at all. */
	public boolean none() {
		return this.rain == 0 && this.thunder == 0 && !this.lightning && this.fog == 0 && this.tint == 0 && alpha(this.screenArgb) == 0
				&& this.ash == 0 && this.sand == 0 && this.bubbles == 0;
	}

	/** The look {@code t} of the way from {@code a} to {@code b} (0: a, 1: b); a colour without strength takes the other's. */
	public static WeatherLook blend(WeatherLook a, WeatherLook b, float t) {
		t = clamp01(t);
		if (t == 0) {
			return a;
		}
		if (t == 1) {
			return b;
		}
		boolean late = t >= 0.5F;
		float fogEnd = a.fog == 0 ? b.fogEnd : b.fog == 0 ? a.fogEnd : lerp(a.fogEnd, b.fogEnd, t);
		int tintRgb = a.tint == 0 ? b.tintRgb : b.tint == 0 ? a.tintRgb : lerpRgb(a.tintRgb, b.tintRgb, t);
		return new WeatherLook(lerp(a.rain, b.rain, t), late ? b.snow : a.snow, lerp(a.thunder, b.thunder, t), late ? b.lightning : a.lightning,
				lerp(a.fog, b.fog, t), fogEnd, tintRgb, lerp(a.tint, b.tint, t), lerpArgb(a.screenArgb, b.screenArgb, t), lerp(a.ash, b.ash, t),
				lerp(a.sand, b.sand, t), lerp(a.bubbles, b.bubbles, t));
	}

	/** This look at {@code k} of its strength (0: nothing, 1: as it is); lightning only from half strength. */
	public WeatherLook scaled(float k) {
		k = clamp01(k);
		if (k >= 1) {
			return this;
		}
		int alpha = Math.round(alpha(this.screenArgb) * k);
		return new WeatherLook(this.rain * k, this.snow, this.thunder * k, this.lightning && k >= 0.5F, this.fog * k, this.fogEnd, this.tintRgb,
				this.tint * k, alpha << 24 | (this.screenArgb & 0xFFFFFF), this.ash * k, this.sand * k, this.bubbles * k);
	}

	static int alpha(int argb) {
		return argb >>> 24;
	}

	static float clamp01(float v) {
		return v != v ? 0 : Math.max(0, Math.min(1, v));
	}

	static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	static int lerpRgb(int a, int b, float t) {
		int r = Math.round(lerp(a >> 16 & 0xFF, b >> 16 & 0xFF, t));
		int g = Math.round(lerp(a >> 8 & 0xFF, b >> 8 & 0xFF, t));
		int bl = Math.round(lerp(a & 0xFF, b & 0xFF, t));
		return r << 16 | g << 8 | bl;
	}

	/** Colours blend only where both show (a colour without alpha takes the other's), alpha always. */
	static int lerpArgb(int a, int b, float t) {
		int aa = alpha(a);
		int ba = alpha(b);
		int rgb = aa == 0 ? b & 0xFFFFFF : ba == 0 ? a & 0xFFFFFF : lerpRgb(a & 0xFFFFFF, b & 0xFFFFFF, t);
		return Math.round(lerp(aa, ba, t)) << 24 | rgb;
	}
}
