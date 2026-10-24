package dev.kantobridge.pokemon;

import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.pokemon.evolution.Evolution;
import com.cobblemon.mod.common.api.pokemon.requirement.Requirement;
import com.cobblemon.mod.common.api.storage.party.PartyStore;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.OriginalTrainerType;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.evolution.variants.ItemInteractionEvolution;
import com.cobblemon.mod.common.pokemon.status.PersistentStatusContainer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kantobridge.proto.Ids;
import dev.kantobridge.world.Items;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * The {@code party.sync} body (protocol/messages/party.sync.schema.json): Cobblemon's party in slot order, which
 * gen1-bridge turns into the hidden game's shadow party, and how many Pokémon the PC holds. Cobblemon is authoritative
 * (CLAUDE.md "Ownership"). Values are clamped to the schema's ranges so the message is always valid.
 *
 * <p>FOREIGN_POKEMON_PLAN §3.2, §8 (FP1): each member also carries what a visitor record needs in a game that can't show
 * it as itself: its form (Cobblemon's Showdown form id; none for the standard form), its six stats, types and nature,
 * the original trainer's name when it isn't the owner, the machine moves its learnset allows ({@code canLearn}) and the
 * stones that would evolve it now ({@code stones}, the evolution rules of {@link PartyService} applied). A species with
 * no national number goes out as {@code dex: 0} (Q21) instead of being dropped.
 */
public final class PartySync {
	public static final int MAX_PARTY = 6;
	public static final int MAX_STAT = 9999;
	public static final int MAX_DEX = 2000;
	public static final int MAX_NAME_ID = 24;
	public static final int MAX_CAN_LEARN = 512;
	public static final int MAX_STONES = 16;
	/** A form, type or nature id as the protocol carries it (defs {@code form}, {@code typeName}, {@code nature}). */
	static final Pattern NAME_ID = Pattern.compile("^[a-z][a-z0-9_]{0,23}$");
	static final Pattern FORM_ID = Pattern.compile("^[a-z0-9_]{1,24}$");

	private PartySync() {
	}

	/** The message for a party and a PC's contents (no owner: no {@code stones}). */
	public static JsonObject of(PartyStore party, Iterable<Pokemon> pc) {
		return of(party, pc, null);
	}

	/** The message for a party and a PC's contents; {@code owner} decides the stones (their world, their region). */
	public static JsonObject of(PartyStore party, Iterable<Pokemon> pc, @Nullable ServerPlayer owner) {
		JsonArray list = new JsonArray();
		for (int slot = 0; slot < party.size() && list.size() < MAX_PARTY; slot++) {
			Pokemon pokemon = party.get(slot);
			if (pokemon != null) {
				list.add(entry(pokemon, owner));
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

	/** One party member, without an owner (no {@code stones}). */
	public static JsonObject entry(Pokemon pokemon) {
		return entry(pokemon, null);
	}

	/** One party member; a species with no national number (or one past the protocol's numbers) is {@code dex: 0}. */
	public static JsonObject entry(Pokemon pokemon, @Nullable ServerPlayer owner) {
		int dex = partyDex(pokemon.getSpecies().getNationalPokedexNumber());
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
		// MG9 (G2F): what a game with held items, shininess, genders and friendship keeps (Gen 2); the others ignore it
		details(pokemon).write(entry);
		// FP1: what a visitor record needs (FOREIGN_POKEMON_PLAN §3.2)
		visitorFields(pokemon, owner, entry);
		return entry;
	}

	/** A national dex number as {@code party.sync} carries it: 0 when there is none the protocol allows (Q21). */
	public static int partyDex(int national) {
		return national >= 1 && national <= MAX_DEX ? national : 0;
	}

	/** FP1: form, stats, types, nature, original trainer, machine moves and stones (each only when there is one). */
	static void visitorFields(Pokemon pokemon, @Nullable ServerPlayer owner, JsonObject entry) {
		// FP2 (§3.4): the species' name, which a visitor without a nickname shows
		String name = speciesName(pokemon.getSpecies().getName());
		if (name != null) {
			entry.addProperty("name", name);
		}
		FormData form = pokemon.getForm();
		String formId = form == pokemon.getSpecies().getStandardForm() ? null : formId(form.formOnlyShowdownId());
		if (formId != null) {
			entry.addProperty("form", formId);
		}
		JsonObject stats = new JsonObject();
		stats.addProperty("hp", stat(pokemon.getMaxHealth()));
		stats.addProperty("atk", stat(pokemon.getAttack()));
		stats.addProperty("def", stat(pokemon.getDefence()));
		stats.addProperty("spa", stat(pokemon.getSpecialAttack()));
		stats.addProperty("spd", stat(pokemon.getSpecialDefence()));
		stats.addProperty("spe", stat(pokemon.getSpeed()));
		entry.add("stats", stats);
		List<String> typeNames = new ArrayList<>();
		for (ElementalType type : pokemon.getTypes()) {
			String typeName = nameId(type.getName());
			if (typeName != null && !typeNames.contains(typeName) && typeNames.size() < 2) {
				typeNames.add(typeName);
			}
		}
		if (!typeNames.isEmpty()) {
			JsonArray types = new JsonArray();
			typeNames.forEach(types::add);
			entry.add("types", types);
		}
		String nature = nameId(pokemon.getNature().getName().getPath());
		if (nature != null) {
			entry.addProperty("nature", nature);
		}
		String ot = otName(pokemon.getOriginalTrainerType() == OriginalTrainerType.PLAYER, pokemon.getOriginalTrainer(),
				pokemon.getOriginalTrainerName(), pokemon.getOwnerUUID());
		if (ot != null) {
			entry.addProperty("ot", ot);
		}
		JsonArray canLearn = new JsonArray();
		canLearn(form.getMoves().getTmMoves()).forEach(canLearn::add);
		entry.add("canLearn", canLearn);
		if (owner != null) {
			JsonArray stones = new JsonArray();
			stones(pokemon, owner).forEach(stones::add);
			entry.add("stones", stones);
		}
	}

	/** A species' name as the protocol carries it: 1–32 characters, or null. */
	static @Nullable String speciesName(@Nullable String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		return name.codePointCount(0, name.length()) <= PartyGive.MAX_NAME ? name : name.substring(0, name.offsetByCodePoints(0, PartyGive.MAX_NAME));
	}

	/** A stat clamped to the schema's 1–9999. */
	static int stat(int value) {
		return Math.max(1, Math.min(MAX_STAT, value));
	}

	/** Cobblemon's form-only Showdown id (lower case, letters and digits) as the protocol's form id, or null. */
	static @Nullable String formId(@Nullable String showdown) {
		if (showdown == null) {
			return null;
		}
		String id = showdown.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
		if (id.length() > MAX_NAME_ID) {
			id = id.substring(0, MAX_NAME_ID);
		}
		return FORM_ID.matcher(id).matches() ? id : null;
	}

	/** A type's or nature's name as the protocol's lower-case id ({@code Fire} → {@code fire}), or null. */
	static @Nullable String nameId(@Nullable String name) {
		if (name == null) {
			return null;
		}
		String id = name.toLowerCase(Locale.ROOT);
		return NAME_ID.matcher(id).matches() ? id : null;
	}

	/**
	 * The original trainer's name to send, or null: none when there is no trainer, or when the trainer is a player who
	 * owns the Pokémon now (the plan sends {@code ot} only when it isn't the owner, Q2). At most 32 characters.
	 *
	 * @param player  the trainer is a player (Cobblemon keeps their uuid as text in {@code trainer})
	 * @param trainer Cobblemon's original trainer: a player's uuid, or an NPC's name
	 * @param name    the trainer's display name, when Cobblemon knows it
	 * @param owner   the owner's uuid
	 */
	static @Nullable String otName(boolean player, @Nullable String trainer, @Nullable String name, @Nullable UUID owner) {
		if (trainer == null || trainer.isBlank()) {
			return null;
		}
		if (player && owner != null && trainer.equalsIgnoreCase(owner.toString())) {
			return null;
		}
		String text = name != null && !name.isBlank() ? name : player ? null : trainer;
		if (text == null) {
			return null;
		}
		return text.codePointCount(0, text.length()) <= PartyGive.MAX_NAME ? text
				: text.substring(0, text.offsetByCodePoints(0, PartyGive.MAX_NAME));
	}

	/** The machine moves' numbers, distinct and sorted (so the sync's text is stable), at most 512. */
	static List<Integer> canLearn(@Nullable Iterable<MoveTemplate> moves) {
		TreeSet<Integer> numbers = new TreeSet<>();
		if (moves != null) {
			for (MoveTemplate move : moves) {
				int number = move == null ? 0 : move.getNum();
				if (number >= 1 && number <= MAX_DEX) {
					numbers.add(number);
				}
			}
		}
		List<Integer> out = new ArrayList<>(numbers);
		return out.size() <= MAX_CAN_LEARN ? out : out.subList(0, MAX_CAN_LEARN);
	}

	/**
	 * The catalogue's stones (protocol/items.json class {@code stone}) that would evolve this Pokémon now: an item
	 * evolution that takes the stone, its requirements met, and the evolution rules allowing the result
	 * ({@link PartyService#evolutionAllowed}, the same check a stone from the bag meets). In catalogue id order.
	 */
	static List<String> stones(Pokemon pokemon, ServerPlayer owner) {
		List<String> out = new ArrayList<>();
		for (Map.Entry<String, Items.Item> entry : new TreeMap<>(Items.bundled().entries()).entrySet()) {
			Items.Item item = entry.getValue();
			if (!"stone".equals(item.clazz()) || item.cobblemon() == null || out.size() >= MAX_STONES) {
				continue;
			}
			Item stone = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(GenOneIds.NAMESPACE, item.cobblemon()));
			if (stone == net.minecraft.world.item.Items.AIR) {
				continue;
			}
			ItemInteractionEvolution.ItemInteractionContext context = new ItemInteractionEvolution.ItemInteractionContext(new ItemStack(stone),
					owner.level());
			for (Evolution evolution : pokemon.getEvolutions()) {
				if (evolution instanceof ItemInteractionEvolution byItem && evolves(pokemon, byItem, context, owner)) {
					out.add(entry.getKey());
					break;
				}
			}
		}
		return out;
	}

	/** True when the stone fits, every requirement holds and the result is allowed; a requirement that fails to run is false. */
	private static boolean evolves(Pokemon pokemon, ItemInteractionEvolution evolution, ItemInteractionEvolution.ItemInteractionContext context,
			ServerPlayer owner) {
		try {
			if (!evolution.testContext(pokemon, context)) {
				return false;
			}
			for (Object requirement : evolution.getRequirements()) {
				if (requirement instanceof Requirement check && !check.check(pokemon)) {
					return false;
				}
			}
			return PartyService.evolutionAllowed(evolution, owner);
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** A Cobblemon Pokémon's details as the protocol carries them: always its shininess, gender and friendship. */
	public static MonDetails details(Pokemon pokemon) {
		String gender = switch (pokemon.getGender()) {
			case MALE -> "male";
			case FEMALE -> "female";
			default -> "genderless";
		};
		int friendship = Math.max(0, Math.min(MonDetails.MAX_FRIENDSHIP, pokemon.getFriendship()));
		return new MonDetails(Gen1Mons.heldItemId(pokemon), pokemon.getShiny(), gender, null, friendship);
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
