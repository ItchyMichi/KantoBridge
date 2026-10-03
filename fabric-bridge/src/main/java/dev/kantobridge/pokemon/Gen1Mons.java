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
 * national dex number and moves by Gen 1 move number (= Showdown {@code num}), each with a by-id fallback from
 * {@link GenOneIds}. Moves are set slot by slot: Cobblemon's {@code moves=} property would keep modern default moves in
 * the free slots. Server thread only (it reads Cobblemon's registries).
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

	/** The Cobblemon species for a Gen 1 dex number, or null. */
	public static @Nullable Species species(int dex) {
		if (!GenOneIds.isDex(dex)) {
			return null;
		}
		Species species = PokemonSpecies.getByPokedexNumber(dex, GenOneIds.NAMESPACE);
		return species != null ? species
				: PokemonSpecies.getByIdentifier(ResourceLocation.fromNamespaceAndPath(GenOneIds.NAMESPACE, GenOneIds.speciesId(dex)));
	}

	/** The Cobblemon move for a Gen 1 move number, or null. */
	public static @Nullable MoveTemplate move(int number) {
		if (!GenOneIds.isMove(number)) {
			return null;
		}
		MoveTemplate move = Moves.getByNumericalId(number);
		return move != null ? move : Moves.getByName(GenOneIds.moveId(number));
	}

	/**
	 * A trainer's Pokémon (PLAN P11): the species at the level given, exactly the moves given, IVs of {@code 2·DV + 1}
	 * (HP from the derived HP DV), nature Hardy, the species' first regular ability, no held item, never shiny, full HP and
	 * PP.
	 */
	public static Pokemon trainerMon(Gen1Mon mon) throws Missing {
		Pokemon pokemon = base(mon);
		pokemon.setNature(Natures.HARDY);
		firstAbility(pokemon);
		pokemon.heal();
		return pokemon;
	}

	/**
	 * A Pokémon the hidden game gives the player: the species at the level given, the moves and DVs when given (else
	 * Cobblemon's defaults), Cobblemon's own nature and ability, no held item, never shiny, full HP and PP.
	 */
	public static Pokemon gift(Gen1Mon mon) throws Missing {
		Pokemon pokemon = base(mon);
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
		if (mon.dvs() != null) {
			setIvs(pokemon, mon.dvs());
		}
		if (!moves.isEmpty()) {
			setMoves(pokemon, moves);
		}
		return pokemon;
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
