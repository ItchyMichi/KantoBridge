package dev.kantobridge.pokemon;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.Ids;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * A {@code party.give} as the server receives it (protocol/messages/party.give.schema.json): the hidden game gives the
 * player a Pokémon (the starter, a gift, a trade, a prize, a native catch). Checked strictly; problems are a JSON path
 * and a reason, never a value (nicknames and trainer names are ROM-derived). Plain Java.
 *
 * @param give the correlation id gen1-bridge chose
 * @param nick a nickname, or null for none
 * @param ot   the original trainer's name; only a trade's is kept (an NPC trainer), others belong to the player
 */
public record PartyGive(String give, Gen1Mon mon, @Nullable String nick, @Nullable String ot, Source source, @Nullable String nature) {
	public static final int MAX_NAME = 32;
	/** FP7: a nature's name (Cobblemon's, Showdown's), as the protocol's defs nature. */
	static final java.util.regex.Pattern NATURE = java.util.regex.Pattern.compile("^[a-z][a-z0-9_]{0,23}$");
	private static final Set<String> FIELDS = Set.of("give", "dex", "level", "moves", "dvs", "ivs", "nick", "ot", "source", "region", "nature", "item",
			"shiny", "gender", "form", "friendship");

	/** Where the Pokémon comes from ({@code EGG}: an egg that hatched in the hidden game, which keeps eggs itself). */
	public enum Source {
		STARTER, GIFT, TRADE, PRIZE, NATIVE_CATCH, EGG;

		public String id() {
			return this.name().toLowerCase(Locale.ROOT);
		}

		static @Nullable Source byId(String id) {
			for (Source source : values()) {
				if (source.id().equals(id)) {
					return source;
				}
			}
			return null;
		}
	}

	public static PartyGive read(JsonObject data) throws DataException {
		DataReader reader = DataReader.of(data, "").allowOnly(FIELDS);
		String give = reader.string("give", Ids.ID);
		int dex = reader.integer("dex", 1, Gen1Mon.MAX_NATIONAL);
		int level = reader.integer("level", 1, Gen1Mon.MAX_LEVEL);
		List<Integer> moves = reader.has("moves") ? Gen1Mon.readMoves(reader.array("moves"), reader.path("moves")) : List.of();
		Dvs dvs = reader.has("dvs") ? Dvs.read(reader.element("dvs"), reader.path("dvs")) : null;
		Ivs ivs = reader.has("ivs") ? Ivs.read(reader.element("ivs"), reader.path("ivs")) : null;
		String nick = reader.has("nick") ? name(reader, "nick") : null;
		String ot = reader.has("ot") ? name(reader, "ot") : null;
		Source source = Source.byId(reader.string("source"));
		if (source == null) {
			throw new DataException(reader.path("source"), "unknown source");
		}
		String nature = reader.has("nature") ? reader.string("nature", NATURE) : null;
		return new PartyGive(give, new Gen1Mon(dex, level, moves, dvs, ivs, MonDetails.read(reader, true)), nick, ot, source, nature);
	}

	/** A gift as before FP7 (no nature). */
	public PartyGive(String give, Gen1Mon mon, @Nullable String nick, @Nullable String ot, Source source) {
		this(give, mon, nick, ot, source, null);
	}

	/** A name field: a string of at most 32 characters (code points, as JSON Schema counts them). */
	static String name(DataReader reader, String field) throws DataException {
		String value = reader.string(field);
		if (value.codePointCount(0, value.length()) > MAX_NAME) {
			throw new DataException(reader.path(field), "too long");
		}
		return value;
	}
}
