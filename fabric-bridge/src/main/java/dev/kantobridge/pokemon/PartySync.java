package dev.kantobridge.pokemon;

import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.api.storage.party.PartyStore;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.status.PersistentStatusContainer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.proto.Ids;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

/**
 * The {@code party.sync} body (protocol/messages/party.sync.schema.json): Cobblemon's party in slot order, which
 * gen1-bridge turns into the hidden game's shadow party, and how many Pokémon the PC holds. Cobblemon is authoritative
 * (CLAUDE.md "Ownership"). Values are clamped to the schema's ranges so the message is always valid.
 */
public final class PartySync {
	public static final int MAX_PARTY = 6;
	public static final int MAX_STAT = 9999;
	public static final int MAX_DEX = 2000;

	private PartySync() {
	}

	/** The message for a party and a PC's contents. */
	public static JsonObject of(PartyStore party, Iterable<Pokemon> pc) {
		JsonArray list = new JsonArray();
		for (int slot = 0; slot < party.size() && list.size() < MAX_PARTY; slot++) {
			Pokemon pokemon = party.get(slot);
			JsonObject entry = pokemon == null ? null : entry(pokemon);
			if (entry != null) {
				list.add(entry);
			}
		}
		int count = 0;
		for (Pokemon ignored : pc) {
			count++;
		}
		JsonObject box = new JsonObject();
		box.addProperty("count", count);
		JsonObject data = new JsonObject();
		data.add("party", list);
		data.add("box", box);
		return data;
	}

	/** One party member, or null for a species outside the national dex numbers the protocol allows. */
	public static @Nullable JsonObject entry(Pokemon pokemon) {
		int dex = pokemon.getSpecies().getNationalPokedexNumber();
		if (dex < 1 || dex > MAX_DEX) {
			return null;
		}
		JsonObject entry = new JsonObject();
		entry.addProperty("uid", pokemon.getUuid().toString());
		// The party.give id a gift came with, so gen1-bridge matches its pending gift exactly (not by dex and level).
		String give = pokemon.getPersistentData().getString(PartyService.GIVE_KEY);
		if (Ids.ID.matcher(give).matches()) {
			entry.addProperty("give", give);
		}
		entry.addProperty("dex", dex);
		entry.addProperty("level", Math.max(1, Math.min(100, pokemon.getLevel())));
		String nick = nickname(pokemon.getNickname());
		if (nick != null) {
			entry.addProperty("nick", nick);
		}
		JsonArray moves = new JsonArray();
		for (Move move : pokemon.getMoveSet().getMovesWithNulls()) {
			int number = move == null ? 0 : move.getTemplate().getNum();
			if (number >= 1 && number <= MAX_DEX && moves.size() < 4) {
				moves.add(number);
			}
		}
		entry.add("moves", moves);
		int maxHp = Math.max(1, Math.min(MAX_STAT, pokemon.getMaxHealth()));
		entry.addProperty("hp", Math.max(0, Math.min(maxHp, pokemon.getCurrentHealth())));
		entry.addProperty("maxhp", maxHp);
		PersistentStatusContainer status = pokemon.getStatus();
		String gen1Status = status == null ? null : gen1Status(status.getStatus().getShowdownName());
		if (gen1Status != null) {
			entry.addProperty("status", gen1Status);
		}
		return entry;
	}

	/** Cobblemon's persistent status (Showdown names) as Gen 1's: bad poison is plain poison outside battle. */
	public static @Nullable String gen1Status(@Nullable String showdown) {
		if (showdown == null) {
			return null;
		}
		return switch (showdown) {
			case "psn", "tox" -> "psn";
			case "brn", "frz", "par", "slp" -> showdown;
			default -> null;
		};
	}

	/** A nickname as plain text, at most 32 characters, or null when there is none. */
	static @Nullable String nickname(@Nullable MutableComponent nickname) {
		if (nickname == null) {
			return null;
		}
		String text = nickname.getString();
		if (text.isEmpty()) {
			return null;
		}
		return text.codePointCount(0, text.length()) <= PartyGive.MAX_NAME ? text
				: text.substring(0, text.offsetByCodePoints(0, PartyGive.MAX_NAME));
	}
}
