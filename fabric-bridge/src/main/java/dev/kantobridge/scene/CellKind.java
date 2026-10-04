package dev.kantobridge.scene;

import org.jetbrains.annotations.Nullable;

/**
 * What a walk-grid cell is, in the neutral vocabulary every game family maps to (docs/specs/VOXEL_BLOCKS_PLAN.md §5.3).
 * The ids are the scene's serialised form.
 */
public enum CellKind {
	/** Walkable: ground, grass, flowers, floors, doors, mats, stairs. */
	WALK(0),
	/** Not walkable and not water: walls, trees, roofs, objects drawn in the map. */
	SOLID(1),
	/** Surfable water, shore included. */
	WATER(2),
	/** A one-way ledge. */
	LEDGE(3),
	/** A counter (people are talked to across it). */
	COUNTER(4);

	private final int id;

	CellKind(int id) {
		this.id = id;
	}

	public int id() {
		return this.id;
	}

	public static @Nullable CellKind byId(int id) {
		for (CellKind kind : values()) {
			if (kind.id == id) {
				return kind;
			}
		}
		return null;
	}
}
