package dev.kantobridge.client.shape;

import dev.kantobridge.KantoBridge;
import dev.kantobridge.block.KbBlocks;
import dev.kantobridge.block.ShapeBlock;
import dev.kantobridge.block.TileBlock;
import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.shape.geom.ShapeTable;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.packs.GeneratedPack;
import dev.kantobridge.client.packs.KbPackReload;
import dev.kantobridge.client.packs.PackInfo;
import dev.kantobridge.client.packs.PackOverrides;
import dev.kantobridge.client.world.ClientWorldIndex;
import dev.kantobridge.scene.ArtSource;
import dev.kantobridge.scene.gen1.BundleArtSource;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.Scale;
import dev.kantobridge.world.ShapeIndex;
import dev.kantobridge.world.ShapeMap;
import dev.kantobridge.world.WorldIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

/**
 * The world's shapes on this client (docs/specs/VOXEL_BLOCKS_PLAN.md §5.8): the shape index and shape map the server
 * sent ({@code world.shapes}), checked against each other and against the WorldIndex this client holds, and the shape
 * table built from them, off the client thread, with this client's own art (from V4: its export bundle, for tufts and
 * cell units); cached under {@code <gameDir>/kantobridge/shapes/} by index, geometry version and art (ROM-derived,
 * never sent). Until the table is ready, or when anything doesn't fit, the world draws cubes and {@link #reason()} says
 * why. Client thread only, except the table build.
 */
public final class ClientShapes {
	/** A shape map larger than this, inflated, is refused (the real build's is a few MB). */
	static final int MAX_MAP_BYTES = 64 * 1024 * 1024;
	/** {@link #topHeight} counts at most this many blocks. */
	static final int MAX_TOP = 8;

	private static boolean registered;
	private static @Nullable ShapeIndex index;
	private static @Nullable ShapeMap map;
	private static ShapeMap.@Nullable Lookup lookup;
	private static @Nullable ShapeTable table;
	private static @Nullable String reason = "no_shapes";
	private static long generation;
	private static long tableMs = -1;
	private static boolean fromCache;
	private static @Nullable String builtArt;
	private static @Nullable Runnable onChange;
	/** Per map id, per cell: the colliding blocks from feet level up (-1 until the cell's chunk was loaded). */
	private static final Map<String, int[]> TOPS = new HashMap<>();

	private ClientShapes() {
	}

	/** Call once from client init, after {@link ClientWorldIndex#init()}; {@code changed} runs when what is drawn changes. */
	public static void init(Runnable changed) {
		if (registered) {
			return;
		}
		registered = true;
		onChange = changed;
		ClientWorldIndex.onShapes(ClientShapes::received);
		ClientWorldIndex.addListener(() -> {
			TOPS.clear();
			// A new index (a rebuild) makes shapes bound to the old one stale until its own arrive.
			ShapeMap held = map;
			if (held != null && !held.indexHash().equals(ClientWorldIndex.currentHash())) {
				clear();
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
	}

	/** True when the player asked for shapes ({@code kantobridge-client.json}). */
	public static boolean enabled() {
		BridgeRuntime bridge = BridgeRuntime.get();
		return bridge == null || bridge.config().shapes();
	}

	/** The verified shape map's lookup, or null (cubes everywhere). */
	public static ShapeMap.@Nullable Lookup lookup() {
		return enabled() && table != null ? lookup : null;
	}

	/** The shape table, or null while it builds or without shapes. */
	public static @Nullable ShapeTable table() {
		return enabled() ? table : null;
	}

	/** The verified shape map (with the closed art's overlays, V5), or null; held even with shapes off. */
	public static @Nullable ShapeMap map() {
		return map;
	}

	/** The verified shape index, or null. */
	public static @Nullable ShapeIndex index() {
		return index;
	}

	/** Shape ids this client has geometry for; 0 while it draws cubes. */
	public static int shapes() {
		ShapeTable built = table();
		return built == null ? 0 : built.built();
	}

	/** Why the world draws cubes, or null when it draws its shapes. */
	public static @Nullable String reason() {
		return enabled() ? reason : "shapes_off";
	}

	public static void clear() {
		generation++;
		index = null;
		map = null;
		lookup = null;
		table = null;
		builtArt = null;
		reason = "no_shapes";
		TOPS.clear();
		changed();
	}

	/** Both shape documents are in: check them, then build the table off-thread. */
	static void received(String indexText, String mapWire) {
		generation++;
		table = null;
		lookup = null;
		ShapeCheck.Accepted accepted;
		try {
			accepted = ShapeCheck.check(indexText, mapWire, ClientWorldIndex.currentHash(), MAX_MAP_BYTES);
		} catch (ShapeCheck.Rejected e) {
			fail(e.code(), "world.shapes: refused (" + e.getMessage() + "); drawing cubes");
			return;
		}
		index = accepted.index();
		map = accepted.map();
		reason = "building";
		changed();
		startBuild();
	}

	/**
	 * This player's art may have changed (a new export and pack): when the held table was built from other art, build it
	 * again. Client thread.
	 */
	public static void artChanged() {
		if (index == null || map == null) {
			return;
		}
		Art art = Art.local();
		if (art.hash().equals(builtArt)) {
			return;
		}
		generation++;
		startBuild();
	}

	/** Builds (or reads from the cache) the table for the held index and map, off the client thread. */
	private static void startBuild() {
		ShapeIndex parsed = index;
		ShapeMap received = map;
		if (parsed == null || received == null) {
			return;
		}
		long mine = generation;
		Art art = Art.local();
		Path cache = FabricLoader.getInstance().getGameDir().resolve("kantobridge").resolve("shapes")
				.resolve(ShapeTable.cacheName(parsed.hash(), ShapeTable.GEOMETRY, art.hash()));
		Minecraft minecraft = Minecraft.getInstance();
		Util.backgroundExecutor().execute(() -> {
			long started = System.nanoTime();
			boolean cached = false;
			ShapeTable built = null;
			try {
				if (Files.isRegularFile(cache)) {
					built = ShapeTable.decode(Files.readAllBytes(cache), parsed.hash(), ShapeTable.GEOMETRY, art.hash());
					cached = true;
				}
			} catch (IOException | DataException e) {
				built = null;
			}
			if (built == null) {
				built = ShapeTable.build(parsed, art.source(), art.hash(), art.packs());
				try {
					Files.createDirectories(cache.getParent());
					Files.write(cache, built.encode());
				} catch (IOException e) {
					KantoBridge.LOGGER.warn("shapes: could not write the shape table cache ({})", e.getClass().getSimpleName());
				}
			}
			ShapeTable done = built;
			boolean wasCached = cached;
			long ms = (System.nanoTime() - started) / 1_000_000L;
			ShapeMap.Lookup fast = received.lookup();
			minecraft.execute(() -> {
				if (mine != generation) {
					return;
				}
				table = done;
				lookup = fast;
				tableMs = ms;
				fromCache = wasCached;
				builtArt = art.hash();
				reason = null;
				KantoBridge.LOGGER.info("shapes: table for index {} ready: {} shapes, {} quads, {} textures, art {}, {} ms{}",
						Hashes.shortHash(parsed.hash()), done.built(), done.quads(), done.textureKeys().size(),
						art.source() == ArtSource.NONE ? "none" : Hashes.shortHash(art.hash()), ms, wasCached ? " (cache)" : "");
				changed();
			});
		});
	}

	/**
	 * This client's art for geometry: the export bundle its generated pack was made from ({@code export/<game>/}), or none
	 * (units then draw their column version and tufts nothing), with the texture packs' rules and models (V6). Its hash
	 * names the export and the packs' geometry in the cache file's name.
	 */
	record Art(ArtSource source, String hash, PackOverrides packs) {
		/** Without an export: units draw their column version, tufts nothing; a pack's models and rules still apply. */
		private static Art none(PackOverrides packs) {
			String geometry = packs.geometryHash();
			return new Art(ArtSource.NONE, geometry.equals(ShapeTable.NO_OVERRIDES) ? ShapeTable.NO_OVERRIDES : Hashes.sha256("art|-|" + geometry), packs);
		}

		static Art local() {
			Path gameDir = FabricLoader.getInstance().getGameDir();
			PackInfo pack = PackInfo.read(GeneratedPack.directory(gameDir));
			PackOverrides packs = KbPackReload.current();
			if (pack == null || !pack.usable()) {
				return none(packs);
			}
			Path bundleDir = gameDir.resolve("kantobridge").resolve("export").resolve(pack.game());
			if (!Files.isRegularFile(bundleDir.resolve("index.json"))) {
				return none(packs);
			}
			return new Art(new LazyArt(bundleDir), Hashes.sha256("art|" + pack.exportHash() + "|" + packs.geometryHash()), packs);
		}
	}

	/** The bundle's pixels, opened on the first tile asked for (on the build thread); none when it can't be read. */
	private static final class LazyArt implements ArtSource {
		private final Path dir;
		private @Nullable ArtSource opened;

		LazyArt(Path dir) {
			this.dir = dir;
		}

		private synchronized ArtSource art() {
			if (this.opened == null) {
				try {
					this.opened = BundleArtSource.of(ExportBundle.open(this.dir));
				} catch (BundleException | RuntimeException e) {
					KantoBridge.LOGGER.warn("shapes: this player's export can't be read ({}); units draw as columns", e.getClass().getSimpleName());
					this.opened = ArtSource.NONE;
				}
			}
			return this.opened;
		}

		@Override
		public int @Nullable [] tileShades(String atlas, int tile) {
			return this.art().tileShades(atlas, tile);
		}

		@Override
		public java.util.List<int[]> frameShades(String atlas, int tile) {
			return this.art().frameShades(atlas, tile);
		}

		@Override
		public int artPx() {
			return this.art().artPx();
		}
	}

	/**
	 * How many blocks a puppet standing on cell (cx, cy) of a map stands on (docs/specs/VOXEL_BLOCKS_PLAN.md §5.7): the
	 * colliding carriers from feet level up in the cell's north-west column of this client's level, so an item on a
	 * table sits on the table. 0 for a walkable cell; cached per cell once its chunk is loaded.
	 */
	public static int topHeight(WorldIndex worldIndex, String mapId, int cx, int cy) {
		MapPlacement placement = worldIndex.map(mapId);
		ClientLevel level = Minecraft.getInstance().level;
		if (placement == null || level == null) {
			return 0;
		}
		if (cx < 0 || cy < 0 || cx >= placement.w() || cy >= placement.h()) {
			return 0;
		}
		int[] cells = TOPS.get(mapId);
		if (cells == null) {
			cells = new int[placement.w() * placement.h()];
			java.util.Arrays.fill(cells, -1);
			TOPS.put(mapId, cells);
		}
		int i = cy * placement.w() + cx;
		if (cells[i] >= 0) {
			return cells[i];
		}
		BlockPos feet = new BlockPos(placement.x() + cx * Scale.CELL_BLOCKS, placement.y(), placement.z() + cy * Scale.CELL_BLOCKS);
		if (!level.hasChunkAt(feet)) {
			return 0;
		}
		int top = 0;
		while (top < MAX_TOP && colliding(level, feet.above(top))) {
			top++;
		}
		cells[i] = top;
		return top;
	}

	/** A full-cube collider: {@code tile_full} or {@code shape_full}. */
	private static boolean colliding(ClientLevel level, BlockPos pos) {
		Block block = level.getBlockState(pos).getBlock();
		return block instanceof ShapeBlock || (block instanceof TileBlock tile && KbBlocks.tileFull().contains(tile));
	}

	private static void fail(String code, String log) {
		index = null;
		map = null;
		reason = code;
		KantoBridge.LOGGER.warn(log);
		changed();
	}

	private static void changed() {
		if (onChange != null) {
			onChange.run();
		}
	}

	/** The F7 line. */
	public static String debugLine() {
		ShapeTable built = table;
		ShapeIndex known = index;
		return I18n.get("kantobridge.debug.shapes", enabled() ? I18n.get("kantobridge.debug.yes") : I18n.get("kantobridge.debug.no"),
				known == null ? "-" : Hashes.shortHash(known.hash()), known == null ? 0 : known.size(), built == null ? 0 : built.built(),
				built == null ? 0 : built.quads(), tableMs, fromCache ? I18n.get("kantobridge.debug.yes") : I18n.get("kantobridge.debug.no"),
				reason() == null ? "-" : reason());
	}
}
