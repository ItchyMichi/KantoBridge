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
import dev.kantobridge.client.world.ClientRegions;
import dev.kantobridge.client.world.ClientWorldIndex;
import dev.kantobridge.scene.ArtSource;
import dev.kantobridge.scene.FrontEnds;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.RegionRecord;
import dev.kantobridge.world.ShapeIndex;
import dev.kantobridge.world.ShapeMap;
import dev.kantobridge.world.WorldIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
 * The world's shapes on this client (docs/specs/VOXEL_BLOCKS_PLAN.md §5.8), per region since MG3
 * (docs/specs/MULTI_GAME_PLAN.md §4.6): each region's shape index and shape map the server sent ({@code world.shapes}
 * tagged with the region), checked against each other and against that region's WorldIndex, and the shape table built
 * from them, off the client thread, with this client's own art for that region's game (from V4: its export bundle, for
 * tufts and cell units); cached under {@code <gameDir>/kantobridge/shapes/} by index, geometry version and art
 * (ROM-derived, never sent). The render snapshot draws the followed region's shapes (the no-argument views); a region
 * whose game this client has no art for builds its table without art (units as columns, tufts nothing). Until a table
 * is ready, or when anything doesn't fit, that region draws cubes and {@link #reason(String)} says why. Client thread
 * only, except the table builds.
 */
public final class ClientShapes {
	/** A shape map larger than this, inflated, is refused (Hoenn's with the landscape models is about 25 MB). */
	static final int MAX_MAP_BYTES = 256 * 1024 * 1024;
	/** {@link #topHeight} counts at most this many blocks. */
	static final int MAX_TOP = 8;

	/** One region's shapes. */
	private static final class Held {
		@Nullable ShapeIndex index;
		@Nullable ShapeMap map;
		ShapeMap.@Nullable Lookup lookup;
		@Nullable ShapeTable table;
		@Nullable String reason = "no_shapes";
		long generation;
		long tableMs = -1;
		boolean fromCache;
		@Nullable String builtArt;
		/** The shape map wire form this region's held documents were accepted from (so a pair is not accepted twice). */
		@Nullable String acceptedMapWire;
	}

	private static boolean registered;
	private static final Map<String, Held> HELD = new LinkedHashMap<>();
	private static @Nullable Runnable onChange;
	/** Per index (identity), per map id, per cell: the colliding blocks from feet level up (-1 until the chunk was loaded). */
	private static final Map<WorldIndex, Map<String, int[]>> TOPS = new HashMap<>();

	private ClientShapes() {
	}

	/** Call once from client init, after {@link ClientWorldIndex#init()}; {@code changed} runs when what is drawn changes. */
	public static void init(Runnable changed) {
		if (registered) {
			return;
		}
		registered = true;
		onChange = changed;
		ClientRegions.onShapes(ClientShapes::received);
		ClientRegions.addListener(() -> {
			TOPS.clear();
			// A region's new index (a rebuild) makes shapes bound to the old one stale until its own arrive; a region that
			// left the set takes its shapes with it; a pair that arrived before its region was named is accepted now.
			List<String> gone = new ArrayList<>();
			for (Map.Entry<String, Held> entry : HELD.entrySet()) {
				String region = entry.getKey();
				ShapeMap map = entry.getValue().map;
				if (ClientRegions.byId(region) == null) {
					gone.add(region);
				} else if (map != null && !map.indexHash().equals(ClientRegions.indexHash(region))) {
					clearRegion(region);
				}
			}
			for (String region : gone) {
				HELD.remove(region);
			}
			for (String region : ClientRegions.indexed()) {
				ClientRegions.ShapeDocs docs = ClientRegions.shapesOf(region);
				Held held = HELD.get(region);
				if (docs != null && (held == null || !docs.mapWire().equals(held.acceptedMapWire))) {
					received(region, docs.indexText(), docs.mapWire());
				}
			}
			changed();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
	}

	/** True when the player asked for shapes ({@code kantobridge-client.json}). */
	public static boolean enabled() {
		BridgeRuntime bridge = BridgeRuntime.get();
		return bridge == null || bridge.config().shapes();
	}

	/** The followed region's id, or null outside a region. */
	private static @Nullable String followedId() {
		RegionRecord record = ClientRegions.followed();
		return record == null ? null : record.id();
	}

	private static @Nullable Held held(@Nullable String region) {
		return region == null ? null : HELD.get(region);
	}

	private static Held heldOrNew(String region) {
		return HELD.computeIfAbsent(region, id -> new Held());
	}

	/** The followed region's verified shape map lookup, or null (cubes everywhere). */
	public static ShapeMap.@Nullable Lookup lookup() {
		return lookup(followedId());
	}

	/** A region's verified shape map lookup, or null (that region draws cubes). */
	public static ShapeMap.@Nullable Lookup lookup(@Nullable String region) {
		Held held = held(region);
		return enabled() && held != null && held.table != null ? held.lookup : null;
	}

	/** The followed region's shape table, or null while it builds or without shapes. */
	public static @Nullable ShapeTable table() {
		return table(followedId());
	}

	public static @Nullable ShapeTable table(@Nullable String region) {
		Held held = held(region);
		return enabled() && held != null ? held.table : null;
	}

	/** The followed region's verified shape map (with the closed art's overlays, V5), or null; held even with shapes off. */
	public static @Nullable ShapeMap map() {
		return map(followedId());
	}

	public static @Nullable ShapeMap map(@Nullable String region) {
		Held held = held(region);
		return held == null ? null : held.map;
	}

	/** The followed region's verified shape index, or null. */
	public static @Nullable ShapeIndex index() {
		return index(followedId());
	}

	public static @Nullable ShapeIndex index(@Nullable String region) {
		Held held = held(region);
		return held == null ? null : held.index;
	}

	/** Shape ids this client has geometry for in the followed region; 0 while it draws cubes. */
	public static int shapes() {
		ShapeTable built = table();
		return built == null ? 0 : built.built();
	}

	/** Why the followed region draws cubes, or null when it draws its shapes. */
	public static @Nullable String reason() {
		return reason(followedId());
	}

	/** Why a region draws cubes ({@code no_shapes} for one without documents yet), or null when it draws its shapes. */
	public static @Nullable String reason(@Nullable String region) {
		if (!enabled()) {
			return "shapes_off";
		}
		Held held = held(region);
		return held == null ? "no_shapes" : held.reason;
	}

	/** The regions holding shape documents (verified or refused). */
	public static List<String> regions() {
		return new ArrayList<>(HELD.keySet());
	}

	public static void clear() {
		for (Held held : HELD.values()) {
			held.generation++;
		}
		HELD.clear();
		TOPS.clear();
		changed();
	}

	private static void clearRegion(String region) {
		Held held = HELD.get(region);
		if (held == null) {
			return;
		}
		held.generation++;
		held.index = null;
		held.map = null;
		held.lookup = null;
		held.table = null;
		held.builtArt = null;
		held.acceptedMapWire = null;
		held.reason = "no_shapes";
	}

	/** Both of a region's shape documents are in: check them, then build the table off-thread. */
	static void received(String region, String indexText, String mapWire) {
		Held held = heldOrNew(region);
		held.generation++;
		held.table = null;
		held.lookup = null;
		held.acceptedMapWire = mapWire;
		ShapeCheck.Accepted accepted;
		try {
			accepted = ShapeCheck.check(indexText, mapWire, ClientRegions.indexHash(region), MAX_MAP_BYTES);
		} catch (ShapeCheck.Rejected e) {
			fail(held, e.code(), "world.shapes: " + region + " refused (" + e.getMessage() + "); drawing cubes");
			return;
		}
		held.index = accepted.index();
		held.map = accepted.map();
		held.reason = "building";
		changed();
		startBuild(region, held);
	}

	/**
	 * This player's art may have changed (a new export and pack): every region whose held table was built from other art
	 * builds it again. Client thread.
	 */
	public static void artChanged() {
		for (Map.Entry<String, Held> entry : HELD.entrySet()) {
			Held held = entry.getValue();
			if (held.index == null || held.map == null) {
				continue;
			}
			Art art = Art.local(entry.getKey());
			if (art.hash().equals(held.builtArt)) {
				continue;
			}
			held.generation++;
			startBuild(entry.getKey(), held);
		}
	}

	/** Builds (or reads from the cache) a region's table for its held index and map, off the client thread. */
	private static void startBuild(String region, Held held) {
		ShapeIndex parsed = held.index;
		ShapeMap received = held.map;
		if (parsed == null || received == null) {
			return;
		}
		long mine = held.generation;
		Art art = Art.local(region);
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
				built = ShapeTable.build(parsed, art.source(), art.hash(), art.packs(), false);
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
				if (mine != held.generation || HELD.get(region) != held) {
					return;
				}
				held.table = done;
				held.lookup = fast;
				held.tableMs = ms;
				held.fromCache = wasCached;
				held.builtArt = art.hash();
				held.reason = null;
				KantoBridge.LOGGER.info("shapes: table for region {} index {} ready: {} shapes, {} quads, {} textures, art {}, {} ms{}", region,
						Hashes.shortHash(parsed.hash()), done.built(), done.quads(), done.textureKeys().size(),
						art.source() == ArtSource.NONE ? "none" : Hashes.shortHash(art.hash()), ms, wasCached ? " (cache)" : "");
				changed();
			});
		});
	}

	/**
	 * This client's art for a region's geometry: the export bundle its generated pack was made from ({@code export/<game>/})
	 * when that art can draw the region's game (the same game, or one family), else none (units then draw their column
	 * version and tufts nothing: the region's game is one this client has no art for), with the texture packs' rules and
	 * models (V6). Its hash names the export and the packs' geometry in the cache file's name.
	 */
	record Art(ArtSource source, String hash, PackOverrides packs) {
		/** Without an export: units draw their column version, tufts nothing; a pack's models and rules still apply. */
		private static Art none(PackOverrides packs) {
			String geometry = packs.geometryHash();
			return new Art(ArtSource.NONE, geometry.equals(ShapeTable.NO_OVERRIDES) ? ShapeTable.NO_OVERRIDES : Hashes.sha256("art|-|" + geometry), packs);
		}

		/** The art for the followed region (a legacy world's one region). */
		static Art local() {
			RegionRecord record = ClientRegions.followed();
			return local(record == null ? null : record.id());
		}

		static Art local(@Nullable String region) {
			Path gameDir = FabricLoader.getInstance().getGameDir();
			PackInfo pack = PackInfo.read(GeneratedPack.directory(gameDir));
			PackOverrides packs = KbPackReload.current();
			if (pack == null || !pack.usable()) {
				return none(packs);
			}
			RegionRecord record = ClientRegions.byId(region);
			// MG7: the pack's set that draws the region's game (a pack may hold several games' art); another family's region
			// whose art this client hasn't got draws none.
			PackInfo.ArtSet set = record == null ? pack.setFor(pack.game()) : pack.setFor(record.game());
			if (set == null) {
				return none(packs);
			}
			Path bundleDir = gameDir.resolve("kantobridge").resolve("export").resolve(set.game());
			if (!Files.isRegularFile(bundleDir.resolve("index.json"))) {
				return none(packs);
			}
			return new Art(new LazyArt(bundleDir), Hashes.sha256("art|" + set.exportHash() + "|" + packs.geometryHash()), packs);
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
					this.opened = FrontEnds.artSource(ExportBundle.open(this.dir));
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
	 * table sits on the table, and a seat collider on top as half a block (a character on a stool, HAND_MODELS.md section
	 * 6). 0 for a walkable cell; cached per index and cell once its chunk is loaded (two regions may both have a ROUTE_1,
	 * so the cache is per index).
	 */
	public static double topHeight(WorldIndex worldIndex, String mapId, int cx, int cy) {
		MapPlacement placement = worldIndex.map(mapId);
		ClientLevel level = Minecraft.getInstance().level;
		if (placement == null || level == null) {
			return 0;
		}
		if (cx < 0 || cy < 0 || cx >= placement.w() || cy >= placement.h()) {
			return 0;
		}
		Map<String, int[]> maps = TOPS.computeIfAbsent(worldIndex, index -> new HashMap<>());
		int[] cells = maps.get(mapId);
		if (cells == null) {
			cells = new int[placement.w() * placement.h()];
			java.util.Arrays.fill(cells, -1);
			maps.put(mapId, cells);
		}
		int i = cy * placement.w() + cx;
		if (cells[i] >= 0) {
			return cells[i] / 2.0;
		}
		BlockPos feet = worldIndex.cellOrigin(mapId, cx, cy);
		if (feet == null || !level.hasChunkAt(feet)) {
			return 0;
		}
		int top = 0;
		while (top < MAX_TOP && colliding(level, feet.above(top))) {
			top++;
		}
		// in half blocks: a seat collider on the column is half a block more
		int halves = 2 * top + (level.getBlockState(feet.above(top)).is(KbBlocks.SEAT_COLLIDER) ? 1 : 0);
		cells[i] = halves;
		return halves / 2.0;
	}

	/** A full-cube collider: {@code tile_full} or {@code shape_full}. */
	private static boolean colliding(ClientLevel level, BlockPos pos) {
		Block block = level.getBlockState(pos).getBlock();
		return block instanceof ShapeBlock || (block instanceof TileBlock tile && KbBlocks.tileFull().contains(tile));
	}

	private static void fail(Held held, String code, String log) {
		held.index = null;
		held.map = null;
		held.reason = code;
		KantoBridge.LOGGER.warn(log);
		changed();
	}

	private static void changed() {
		if (onChange != null) {
			onChange.run();
		}
	}

	/** The F7 line: the followed region's shapes. */
	public static String debugLine() {
		Held held = held(followedId());
		ShapeTable built = held == null ? null : held.table;
		ShapeIndex known = held == null ? null : held.index;
		return I18n.get("kantobridge.debug.shapes", enabled() ? I18n.get("kantobridge.debug.yes") : I18n.get("kantobridge.debug.no"),
				known == null ? "-" : Hashes.shortHash(known.hash()), known == null ? 0 : known.size(), built == null ? 0 : built.built(),
				built == null ? 0 : built.quads(), held == null ? -1 : held.tableMs,
				held != null && held.fromCache ? I18n.get("kantobridge.debug.yes") : I18n.get("kantobridge.debug.no"),
				reason() == null ? "-" : reason());
	}
}
