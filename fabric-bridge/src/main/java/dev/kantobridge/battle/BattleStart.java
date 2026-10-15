package dev.kantobridge.battle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.pokemon.Dvs;
import dev.kantobridge.pokemon.Gen1Mon;
import dev.kantobridge.pokemon.MonDetails;
import dev.kantobridge.proto.Ids;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * A {@code battle.start} as the server receives it (protocol/messages/battle.start.schema.json), checked strictly: the
 * relay checks envelopes, not schemas, and the data came from a client. Problems are reported as a JSON path and a
 * short reason ({@link DataException}), never a value: trainer teams and names are ROM-derived. Plain Java.
 *
 * @param battle  gen1-bridge's correlation id, echoed in {@code battle.result}
 * @param trainer required for trainer and rival battles
 * @param team    a trainer's (or a scripted battle's) Pokémon, 1–6; empty for wild kinds
 * @param wild    required for static, surf and fish encounters
 */
public record BattleStart(String battle, Kind kind, @Nullable Trainer trainer, List<Gen1Mon> team, @Nullable Wild wild, boolean canCatch,
		boolean canFlee) {
	static final int MAX_COORD = 4096;
	static final int MAX_NAME = 32;
	public static final int MAX_TEAM = 6;
	/** A trainer's party index (Gen 1, within its class) or id (Gen 3: Emerald's reach 854). */
	public static final int MAX_PARTY = 1023;

	private static final Set<String> FIELDS = Set.of("battle", "kind", "trainer", "team", "wild", "rules", "region");
	private static final Set<String> TRAINER_FIELDS = Set.of("cls", "party", "name", "npc", "map", "x", "y");
	private static final Set<String> WILD_FIELDS = Set.of("dex", "level", "x", "y", "dvs", "item", "shiny", "gender", "form");
	private static final Set<String> RULE_FIELDS = Set.of("catch", "flee");

	/** The battle kinds of the protocol. */
	public enum Kind {
		TRAINER, RIVAL, SCRIPTED, STATIC, SURF, FISH;

		public String id() {
			return this.name().toLowerCase(Locale.ROOT);
		}

		static @Nullable Kind byId(String id) {
			for (Kind kind : values()) {
				if (kind.id().equals(id)) {
					return kind;
				}
			}
			return null;
		}
	}

	/**
	 * The trainer: class symbol ({@code OPP_BROCK} on Gen 1, {@code TRAINER_CLASS_LEADER} on Gen 3), party index or trainer
	 * id, and optionally the display name (ROM-derived), the NPC id and its cell.
	 */
	public record Trainer(String cls, int party, @Nullable String name, @Nullable String npc, @Nullable String map, @Nullable Integer x,
			@Nullable Integer y) {
		/** True when the trainer's cell is known (map, x and y). */
		public boolean hasCell() {
			return this.map != null && this.x != null && this.y != null;
		}
	}

	/**
	 * A wild encounter's Pokémon and, optionally, its cell on the player's map; MG9 (G2F): its DVs and details where the
	 * game gives them (Gen 2), so Cobblemon's matches its stats, gender and shininess.
	 */
	public record Wild(int dex, int level, @Nullable Integer x, @Nullable Integer y, @Nullable Dvs dvs, MonDetails details) {
		public Wild {
			details = details == null ? MonDetails.NONE : details;
		}

		public Wild(int dex, int level, @Nullable Integer x, @Nullable Integer y) {
			this(dex, level, x, y, null, MonDetails.NONE);
		}
	}

	public BattleStart {
		team = List.copyOf(team);
	}

	/** True when a team (rather than a wild Pokémon) is to be fought. */
	public boolean hasTeam() {
		return !this.team.isEmpty();
	}

	/** The message's {@code battle} id when it is well formed (so even a broken message can be answered), else null. */
	public static @Nullable String idOf(JsonObject data) {
		JsonElement id = data.get("battle");
		if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) {
			return null;
		}
		String value = id.getAsString();
		return Ids.ID.matcher(value).matches() ? value : null;
	}

	/** Reads and checks a whole {@code battle.start} body. */
	public static BattleStart read(JsonObject data) throws DataException {
		DataReader reader = DataReader.of(data, "").allowOnly(FIELDS);
		String battle = reader.string("battle", Ids.ID);
		Kind kind = Kind.byId(reader.string("kind"));
		if (kind == null) {
			throw new DataException(reader.path("kind"), "unknown kind");
		}
		Trainer trainer = reader.has("trainer") ? trainer(reader.object("trainer")) : null;
		List<Gen1Mon> team = reader.has("team") ? team(reader.array("team"), reader.path("team")) : List.of();
		Wild wild = reader.has("wild") ? wild(reader.object("wild")) : null;
		DataReader rules = reader.object("rules").allowOnly(RULE_FIELDS);
		boolean canCatch = rules.bool("catch");
		boolean canFlee = rules.bool("flee");
		switch (kind) {
			case TRAINER, RIVAL -> {
				if (trainer == null || team.isEmpty()) {
					throw new DataException(reader.path("team"), "a trainer battle needs trainer and team");
				}
			}
			case STATIC, SURF, FISH -> {
				if (wild == null) {
					throw new DataException(reader.path("wild"), "an encounter needs wild");
				}
			}
			case SCRIPTED -> {
				if (team.isEmpty() && wild == null) {
					throw new DataException(reader.path("team"), "a scripted battle needs team or wild");
				}
			}
		}
		return new BattleStart(battle, kind, trainer, team, wild, canCatch, canFlee);
	}

	private static Trainer trainer(DataReader reader) throws DataException {
		reader.allowOnly(TRAINER_FIELDS);
		String cls = reader.string("cls", Ids.SYMBOL);
		int party = reader.integer("party", 0, MAX_PARTY);
		String name = null;
		if (reader.has("name")) {
			name = reader.string("name");
			if (name.codePointCount(0, name.length()) > MAX_NAME) {
				throw new DataException(reader.path("name"), "too long");
			}
		}
		String npc = reader.has("npc") ? reader.string("npc", Ids.NPC_ID) : null;
		String map = reader.has("map") ? reader.string("map", DataReader.MAP_ID) : null;
		Integer x = reader.has("x") ? reader.integer("x", -MAX_COORD, MAX_COORD) : null;
		Integer y = reader.has("y") ? reader.integer("y", -MAX_COORD, MAX_COORD) : null;
		return new Trainer(cls, party, name, npc, map, x, y);
	}

	private static List<Gen1Mon> team(JsonArray array, String path) throws DataException {
		if (array.isEmpty() || array.size() > MAX_TEAM) {
			throw new DataException(path, "needs 1-6 Pokémon");
		}
		List<Gen1Mon> team = new ArrayList<>(array.size());
		for (int i = 0; i < array.size(); i++) {
			team.add(Gen1Mon.readTeamMember(array.get(i), path + "/" + i));
		}
		return team;
	}

	private static Wild wild(DataReader reader) throws DataException {
		reader.allowOnly(WILD_FIELDS);
		int dex = reader.integer("dex", 1, Gen1Mon.MAX_NATIONAL);
		int level = reader.integer("level", 1, Gen1Mon.MAX_LEVEL);
		Integer x = reader.has("x") ? reader.integer("x", -MAX_COORD, MAX_COORD) : null;
		Integer y = reader.has("y") ? reader.integer("y", -MAX_COORD, MAX_COORD) : null;
		Dvs dvs = reader.has("dvs") ? Dvs.read(reader.element("dvs"), reader.path("dvs")) : null;
		return new Wild(dex, level, x, y, dvs, MonDetails.read(reader, false));
	}
}
