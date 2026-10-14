package dev.kantobridge.pokemon;

import java.util.List;

/**
 * Cobblemon's species ids for national dex numbers 1 to 386, the first three generations (MG7, docs/specs/GEN3_BRIDGE_PLAN.md
 * §6 "Habitats"). Gen 1's 151 come from {@link GenOneIds}; numbers 152 to 386 are Cobblemon 1.8.1's own species file names
 * ({@code data/cobblemon/species/generation2} and {@code generation3}, read from its jar: public mod data, no game data). A
 * GameTest checks that Cobblemon's registry still agrees number for number. Plain Java.
 */
public final class NationalDexIds {
	/** The highest national dex number a Gen 3 game uses (Deoxys). */
	public static final int MAX_DEX = 386;

	/** Index = national dex number - 152. */
	private static final List<String> LATER = List.of(
			"chikorita", "bayleef", "meganium", "cyndaquil", "quilava", "typhlosion", "totodile", "croconaw", "feraligatr",
			"sentret", "furret", "hoothoot", "noctowl", "ledyba", "ledian", "spinarak", "ariados", "crobat", "chinchou",
			"lanturn", "pichu", "cleffa", "igglybuff", "togepi", "togetic", "natu", "xatu", "mareep", "flaaffy", "ampharos",
			"bellossom", "marill", "azumarill", "sudowoodo", "politoed", "hoppip", "skiploom", "jumpluff", "aipom", "sunkern",
			"sunflora", "yanma", "wooper", "quagsire", "espeon", "umbreon", "murkrow", "slowking", "misdreavus", "unown",
			"wobbuffet", "girafarig", "pineco", "forretress", "dunsparce", "gligar", "steelix", "snubbull", "granbull",
			"qwilfish", "scizor", "shuckle", "heracross", "sneasel", "teddiursa", "ursaring", "slugma", "magcargo", "swinub",
			"piloswine", "corsola", "remoraid", "octillery", "delibird", "mantine", "skarmory", "houndour", "houndoom", "kingdra",
			"phanpy", "donphan", "porygon2", "stantler", "smeargle", "tyrogue", "hitmontop", "smoochum", "elekid", "magby",
			"miltank", "blissey", "raikou", "entei", "suicune", "larvitar", "pupitar", "tyranitar", "lugia", "hooh", "celebi",
			"treecko", "grovyle", "sceptile", "torchic", "combusken", "blaziken", "mudkip", "marshtomp", "swampert", "poochyena",
			"mightyena", "zigzagoon", "linoone", "wurmple", "silcoon", "beautifly", "cascoon", "dustox", "lotad", "lombre",
			"ludicolo", "seedot", "nuzleaf", "shiftry", "taillow", "swellow", "wingull", "pelipper", "ralts", "kirlia",
			"gardevoir", "surskit", "masquerain", "shroomish", "breloom", "slakoth", "vigoroth", "slaking", "nincada", "ninjask",
			"shedinja", "whismur", "loudred", "exploud", "makuhita", "hariyama", "azurill", "nosepass", "skitty", "delcatty",
			"sableye", "mawile", "aron", "lairon", "aggron", "meditite", "medicham", "electrike", "manectric", "plusle", "minun",
			"volbeat", "illumise", "roselia", "gulpin", "swalot", "carvanha", "sharpedo", "wailmer", "wailord", "numel",
			"camerupt", "torkoal", "spoink", "grumpig", "spinda", "trapinch", "vibrava", "flygon", "cacnea", "cacturne", "swablu",
			"altaria", "zangoose", "seviper", "lunatone", "solrock", "barboach", "whiscash", "corphish", "crawdaunt", "baltoy",
			"claydol", "lileep", "cradily", "anorith", "armaldo", "feebas", "milotic", "castform", "kecleon", "shuppet",
			"banette", "duskull", "dusclops", "tropius", "chimecho", "absol", "wynaut", "snorunt", "glalie", "spheal", "sealeo",
			"walrein", "clamperl", "huntail", "gorebyss", "relicanth", "luvdisc", "bagon", "shelgon", "salamence", "beldum",
			"metang", "metagross", "regirock", "regice", "registeel", "latias", "latios", "kyogre", "groudon", "rayquaza",
			"jirachi", "deoxys");

	private NationalDexIds() {
	}

	public static boolean isDex(int dex) {
		return dex >= 1 && dex <= MAX_DEX;
	}

	/** Cobblemon's species id (the path of {@code cobblemon:<id>}) for a national dex number up to 386. */
	public static String speciesId(int dex) {
		if (!isDex(dex)) {
			throw new IllegalArgumentException("not a national dex number up to 386");
		}
		return dex <= GenOneIds.MAX_DEX ? GenOneIds.speciesId(dex) : LATER.get(dex - GenOneIds.MAX_DEX - 1);
	}

	/** The dex number of a Cobblemon species id, or 0 when it isn't one of the first 386. */
	public static int dexOf(String speciesId) {
		int gen1 = GenOneIds.dexOf(speciesId);
		if (gen1 > 0) {
			return gen1;
		}
		int index = LATER.indexOf(speciesId);
		return index < 0 ? 0 : index + GenOneIds.MAX_DEX + 1;
	}
}
