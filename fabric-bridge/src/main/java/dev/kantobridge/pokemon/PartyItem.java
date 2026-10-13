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
 * @param item the Gen 1 item symbol
 * @param uid  the Pokémon's Cobblemon uuid
 * @param slot TM/HM: the move slot replaced (0–3); absent when a slot was free
 * @param move TM/HM: the Gen 1 move number taught
 */
public record PartyItem(String item, UUID uid, @Nullable Integer slot, @Nullable Integer move) {
	private static final Set<String> FIELDS = Set.of("item", "uid", "slot", "move", "region");
	public static final String RARE_CANDY = "RARE_CANDY";
	/** Gen 1's evolution stones and Cobblemon's item paths for them (Appendix B). */
	public static final Map<String, String> STONES = Map.of(
			"FIRE_STONE", "fire_stone",
			"WATER_STONE", "water_stone",
			"THUNDER_STONE", "thunder_stone",
			"LEAF_STONE", "leaf_stone",
			"MOON_STONE", "moon_stone");

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

	public Kind kind() {
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
		Integer move = reader.has("move") ? reader.integer("move", 1, GenOneIds.MAX_MOVE) : null;
		if (slot != null && move == null) {
			throw new DataException(reader.path("slot"), "a slot needs a move");
		}
		return new PartyItem(item, uid, slot, move);
	}
}
