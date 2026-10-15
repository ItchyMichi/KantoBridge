package dev.kantobridge.battle;

import java.util.Locale;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Which battle-proxy NPC class (shipped in {@code data/kantobridge/npcs/}) stands in for a trainer, and so how well
 * Cobblemon's AI plays (docs/PLAN.md P11): skill 1 for regular trainers, 3 for the rival and the gym leaders, 5 for the
 * Elite Four and the Champion. The class comes from the engine's trainer class symbol and the battle kind: Gen 1's
 * trainer's own class ({@code OPP_BROCK}; the {@code OPP_} prefix is optional), Gen 3's role ({@code TRAINER_CLASS_LEADER};
 * MG7). Plain Java.
 */
public enum ProxyClass {
	REGULAR("battle_proxy", 1),
	RIVAL("battle_proxy_rival", 3),
	GYM("battle_proxy_gym", 3),
	ELITE("battle_proxy_elite", 5);

	/** Gym leaders; Giovanni keeps the class (and skill) in his Rocket battles too. */
	static final Set<String> GYM_LEADERS = Set.of("BROCK", "MISTY", "LT_SURGE", "ERIKA", "KOGA", "SABRINA", "BLAINE", "GIOVANNI");
	/** The Elite Four and the Champion (the rival's third class). */
	static final Set<String> ELITE_FOUR = Set.of("LORELEI", "BRUNO", "AGATHA", "LANCE", "RIVAL3");
	/** The rival before the Champion fight. */
	static final Set<String> RIVALS = Set.of("RIVAL1", "RIVAL2");
	/** Gen 3's class symbols start so (pokeemerald's {@code TRAINER_CLASS_*}). */
	static final String GEN3_PREFIX = "TRAINER_CLASS_";
	/** Gen 3's gym leaders, and the team leaders who keep a leader's skill as Giovanni does. */
	static final Set<String> GEN3_GYM = Set.of("LEADER", "AQUA_LEADER", "MAGMA_LEADER");
	/** Gen 3's Elite Four and Champion. */
	static final Set<String> GEN3_ELITE = Set.of("ELITE_FOUR", "CHAMPION");
	/** Gen 3's rivals (Emerald's Brendan, May and Wally). */
	static final Set<String> GEN3_RIVALS = Set.of("RIVAL");
	/**
	 * MG9 (G2F): Gen 2's gym leaders, Johto's and Kanto's three years later (pokecrystal's class constants; Koga is in
	 * the Elite Four there, Janine leads Fuchsia's gym).
	 */
	static final Set<String> GEN2_GYM = Set.of("FALKNER", "BUGSY", "WHITNEY", "MORTY", "CHUCK", "JASMINE", "PRYCE", "CLAIR",
			"BROCK", "MISTY", "LT_SURGE", "ERIKA", "JANINE", "SABRINA", "BLAINE", "BLUE");
	/** Gen 2's Elite Four, the Champion and Red on Mt. Silver. */
	static final Set<String> GEN2_ELITE = Set.of("WILL", "KOGA", "BRUNO", "KAREN", "CHAMPION", "RED");
	/** The family whose class symbols mean Gen 2's. */
	static final String GEN2 = "gen2";

	private final String path;
	private final int skill;

	ProxyClass(String path, int skill) {
		this.path = path;
		this.skill = skill;
	}

	/** The NPC class id's path in the {@code kantobridge} namespace. */
	public String path() {
		return this.path;
	}

	/** The AI skill the shipped class carries. */
	public int skill() {
		return this.skill;
	}

	/** The class for a battle: by trainer class first (the Champion is a rival kind), then by kind. */
	public static ProxyClass of(BattleStart.Kind kind, @Nullable String trainerClass) {
		return of(kind, trainerClass, null);
	}

	/**
	 * The class for a battle in a region of {@code family}: Gen 2's class symbols are read by Gen 2's tables (MG9: KOGA is
	 * Elite Four there); Gen 1's and Gen 3's as before.
	 */
	public static ProxyClass of(BattleStart.Kind kind, @Nullable String trainerClass, @Nullable String family) {
		String cls = trainerClass == null ? "" : trainerClass.toUpperCase(Locale.ROOT);
		if (GEN2.equals(family)) {
			if (GEN2_ELITE.contains(cls)) {
				return ELITE;
			}
			if (GEN2_GYM.contains(cls)) {
				return GYM;
			}
			return RIVALS.contains(cls) || kind == BattleStart.Kind.RIVAL ? RIVAL : REGULAR;
		}
		if (cls.startsWith(GEN3_PREFIX)) {
			String role = cls.substring(GEN3_PREFIX.length());
			if (GEN3_ELITE.contains(role)) {
				return ELITE;
			}
			if (GEN3_GYM.contains(role)) {
				return GYM;
			}
			return GEN3_RIVALS.contains(role) || kind == BattleStart.Kind.RIVAL ? RIVAL : REGULAR;
		}
		if (cls.startsWith("OPP_")) {
			cls = cls.substring(4);
		}
		if (ELITE_FOUR.contains(cls)) {
			return ELITE;
		}
		if (GYM_LEADERS.contains(cls)) {
			return GYM;
		}
		if (RIVALS.contains(cls) || kind == BattleStart.Kind.RIVAL) {
			return RIVAL;
		}
		return REGULAR;
	}
}
