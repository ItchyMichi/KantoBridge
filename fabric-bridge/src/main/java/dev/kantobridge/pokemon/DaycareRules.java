package dev.kantobridge.pokemon;

/**
 * Gen 1's daycare in numbers (docs/PLAN.md P15): a Pokémon left there gains one experience point for every step its
 * trainer walks, and never grows past level 100. The hidden game counts the steps ({@code daycare.withdraw.steps});
 * Cobblemon's experience curve turns the points into levels. Plain Java.
 */
public final class DaycareRules {
	/** Experience per step, as Gen 1 counts it ({@code FieldDefaults.daycareExpPerStep} in the engine). */
	public static final int EXPERIENCE_PER_STEP = 1;
	/** Gen 1's highest level. */
	public static final int MAX_LEVEL = 100;

	private DaycareRules() {
	}

	/**
	 * The experience a withdrawal adds: {@code steps} worth, but never past {@code capExperience} (the experience at the
	 * level cap on this Pokémon's curve).
	 *
	 * @param current the Pokémon's experience now
	 */
	public static int experience(long steps, int current, int capExperience) {
		if (steps <= 0 || current >= capExperience) {
			return 0;
		}
		long room = (long) capExperience - current;
		long earned = steps > room / EXPERIENCE_PER_STEP ? room : steps * EXPERIENCE_PER_STEP;
		return (int) Math.min(Math.min(earned, room), Integer.MAX_VALUE);
	}

	/** The level cap a withdrawal respects: Gen 1's 100, or a lower one Cobblemon is configured with. */
	public static int levelCap(int configuredMax) {
		return Math.max(1, Math.min(MAX_LEVEL, configuredMax));
	}
}
