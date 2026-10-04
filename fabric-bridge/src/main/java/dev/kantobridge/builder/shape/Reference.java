package dev.kantobridge.builder.shape;

import dev.kantobridge.builder.shape.geom.CellUnits;
import dev.kantobridge.builder.shape.geom.ShapeTable;
import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.SceneMap;
import org.jetbrains.annotations.Nullable;

/**
 * The 2D map as the game shows it, composed from the export (docs/specs/VOXEL_BLOCKS_PLAN.md §5.6): for every screen
 * pixel of the map body, which atlas texel it shows (by identity: tile and texel coordinates, never a colour) and
 * whether it belongs to the ground or to a structure. Nothing here is ever written or logged.
 *
 * <p>From V4 a cell unit drawn as a hull or an object changes what its cell should show: its background is ground (its
 * floor wears the unit's ground tile), and its drawn pixels are scored per unit ({@link CellUnits}), so the map's
 * scores leave them out ({@link #excluded}).
 */
public final class Reference {
	private final SceneMap map;
	private final int artPx;
	/** Per screen pixel: the tile a unit's background shows (its ground), or -1. */
	private final int @Nullable [] unitGround;
	/** Per screen pixel: a unit's drawn pixel, scored per unit. */
	private final boolean @Nullable [] unitDrawn;
	/** How many pixels a ledge's lip lifts its art in the game's view (its slab's height), 0 to allow none. */
	private final int ledgeLift;

	public Reference(SceneMap map, int artPx) {
		this.map = map;
		this.artPx = artPx;
		this.unitGround = null;
		this.unitDrawn = null;
		this.ledgeLift = 0;
	}

	/** The reference of a planned map: its units as the table built them. */
	public Reference(SceneMap map, int artPx, ShapePlanner.MapShapes shapes, ShapeTable table) {
		this(map, artPx, shapes, table, 0);
	}

	/**
	 * The reference of a planned map whose ledge lips stand {@code ledgeLift} pixels high (the profile plan, P1): a lip
	 * shows its tile lifted by its own height, by design, so a ledge pixel may also match the texel that many rows lower.
	 */
	public Reference(SceneMap map, int artPx, ShapePlanner.MapShapes shapes, ShapeTable table, int ledgeLift) {
		this.map = map;
		this.artPx = artPx;
		this.ledgeLift = ledgeLift;
		int w = this.width();
		int[] ground = null;
		boolean[] drawn = null;
		int per = map.perCell();
		int cellPx = per * artPx;
		for (Units.Cell cell : shapes.units()) {
			CellUnits.Unit unit = table.unit(map.atlas(), cell.key());
			if (unit == null || (unit.kind() != CellUnits.Kind.HULL && unit.kind() != CellUnits.Kind.OBJECT)) {
				continue;
			}
			if (ground == null) {
				ground = new int[w * this.height()];
				java.util.Arrays.fill(ground, -1);
				drawn = new boolean[w * this.height()];
			}
			int g = cell.key().number(4);
			for (int py = 0; py < cellPx; py++) {
				for (int px = 0; px < cellPx; px++) {
					int sx = cell.cx() * cellPx + px;
					int sy = cell.cy() * cellPx + py;
					int i = sy * w + sx;
					if (unit.foreground()[py * cellPx + px]) {
						drawn[i] = true;
					} else {
						ground[i] = g >= 0 ? g : map.tile(sx / artPx, sy / artPx);
					}
				}
			}
		}
		// The profile's band-table buildings (P2) no longer reproduce their drawing, by design: their claimed tiles and
		// doorways are left out of the map's scores (holes still count).
		for (Templates.Placed placed : shapes.templates()) {
			com.google.gson.JsonObject entry = placed.entry();
			if (!entry.has("band")) {
				continue;
			}
			if (drawn == null) {
				ground = new int[w * this.height()];
				java.util.Arrays.fill(ground, -1);
				drawn = new boolean[w * this.height()];
			}
			int gw = entry.get("w").getAsInt();
			int gd = entry.get("d").getAsInt();
			com.google.gson.JsonArray tiles = entry.getAsJsonArray("tiles");
			com.google.gson.JsonObject band = entry.getAsJsonObject("band");
			com.google.gson.JsonArray air = band.getAsJsonArray("air");
			int north = band.get("north").getAsInt();
			int rows = band.getAsJsonArray("rows").size();
			for (int gy = 0; gy < gd; gy++) {
				for (int gx = 0; gx < gw; gx++) {
					int g = gy * gw + gx;
					boolean doorway = gy >= north && gy < north + rows && air.get(g).getAsInt() > 0;
					if (tiles.get(g).getAsInt() < 0 && !doorway) {
						continue;
					}
					int tx = placed.x0() + gx;
					int ty = placed.y0() + gy;
					for (int py = 0; py < artPx; py++) {
						for (int px = 0; px < artPx; px++) {
							int sx = tx * artPx + px;
							int sy = ty * artPx + py;
							if (sx >= 0 && sy >= 0 && sx < w && sy < this.height()) {
								drawn[sy * w + sx] = true;
							}
						}
					}
				}
			}
		}
		this.unitGround = ground;
		this.unitDrawn = drawn;
	}

	public int width() {
		return this.map.artCols() * this.artPx;
	}

	public int height() {
		return this.map.artRows() * this.artPx;
	}

	/** The texel identity at screen pixel (sx, sy): tile, texel column and row packed. */
	public long texel(int sx, int sy) {
		int tile = this.map.tile(sx / this.artPx, sy / this.artPx);
		if (this.unitGround != null && this.unitGround[sy * this.width() + sx] >= 0) {
			tile = this.unitGround[sy * this.width() + sx];
		}
		return SelfCheck.texel(tile, sx % this.artPx, sy % this.artPx);
	}

	/**
	 * True when the pixel shows ground (a walkable or water cell, or a unit's background); false for everything that
	 * stands up from it (solid cells, counters, ledges with their lips).
	 */
	public boolean ground(int sx, int sy) {
		if (this.unitGround != null && this.unitGround[sy * this.width() + sx] >= 0) {
			return true;
		}
		CellKind kind = this.map.kindAtTile(sx / this.artPx, sy / this.artPx);
		return kind == CellKind.WALK || kind == CellKind.WATER;
	}

	/** The rows a pixel's art is lifted by design: a ledge lip's height, 0 elsewhere. */
	public int lift(int sx, int sy) {
		return this.ledgeLift > 0 && this.map.kindAtTile(sx / this.artPx, sy / this.artPx) == CellKind.LEDGE ? this.ledgeLift : 0;
	}

	/** True for a unit's drawn pixel: scored per unit, so the map's scores leave it out. */
	public boolean excluded(int sx, int sy) {
		return this.unitDrawn != null && this.unitDrawn[sy * this.width() + sx];
	}
}
