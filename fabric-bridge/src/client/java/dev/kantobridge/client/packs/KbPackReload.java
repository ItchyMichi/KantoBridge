package dev.kantobridge.client.packs;

import dev.kantobridge.KantoBridge;
import dev.kantobridge.builder.pack.PackKey;
import dev.kantobridge.builder.pack.Textures;
import dev.kantobridge.builder.shape.geom.CellUnits;
import dev.kantobridge.builder.shape.geom.QuadMerger;
import dev.kantobridge.builder.shape.geom.ShapeOverrides;
import dev.kantobridge.builder.shape.geom.ShapeQuad;
import dev.kantobridge.builder.shape.geom.ShapeTable;
import dev.kantobridge.client.shape.vox.VoxFile;
import dev.kantobridge.client.shape.vox.VoxMesher;
import dev.kantobridge.world.Hashes;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleResourceReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.Nullable;

/**
 * Reads the texture packs on every resource reload, F3+T included (docs/specs/VOXEL_BLOCKS_PLAN.md §5.10): each enabled
 * resource pack's {@code assets/kantobridge/kb_pack/*.json}, lowest priority first, parsed and validated
 * ({@link KbPackManifest}); textures and {@code .vox} files checked through the resource manager; models meshed; keys
 * checked against this player's key index when there is one; merged into one {@link PackOverrides}, off the client
 * thread. The result is swapped in on the client thread, and the shape table, the snapshot and the puppets follow. A
 * manifest that doesn't target this player's game is skipped silently. Codes, counts and pack ids only reach the log.
 */
public final class KbPackReload implements SimpleResourceReloadListener<PackOverrides> {
	public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "kb_packs");
	/** The pixels a pack's block textures may add: half of an 8192² atlas (a stitch failure is a crash). */
	static final long MAX_TEXTURE_AREA = 8192L * 8192L / 2;
	/** Voxels a model may hold. */
	static final int MAX_VOXELS = 1 << 18;

	private static volatile PackOverrides current = PackOverrides.NONE;
	private static final List<Runnable> LISTENERS = new ArrayList<>();
	private static boolean registered;

	private KbPackReload() {
	}

	/** Call once from client init. */
	public static void init() {
		if (registered) {
			return;
		}
		registered = true;
		ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new KbPackReload());
	}

	/** The overrides in force (none before the first reload). Any thread. */
	public static PackOverrides current() {
		return current;
	}

	/** Runs on the client thread after each reload that swapped in new overrides. */
	public static void onChange(Runnable listener) {
		LISTENERS.add(listener);
	}

	@Override
	public ResourceLocation getFabricId() {
		return ID;
	}

	@Override
	public CompletableFuture<PackOverrides> load(ResourceManager manager, ProfilerFiller profiler, Executor executor) {
		return CompletableFuture.supplyAsync(() -> {
			Path gameDir = FabricLoader.getInstance().getGameDir();
			PackInfo pack = PackInfo.read(GeneratedPack.directory(gameDir));
			String game = pack == null ? null : pack.game();
			KeyIndex keys = game == null ? null : KeyIndex.read(KeyIndex.file(gameDir, game));
			try {
				return read(manager, game, keys);
			} catch (RuntimeException e) {
				KantoBridge.LOGGER.error("packs: could not read the texture packs ({}); none applied", e.getClass().getSimpleName());
				return PackOverrides.NONE;
			}
		}, executor);
	}

	@Override
	public CompletableFuture<Void> apply(PackOverrides data, ResourceManager manager, ProfilerFiller profiler, Executor executor) {
		return CompletableFuture.runAsync(() -> {
			PackOverrides before = current;
			current = data;
			if (!data.packs().isEmpty() || !data.warnings().isEmpty()) {
				KantoBridge.LOGGER.info("packs: {} applied ({} entries), skipped {}", data.packs(), data.applied(), data.warnings());
			}
			if (before != data) {
				for (Runnable listener : LISTENERS) {
					listener.run();
				}
			}
		}, executor);
	}

	/** A manifest file of one pack: the pack's place in the load order, the file, its resource. */
	private record Found(int pack, ResourceLocation file, Resource resource) {
	}

	/**
	 * Reads every manifest of the manager into one result: packs lowest priority first (a later one wins per key), a
	 * pack's manifests by file name.
	 */
	static PackOverrides read(ResourceManager manager, @Nullable String game, @Nullable KeyIndex keys) {
		PackOverrides.Builder out = new PackOverrides.Builder();
		Map<ResourceLocation, List<Resource>> stacks = manager.listResourceStacks("kb_pack",
				id -> id.getNamespace().equals(KantoBridge.MOD_ID) && id.getPath().endsWith(".json"));
		List<String> order = manager.listPacks().map(PackResources::packId).toList();
		List<Found> found = new ArrayList<>();
		for (Map.Entry<ResourceLocation, List<Resource>> file : stacks.entrySet()) {
			for (Resource resource : file.getValue()) {
				found.add(new Found(order.indexOf(resource.sourcePackId()), file.getKey(), resource));
			}
		}
		found.sort(Comparator.comparingInt(Found::pack).thenComparing(Found::file));
		for (Found manifestFile : found) {
			Resource resource = manifestFile.resource();
			String text;
			try (Reader reader = resource.openAsReader()) {
				StringBuilder buffer = new StringBuilder();
				char[] chunk = new char[4096];
				for (int n; (n = reader.read(chunk)) > 0;) {
					buffer.append(chunk, 0, n);
				}
				text = buffer.toString();
			} catch (IOException e) {
				out.warn("pack.format");
				continue;
			}
			KbPackManifest.Result result = KbPackManifest.parse(text);
			out.warnings(result.warnings());
			KbPackManifest.Manifest manifest = result.manifest();
			if (manifest == null || (game != null && !manifest.games().contains(game))) {
				continue;
			}
			apply(manager, manifest, keys, out);
		}
		return out.build();
	}

	/** One manifest's entries that pass the checks that need the resource manager and the key index. */
	static void apply(ResourceManager manager, KbPackManifest.Manifest manifest, @Nullable KeyIndex keys, PackOverrides.Builder out) {
		long area = 0;
		List<KbPackManifest.Texture> blockTextures = new ArrayList<>();
		for (KbPackManifest.Texture texture : manifest.textures()) {
			if (keys != null && !keys.contains(texture.key())) {
				out.warn("pack.unknown_key");
				continue;
			}
			boolean tile = texture.key().kind() == PackKey.Kind.TILE;
			ResourceLocation file = tile ? textureFile(texture.texture()) : ResourceLocation.tryParse(texture.texture());
			int[] size = pngSize(manager, file);
			if (size == null) {
				out.warn("pack.texture_missing");
				continue;
			}
			if (tile) {
				// A block face: a square power of two of at least 16 (so it can't lower the atlas's mip level), or a strip of
				// such frames with its animation file.
				boolean square = size[0] == size[1];
				boolean strip = size[1] > size[0] && size[1] % size[0] == 0
						&& manager.getResource(file.withPath(file.getPath() + ".mcmeta")).isPresent();
				if (size[0] < 16 || Integer.bitCount(size[0]) != 1 || !(square || strip)) {
					out.warn("pack.texture_size");
					continue;
				}
				area += (long) size[0] * size[1];
				blockTextures.add(texture);
			} else {
				// A character sheet: a whole multiple of the generated layout.
				int w = Textures.SHEET_WIDTH;
				int h = Textures.SHEET_HEIGHT;
				if (size[0] == 0 || size[0] % w != 0 || size[1] % h != 0 || size[0] / w != size[1] / h) {
					out.warn("pack.texture_size");
					continue;
				}
				out.texture(texture);
			}
		}
		if (area > MAX_TEXTURE_AREA) {
			// The pack's block textures would crowd the atlas: refuse them all rather than risk the stitch.
			for (int i = 0; i < blockTextures.size(); i++) {
				out.warn("pack.atlas_size");
			}
		} else {
			blockTextures.forEach(out::texture);
		}
		for (KbPackManifest.Model model : manifest.models()) {
			PackKey key = model.key();
			if (keys != null && (key.kind() == PackKey.Kind.CELL || key.kind() == PackKey.Kind.SPRITE) && !keys.contains(key)) {
				out.warn("pack.unknown_key");
				continue;
			}
			byte[] bytes = bytes(manager, ResourceLocation.tryParse(model.vox()));
			if (bytes == null) {
				out.warn("pack.vox_format");
				continue;
			}
			VoxFile.Model vox;
			try {
				vox = VoxFile.read(bytes);
			} catch (VoxFile.VoxException e) {
				out.warn(e.code());
				continue;
			}
			if (vox.count() > MAX_VOXELS) {
				out.warn("pack.vox_size");
				continue;
			}
			if (key.kind() != PackKey.Kind.CELL) {
				// Structures and placements (V7) and sprites (V8): checked here, drawn by their own milestones.
				out.model(model);
				continue;
			}
			VoxMesher.Mesh mesh;
			try {
				mesh = VoxMesher.mesh(vox, model.voxelsPerBlock(), model.turn(), model.flip(), model.origin());
			} catch (IllegalArgumentException e) {
				out.warn("pack.vox_size");
				continue;
			}
			ShapeOverrides.Model unit = unitModel(mesh);
			if (unit == null) {
				out.warn("pack.vox_size");
				continue;
			}
			if (mesh.maxQuads() > CellUnits.MAX_QUADS) {
				out.warn("pack.model_budget");
				continue;
			}
			String print = Hashes.sha256(bytes) + "|" + model.voxelsPerBlock() + "|" + model.turn() + "|" + model.flip() + "|"
					+ Arrays.toString(model.origin());
			out.cellModel(key, unit, print);
		}
		for (KbPackManifest.Rule rule : manifest.rules()) {
			if (keys != null && rule.key().kind() == PackKey.Kind.TILE && !keys.contains(rule.key())) {
				out.warn("pack.unknown_key");
				continue;
			}
			out.rule(rule);
		}
		out.manifest(manifest);
	}

	/** A meshed model as a cell unit's eight blocks, or null when it doesn't fit the unit's 2 × 2 × 2 blocks. */
	static ShapeOverrides.@Nullable Model unitModel(VoxMesher.Mesh mesh) {
		int per = ShapeTable.UNIT_CELL;
		if (mesh.bw() > per || mesh.bh() > per || mesh.bd() > per) {
			return null;
		}
		List<List<ShapeQuad>> blocks = new ArrayList<>();
		for (int i = 0; i < per * per * per; i++) {
			blocks.add(List.of());
		}
		for (Map.Entry<Integer, List<ShapeQuad>> e : mesh.blocks().entrySet()) {
			int key = e.getKey();
			blocks.set(CellUnits.block(per, QuadMerger.blockX(key), QuadMerger.blockY(key), QuadMerger.blockZ(key)), List.copyOf(e.getValue()));
		}
		return new ShapeOverrides.Model(List.copyOf(blocks), mesh.palette());
	}

	/**
	 * The texture file of a block-atlas sprite id: {@code ns:block/x} is {@code ns:textures/block/x.png}. Null for an id
	 * outside {@code block/}, which the block atlas doesn't stitch.
	 */
	static @Nullable ResourceLocation textureFile(String spriteId) {
		ResourceLocation id = ResourceLocation.tryParse(spriteId);
		return id == null || !id.getPath().startsWith("block/") ? null
				: ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "textures/" + id.getPath() + ".png");
	}

	/** A PNG's width and height from its header, or null when it is missing or not a PNG. */
	static int @Nullable [] pngSize(ResourceManager manager, @Nullable ResourceLocation file) {
		if (file == null) {
			return null;
		}
		Optional<Resource> resource = manager.getResource(file);
		if (resource.isEmpty()) {
			return null;
		}
		try (InputStream in = resource.get().open()) {
			byte[] head = in.readNBytes(24);
			if (head.length < 24 || head[0] != (byte) 0x89 || head[1] != 'P' || head[2] != 'N' || head[3] != 'G') {
				return null;
			}
			ByteBuffer b = ByteBuffer.wrap(head);
			return new int[] {b.getInt(16), b.getInt(20)};
		} catch (IOException e) {
			return null;
		}
	}

	private static byte @Nullable [] bytes(ResourceManager manager, @Nullable ResourceLocation file) {
		if (file == null) {
			return null;
		}
		Optional<Resource> resource = manager.getResource(file);
		if (resource.isEmpty()) {
			return null;
		}
		try (InputStream in = resource.get().open()) {
			return in.readAllBytes();
		} catch (IOException e) {
			return null;
		}
	}

	/** The F7 line: packs applied, entries, what was skipped by code. */
	public static String debugLine() {
		PackOverrides packs = current;
		StringBuilder skipped = new StringBuilder();
		packs.warnings().forEach((code, n) -> skipped.append(skipped.isEmpty() ? "" : " ").append(code.substring(code.indexOf('.') + 1)).append('×').append(n));
		return I18n.get("kantobridge.debug.packs", packs.packs().size(), packs.applied(), skipped.isEmpty() ? "-" : skipped.toString());
	}
}
