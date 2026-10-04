package dev.kantobridge.client.shape;

import dev.kantobridge.KantoBridge;
import dev.kantobridge.builder.shape.geom.ShapeTable;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.world.ClientWorldIndex;
import dev.kantobridge.scene.ArtSource;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.ShapeIndex;
import dev.kantobridge.world.ShapeMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.Nullable;

/**
 * The world's shapes on this client (docs/specs/VOXEL_BLOCKS_PLAN.md §5.8): the shape index and shape map the server
 * sent ({@code world.shapes}), checked against each other and against the WorldIndex this client holds, and the shape
 * table built from them, off the client thread, with this client's own art; cached under
 * {@code <gameDir>/kantobridge/shapes/} (ROM-derived, never sent). Until the table is ready, or when anything doesn't
 * fit, the world draws cubes and {@link #reason()} says why. Client thread only, except the table build.
 */
public final class ClientShapes {
	/** A shape map larger than this, inflated, is refused (the real build's is a few MB). */
	static final int MAX_MAP_BYTES = 64 * 1024 * 1024;

	private static boolean registered;
	private static @Nullable ShapeIndex index;
	private static @Nullable ShapeMap map;
	private static ShapeMap.@Nullable Lookup lookup;
	private static @Nullable ShapeTable table;
	private static @Nullable String reason = "no_shapes";
	private static long generation;
	private static long tableMs = -1;
	private static boolean fromCache;
	private static @Nullable Runnable onChange;

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
		reason = "no_shapes";
		changed();
	}

	/** Both shape documents are in: check them, then build the table off-thread. */
	static void received(String indexText, String mapWire) {
		generation++;
		long mine = generation;
		table = null;
		lookup = null;
		ShapeCheck.Accepted accepted;
		try {
			accepted = ShapeCheck.check(indexText, mapWire, ClientWorldIndex.currentHash(), MAX_MAP_BYTES);
		} catch (ShapeCheck.Rejected e) {
			fail(e.code(), "world.shapes: refused (" + e.getMessage() + "); drawing cubes");
			return;
		}
		ShapeIndex parsed = accepted.index();
		ShapeMap received = accepted.map();
		index = parsed;
		map = received;
		reason = "building";
		changed();
		Path cache = FabricLoader.getInstance().getGameDir().resolve("kantobridge").resolve("shapes")
				.resolve(ShapeTable.cacheName(parsed.hash(), ShapeTable.GEOMETRY, ShapeTable.NO_OVERRIDES));
		Minecraft minecraft = Minecraft.getInstance();
		Util.backgroundExecutor().execute(() -> {
			long started = System.nanoTime();
			boolean cached = false;
			ShapeTable built = null;
			try {
				if (Files.isRegularFile(cache)) {
					built = ShapeTable.decode(Files.readAllBytes(cache), parsed.hash(), ShapeTable.GEOMETRY, ShapeTable.NO_OVERRIDES);
					cached = true;
				}
			} catch (IOException | DataException e) {
				built = null;
			}
			if (built == null) {
				built = ShapeTable.build(parsed, ArtSource.NONE);
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
				reason = null;
				KantoBridge.LOGGER.info("shapes: table for index {} ready: {} shapes, {} quads, {} textures, {} ms{}", Hashes.shortHash(parsed.hash()),
						done.built(), done.quads(), done.textureKeys().size(), ms, wasCached ? " (cache)" : "");
				changed();
			});
		});
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
