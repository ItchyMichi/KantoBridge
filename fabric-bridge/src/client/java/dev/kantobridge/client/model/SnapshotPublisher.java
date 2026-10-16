package dev.kantobridge.client.model;

import dev.kantobridge.KantoBridge;
import dev.kantobridge.builder.pack.ArtKeys;
import dev.kantobridge.builder.shape.geom.ShapeTable;
import dev.kantobridge.builder.shape.geom.TextureKey;
import dev.kantobridge.client.packs.KbPackReload;
import dev.kantobridge.client.packs.LoadedPack;
import dev.kantobridge.client.packs.PackInfo;
import dev.kantobridge.client.packs.PackOverrides;
import dev.kantobridge.client.shape.ClientShapes;
import dev.kantobridge.client.story.ClientStoryView;
import dev.kantobridge.client.story.StoryDirty;
import dev.kantobridge.client.world.ClientRegions;
import dev.kantobridge.client.world.ClientWorldIndex;
import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.RegionRecord;
import dev.kantobridge.world.ShapeMap;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.WorldIndex;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.InvalidateRenderStateCallback;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.BlendMode;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.MeshBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Builds and swaps the {@link RenderSnapshot} (docs/specs/VOXEL_BLOCKS_PLAN.md §5.1), on the client thread only:
 *
 * <ul>
 * <li>when the world's index or slot map arrives or goes ({@link #request()}, then the next client tick; re-meshes);</li>
 * <li>when a resource reload ends: Fabric's {@code InvalidateRenderStateCallback} fires at the head of
 * {@code allChanged()}, so the new snapshot (new atlas, new sprites) is in place before any section is rebuilt;</li>
 * <li>on disconnect (the world's art goes).</li>
 * </ul>
 * Building resolves every key of the world's slot map to its sprite in the block atlas
 * ({@code kantobridge:block/<art set>/tile/...}) and bakes each slot's cube once; a key the atlas lacks is counted
 * ({@link #missing()}) and drawn as the placeholder. MG7: the loaded pack may hold several art sets (one per game
 * family); a region's key is drawn with the loaded set of its own game (or a compatible one), a legacy key with the
 * pack's own set, and each region's shapes with the set of its own game: the followed region's first, then every other
 * region whose shape table is built ({@link RenderSnapshot.Layer}). After a swap that changes drawing it calls
 * {@code levelRenderer.allChanged()}. Logs carry counts and short hashes only.
 */
public final class SnapshotPublisher {
	private static boolean registered;
	private static boolean dirty = true;
	private static boolean remeshing;
	private static long builtGeneration = Long.MIN_VALUE;
	private static @Nullable SlotMap builtSlots;
	private static @Nullable String builtArtSet;
	private static List<String> builtArtSets = List.of();
	private static @Nullable String builtShapeSet;
	private static boolean builtKanto;
	private static ShapeMap.@Nullable Lookup builtShapes;
	private static @Nullable ShapeTable builtTable;
	private static @Nullable ShapeMap builtOverlays;
	private static @Nullable WorldIndex builtIndex;
	private static @Nullable PackOverrides builtPacks;
	private static List<Other> builtOthers = List.of();
	/** A pack model's colours are tints over this sprite (V6). */
	static final ResourceLocation WHITE = ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "block/white");
	private static long publishes;

	private SnapshotPublisher() {
	}

	/** Call once from client init, after {@link ClientWorldIndex#init()}. */
	public static void init() {
		if (registered) {
			return;
		}
		registered = true;
		LoadedPack.init();
		InvalidateRenderStateCallback.EVENT.register(SnapshotPublisher::onInvalidate);
		ClientTickEvents.END_CLIENT_TICK.register(SnapshotPublisher::tick);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> request());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> request());
		ClientWorldIndex.addListener(SnapshotPublisher::request);
		ClientShapes.init(SnapshotPublisher::request);
	}

	/**
	 * The local player's closed tile cells changed (client thread; V5): publish the new closed set, then re-mesh the closed
	 * columns of every cell that opened or closed. Cells without an overlay are the block-entity renderer's.
	 */
	public static void storyChanged(Set<String> before, Set<String> after) {
		RenderSnapshot current = RenderSnapshot.current();
		ClosedArt art = current.closedArt();
		if (art == null) {
			return;
		}
		RenderSnapshot.publish(current.withClosed(art.bits(after)));
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			return;
		}
		for (int[] box : StoryDirty.boxes(before, after, art)) {
			minecraft.levelRenderer.setBlocksDirty(box[0], box[1], box[2], box[3], box[4], box[5]);
		}
	}

	/** Another region's shapes as one snapshot draws them: compared by identity to see whether drawing changed. */
	record Other(String region, ShapeMap.Lookup lookup, ShapeTable table, @Nullable String artSet) {
		boolean same(Other o) {
			return this.region.equals(o.region) && this.lookup == o.lookup && this.table == o.table && Objects.equals(this.artSet, o.artSet);
		}
	}

	/** Every region but the followed one whose shapes are ready, with the art set of its game (MG3). */
	static List<Other> others(@Nullable String followed) {
		List<Other> out = new ArrayList<>();
		for (String region : ClientShapes.regions()) {
			if (region.equals(followed)) {
				continue;
			}
			ShapeMap.Lookup lookup = ClientShapes.lookup(region);
			ShapeTable table = lookup == null ? null : ClientShapes.table(region);
			RegionRecord record = ClientRegions.byId(region);
			if (lookup != null && table != null && record != null) {
				out.add(new Other(region, lookup, table, LoadedPack.artSetFor(record.game())));
			}
		}
		return out;
	}

	private static boolean sameOthers(List<Other> a, List<Other> b) {
		if (a.size() != b.size()) {
			return false;
		}
		for (int i = 0; i < a.size(); i++) {
			if (!a.get(i).same(b.get(i))) {
				return false;
			}
		}
		return true;
	}

	/** Something the snapshot is built from changed: build again on the next client tick. */
	public static void request() {
		dirty = true;
	}

	/** Keys of the world's slot map whose sprite this client lacks; -1 without a slot map. */
	public static int missing() {
		return RenderSnapshot.current().missing();
	}

	/** Snapshots published so far (F7). */
	public static long publishes() {
		return publishes;
	}

	private static void tick(Minecraft minecraft) {
		if (!dirty) {
			return;
		}
		dirty = false;
		if (rebuild(minecraft) && minecraft.level != null) {
			remeshing = true;
			try {
				minecraft.levelRenderer.allChanged();
			} finally {
				remeshing = false;
			}
		}
	}

	private static void onInvalidate() {
		if (remeshing) {
			// Our own re-mesh: the snapshot it draws with was just published.
			return;
		}
		// A resource reload ends with allChanged(); anything else (a new level, render distance) changes no input.
		rebuild(Minecraft.getInstance());
	}

	/** Builds and publishes a snapshot when an input changed; true when what is drawn changed. */
	static boolean rebuild(Minecraft minecraft) {
		WorldIndex index = ClientWorldIndex.current();
		SlotMap slots = index == null ? null : ClientWorldIndex.slotMap();
		String artSet = LoadedPack.artSet();
		List<String> artSets = LoadedPack.artSets();
		// MG7: the shapes are the followed region's, drawn with the loaded set of its game
		String shapeSet = index == null ? artSet : LoadedPack.artSetFor(index.game());
		long generation = LoadedPack.generation();
		boolean kanto = index != null;
		ShapeMap.Lookup shapes = kanto ? ClientShapes.lookup() : null;
		ShapeTable table = shapes == null ? null : ClientShapes.table();
		// The closed story art comes with the verified shape map even when shapes are off: its fallback is the tile cube.
		ShapeMap overlays = kanto ? ClientShapes.map() : null;
		PackOverrides packs = KbPackReload.current();
		RegionRecord followed = kanto ? ClientRegions.followed() : null;
		List<Other> others = kanto ? others(followed == null ? null : followed.id()) : List.of();
		boolean drawingChanges = !sameOthers(others, builtOthers) || generation != builtGeneration || slots != builtSlots || !Objects.equals(artSet, builtArtSet) || shapes != builtShapes
				|| table != builtTable || overlays != builtOverlays || index != builtIndex || packs != builtPacks || !artSets.equals(builtArtSets)
				|| !Objects.equals(shapeSet, builtShapeSet);
		if (!drawingChanges && kanto == builtKanto) {
			return false;
		}
		TextureAtlas atlas;
		try {
			atlas = minecraft.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
			atlas.getSprite(TileUnbakedModel.PLACEHOLDER);
		} catch (RuntimeException e) {
			// No atlas yet (before the first resource load): the boot snapshot stays.
			return false;
		}
		long started = System.nanoTime();
		ClosedArt closedArt = ClosedArt.of(overlays, index);
		RenderSnapshot snapshot = build(atlas, RendererAccess.INSTANCE.hasRenderer() ? RendererAccess.INSTANCE.getRenderer() : null, publishes,
				kanto, slots, artSet, artSets, shapeSet, shapes, table, closedArt, closedArt == null ? new BitSet() : closedArt.bits(ClientStoryView.drawnKeys()),
				others);
		RenderSnapshot.publish(snapshot);
		publishes++;
		builtGeneration = generation;
		builtSlots = slots;
		builtArtSet = artSet;
		builtArtSets = artSets;
		builtShapeSet = shapeSet;
		builtKanto = kanto;
		builtShapes = shapes;
		builtOverlays = overlays;
		builtIndex = index;
		builtPacks = packs;
		builtTable = table;
		builtOthers = others;
		if (kanto && drawingChanges) {
			KantoBridge.LOGGER.info("render: snapshot {} for slot map {}: {} keys, {} without art (art set {}), shapes {}, other regions' shapes {}, {} ms",
					publishes, slots == null ? "-" : Hashes.shortHash(slots.hash()), snapshot.keys(), snapshot.missing(), artSet == null ? "-" : artSet,
					table == null ? "off" : table.built(), others.size(), (System.nanoTime() - started) / 1_000_000L);
		}
		// Entering or leaving a Kanto world without a slot map draws the same placeholders: no re-mesh for that alone.
		return drawingChanges;
	}

	/**
	 * A snapshot of the slot map's art from the atlas; every slot's cube baked for the renderer (none without one).
	 *
	 * @param artSet   the pack's own art set (legacy keys)
	 * @param artSets  every loaded art set (a region's key takes the one of its game, MG7)
	 * @param shapeSet the art set the shape table's textures are drawn with (the followed region's game's)
	 */
	static RenderSnapshot build(TextureAtlas atlas, @Nullable Renderer renderer, long generation, boolean kanto, @Nullable SlotMap slots,
			@Nullable String artSet, List<String> artSets, @Nullable String shapeSet, ShapeMap.@Nullable Lookup shapes, @Nullable ShapeTable table,
			@Nullable ClosedArt closedArt, BitSet closed) {
		return build(atlas, renderer, generation, kanto, slots, artSet, artSets, shapeSet, shapes, table, closedArt, closed, List.of());
	}

	/** {@link #build} with the other regions' shapes, each drawn with its own game's art set (MG3). */
	static RenderSnapshot build(TextureAtlas atlas, @Nullable Renderer renderer, long generation, boolean kanto, @Nullable SlotMap slots,
			@Nullable String artSet, List<String> artSets, @Nullable String shapeSet, ShapeMap.@Nullable Lookup shapes, @Nullable ShapeTable table,
			@Nullable ClosedArt closedArt, BitSet closed, List<Other> others) {
		TextureAtlasSprite placeholder = atlas.getSprite(TileUnbakedModel.PLACEHOLDER);
		RenderMaterial solid = renderer == null ? null : renderer.materialFinder().blendMode(BlendMode.SOLID).find();
		RenderMaterial cutout = renderer == null ? null : renderer.materialFinder().blendMode(BlendMode.CUTOUT).find();
		RenderMaterial flat = renderer == null ? null
				: renderer.materialFinder().blendMode(BlendMode.SOLID).ambientOcclusion(net.fabricmc.fabric.api.util.TriState.FALSE).find();
		// The shape table's texture keys, each to this client's own sprite (a key it can't draw: the placeholder); a pack's
		// texture first (V6); a pack model's colour: the white sprite, tinted.
		PackOverrides packs = KbPackReload.current();
		TextureAtlasSprite[] shapeSprites = new TextureAtlasSprite[table == null ? 0 : table.textureKeys().size()];
		int[] shapeTints = new int[shapeSprites.length];
		resolve(atlas, packs, table, shapeSet, shapeSprites, shapeTints);
		List<RenderSnapshot.Layer> layers = new ArrayList<>();
		for (Other other : others) {
			TextureAtlasSprite[] otherSprites = new TextureAtlasSprite[other.table().textureKeys().size()];
			int[] otherTints = new int[otherSprites.length];
			resolve(atlas, packs, other.table(), other.artSet(), otherSprites, otherTints);
			layers.add(new RenderSnapshot.Layer(other.lookup(), other.table(), otherSprites, otherTints));
		}
		MeshBuilder builder = renderer == null ? null : renderer.meshBuilder();
		Mesh[] placeholderCubes = new Mesh[2];
		if (builder != null) {
			placeholderCubes[CubeEmitter.FULL] = CubeEmitter.mesh(builder, CubeEmitter.FULL, placeholder, solid);
			placeholderCubes[CubeEmitter.HALF] = CubeEmitter.mesh(builder, CubeEmitter.HALF, placeholder, solid);
		}
		TextureAtlasSprite[][] sprites = new TextureAtlasSprite[2][];
		Mesh[][] cubes = new Mesh[2][];
		int keys = 0;
		int missing = -1;
		int foreign = 0;
		if (slots != null) {
			missing = 0;
			for (int shape = CubeEmitter.FULL; shape <= CubeEmitter.HALF; shape++) {
				sprites[shape] = new TextureAtlasSprite[SlotMap.CAPACITY];
				cubes[shape] = new Mesh[SlotMap.CAPACITY];
			}
			for (Map.Entry<SlotMap.Key, SlotMap.Slot> entry : slots.slots().entrySet()) {
				SlotMap.Key key = entry.getKey();
				int shape = CubeEmitter.shape(key.shape() == SlotMap.Shape.HALF);
				int index = entry.getValue().index();
				keys++;
				// MG3: a version-2 map's key names the art set of its region's game; MG7: it is drawn with the loaded set of that
				// game (or a compatible one). A key no loaded set can draw (another family's region whose art this client hasn't
				// got) is a placeholder, not a missing texture of its own set.
				String set = key.set().isEmpty() ? artSet : PackInfo.drawSet(artSets, key.set());
				if (!key.set().isEmpty() && set == null) {
					foreign++;
					continue;
				}
				TextureAtlasSprite sprite = set == null ? null : tileSprite(atlas, packs, set, key.atlas(), key.palette(), key.tile());
				if (sprite == null) {
					missing++;
					continue;
				}
				sprites[shape][index] = sprite;
				if (builder != null) {
					cubes[shape][index] = CubeEmitter.mesh(builder, shape, sprite, solid);
				}
			}
		}
		if (foreign > 0) {
			KantoBridge.LOGGER.info("render: {} keys of other regions' art sets drawn as placeholders (art set {})", foreign, artSet == null ? "-" : artSet);
		}
		return new RenderSnapshot(generation, placeholder, slots == null ? null : slots.hash(), sprites, cubes, placeholderCubes, solid, kanto,
				artSet, keys, missing, table == null ? null : shapes, table, shapeSprites, shapeTints, cutout, flat, closedArt, closed, layers, foreign);
	}

	/**
	 * A shape table's texture keys, each to this client's own sprite in an art set (a key it can't draw: null, the
	 * placeholder); a pack's texture first (V6); a pack model's colour: the white sprite, tinted (else -1).
	 */
	static void resolve(TextureAtlas atlas, PackOverrides packs, @Nullable ShapeTable table, @Nullable String set, TextureAtlasSprite[] sprites,
			int[] tints) {
		java.util.Arrays.fill(tints, -1);
		for (int i = 0; i < sprites.length; i++) {
			String text = table.textureKeys().get(i);
			if (text.startsWith(ShapeTable.COLOUR)) {
				sprites[i] = found(atlas, WHITE);
				tints[i] = 0xFF000000 | Integer.parseInt(text.substring(ShapeTable.COLOUR.length()), 16);
				continue;
			}
			TextureKey key = TextureKey.parse(text);
			if (key == null || set == null) {
				continue;
			}
			sprites[i] = tileSprite(atlas, packs, set, key.atlas(), key.palette(), key.tile());
		}
	}

	/**
	 * A tile's sprite: a pack's texture when it is in the atlas (V6), else the generated one; null when neither is (the
	 * placeholder is drawn).
	 */
	static @Nullable TextureAtlasSprite tileSprite(TextureAtlas atlas, PackOverrides packs, String artSet, String atlasId, String palette, int tile) {
		String override = packs.tileTexture(atlasId, palette, tile);
		ResourceLocation id = override == null ? null : ResourceLocation.tryParse(override);
		TextureAtlasSprite sprite = id == null ? null : found(atlas, id);
		return sprite != null ? sprite
				: found(atlas, ResourceLocation.fromNamespaceAndPath(ArtKeys.NAMESPACE, ArtKeys.texturePath(artSet, atlasId, palette, tile)));
	}

	/** A sprite of the atlas, or null when the atlas lacks it. */
	static @Nullable TextureAtlasSprite found(TextureAtlas atlas, ResourceLocation id) {
		TextureAtlasSprite sprite = atlas.getSprite(id);
		return MissingTextureAtlasSprite.getLocation().equals(sprite.contents().name()) ? null : sprite;
	}
}
