package dev.kantobridge.scene;

/**
 * How a game family colours its art (docs/specs/VOXEL_BLOCKS_PLAN.md §5.3). Gen 1 has one four-colour palette per map;
 * later families colour per tile or per pixel.
 */
public enum PaletteModel {
	/** One four-colour palette per map (Gen 1). */
	MAP4("map4");

	private final String id;

	PaletteModel(String id) {
		this.id = id;
	}

	public String id() {
		return this.id;
	}
}
