package dev.kantobridge.builder.shape;

import dev.kantobridge.scene.CellKind;
import dev.kantobridge.scene.SceneMap;

/**
 * The 2D map as the game shows it, composed from the export (docs/specs/VOXEL_BLOCKS_PLAN.md §5.6): for every screen
 * pixel of the map body, which atlas texel it shows (by identity: tile and texel coordinates, never a colour) and
 * whether it belongs to the ground or to a structure. Nothing here is ever written or logged.
 */
public final class Reference {
	private final SceneMap map;
	private final int artPx;

	public Reference(SceneMap map, int artPx) {
		this.map = map;
		this.artPx = artPx;
	}

	public int width() {
		return this.map.artCols() * this.artPx;
	}

	public int height() {
		return this.map.artRows() * this.artPx;
	}

	/** The texel identity at screen pixel (sx, sy): tile, texel column and row packed. */
	public long texel(int sx, int sy) {
		return SelfCheck.texel(this.map.tile(sx / this.artPx, sy / this.artPx), sx % this.artPx, sy % this.artPx);
	}

	/** True when the pixel shows ground (a walkable, water or ledge cell); false for a structure (solid, counter). */
	public boolean ground(int sx, int sy) {
		CellKind kind = this.map.kindAtTile(sx / this.artPx, sy / this.artPx);
		return kind == CellKind.WALK || kind == CellKind.WATER || kind == CellKind.LEDGE;
	}
}
