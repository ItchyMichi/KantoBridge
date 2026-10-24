package dev.kantobridge.pokemon;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.Ids;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A {@code party.item} as the server receives it (protocol/messages/party.item.schema.json): the hidden game used an item
 * from its bag on a party Pokémon, and Cobblemon must do the same (docs/PLAN.md §4.4 {@code PartyService}, P13, P14,
 * Appendix B). Checked strictly; problems are a JSON path and a reason. Plain Java.
 *
 * FOREIGN_POKEMON_PLAN §4.4 (FP1): an {@code id} (Cobblemon applies an id once, FP4), the item's {@code kind} (absent:
 * decided from the item and the move, as before) and the move a machine replaced ({@code forget}, by number).
 *
 * @param item     the game's item symbol
 * @param uid      the Pokémon's Cobblemon uuid
 * @param slot     TM/HM: the move slot replaced (0–3); absent when a slot was free
 * @param move     TM/HM: the move number taught (Showdown's)
 * @param id       the sender's id, or null (older senders)
 * @param sentKind the {@code kind} the sender said, or null
 * @param forget   TM/HM: the move number replaced, or null
 */
public record PartyItem(String item, UUID uid, @Nullable Integer slot, @Nullable Integer move, @Nullable String id, @Nullable String sentKind,
		@Nullable Integer forget) {
	private static final Set<String> FIELDS = Set.of("item", "uid", "slot", "move", "region", "id", "kind", "forget");
	/** The protocol's kinds (party.item.schema.json). */
	public static final Set<String> KINDS = Set.of("stone", "machine", "candy", "medicine", "pp", "vitamin", "other");
	public static final String RARE_CANDY = "RARE_CANDY";
	/** The evolution stones (Gen 1's five, Appendix B; Gen 3 adds the Sun Stone) and Cobblemon's item paths for them. */
	public static final Map<String, String> STONES = Map.of(
			"FIRE_STONE", "fire_stone",
			"WATER_STONE", "water_stone",
			"THUNDER_STONE", "thunder_stone",
			"LEAF_STONE", "leaf_stone",
			"MOON_STONE", "moon_stone",
			"SUN_STONE", "sun_stone");

	/** What the item does in Cobblemon. */
	public enum Kind {
		/** Evolves the Pokémon at once (P14: stones from the Gen 1 bag evolve at once). */
		STONE,
		/** A TM or HM: teaches {@code move}, into {@code slot} or a free slot. */
		MACHINE,
		/** One level up. */
		RARE_CANDY,
		/** Nothing Cobblemon needs to do. */
		OTHER
	}

	public PartyItem(String item, UUID uid, @Nullable Integer slot, @Nullable Integer move) {
		this(item, uid, slot, move, null, null, null);
	}

	/**
	 * What Cobblemon does with it: a machine with a move teaches, a stone in the table evolves, Rare Candy levels; a
	 * sent kind Cobblemon has no rule for yet (medicine, pp, vitamin, other; FP4) is {@link Kind#OTHER}.
	 */
	public Kind kind() {
		if (this.sentKind != null && !this.sentKind.equals("stone") && !this.sentKind.equals("machine") && !this.sentKind.equals("candy")) {
			return Kind.OTHER;
		}
		if (this.move != null) {
			return Kind.MACHINE;
		}
		if (STONES.containsKey(this.item)) {
			return Kind.STONE;
		}
		return RARE_CANDY.equals(this.item) ? Kind.RARE_CANDY : Kind.OTHER;
	}

	public static PartyItem read(JsonObject data) throws DataException {
		DataReader reader = DataReader.of(data, "").allowOnly(FIELDS);
		String item = reader.string("item", Ids.ITEM_ID);
		UUID uid = UUID.fromString(reader.string("uid", Ids.UID));
		Integer slot = reader.has("slot") ? reader.integer("slot", 0, 3) : null;
		Integer move = reader.has("move") ? reader.integer("move", 1, Gen1Mon.MAX_MOVE_NUM) : null;
		if (slot != null && move == null) {
			throw new DataException(reader.path("slot"), "a slot needs a move");
		}
		String id = reader.has("id") ? reader.string("id", Ids.ID) : null;
		String kind = null;
		if (reader.has("kind")) {
			kind = reader.string("kind");
			if (!KINDS.contains(kind)) {
				throw new DataException(reader.path("kind"), "unknown kind");
			}
		}
		Integer forget = reader.has("forget") ? reader.integer("forget", 1, Gen1Mon.MAX_MOVE_NUM) : null;
		if (forget != null && move == null) {
			throw new DataException(reader.path("forget"), "forget needs a move");
		}
		return new PartyItem(item, uid, slot, move, id, kind, forget);
	}
}
