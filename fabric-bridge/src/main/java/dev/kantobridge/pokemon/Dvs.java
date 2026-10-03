package dev.kantobridge.pokemon;

import com.google.gson.JsonElement;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.Set;

/**
 * Gen 1 determinant values (0–15 each, protocol/defs.schema.json {@code dvs}): Attack, Defense, Speed and Special; the HP
 * DV is made of their lowest bits. Cobblemon wants IVs (0–31): PLAN P11 maps each DV to {@code 2·DV + 1}, Special to
 * both Special Attack and Special Defense, and HP from the derived HP DV. Plain Java.
 *
 * @param atk Attack DV
 * @param def Defense DV
 * @param spd Speed DV
 * @param spc Special DV
 */
public record Dvs(int atk, int def, int spd, int spc) {
	public static final int MAX = 15;
	private static final Set<String> FIELDS = Set.of("atk", "def", "spd", "spc");

	/** The six stats Cobblemon keeps IVs for, in its own order. */
	public enum Stat {
		HP, ATTACK, DEFENCE, SPECIAL_ATTACK, SPECIAL_DEFENCE, SPEED
	}

	public Dvs {
		if (!valid(atk) || !valid(def) || !valid(spd) || !valid(spc)) {
			throw new IllegalArgumentException("DV out of range");
		}
	}

	/** Reads a {@code dvs} object strictly (all four fields, 0–15, nothing else). */
	public static Dvs read(JsonElement element, String path) throws DataException {
		DataReader reader = DataReader.of(element, path).allowOnly(FIELDS);
		return new Dvs(reader.integer("atk", 0, MAX), reader.integer("def", 0, MAX), reader.integer("spd", 0, MAX),
				reader.integer("spc", 0, MAX));
	}

	/** Gen 1's HP DV: the lowest bits of Attack, Defense, Speed and Special, in that order from the top. */
	public int hp() {
		return (this.atk & 1) << 3 | (this.def & 1) << 2 | (this.spd & 1) << 1 | this.spc & 1;
	}

	/** The IV for one DV (PLAN P11): 0 → 1, 15 → 31. */
	public static int iv(int dv) {
		if (!valid(dv)) {
			throw new IllegalArgumentException("DV out of range");
		}
		return 2 * dv + 1;
	}

	/** The Cobblemon IV for a stat. */
	public int ivFor(Stat stat) {
		return switch (stat) {
			case HP -> iv(this.hp());
			case ATTACK -> iv(this.atk);
			case DEFENCE -> iv(this.def);
			case SPECIAL_ATTACK, SPECIAL_DEFENCE -> iv(this.spc);
			case SPEED -> iv(this.spd);
		};
	}

	private static boolean valid(int dv) {
		return dv >= 0 && dv <= MAX;
	}
}
