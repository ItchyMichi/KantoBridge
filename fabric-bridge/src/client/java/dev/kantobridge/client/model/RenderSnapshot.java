package dev.kantobridge.client.model;

import dev.kantobridge.block.CarrierKind;
import dev.kantobridge.world.SlotMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.ModelHelper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

/**
 * Everything the runtime model reads while meshing (docs/specs/VOXEL_BLOCKS_PLAN.md §3.6, §5.1): per slot of the world's
 * slot map its sprite and its cube, already built, and the materials. Deeply immutable once built, and published
 * through one {@code volatile} reference: a mesh thread does one read ({@link #current()}) and sees a whole snapshot or
 * the previous whole one, never half of each (I5). Built and swapped on the client thread by {@link SnapshotPublisher}.
 *
 * <p>A slot the world's slot map doesn't name, a key whose sprite the atlas lacks, or no snapshot yet at all draws the
 * jar's placeholder tile: never a hole (I3).
 */
public final class RenderSnapshot {
	/** Before the first publish: the model draws its own placeholder cube. */
	public static final RenderSnapshot BOOT = new RenderSnapshot(-1, null, null, new TextureAtlasSprite[2][], new Mesh[2][], new Mesh[2],
			null, false, null, 0, -1);

	private static volatile RenderSnapshot current = BOOT;

	private final long generation;
	private final @Nullable TextureAtlasSprite placeholder;
	private final @Nullable String slotMapHash;
	/** [shape][slot index]: the slot's sprite, or null where the world's slot map has no such slot. */
	private final TextureAtlasSprite[][] sprites;
	/** [shape][slot index]: the slot's cube; null where a placeholder cube is drawn. */
	private final Mesh[][] cubes;
	/** [shape]: the placeholder's cube, or null without a renderer. */
	private final Mesh[] placeholderCubes;
	/** [shape][slot index, or CAPACITY for the placeholder]: vanilla quad lists for state-keyed consumers, built on demand. */
	private final AtomicReferenceArray<List<BakedQuad>[]>[] quadLists;
	private final @Nullable RenderMaterial solid;
	private final boolean kanto;
	private final @Nullable String artSet;
	private final int keys;
	private final int missing;

	@SuppressWarnings("unchecked")
	RenderSnapshot(long generation, @Nullable TextureAtlasSprite placeholder, @Nullable String slotMapHash, TextureAtlasSprite[][] sprites,
			Mesh[][] cubes, Mesh[] placeholderCubes, @Nullable RenderMaterial solid, boolean kanto, @Nullable String artSet, int keys,
			int missing) {
		this.generation = generation;
		this.placeholder = placeholder;
		this.slotMapHash = slotMapHash;
		this.sprites = sprites;
		this.cubes = cubes;
		this.placeholderCubes = placeholderCubes;
		this.quadLists = new AtomicReferenceArray[] {new AtomicReferenceArray<>(SlotMap.CAPACITY + 1),
				new AtomicReferenceArray<>(SlotMap.CAPACITY + 1)};
		this.solid = solid;
		this.kanto = kanto;
		this.artSet = artSet;
		this.keys = keys;
		this.missing = missing;
	}

	/** The snapshot to draw with: one volatile read. */
	public static RenderSnapshot current() {
		return current;
	}

	/** Swaps the snapshot (client thread only; {@link SnapshotPublisher}). */
	static void publish(RenderSnapshot next) {
		current = next;
	}

	/**
	 * Emits the plain cube of a slot: its own art, or the placeholder; with no snapshot yet, the model's baked
	 * placeholder. Returns the quads emitted.
	 */
	public int emitCube(CarrierKind kind, int slot, TextureAtlasSprite fallback, QuadEmitter out) {
		int shape = CubeEmitter.shape(kind.half());
		Mesh[] byShape = this.cubes[shape];
		Mesh mesh = byShape != null && slot >= 0 && slot < byShape.length ? byShape[slot] : null;
		if (mesh == null) {
			mesh = this.placeholderCubes[shape];
		}
		if (mesh != null) {
			mesh.outputTo(out);
		} else {
			CubeEmitter.emit(out, shape, this.placeholder != null ? this.placeholder : fallback, this.solid);
		}
		return 6;
	}

	/** The sprite a slot draws with (its art, or the placeholder). */
	public TextureAtlasSprite sprite(CarrierKind kind, int slot, TextureAtlasSprite fallback) {
		TextureAtlasSprite[] byShape = this.sprites[CubeEmitter.shape(kind.half())];
		TextureAtlasSprite sprite = byShape != null && slot >= 0 && slot < byShape.length ? byShape[slot] : null;
		if (sprite != null) {
			return sprite;
		}
		return this.placeholder != null ? this.placeholder : fallback;
	}

	/**
	 * The slot's cube as vanilla quads for one side (null: the unculled ones, none for a cube), for state-keyed consumers:
	 * {@code renderSingleBlock}, the breaking overlay, distant-terrain mods. Built on first use and kept; two threads may
	 * build the same lists, which are equal.
	 */
	public List<BakedQuad> quads(CarrierKind kind, int slot, @Nullable Direction side, TextureAtlasSprite fallback) {
		int shape = CubeEmitter.shape(kind.half());
		TextureAtlasSprite[] byShape = this.sprites[shape];
		boolean own = byShape != null && slot >= 0 && slot < byShape.length && byShape[slot] != null;
		int index = own ? slot : SlotMap.CAPACITY;
		List<BakedQuad>[] lists = this.quadLists[shape].get(index);
		if (lists == null) {
			lists = quadLists(shape, own ? byShape[slot] : this.placeholder != null ? this.placeholder : fallback);
			if (this.generation >= 0) {
				// Not before the first publish: the fallback is the model's own sprite, which a reload replaces.
				this.quadLists[shape].set(index, lists);
			}
		}
		return lists[ModelHelper.toFaceIndex(side)];
	}

	@SuppressWarnings("unchecked")
	static List<BakedQuad>[] quadLists(int shape, TextureAtlasSprite sprite) {
		List<BakedQuad>[] lists = new List[7];
		for (Direction face : Direction.values()) {
			BakedQuad quad = CubeEmitter.bake(shape, face, sprite);
			// A face on the block's surface is culled with its side; the half cube's top is drawn with the unculled ones.
			lists[CubeEmitter.culls(shape, face) ? face.get3DDataValue() : ModelHelper.NULL_FACE_ID] = List.of(quad);
		}
		for (int i = 0; i < lists.length; i++) {
			if (lists[i] == null) {
				lists[i] = List.of();
			}
		}
		return lists;
	}

	/** Which publish this is; -1 before the first. */
	public long generation() {
		return this.generation;
	}

	/** True inside a Kanto world (a WorldIndex arrived). */
	public boolean kanto() {
		return this.kanto;
	}

	/** The hash of the slot map the sprites were resolved against, or null without one. */
	public @Nullable String slotMapHash() {
		return this.slotMapHash;
	}

	/** The art set drawn with, or null (placeholders only). */
	public @Nullable String artSet() {
		return this.artSet;
	}

	/** The world's slot-map keys. */
	public int keys() {
		return this.keys;
	}

	/** Keys whose sprite this client lacks (drawn as placeholders); -1 without a slot map. */
	public int missing() {
		return this.missing;
	}
}
