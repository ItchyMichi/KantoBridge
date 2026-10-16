package dev.kantobridge.client.model;

import dev.kantobridge.block.CarrierKind;
import dev.kantobridge.builder.shape.geom.FaceMasks;
import dev.kantobridge.builder.shape.geom.ShapeMesh;
import dev.kantobridge.builder.shape.geom.ShapeQuad;
import dev.kantobridge.builder.shape.geom.ShapeTable;
import dev.kantobridge.world.ShapeMap;
import dev.kantobridge.world.SlotMap;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.ModelHelper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

/**
 * Everything the runtime model reads while meshing (docs/specs/VOXEL_BLOCKS_PLAN.md §3.6, §5.1): per slot of the world's
 * slot map its sprite and its cube, already built; the world's shape map lookup and shape table with every texture key
 * resolved to a sprite (V2); the closed story art of its tile cells and the local player's closed set (V5); and the
 * materials. Deeply immutable once built, and published
 * through one {@code volatile} reference: a mesh thread does one read ({@link #current()}) and sees a whole snapshot or
 * the previous whole one, never half of each (I5). Built and swapped on the client thread by {@link SnapshotPublisher}.
 *
 * <p>Several regions (MG3): the followed region's shapes are layer 0, and every other region whose shape table is built
 * is a layer of its own ({@link Layer}: its lookup, its table and its own game's sprites), so each region draws its own
 * shapes at once. A layer's lookup answers only inside its own region's grids (world coordinates), and a shape id
 * carries its layer above {@link #LAYER_SHIFT}, so neighbours cull across layers too. Layer 0's ids are plain (the
 * closed story art, which is the followed region's, uses them as they are).
 *
 * <p>A slot the world's slot map doesn't name, a key whose sprite the atlas lacks, or no snapshot yet at all draws the
 * jar's placeholder tile: never a hole (I3).
 */
public final class RenderSnapshot {
	/** Before the first publish: the model draws its own placeholder cube. */
	public static final RenderSnapshot BOOT = new RenderSnapshot(-1, null, null, new TextureAtlasSprite[2][], new Mesh[2][], new Mesh[2],
			null, false, null, 0, -1, null, null, new TextureAtlasSprite[0], new int[0], null, null, null, new BitSet());

	private static volatile RenderSnapshot current = BOOT;

	/** A shape id's layer sits above these bits (layer 0's ids are plain). */
	static final int LAYER_SHIFT = 24;
	private static final int ID_MASK = (1 << LAYER_SHIFT) - 1;

	/**
	 * One more region's shapes (MG3): its verified lookup, its built table and, by the table's texture index, its own
	 * game's sprites (null: the placeholder) and tints (-1 for none).
	 */
	public record Layer(ShapeMap.Lookup lookup, ShapeTable table, TextureAtlasSprite[] sprites, int[] tints) {
	}

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
	private final ShapeMap.@Nullable Lookup shapes;
	private final @Nullable ShapeTable table;
	/** By the shape table's texture index: the key's sprite, or null (the placeholder). */
	private final TextureAtlasSprite[] shapeSprites;
	/** By the shape table's texture index: a pack model colour's tint over the white sprite, -1 for none (V6). */
	private final int[] shapeTints;
	private final @Nullable RenderMaterial cutout;
	/** Solid without ambient occlusion: thin standing cards (tufts), which would darken their own neighbours. */
	private final @Nullable RenderMaterial flat;
	/** The tile cells' closed art (the shape map's overlays), or null: the block-entity renderer draws it. */
	private final @Nullable ClosedArt closedArt;
	/** The overlays the local player sees closed. Never changed once published. */
	private final BitSet closed;
	/** The other regions' shapes, layers 1.. (MG3). */
	private final Layer[] others;
	/** Keys of other regions' art sets that no loaded set draws (placeholders; MG7). */
	private final int foreign;

	@SuppressWarnings("unchecked")
	RenderSnapshot(long generation, @Nullable TextureAtlasSprite placeholder, @Nullable String slotMapHash, TextureAtlasSprite[][] sprites,
			Mesh[][] cubes, Mesh[] placeholderCubes, @Nullable RenderMaterial solid, boolean kanto, @Nullable String artSet, int keys,
			int missing, ShapeMap.@Nullable Lookup shapes, @Nullable ShapeTable table, TextureAtlasSprite[] shapeSprites, int[] shapeTints,
			@Nullable RenderMaterial cutout, @Nullable RenderMaterial flat, @Nullable ClosedArt closedArt, BitSet closed) {
		this(generation, placeholder, slotMapHash, sprites, cubes, placeholderCubes, solid, kanto, artSet, keys, missing, shapes, table,
				shapeSprites, shapeTints, cutout, flat, closedArt, closed, List.of(), 0);
	}

	@SuppressWarnings("unchecked")
	RenderSnapshot(long generation, @Nullable TextureAtlasSprite placeholder, @Nullable String slotMapHash, TextureAtlasSprite[][] sprites,
			Mesh[][] cubes, Mesh[] placeholderCubes, @Nullable RenderMaterial solid, boolean kanto, @Nullable String artSet, int keys,
			int missing, ShapeMap.@Nullable Lookup shapes, @Nullable ShapeTable table, TextureAtlasSprite[] shapeSprites, int[] shapeTints,
			@Nullable RenderMaterial cutout, @Nullable RenderMaterial flat, @Nullable ClosedArt closedArt, BitSet closed, List<Layer> others,
			int foreign) {
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
		this.shapes = shapes;
		this.table = table;
		this.shapeSprites = shapeSprites;
		this.shapeTints = shapeTints;
		this.cutout = cutout;
		this.flat = flat;
		this.closedArt = closedArt;
		this.closed = (BitSet) closed.clone();
		if (others.size() >= (1 << (31 - LAYER_SHIFT)) - 1) {
			throw new IllegalArgumentException("too many shape layers");
		}
		this.others = others.toArray(new Layer[0]);
		this.foreign = foreign;
	}

	/** A copy sharing everything with {@code base} but the closed set. */
	private RenderSnapshot(RenderSnapshot base, BitSet closed) {
		this.generation = base.generation;
		this.placeholder = base.placeholder;
		this.slotMapHash = base.slotMapHash;
		this.sprites = base.sprites;
		this.cubes = base.cubes;
		this.placeholderCubes = base.placeholderCubes;
		this.quadLists = base.quadLists;
		this.solid = base.solid;
		this.kanto = base.kanto;
		this.artSet = base.artSet;
		this.keys = base.keys;
		this.missing = base.missing;
		this.shapes = base.shapes;
		this.table = base.table;
		this.shapeSprites = base.shapeSprites;
		this.shapeTints = base.shapeTints;
		this.cutout = base.cutout;
		this.flat = base.flat;
		this.closedArt = base.closedArt;
		this.closed = (BitSet) closed.clone();
		this.others = base.others;
		this.foreign = base.foreign;
	}

	/** A layer's sprites by texture index (layer 0: the followed region's). */
	private TextureAtlasSprite[] spritesOf(int layer) {
		return layer == 0 ? this.shapeSprites : this.others[layer - 1].sprites();
	}

	/** A layer's tints by texture index. */
	private int[] tintsOf(int layer) {
		return layer == 0 ? this.shapeTints : this.others[layer - 1].tints();
	}

	private static int tint(int[] tints, int texture) {
		return texture < tints.length ? tints[texture] : -1;
	}

	/** This snapshot with another closed set (the local player's story changed; V5). */
	public RenderSnapshot withClosed(BitSet closed) {
		return new RenderSnapshot(this, closed);
	}

	/** The tile cells' closed art, or null when the block-entity renderer draws it (a world built before V5). */
	public @Nullable ClosedArt closedArt() {
		return this.closedArt;
	}

	/** True when the chunk mesh draws this tile cell's closed art (its overlay is known). */
	public boolean drawsClosed(String key) {
		ClosedArt art = this.closedArt;
		return art != null && art.overlay(key) >= 0;
	}

	/** The shape id of the closed art drawn at a position for the local player, 0 for none (the dev mesh check). */
	public int closedIdAt(int x, int y, int z) {
		ClosedArt art = this.closedArt;
		int[] at = art == null ? null : art.at(x, y, z);
		if (at == null || !this.closed.get(at[0])) {
			return 0;
		}
		int[] ids = art.ids(at[0], at[1]);
		int level = y - at[2];
		return level >= 0 && level < ids.length ? ids[level] : 0;
	}

	/**
	 * Emits the closed story art at (x, y, z) when the local player sees its cell closed (V5): the overlay's shape at
	 * this level, every quad of it (closed shapes neither cull nor are culled); without a shape table, the closed art's
	 * tile as a cube, or as a thin layer on the floor for flat art. Returns the quads emitted.
	 */
	public int emitClosed(int x, int y, int z, TextureAtlasSprite fallback, QuadEmitter out) {
		ClosedArt art = this.closedArt;
		int[] at = art == null ? null : art.at(x, y, z);
		if (at == null || !this.closed.get(at[0])) {
			return 0;
		}
		int[] ids = art.ids(at[0], at[1]);
		int level = y - at[2];
		if (level < 0 || level >= ids.length) {
			return 0;
		}
		ShapeMesh mesh = this.mesh(ids[level]);
		if (mesh != null) {
			TextureAtlasSprite placeholder = this.placeholder != null ? this.placeholder : fallback;
			List<ShapeQuad> quads = mesh.quads();
			for (int q = 0; q < quads.size(); q++) {
				ShapeQuad quad = quads.get(q);
				int texture = quad.texture();
				TextureAtlasSprite sprite = texture < this.shapeSprites.length && this.shapeSprites[texture] != null ? this.shapeSprites[texture]
						: placeholder;
				ShapeEmitter.emit(quad, sprite, quad.has(ShapeQuad.CUTOUT) ? this.cutout : quad.has(ShapeQuad.NO_AO) ? this.flat : this.solid,
						tint(this.shapeTints, texture), out);
			}
			return quads.size();
		}
		int slot = art.slot(at[0], at[1]);
		if (art.flat(at[0])) {
			out.square(Direction.UP, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F - FLAT_LIFT);
			out.spriteBake(this.sprite(CarrierKind.TILE_FULL, slot, fallback), MutableQuadView.BAKE_LOCK_UV);
			out.color(-1, -1, -1, -1);
			if (this.solid != null) {
				out.material(this.solid);
			}
			out.emit();
			return 1;
		}
		return this.emitCube(CarrierKind.TILE_FULL, slot, fallback, out);
	}

	/** Flat closed art without a shape table lies this far above the floor (the overlay's slab is as high). */
	static final float FLAT_LIFT = 1.0F / 16.0F;

	/**
	 * The shape id at a position: 0 without one, without a shape map, or while the table builds. The followed region's
	 * first, then each other region's (its layer above {@link #LAYER_SHIFT}).
	 */
	public int shapeAt(int x, int y, int z) {
		ShapeMap.Lookup lookup = this.shapes;
		int id = lookup == null || this.table == null ? 0 : lookup.shapeAt(x, y, z);
		if (id != 0) {
			return id;
		}
		Layer[] layers = this.others;
		for (int i = 0; i < layers.length; i++) {
			int other = layers[i].lookup().shapeAt(x, y, z);
			if (other != 0) {
				return other | ((i + 1) << LAYER_SHIFT);
			}
		}
		return 0;
	}

	/** The mesh of a shape id (its layer's table), or null (the plain cube is drawn). */
	public @Nullable ShapeMesh mesh(int id) {
		int layer = id >>> LAYER_SHIFT;
		if (layer == 0) {
			ShapeTable built = this.table;
			return built == null ? null : built.mesh(id);
		}
		return layer <= this.others.length ? this.others[layer - 1].table().mesh(id & ID_MASK) : null;
	}

	/**
	 * Emits the shape at (x, y, z) with this snapshot's sprites and materials, leaving out every quad on a block face
	 * that the neighbour's shape covers there (its face mask; Minecraft itself culls against opaque neighbours). Returns
	 * the quads emitted.
	 */
	public int emitShape(ShapeMesh mesh, int x, int y, int z, TextureAtlasSprite fallback, QuadEmitter out) {
		return this.emitShape(0, mesh, x, y, z, fallback, out);
	}

	/** {@link #emitShape(ShapeMesh, int, int, int, TextureAtlasSprite, QuadEmitter)} for the shape of id {@code id}: its layer's sprites. */
	public int emitShape(int id, ShapeMesh mesh, int x, int y, int z, TextureAtlasSprite fallback, QuadEmitter out) {
		int layer = id >>> LAYER_SHIFT;
		TextureAtlasSprite[] layerSprites = this.spritesOf(layer);
		int[] layerTints = this.tintsOf(layer);
		TextureAtlasSprite placeholder = this.placeholder != null ? this.placeholder : fallback;
		List<ShapeQuad> quads = mesh.quads();
		int emitted = 0;
		for (int q = 0; q < quads.size(); q++) {
			ShapeQuad quad = quads.get(q);
			int cull = quad.cull();
			if (cull >= 0 && mesh.footprint(q) != 0) {
				int neighbour = this.shapeAt(x + FaceMasks.DX[cull], y + FaceMasks.DY[cull], z + FaceMasks.DZ[cull]);
				if (neighbour != 0 && FaceMasks.culled(mesh, q, this.mesh(neighbour))) {
					continue;
				}
			}
			int texture = quad.texture();
			TextureAtlasSprite sprite = texture < layerSprites.length && layerSprites[texture] != null ? layerSprites[texture] : placeholder;
			ShapeEmitter.emit(quad, sprite, quad.has(ShapeQuad.CUTOUT) ? this.cutout : quad.has(ShapeQuad.NO_AO) ? this.flat : this.solid,
					tint(layerTints, texture), out);
			emitted++;
		}
		return emitted;
	}

	/** True when the followed region's shapes are drawn (a verified shape map and a built table). */
	public boolean shaped() {
		return this.shapes != null && this.table != null;
	}

	/** The other regions drawn with their own shapes (MG3). */
	public int otherLayers() {
		return this.others.length;
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

	/** Keys of other regions' games that no loaded art set draws (placeholders until the pack holds their art). */
	public int foreign() {
		return this.foreign;
	}
}
