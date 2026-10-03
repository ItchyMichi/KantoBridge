package dev.kantobridge.bag;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * The Gen 1 battle items mirrored into the Minecraft inventory as Cobblemon items (docs/PLAN.md P17, Appendix B), by
 * the hidden game's item symbol. Ids were checked against Cobblemon 1.8.1's item registry (its {@code CobblemonItems}
 * and the jar's item models); a GameTest checks each one still resolves. Two differ from Appendix B's draft: Cobblemon
 * spells {@code x_defence}, and its Special Attack X item is {@code x_special_attack}. Items not listed (key items, TMs,
 * stones, Rare Candy, Repels, …) stay Gen 1-only; stones, TMs and Rare Candy reach Cobblemon as {@code party.item}. Plain
 * Java.
 */
public final class BagItems {
	/** Cobblemon's item namespace. */
	public static final String NAMESPACE = "cobblemon";

	private static final Map<String, String> TABLE;

	static {
		Map<String, String> table = new LinkedHashMap<>();
		// Balls.
		table.put("POKE_BALL", "poke_ball");
		table.put("GREAT_BALL", "great_ball");
		table.put("ULTRA_BALL", "ultra_ball");
		table.put("MASTER_BALL", "master_ball");
		table.put("SAFARI_BALL", "safari_ball");
		// Medicine.
		table.put("POTION", "potion");
		table.put("SUPER_POTION", "super_potion");
		table.put("HYPER_POTION", "hyper_potion");
		table.put("MAX_POTION", "max_potion");
		table.put("FULL_RESTORE", "full_restore");
		table.put("REVIVE", "revive");
		table.put("MAX_REVIVE", "max_revive");
		table.put("ANTIDOTE", "antidote");
		table.put("BURN_HEAL", "burn_heal");
		table.put("ICE_HEAL", "ice_heal");
		table.put("AWAKENING", "awakening");
		table.put("PARLYZ_HEAL", "paralyze_heal");
		table.put("FULL_HEAL", "full_heal");
		table.put("ETHER", "ether");
		table.put("MAX_ETHER", "max_ether");
		table.put("ELIXER", "elixir");
		table.put("MAX_ELIXER", "max_elixir");
		// X items. Gen 1's X Special raises the one Special stat; Cobblemon's raises Special Attack only.
		table.put("X_ATTACK", "x_attack");
		table.put("X_DEFEND", "x_defence");
		table.put("X_SPEED", "x_speed");
		table.put("X_SPECIAL", "x_special_attack");
		table.put("X_ACCURACY", "x_accuracy");
		table.put("DIRE_HIT", "dire_hit");
		table.put("GUARD_SPEC", "guard_spec");
		TABLE = Collections.unmodifiableMap(table);
	}

	private BagItems() {
	}

	/** Every mirrored Gen 1 item and its Cobblemon item path, in a fixed order. */
	public static Map<String, String> table() {
		return TABLE;
	}

	public static Set<String> gen1Items() {
		return TABLE.keySet();
	}

	/** The Cobblemon item path (in namespace {@value #NAMESPACE}) for a Gen 1 item symbol, or null when it stays Gen 1-only. */
	public static @Nullable String cobblemonPath(String gen1) {
		return TABLE.get(gen1);
	}

	/** The Gen 1 item symbol of a Cobblemon item path, or null. */
	public static @Nullable String gen1Of(String cobblemonPath) {
		for (Map.Entry<String, String> entry : TABLE.entrySet()) {
			if (entry.getValue().equals(cobblemonPath)) {
				return entry.getKey();
			}
		}
		return null;
	}
}
