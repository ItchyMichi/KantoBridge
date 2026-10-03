package dev.kantobridge.battle;

import java.util.Locale;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Which battle-proxy NPC class (shipped in {@code data/kantobridge/npcs/}) stands in for a trainer, and so how well
 * Cobblemon's AI plays (docs/PLAN.md P11): skill 1 for regular trainers, 3 for the rival and the gym leaders, 5 for the
 * Elite Four and the Champion. The class comes from the engine's trainer class symbol ({@code OPP_BROCK}; the {@code OPP_}
 * prefix is optional) and the battle kind. Plain Java.
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
		String cls = trainerClass == null ? "" : trainerClass.toUpperCase(Locale.ROOT);
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
