package dev.kantobridge.pokemon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * One Pokémon as the hidden game describes it: species (a national dex number), level, moves (Showdown's numbers, which
 * are Cobblemon's and, for Gen 1's moves, Gen 1's own) and DVs (Gen 1) or IVs (Gen 3, MG7). A trainer's team member always
 * has species, level, moves and one of DVs and IVs (protocol/defs.schema.json {@code teamMon}); a gift ({@code party.give})
 * may leave out moves and both, and Cobblemon's defaults fill them in. Numbers are checked against the protocol's ranges
 * only: what a game can hold is its compat ceiling (the bridge keeps to it), and whether Cobblemon knows a number is
 * {@link Gen1Mons}' lookup. The name dates from Gen 1, the first game it described. Plain Java.
 *
 * @param moves   0–4 distinct move numbers in slot order; empty means Cobblemon's default moveset
 * @param dvs     Gen 1's DVs (Gen 2's too); with {@code ivs} also null, Cobblemon's random IVs
 * @param ivs     Gen 3's IVs, used over {@code dvs} when a gift carries both
 * @param details MG9 (G2F): the held item, shininess, gender, form and friendship where the game has them
 */
public record Gen1Mon(int dex, int level, List<Integer> moves, @Nullable Dvs dvs, @Nullable Ivs ivs, MonDetails details) {
	public static final int MAX_LEVEL = 100;
	public static final int MAX_MOVES = 4;
	/** The protocol's national dex numbers (defs {@code nationalDex}). */
	public static final int MAX_NATIONAL = 2000;
	/** The protocol's move numbers (defs {@code moveNum}, Showdown's). */
	public static final int MAX_MOVE_NUM = 2000;
	private static final Set<String> TEAM_FIELDS = Set.of("dex", "level", "moves", "dvs", "ivs", "item", "shiny", "gender", "form");

	public Gen1Mon {
		details = details == null ? MonDetails.NONE : details;
		if (!isNational(dex) || level < 1 || level > MAX_LEVEL || moves.size() > MAX_MOVES) {
			throw new IllegalArgumentException("not a Pokémon the protocol describes");
		}
		moves = List.copyOf(moves);
		for (int move : moves) {
			if (!isMoveNum(move)) {
				throw new IllegalArgumentException("not a move number");
			}
		}
	}

	/** A Pokémon with DVs (Gen 1) or no individual values at all. */
	public Gen1Mon(int dex, int level, List<Integer> moves, @Nullable Dvs dvs) {
		this(dex, level, moves, dvs, null, MonDetails.NONE);
	}

	/** A Pokémon with DVs or IVs and no details (Gen 1, Gen 3). */
	public Gen1Mon(int dex, int level, List<Integer> moves, @Nullable Dvs dvs, @Nullable Ivs ivs) {
		this(dex, level, moves, dvs, ivs, MonDetails.NONE);
	}

	/** A national dex number the protocol carries. */
	public static boolean isNational(int dex) {
		return dex >= 1 && dex <= MAX_NATIONAL;
	}

	/** A move number the protocol carries. */
	public static boolean isMoveNum(int move) {
		return move >= 1 && move <= MAX_MOVE_NUM;
	}

	/** A trainer team member: {@code {dex, level, moves[1..4]}} and exactly one of {@code dvs} and {@code ivs}. */
	public static Gen1Mon readTeamMember(JsonElement element, String path) throws DataException {
		DataReader reader = DataReader.of(element, path).allowOnly(TEAM_FIELDS);
		int dex = reader.integer("dex", 1, MAX_NATIONAL);
		int level = reader.integer("level", 1, MAX_LEVEL);
		List<Integer> moves = readMoves(reader.array("moves"), reader.path("moves"));
		boolean hasDvs = reader.has("dvs");
		if (hasDvs == reader.has("ivs")) {
			throw new DataException(reader.path(hasDvs ? "ivs" : "dvs"), hasDvs ? "dvs or ivs, not both" : "needs dvs or ivs");
		}
		Dvs dvs = hasDvs ? Dvs.read(reader.element("dvs"), reader.path("dvs")) : null;
		Ivs ivs = hasDvs ? null : Ivs.read(reader.element("ivs"), reader.path("ivs"));
		return new Gen1Mon(dex, level, moves, dvs, ivs, MonDetails.read(reader, false));
	}

	/**
	 * A move list: 1–4 move numbers, in slot order. A repeated move (never seen in Gen 1 play, but a special-move insertion
	 * could make one) is kept once: Showdown tracks PP per move id, so two slots of one move would share it.
	 */
	public static List<Integer> readMoves(JsonArray array, String path) throws DataException {
		if (array.isEmpty() || array.size() > MAX_MOVES) {
			throw new DataException(path, "needs 1-4 moves");
		}
		List<Integer> moves = new ArrayList<>(array.size());
		Set<Integer> seen = new HashSet<>();
		for (int i = 0; i < array.size(); i++) {
			int move = DataReader.asInt(array.get(i), path + "/" + i, 1, MAX_MOVE_NUM);
			if (seen.add(move)) {
				moves.add(move);
			}
		}
		return moves;
	}
}
