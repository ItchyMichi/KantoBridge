package dev.kantobridge.routes.gen;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * A generated zone or route (docs/specs/WILD_ROUTES_PLAN.md §4.3), in cells (one Gen 1 step, 2×2 Minecraft blocks):
 * x grows east, y grows south, (0, 0) is the north-west corner of its box. Game-neutral: species, items and trainer
 * classes are chosen later, from each end's game.
 *
 * <p>The route map in the hidden game is one row of cells, {@code [A][x1]} for a free-roam zone and
 * {@code [A][x1][x2][B]} for a connecting route (§4.2). {@link Gate#routeMapCells()} gives each gate's part of it,
 * west to east, the way the WorldIndex will place it.
 *
 * @param seed      the seed it was made from
 * @param width     the box's width in cells, scenery margin included
 * @param height    the box's height in cells
 * @param cells     row-major, {@code y * width + x}; don't modify
 * @param items     item balls, in the order they were placed
 * @param trainers  trainers, in the order they were placed
 * @param dungeon   the dungeon's entrance, if this zone has one
 * @param trailhead where you arrive and leave: its door is the route map's A, its stand x1
 * @param farGate   a connecting route's far end: its stand is x2, its door B; null for a free-roam zone
 * @param areas     where each area went, the main line first, then the side area
 * @param stats     numbers for logs, tests and the build report
 */
public record RouteLayout(long seed, RouteSpec.Kind kind, String theme, int width, int height, CellRole[] cells,
		List<Cell> items, List<Trainer> trainers, @Nullable Cell dungeon, Gate trailhead, @Nullable Gate farGate,
		List<AreaPlacement> areas, Stats stats) {
	/**
	 * A trainer: where it stands, which way it looks and how far it sees.
	 *
	 * @param area the index of its area in {@link #areas()}
	 */
	public record Trainer(Cell cell, Dir facing, int sight, int area) {
	}

	/**
	 * A gate: a small plaza (x, y, width, height), never turned, plus its door, a single cell sticking out of it.
	 *
	 * @param door  the cell that leaves the zone (A at the trailhead, B at the far gate)
	 * @param stand the cell next to it where the hidden player waits (x1 or x2)
	 */
	public record Gate(int x, int y, int width, int height, Cell door, Cell stand) {
		/** The gate's two route-map cells, west to east: {A, x1} at the trailhead, {x2, B} at the far gate. */
		public List<Cell> routeMapCells() {
			return this.door.x() < this.stand.x() ? List.of(this.door, this.stand) : List.of(this.stand, this.door);
		}
	}

	/**
	 * An area's place: the north-west corner of its square, turned {@code rotation} quarter turns clockwise from its
	 * own frame (where its entrance is on the bottom edge).
	 *
	 * @param levelOffset levels added to the zone's wild levels here (Q5: +1 per area, up to +3)
	 * @param progress    how far along the main line, 0 to 1, for a connecting route's levels between its two ends
	 * @param side        the side area, a dead-end branch off the main line
	 */
	public record AreaPlacement(int x, int y, int rotation, int levelOffset, double progress, boolean side) {
	}

	/**
	 * @param through   the shortest walk in cells from the trailhead's stand to the far gate's, or to the farthest
	 *                  reachable cell of a free-roam zone
	 * @param walkable  walkable cells
	 * @param deadEnds  dead-end blocks
	 * @param junctions blocks with three or four ways out
	 * @param retries   whole attempts thrown away before this one
	 */
	public record Stats(int through, int walkable, int deadEnds, int junctions, int retries) {
	}

	public CellRole at(int x, int y) {
		return this.cells[y * this.width + x];
	}

	public CellRole at(Cell cell) {
		return this.at(cell.x(), cell.y());
	}

	public boolean inside(Cell cell) {
		return cell.x() >= 0 && cell.y() >= 0 && cell.x() < this.width && cell.y() < this.height;
	}

	/** The cells as text, one line per row (for logs of synthetic tests; never for anything ROM-derived). */
	public String ascii() {
		StringBuilder out = new StringBuilder();
		for (int y = 0; y < this.height; y++) {
			for (int x = 0; x < this.width; x++) {
				out.append(this.at(x, y).symbol);
			}
			out.append('\n');
		}
		return out.toString();
	}

	private static String gate(@Nullable Gate g) {
		return g == null ? "-" : g.x() + "," + g.y() + "," + g.width() + "," + g.height() + " door " + g.door().x() + ","
				+ g.door().y() + " stand " + g.stand().x() + "," + g.stand().y();
	}

	/** SHA-256 over the cells and every placed object: equal fingerprints, equal zones. */
	public String fingerprint() {
		StringBuilder s = new StringBuilder("route/1;").append(this.kind).append(';').append(this.theme).append(';')
				.append(this.width).append('x').append(this.height).append(';');
		for (CellRole role : this.cells) {
			s.append(role.symbol);
		}
		s.append(";items");
		this.items.forEach(c -> s.append(' ').append(c.x()).append(',').append(c.y()));
		s.append(";trainers");
		this.trainers.forEach(t -> s.append(' ').append(t.cell().x()).append(',').append(t.cell().y()).append(t.facing().letter)
				.append(t.sight()).append('@').append(t.area()));
		s.append(";dungeon ").append(this.dungeon == null ? "-" : this.dungeon.x() + "," + this.dungeon.y());
		s.append(";gates ").append(gate(this.trailhead)).append(' ').append(gate(this.farGate));
		s.append(";areas");
		this.areas.forEach(a -> s.append(' ').append(a.x()).append(',').append(a.y()).append('r').append(a.rotation())
				.append('+').append(a.levelOffset()).append(a.side() ? "s" : ""));
		try {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(sha.digest(s.toString().getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
