package dev.kantobridge.block;

/**
 * What a carrier block is to the client's runtime model (docs/specs/VOXEL_BLOCKS_PLAN.md §3.3, §5.1): a block whose
 * family fixes collision, outline, occlusion and light, and at whose position the model draws. Tile carriers keep a slot
 * in their state (family × 256 + {@code hi} × 16 + {@code lo}): it names their base tile through the world's slot map,
 * the cube they fall back to. The others draw only what the world's shape map gives their position.
 */
public enum CarrierKind {
	/** {@code tile_full_NN}: an opaque full cube; ground, floors, ceilings, walls, folded facades. */
	TILE_FULL(true, false),
	/** {@code tile_half_NN}: the lower half of a block; water. */
	TILE_HALF(true, true),
	/** {@code shape_full_NN}: collides and is picked like a full tile but never occludes; roofs and other shaped art. */
	SHAPE_FULL(true, false),
	/** {@code decor}: visual-only geometry where the world is air, no collision and no outline. */
	DECOR(false, false),
	/** {@code decor_leaves}: the same for foliage, so shader packs and distant-terrain mods can tell it apart. */
	DECOR_LEAVES(false, false),
	/** {@code ledge_collider}: the ledge's one-way wall; carries the ledge lip. */
	LEDGE(false, false);

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
