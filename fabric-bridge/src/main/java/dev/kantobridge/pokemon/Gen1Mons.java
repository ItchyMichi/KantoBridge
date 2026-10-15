package dev.kantobridge.pokemon;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.abilities.PotentialAbility;
import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.api.moves.MoveSet;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.api.pokemon.Natures;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import java.util.ArrayList;
import java.util.List;
import kotlin.Unit;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Builds Cobblemon Pokémon from the hidden game's numbers (docs/PLAN.md U3, P11, Appendix B). Species are looked up by
 * national dex number and moves by Showdown {@code num} (= Gen 1's move numbers for Gen 1's moves), each with a by-id
 * fallback: {@link NationalDexIds} for the first 386 species, {@link GenOneIds} for Gen 1's moves. Moves are set slot by
 * slot: Cobblemon's {@code moves=} property would keep modern default moves in the free slots. Server thread only (it
 * reads Cobblemon's registries).
 */
public final class Gen1Mons {
	/** Cobblemon lacks a species or move the hidden game asked for; the code says which, never the value. */
	public static final class Missing extends Exception {
		private final String code;

		public Missing(String code) {
			super(code, null, false, false);
			this.code = code;
		}

		public String code() {
			return this.code;
		}
	}

	private Gen1Mons() {
	}

	/** The Cobblemon species for a national dex number (MG7: any game's), or null. */
	public static @Nullable Species species(int dex) {
		if (!Gen1Mon.isNational(dex)) {
			return null;
		}
		Species species = PokemonSpecies.getByPokedexNumber(dex, GenOneIds.NAMESPACE);
		if (species != null || !NationalDexIds.isDex(dex)) {
			return species;
		}
		return PokemonSpecies.getByIdentifier(ResourceLocation.fromNamespaceAndPath(GenOneIds.NAMESPACE, NationalDexIds.speciesId(dex)));
	}

	/** The Cobblemon move for a move number (Showdown's; MG7: any game's), or null. */
	public static @Nullable MoveTemplate move(int number) {
		if (!Gen1Mon.isMoveNum(number)) {
			return null;
		}
		MoveTemplate move = Moves.getByNumericalId(number);
		return move != null || !GenOneIds.isMove(number) ? move : Moves.getByName(GenOneIds.moveId(number));
	}

	/**
	 * A trainer's Pokémon (PLAN P11): the species at the level given, exactly the moves given, IVs of {@code 2·DV + 1}
	 * (HP from the derived HP DV) or the IVs given (Gen 3), nature Hardy, the species' first regular ability, no held item,
	 * never shiny, full HP and PP.
	 */
	public static Pokemon trainerMon(Gen1Mon mon) throws Missing {
		Pokemon pokemon = base(mon);
		pokemon.setNature(Natures.HARDY);
		firstAbility(pokemon);
		pokemon.heal();
		return pokemon;
	}

	/**
	 * A Pokémon the hidden game gives the player: the species at the level given, the moves and DVs or IVs when given (else
	 * Cobblemon's defaults), Cobblemon's own nature and ability, no held item, never shiny, full HP and PP.
	 */
	public static Pokemon gift(Gen1Mon mon) throws Missing {
		Pokemon pokemon = base(mon);
		pokemon.heal();
		return pokemon;
	}

	/**
	 * A wild Pokémon of a static, surfing or fishing encounter: the species at the level given, Cobblemon's own moves for
	 * that level (Gen 1 gives wild Pokémon their latest level-up moves too), random IVs, nature and ability, never shiny,
	 * no held item, full HP and PP.
	 */
	public static Pokemon wild(int dex, int level) throws Missing {
		return wild(dex, level, null, MonDetails.NONE);
	}

	/** MG9 (G2F): a wild Pokémon with the DVs and details its game gave it (Gen 2's: a shiny red Gyarados is shiny). */
	public static Pokemon wild(int dex, int level, @Nullable Dvs dvs, MonDetails details) throws Missing {
		Pokemon pokemon = base(new Gen1Mon(dex, level, List.of(), dvs, null, details));
		pokemon.heal();
		return pokemon;
	}

	private static Pokemon base(Gen1Mon mon) throws Missing {
		Species species = species(mon.dex());
		if (species == null) {
			throw new Missing("species");
		}
		List<MoveTemplate> moves = new ArrayList<>(mon.moves().size());
		for (int number : mon.moves()) {
			MoveTemplate move = move(number);
			if (move == null) {
				throw new Missing("move");
			}
			moves.add(move);
		}
		Pokemon pokemon = species.create(mon.level());
		// Appendix C: no Gen 1 shinies, whatever the configured rate rolled.
		pokemon.setShiny(false);
		if (!pokemon.heldItem().isEmpty()) {
			pokemon.removeHeldItem();
		}
		if (mon.ivs() != null) {
			setIvs(pokemon, mon.ivs());
		} else if (mon.dvs() != null) {
			setIvs(pokemon, mon.dvs());
		}
		if (!moves.isEmpty()) {
			setMoves(pokemon, moves);
		}
		applyDetails(pokemon, mon.details());
		return pokemon;
	}

	/**
	 * MG9 (G2F): what the game says of a Pokémon beyond its numbers: shininess and gender as said, the held item through
	 * the item catalogue (its Cobblemon item, when Cobblemon has one), friendship. A form (Unown's letter) is carried but
	 * not applied: Cobblemon's Unown has no letters. Anything Cobblemon refuses is left as it was.
	 */
	public static void applyDetails(Pokemon pokemon, MonDetails details) {
		if (details == null || details.isEmpty()) {
			return;
		}
		if (details.shiny() != null) {
			pokemon.setShiny(details.shiny());
		}
		if (details.gender() != null) {
			try {
				pokemon.setGender(switch (details.gender()) {
					case "male" -> com.cobblemon.mod.common.pokemon.Gender.MALE;
					case "female" -> com.cobblemon.mod.common.pokemon.Gender.FEMALE;
					default -> com.cobblemon.mod.common.pokemon.Gender.GENDERLESS;
				});
			} catch (RuntimeException e) {
				// a gender the species can't have: Cobblemon's own stays
			}
		}
		net.minecraft.world.item.Item held = heldItem(details.item());
		if (held != null) {
			pokemon.swapHeldItem(new net.minecraft.world.item.ItemStack(held), false, false);
		}
		if (details.friendship() != null) {
			pokemon.setFriendship(details.friendship(), true);
		}
	}

	/** The Cobblemon item a catalogue id is mirrored as, or null (no id, none in the catalogue, none in Cobblemon). */
	public static net.minecraft.world.item.@Nullable Item heldItem(@Nullable String id) {
		String path = dev.kantobridge.world.Items.cobblemonPath(id);
		if (path == null) {
			return null;
		}
		return net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(ResourceLocation.fromNamespaceAndPath(GenOneIds.NAMESPACE, path))
				.orElse(null);
	}

	/** The catalogue id of the Cobblemon item a Pokémon holds, or null (none, or not one the catalogue maps). */
	public static @Nullable String heldItemId(Pokemon pokemon) {
		net.minecraft.world.item.ItemStack stack = pokemon.heldItem();
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
		if (!GenOneIds.NAMESPACE.equals(key.getNamespace())) {
			return null;
		}
		return dev.kantobridge.world.Items.bundled().byCobblemon(key.getPath());
	}

	/** Replaces the whole moveset with {@code moves} in slot order (full PP); the other slots stay empty. */
	public static void setMoves(Pokemon pokemon, List<MoveTemplate> moves) {
		if (moves.size() > MoveSet.MOVE_COUNT) {
			throw new IllegalArgumentException("more than four moves");
		}
		MoveSet moveSet = pokemon.getMoveSet();
		moveSet.doWithoutEmitting(() -> {
			moveSet.clear();
			for (int i = 0; i < moves.size(); i++) {
				moveSet.setMove(i, moves.get(i).create());
			}
			return Unit.INSTANCE;
		});
		moveSet.update();
	}

	/** IVs from Gen 1 DVs (PLAN P11). */
	public static void setIvs(Pokemon pokemon, Dvs dvs) {
		for (Dvs.Stat stat : Dvs.Stat.values()) {
			pokemon.setIV(cobblemonStat(stat), dvs.ivFor(stat));
		}
	}

	/** IVs as Gen 3 keeps them: the same scale as Cobblemon's, so unchanged. */
	public static void setIvs(Pokemon pokemon, Ivs ivs) {
		for (Dvs.Stat stat : Dvs.Stat.values()) {
			pokemon.setIV(cobblemonStat(stat), ivs.ivFor(stat));
		}
	}

	/** Cobblemon's stat for one of {@link Dvs}' stats. */
	public static Stats cobblemonStat(Dvs.Stat stat) {
		return switch (stat) {
			case HP -> Stats.HP;
			case ATTACK -> Stats.ATTACK;
			case DEFENCE -> Stats.DEFENCE;
			case SPECIAL_ATTACK -> Stats.SPECIAL_ATTACK;
			case SPECIAL_DEFENCE -> Stats.SPECIAL_DEFENCE;
			case SPEED -> Stats.SPEED;
		};
	}

	/**
	 * Gives the species' first regular ability: the first entry of Cobblemon's lowest-priority group, which keeps the
	 * species file's order (hidden abilities sit in a higher group).
	 */
	public static void firstAbility(Pokemon pokemon) {
		PotentialAbility first = firstRegularAbility(pokemon);
		if (first != null) {
			pokemon.updateAbility(first.getTemplate().create(false, Priority.LOWEST));
		}
	}

	/** The species' first regular ability, or null when the form lists none. */
	public static @Nullable PotentialAbility firstRegularAbility(Pokemon pokemon) {
		List<PotentialAbility> regular = pokemon.getForm().getAbilities().getMapping().get(Priority.LOWEST);
		return regular == null || regular.isEmpty() ? null : regular.get(0);
	}

	/** The Showdown numbers of a Pokémon's moves in slot order (= Gen 1 move numbers for Gen 1 moves). */
	public static List<Integer> moveNumbers(Pokemon pokemon) {
		List<Integer> numbers = new ArrayList<>(MoveSet.MOVE_COUNT);
		for (Move move : pokemon.getMoveSet().getMovesWithNulls()) {
			if (move != null) {
				numbers.add(move.getTemplate().getNum());
			}
		}
		return numbers;
	}
}
