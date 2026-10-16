package dev.kantobridge.client.weather;

import java.util.random.RandomGenerator;

/**
 * When the game's lightning strikes (SE6; Emerald's thunderstorm and downpour, field_weather_rse.lua:1061-1200): every 6
 * to 12 seconds one or two short bolts (2 ticks lit, the second 3 ticks after the first) or, one strike in three, a long
 * one fading over half a second; the thunder follows each strike after half a second to two. Pure Java, ticked 20 times a
 * second.
 */
public final class LightningClock {
	/** What one tick does: a bolt starts (the sky flashes), how bright the screen flash is (0..1), thunder sounds. */
	public record Tick(boolean bolt, float flash, boolean thunder) {
		static final Tick QUIET = new Tick(false, 0, false);
	}

	static final int MIN_WAIT = 120;
	static final int MAX_WAIT = 240;
	static final int LONG_TICKS = 10;

	private final RandomGenerator random;
	private int wait;
	private int age = -1;
	private boolean longBolt;
	private boolean twice;
	private int thunderIn = -1;

	public LightningClock(RandomGenerator random) {
		this.random = random;
		this.wait = this.nextWait();
	}

	private int nextWait() {
		return MIN_WAIT + this.random.nextInt(MAX_WAIT - MIN_WAIT + 1);
	}

	/** One tick; {@code active} is false where the weather has no lightning (nothing strikes, a strike under way stops). */
	public Tick tick(boolean active) {
		if (!active) {
			this.age = -1;
			this.thunderIn = -1;
			return Tick.QUIET;
		}
		boolean thunder = false;
		if (this.thunderIn > 0 && --this.thunderIn == 0) {
			thunder = true;
			this.thunderIn = -1;
		}
		if (this.age < 0) {
			if (--this.wait > 0) {
				return thunder ? new Tick(false, 0, true) : Tick.QUIET;
			}
			this.age = 0;
			this.longBolt = this.random.nextInt(3) == 0;
			this.twice = !this.longBolt && this.random.nextBoolean();
			this.thunderIn = 10 + this.random.nextInt(31);
			this.wait = this.nextWait();
		}
		int end = this.longBolt ? LONG_TICKS : this.twice ? 7 : 2;
		if (this.age >= end) {
			this.age = -1;
			return thunder ? new Tick(false, 0, true) : Tick.QUIET;
		}
		boolean bolt;
		float flash;
		if (this.longBolt) {
			bolt = this.age == 0;
			flash = 1.0F - (float) this.age / LONG_TICKS;
		} else {
			bolt = this.age == 0 || this.twice && this.age == 5;
			flash = this.age <= 1 || this.twice && this.age >= 5 ? 1.0F : 0.0F;
		}
		this.age++;
		return new Tick(bolt, flash, thunder);
	}
}
