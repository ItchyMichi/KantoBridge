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
 * One Pokémon as the hidden game describes it: species (national dex 1–151), level, Gen 1 moves and DVs. A trainer's
 * team member always has all four (protocol/defs.schema.json {@code teamMon}); a gift ({@code party.give}) may leave out
 * moves and DVs, and Cobblemon's defaults fill them in. Plain Java.
 *
 * @param moves 0–4 distinct Gen 1 move numbers in slot order; empty means Cobblemon's default moveset
 * @param dvs   null means Cobblemon's random IVs
 */
public record Gen1Mon(int dex, int level, List<Integer> moves, @Nullable Dvs dvs) {
	public static final int MAX_LEVEL = 100;
	public static final int MAX_MOVES = 4;
	private static final Set<String> TEAM_FIELDS = Set.of("dex", "level", "moves", "dvs");

	public Gen1Mon {
		if (!GenOneIds.isDex(dex) || level < 1 || level > MAX_LEVEL || moves.size() > MAX_MOVES) {
			throw new IllegalArgumentException("not a Gen 1 Pokémon");
		}
		moves = List.copyOf(moves);
		for (int move : moves) {
			if (!GenOneIds.isMove(move)) {
				throw new IllegalArgumentException("not a Gen 1 move");
			}
		}
	}

	/** A trainer team member: {@code {dex, level, moves[1..4], dvs}}, nothing else. */
	public static Gen1Mon readTeamMember(JsonElement element, String path) throws DataException {
		DataReader reader = DataReader.of(element, path).allowOnly(TEAM_FIELDS);
		int dex = reader.integer("dex", 1, GenOneIds.MAX_DEX);
		int level = reader.integer("level", 1, MAX_LEVEL);
		List<Integer> moves = readMoves(reader.array("moves"), reader.path("moves"));
		Dvs dvs = Dvs.read(reader.element("dvs"), reader.path("dvs"));
		return new Gen1Mon(dex, level, moves, dvs);
	}

	/**
	 * A move list: 1–4 Gen 1 move numbers, in slot order. A repeated move (never seen in Gen 1 play, but a special-move
	 * insertion could make one) is kept once: Showdown tracks PP per move id, so two slots of one move would share it.
	 */
	public static List<Integer> readMoves(JsonArray array, String path) throws DataException {
		if (array.isEmpty() || array.size() > MAX_MOVES) {
			throw new DataException(path, "needs 1-4 moves");
		}
		List<Integer> moves = new ArrayList<>(array.size());
		Set<Integer> seen = new HashSet<>();
		for (int i = 0; i < array.size(); i++) {
			int move = DataReader.asInt(array.get(i), path + "/" + i, 1, GenOneIds.MAX_MOVE);
			if (seen.add(move)) {
				moves.add(move);
			}
		}
		return moves;
	}
}
