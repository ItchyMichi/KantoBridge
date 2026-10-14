package dev.kantobridge.pokemon;

import com.google.gson.JsonElement;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.Set;

/**
 * Individual values as Gen 3 and later keep them (0–31 each, protocol/defs.schema.json {@code ivs}; MG7,
 * docs/specs/GEN3_BRIDGE_PLAN.md §4.10): one per stat, the same scale as Cobblemon's, so they carry over unchanged. Gen 1
 * games send {@link Dvs} instead. Plain Java.
 */
public record Ivs(int hp, int atk, int def, int spa, int spd, int spe) {
	public static final int MAX = 31;
	private static final Set<String> FIELDS = Set.of("hp", "atk", "def", "spa", "spd", "spe");

	public Ivs {
		if (!valid(hp) || !valid(atk) || !valid(def) || !valid(spa) || !valid(spd) || !valid(spe)) {
			throw new IllegalArgumentException("IV out of range");
		}
	}

	/** Reads an {@code ivs} object strictly (all six fields, 0–31, nothing else). */
	public static Ivs read(JsonElement element, String path) throws DataException {
		DataReader reader = DataReader.of(element, path).allowOnly(FIELDS);
		return new Ivs(reader.integer("hp", 0, MAX), reader.integer("atk", 0, MAX), reader.integer("def", 0, MAX),
				reader.integer("spa", 0, MAX), reader.integer("spd", 0, MAX), reader.integer("spe", 0, MAX));
	}

	/** The IV for a stat (the stats {@link Dvs} names, which are Cobblemon's six). */
	public int ivFor(Dvs.Stat stat) {
		return switch (stat) {
			case HP -> this.hp;
			case ATTACK -> this.atk;
			case DEFENCE -> this.def;
			case SPECIAL_ATTACK -> this.spa;
			case SPECIAL_DEFENCE -> this.spd;
			case SPEED -> this.spe;
		};
	}

	private static boolean valid(int iv) {
		return iv >= 0 && iv <= MAX;
	}
}
