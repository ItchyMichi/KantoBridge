package dev.kantobridge.scene;

/**
 * How a game family colours its art (docs/specs/VOXEL_BLOCKS_PLAN.md §5.3). Gen 1 has one four-colour palette per map;
 * later families colour per tile or per pixel.
 */
public enum PaletteModel {
	/** One four-colour palette per map (Gen 1). */
	MAP4("map4"),
	/** Art baked to RGBA in the export, never coloured (Gen 3, MG7: GEN3_BRIDGE_PLAN §10 E4). */
	BAKED("baked");

	private final String id;

	PaletteModel(String id) {
		this.id = id;
	}

	public String id() {
		return this.id;
	}
}
