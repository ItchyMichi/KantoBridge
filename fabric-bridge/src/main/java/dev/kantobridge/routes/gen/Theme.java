package dev.kantobridge.routes.gen;

/**
 * How a kind of zone draws its blocks (docs/specs/WILD_ROUTES_PLAN.md §4.10): the forest now, cave systems and the
 * sea later. The generators stay the same for every theme; a theme only fills in a block's cells.
 */
public interface Theme {
	/** The theme's id in the route list ({@code "forest"}). */
	String id();

	/** The chance that every block of an area is drawn alike (DWM's one-drawing mode): a hedge maze in a forest. */
	double uniformChance();

	/**
	 * Draws one block. Its exits are 2-cell openings at the middle of each side in {@code exits}; every exit must stay
	 * reachable from every other through walkable cells. Cells outside the drawing stay {@link CellRole#WALL}.
	 *
	 * @param uniform this area draws every block alike
	 * @return {@code height} rows of {@code width} cells
	 */
	CellRole[][] drawBlock(RouteRng rng, int exits, boolean uniform, int width, int height);
}
