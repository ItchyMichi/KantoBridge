package dev.kantobridge.block;

/**
 * What a carrier block is to the client's runtime model (docs/specs/VOXEL_BLOCKS_PLAN.md §3.3, §5.1): a block whose
 * family fixes collision, outline, occlusion and light, and at whose position the model draws. Tile carriers keep a slot
 * in their state (family × 256 + {@code hi} × 16 + {@code lo}): it names their base tile through the world's slot map,
 * the cube they fall back to.
 */
public enum CarrierKind {
	/** {@code tile_full_NN}: an opaque full cube; ground, floors, ceilings, walls. */
	TILE_FULL(true, false),
	/** {@code tile_half_NN}: the lower half of a block; water. */
	TILE_HALF(true, true);

	private final boolean slot;
	private final boolean half;

	CarrierKind(boolean slot, boolean half) {
		this.slot = slot;
		this.half = half;
	}

	/** True when the state carries a slot (its base tile). */
	public boolean hasSlot() {
		return this.slot;
	}

	/** True when the fallback cube is the lower half of a block. */
	public boolean half() {
		return this.half;
	}

	/** True when the model draws the plain cube of the slot where it has nothing else to draw. */
	public boolean drawsCube() {
		return this.slot;
	}
}
