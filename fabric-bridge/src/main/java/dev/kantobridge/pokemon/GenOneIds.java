package dev.kantobridge.pokemon;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Gen 1 numbers and Cobblemon ids (docs/PLAN.md Appendix B). gen1-bridge sends species as national dex numbers and moves
 * as Gen 1 move ids, which equal Showdown's move numbers; Cobblemon looks both up by number
 * ({@code PokemonSpecies.getByPokedexNumber(n, "cobblemon")}, {@code Moves.getByNumericalId(n)}, see {@link Gen1Mons}).
 *
 * <p>The tables here are Cobblemon's and Showdown's public ids for those numbers (no game data). They let a GameTest check
 * that Cobblemon's registries still agree number for number, give the number lookups a by-name fallback, and turn the
 * engine's Gen 1 symbols into ids where the plain rule (lowercase, drop spaces, hyphens and dots) isn't enough. Plain
 * Java.
 */
public final class GenOneIds {
	public static final int MAX_DEX = 151;
	public static final int MAX_MOVE = 165;
	/** Cobblemon's species namespace. */
	public static final String NAMESPACE = "cobblemon";

	/** Index = national dex number - 1. */
	private static final List<String> SPECIES = List.of(
			"bulbasaur", "ivysaur", "venusaur", "charmander", "charmeleon", "charizard", "squirtle", "wartortle", "blastoise",
			"caterpie", "metapod", "butterfree", "weedle", "kakuna", "beedrill", "pidgey", "pidgeotto", "pidgeot", "rattata",
			"raticate", "spearow", "fearow", "ekans", "arbok", "pikachu", "raichu", "sandshrew", "sandslash", "nidoranf",
			"nidorina", "nidoqueen", "nidoranm", "nidorino", "nidoking", "clefairy", "clefable", "vulpix", "ninetales",
			"jigglypuff", "wigglytuff", "zubat", "golbat", "oddish", "gloom", "vileplume", "paras", "parasect", "venonat",
			"venomoth", "diglett", "dugtrio", "meowth", "persian", "psyduck", "golduck", "mankey", "primeape", "growlithe",
			"arcanine", "poliwag", "poliwhirl", "poliwrath", "abra", "kadabra", "alakazam", "machop", "machoke", "machamp",
			"bellsprout", "weepinbell", "victreebel", "tentacool", "tentacruel", "geodude", "graveler", "golem", "ponyta",
			"rapidash", "slowpoke", "slowbro", "magnemite", "magneton", "farfetchd", "doduo", "dodrio", "seel", "dewgong",
			"grimer", "muk", "shellder", "cloyster", "gastly", "haunter", "gengar", "onix", "drowzee", "hypno", "krabby",
			"kingler", "voltorb", "electrode", "exeggcute", "exeggutor", "cubone", "marowak", "hitmonlee", "hitmonchan",
			"lickitung", "koffing", "weezing", "rhyhorn", "rhydon", "chansey", "tangela", "kangaskhan", "horsea", "seadra",
			"goldeen", "seaking", "staryu", "starmie", "mrmime", "scyther", "jynx", "electabuzz", "magmar", "pinsir", "tauros",
			"magikarp", "gyarados", "lapras", "ditto", "eevee", "vaporeon", "jolteon", "flareon", "porygon", "omanyte",
			"omastar", "kabuto", "kabutops", "aerodactyl", "snorlax", "articuno", "zapdos", "moltres", "dratini", "dragonair",
			"dragonite", "mewtwo", "mew");

	/** Index = Gen 1 move id - 1 (= Showdown {@code num} - 1). */
	private static final List<String> MOVES = List.of(
			"pound", "karatechop", "doubleslap", "cometpunch", "megapunch", "payday", "firepunch", "icepunch", "thunderpunch",
			"scratch", "visegrip", "guillotine", "razorwind", "swordsdance", "cut", "gust", "wingattack", "whirlwind", "fly",
			"bind", "slam", "vinewhip", "stomp", "doublekick", "megakick", "jumpkick", "rollingkick", "sandattack", "headbutt",
			"hornattack", "furyattack", "horndrill", "tackle", "bodyslam", "wrap", "takedown", "thrash", "doubleedge",
			"tailwhip", "poisonsting", "twineedle", "pinmissile", "leer", "bite", "growl", "roar", "sing", "supersonic",
			"sonicboom", "disable", "acid", "ember", "flamethrower", "mist", "watergun", "hydropump", "surf", "icebeam",
			"blizzard", "psybeam", "bubblebeam", "aurorabeam", "hyperbeam", "peck", "drillpeck", "submission", "lowkick",
			"counter", "seismictoss", "strength", "absorb", "megadrain", "leechseed", "growth", "razorleaf", "solarbeam",
			"poisonpowder", "stunspore", "sleeppowder", "petaldance", "stringshot", "dragonrage", "firespin", "thundershock",
			"thunderbolt", "thunderwave", "thunder", "rockthrow", "earthquake", "fissure", "dig", "toxic", "confusion",
			"psychic", "hypnosis", "meditate", "agility", "quickattack", "rage", "teleport", "nightshade", "mimic", "screech",
			"doubleteam", "recover", "harden", "minimize", "smokescreen", "confuseray", "withdraw", "defensecurl", "barrier",
			"lightscreen", "haze", "reflect", "focusenergy", "bide", "metronome", "mirrormove", "selfdestruct", "eggbomb",
			"lick", "smog", "sludge", "boneclub", "fireblast", "waterfall", "clamp", "swift", "skullbash", "spikecannon",
			"constrict", "amnesia", "kinesis", "softboiled", "highjumpkick", "glare", "dreameater", "poisongas", "barrage",
			"leechlife", "lovelykiss", "skyattack", "transform", "bubble", "dizzypunch", "spore", "flash", "psywave", "splash",
			"acidarmor", "crabhammer", "explosion", "furyswipes", "bonemerang", "rest", "rockslide", "hyperfang", "sharpen",
			"conversion", "triattack", "superfang", "slash", "substitute", "struggle");

	/**
	 * Gen 1 names and engine symbols whose ids the plain rule can't produce (Appendix B): the gender signs, the
	 * apostrophe and dot are dropped by it, but these names also differ from the modern ids.
	 */
	private static final Map<String, String> SPECIAL_SPECIES = Map.of(
			"NIDORAN♀", "nidoranf", "NIDORAN_F", "nidoranf",
			"NIDORAN♂", "nidoranm", "NIDORAN_M", "nidoranm",
			"FARFETCH'D", "farfetchd", "FARFETCHD", "farfetchd",
			"MR.MIME", "mrmime", "MR_MIME", "mrmime");
	private static final Map<String, String> SPECIAL_MOVES = Map.of(
			"VICEGRIP", "visegrip", "VICE_GRIP", "visegrip",
			"HI JUMP KICK", "highjumpkick", "HI_JUMP_KICK", "highjumpkick",
			"PSYCHIC_M", "psychic");

	private GenOneIds() {
	}

	public static boolean isDex(int dex) {
		return dex >= 1 && dex <= MAX_DEX;
	}

	public static boolean isMove(int move) {
		return move >= 1 && move <= MAX_MOVE;
	}

	/** Cobblemon's species id (the path of {@code cobblemon:<id>}) for a Gen 1 dex number. */
	public static String speciesId(int dex) {
		if (!isDex(dex)) {
			throw new IllegalArgumentException("not a Gen 1 dex number");
		}
		return SPECIES.get(dex - 1);
	}

	/** Showdown's move id for a Gen 1 move number. */
	public static String moveId(int move) {
		if (!isMove(move)) {
			throw new IllegalArgumentException("not a Gen 1 move number");
		}
		return MOVES.get(move - 1);
	}

	/** The dex number of a Cobblemon species id, or 0 when it isn't one of the first 151. */
	public static int dexOf(String speciesId) {
		int index = SPECIES.indexOf(speciesId);
		return index < 0 ? 0 : index + 1;
	}

	/** The Gen 1 move number of a Showdown move id, or 0 when it isn't one of the first 165. */
	public static int moveOf(String moveId) {
		int index = MOVES.indexOf(moveId);
		return index < 0 ? 0 : index + 1;
	}

	/** A Gen 1 species name or engine symbol ({@code NIDORAN♀}, {@code MR.MIME}, {@code BULBASAUR}) as a species id, or null. */
	public static @Nullable String speciesIdForName(String name) {
		String upper = name.trim().toUpperCase(Locale.ROOT);
		String id = SPECIAL_SPECIES.getOrDefault(upper, plain(upper));
		return SPECIES.contains(id) ? id : null;
	}

	/** A Gen 1 move name or engine symbol ({@code VICEGRIP}, {@code HI JUMP KICK}, {@code PSYCHIC_M}) as a move id, or null. */
	public static @Nullable String moveIdForName(String name) {
		String upper = name.trim().toUpperCase(Locale.ROOT);
		String id = SPECIAL_MOVES.getOrDefault(upper, plain(upper));
		return MOVES.contains(id) ? id : null;
	}

	/** Lowercase without spaces, hyphens, underscores, dots and apostrophes: {@code SAND-ATTACK} → {@code sandattack}. */
	static String plain(String name) {
		return name.toLowerCase(Locale.ROOT).replaceAll("[\\s\\-_.'’]", "");
	}
}
