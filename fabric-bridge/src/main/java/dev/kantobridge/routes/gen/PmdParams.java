package dev.kantobridge.routes.gen;

/**
 * The knobs of a dungeon floor (docs/research/ROUTES_RESEARCH.md §5.1): PMD's standard floor, re-implemented from the
 * user's report.
 *
 * @param roomDensity   rooms to aim for (plus 0–2)
 * @param connectivity  steps of the walk that links the grid's cells
 * @param allowDeadEnds keep corridor dead ends instead of linking them on
 * @param extraHallways tries at an extra tunnelling hallway, which closes loops
 * @param shopChance    percent chance of a shop room (a Mart stall here)
 * @param houseChance   percent chance of a Monster House when there's no shop
 * @param structures    room structures allowed (pools, islands, dividers)
 * @param rivers        rivers and lakes of water
 * @param lakes         extra lakes
 * @param items         item balls to aim for
 * @param traps         traps to aim for; Gen 1 has none, so the default is 0
 * @param enemies       wild Pokémon spots to aim for
 */
public record PmdParams(int roomDensity, int connectivity, boolean allowDeadEnds, int extraHallways, int shopChance,
		int houseChance, int structures, boolean rivers, int lakes, int items, int traps, int enemies) {
	public PmdParams {
		if (roomDensity < 0 || roomDensity > 250 || connectivity < 0 || extraHallways < 0 || shopChance < 0
				|| houseChance < 0 || structures < 0 || lakes < 0 || items < 0 || traps < 0 || enemies < 0) {
			throw new IllegalArgumentException("negative or too large floor parameter");
		}
	}

	/** The research's floor (room density 6, connectivity 20, no dead ends, 15 extra hallways, rivers, 10 lakes). */
	public static PmdParams standard() {
		return new PmdParams(6, 20, false, 15, 8, 6, 0, true, 10, 4, 0, 6);
	}

	public PmdParams withTraps(int traps) {
		return new PmdParams(this.roomDensity, this.connectivity, this.allowDeadEnds, this.extraHallways, this.shopChance,
				this.houseChance, this.structures, this.rivers, this.lakes, this.items, traps, this.enemies);
	}

	public PmdParams withRooms(int shopChance, int houseChance, int structures) {
		return new PmdParams(this.roomDensity, this.connectivity, this.allowDeadEnds, this.extraHallways, shopChance,
				houseChance, structures, this.rivers, this.lakes, this.items, this.traps, this.enemies);
	}
}
