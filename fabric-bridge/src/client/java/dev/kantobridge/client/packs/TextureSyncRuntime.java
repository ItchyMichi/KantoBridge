package dev.kantobridge.client.packs;

import com.google.gson.JsonObject;
import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.plan.PlanException;
import dev.kantobridge.client.bridge.BridgeClient;
import dev.kantobridge.client.bridge.BridgeClock;
import dev.kantobridge.client.bridge.BridgeConfig;
import dev.kantobridge.client.bridge.BridgeLink;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.bridge.ConnectionState;
import dev.kantobridge.client.bridge.ExportClient;
import dev.kantobridge.client.bridge.HelloOk;
import dev.kantobridge.client.bridge.Links;
import dev.kantobridge.client.bridge.Transport;
import dev.kantobridge.client.model.RenderSnapshot;
import dev.kantobridge.client.shape.ClientShapes;
import dev.kantobridge.client.net.ClientRelay;
import dev.kantobridge.client.world.ClientRegions;
import dev.kantobridge.client.world.ClientWorldIndex;
import dev.kantobridge.client.world.CreateKantoWorldFlow;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.Games;
import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.ShapeIndex;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.WorldIndex;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minecraft glue for {@link TextureSync} (docs/PLAN.md §4.3 "Multiplayer clients", M9; the voxel plan's V1): feeds it
 * the world's game and slot-map hash (the WorldIndex), the generated pack on disk and whether it is the loaded one, how
 * many of the world's keys the render snapshot found no sprite for, and whether the main link is up to a compatible
 * game; runs its export (through {@link ExportClient}, the main link paused as for "Create Kanto world") and pack build
 * ({@link PackGenerator}) on a worker thread; reloads resources; sends {@code texture.status}; and adds a HUD line. The
 * art is written only into this player's own game folder. Logs carry steps, codes, counts and short hashes.
 */
public final class TextureSyncRuntime {
	private static final Logger LOGGER = LoggerFactory.getLogger("kantobridge");
	/** How long the main link may take to hang up before the export connects anyway. */
	private static final long PAUSE_WAIT_MS = 5_000;
	/** A resource reload that hasn't finished by now (and shows no overlay) failed. */
	private static final long RELOAD_LIMIT_MS = 5 * 60_000L;

	private static @Nullable TextureSyncRuntime instance;

	private final BridgeRuntime bridge;
	private final BridgeClock clock = BridgeClock.system();
	private final TextureSync sync;
	private final Path gameDir = FabricLoader.getInstance().getGameDir();
	/** The pack's {@code kantobridge.json}, read again whenever it may have changed. */
	private @Nullable PackInfo local;
	private boolean localRead;
	private boolean wasBusy;
	private volatile @Nullable ExportClient exporter;
	private @Nullable CompletableFuture<Void> reload;
	private long reloadToken;
	private long reloadStartedMs;

	private TextureSyncRuntime(BridgeRuntime bridge) {
		this.bridge = bridge;
		this.sync = new TextureSync(new TextureSync.Port() {
			@Override
			public boolean report(JsonObject status) {
				return ClientRelay.get().send(MsgTypes.TEXTURE_STATUS, status, null);
			}

			@Override
			public void regenerate(long token) {
				TextureSyncRuntime.this.startWorker(token);
			}

			@Override
			public void reload(long token) {
				TextureSyncRuntime.this.startReload(token);
			}

			@Override
			public void warn(String code, long count) {
				LOGGER.warn("textures: {} ({} so far)", code, count);
			}
		});
	}

	/** Call once from the client entrypoint, after {@link BridgeRuntime#init} and {@link ClientWorldIndex#init}. */
	public static void init(BridgeRuntime bridge) {
		if (instance != null) {
			return;
		}
		TextureSyncRuntime runtime = new TextureSyncRuntime(bridge);
		instance = runtime;
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> runtime.restart());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> runtime.restart());
		ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> runtime.cancelWorker());
		ClientTickEvents.END_CLIENT_TICK.register(runtime::tick);
		bridge.addHudLine(() -> runtime.sync.hudLine(runtime.clock.millis()));
		// The export and the pack build pause the link on purpose: no pause overlay meanwhile (M10).
		bridge.pauseExcludedWhile(() -> runtime.sync.phase() == TextureSync.Phase.WORKING || runtime.sync.phase() == TextureSync.Phase.RELOADING);
		bridge.addDebugLine(runtime::debugLine);
	}

	public static @Nullable TextureSyncRuntime get() {
		return instance;
	}

	public TextureSync sync() {
		return this.sync;
	}

	private void restart() {
		this.cancelWorker();
		this.sync.reset();
		this.localRead = false;
	}

	private void tick(Minecraft minecraft) {
		this.tickReload(minecraft);
		if (minecraft.level == null || minecraft.player == null) {
			return;
		}
		boolean busy = CreateKantoWorldFlow.active() != null;
		if (this.wasBusy && !busy) {
			// The flow wrote a pack of its own.
			this.localRead = false;
		}
		this.wasBusy = busy;
		WorldIndex index = ClientWorldIndex.current();
		SlotMap slots = ClientWorldIndex.slotMap();
		if (!this.localRead) {
			this.local = GeneratedPack.info(this.gameDir);
			this.localRead = true;
		}
		PackInfo loaded = LoadedPack.info();
		boolean packLoaded = this.local != null && loaded != null && PackReload.selected(minecraft) && this.local.equals(loaded);
		// The snapshot's count is only about this world once it was resolved against this slot map and the loaded art set.
		RenderSnapshot snapshot = RenderSnapshot.current();
		// MG7: another region's keys that no loaded art set draws count too, so the pack is rebuilt (once per world) with
		// every game's art this player can export
		int missing = slots != null && slots.hash().equals(snapshot.slotMapHash()) && Objects.equals(snapshot.artSet(), LoadedPack.artSet())
				? snapshot.missing() + snapshot.foreign()
				: -1;
		HelloOk hello = this.bridge.state().handshake();
		boolean compatible = this.bridge.client().state() == ConnectionState.CONNECTED && hello != null && index != null
				&& Games.compatible(hello.game(), index.game());
		ShapeIndex shapes = ClientShapes.table() == null ? null : ClientShapes.index();
		// MG3: the followed region's game and its index's slotMapHash (the hash of that region's slice of the one slot map);
		// the report names the region in a region world, nothing in a legacy one.
		TextureSync.Inputs inputs = new TextureSync.Inputs(index == null ? null : index.game(), index == null ? null : index.slotMapHash(),
				this.local, packLoaded, missing, compatible, ClientShapes.shapes(), shapes == null ? null : shapes.hash(), ClientShapes.reason(),
				KbPackReload.current().packs().size(), ClientRegions.tagFor());
		this.sync.tick(this.clock.millis(), inputs, busy);
	}

	// ---- the worker: export and pack ----

	private void startWorker(long token) {
		BridgeClient link = this.bridge.client();
		Catalog catalog = Catalog.bundled();
		ExportClient client = new ExportClient(BridgeConfig.fromCatalog(catalog, link.config().port()), catalog, CreateKantoWorldFlow.builderHello(),
				Transport.socket(), BridgeClock.system(), ExportClient.Options.DEFAULTS);
		this.exporter = client;
		Minecraft minecraft = Minecraft.getInstance();
		LOGGER.info("textures: no usable pack, or it lacks some of this world's art; exporting this player's art to rebuild it");
		Thread thread = new Thread(() -> {
			String failure = this.work(link, client);
			minecraft.execute(() -> {
				this.localRead = false;
				this.sync.finished(token, failure);
				if (failure == null) {
					// A new export: shapes built from this player's art (tufts, units) follow it.
					ClientShapes.artChanged();
				}
			});
		}, "kantobridge-textures");
		thread.setDaemon(true);
		thread.start();
	}

	/** Runs on the worker thread; returns null on success, else a failure code. */
	private @Nullable String work(BridgeClient link, ExportClient client) {
		Path exportDir = this.gameDir.resolve("kantobridge").resolve("export");
		try {
			ExportClient.Result result;
			try (BridgeClient.Pause pause = link.pauseReconnects("textures", PAUSE_WAIT_MS)) {
				result = client.run(exportDir, ExportClient.Listener.NONE);
			}
			// MG7: every other linked game whose art this player has no export of yet, so each region draws its own art
			this.exportOthers(exportDir, link);
			this.sync.work(TextureSync.Work.PACK);
			PackGenerator.Result pack = PackGenerator.generateWithOthers(result.bundleDir(), GeneratedPack.directory(this.gameDir));
			LOGGER.info("textures: rebuilt this player's pack (art set {}, export {}, {} textures)", pack.game(), Hashes.shortHash(pack.exportHash()),
					pack.pack().textures());
			return null;
		} catch (ExportClient.ExportException e) {
			return "export." + e.code();
		} catch (BundleException e) {
			return "pack." + e.code();
		} catch (PlanException e) {
			return "pack." + e.code();
		} catch (IOException e) {
			return "pack.io";
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "interrupted";
		} catch (RuntimeException e) {
			LOGGER.error("textures: unexpected {} while rebuilding the pack", e.getClass().getName(), e);
			return "internal";
		} finally {
			// Only this worker's: a player who left and joined again may have started the next one already.
			if (this.exporter == client) {
				this.exporter = null;
			}
		}
	}

	/**
	 * Exports each other connected link's game that has no export beside the main link's yet (a game compatible with one
	 * already exported, Red with Blue, needs none). A failure is logged and leaves that game's region on placeholders.
	 */
	private void exportOthers(Path exportDir, BridgeClient main) throws InterruptedException {
		Links links = Links.get();
		if (links == null) {
			return;
		}
		Catalog catalog = Catalog.bundled();
		for (BridgeLink other : links.all()) {
			String game = other.game();
			if (other.client() == main || game == null || !other.connected() || hasExport(exportDir, game)) {
				continue;
			}
			ExportClient client = new ExportClient(BridgeConfig.fromCatalog(catalog, other.port()), catalog, CreateKantoWorldFlow.builderHello(),
					Transport.socket(), BridgeClock.system(), ExportClient.Options.DEFAULTS);
			this.exporter = client;
			try (BridgeClient.Pause pause = other.client().pauseReconnects("textures", PAUSE_WAIT_MS)) {
				ExportClient.Result result = client.run(exportDir, ExportClient.Listener.NONE);
				LOGGER.info("textures: exported {}'s art too (export {})", result.game(), Hashes.shortHash(result.indexHash()));
			} catch (ExportClient.ExportException e) {
				LOGGER.warn("textures: {}'s art could not be exported ({}); its region draws placeholders", game, e.code());
			}
		}
	}

	/** True when an export of {@code game} (or a game whose art is the same set) is stored under {@code exportDir}. */
	static boolean hasExport(Path exportDir, String game) {
		try (java.util.stream.Stream<Path> list = java.nio.file.Files.list(exportDir)) {
			return list.anyMatch(dir -> java.nio.file.Files.isRegularFile(dir.resolve("index.json"))
					&& Games.compatible(dir.getFileName().toString(), game));
		} catch (IOException e) {
			return false;
		}
	}

	private void cancelWorker() {
		ExportClient running = this.exporter;
		if (running != null) {
			running.cancel();
		}
	}

	// ---- reloads ----

	private void startReload(long token) {
		Minecraft minecraft = Minecraft.getInstance();
		this.reloadToken = token;
		this.reloadStartedMs = this.clock.millis();
		this.reload = PackReload.reload(minecraft);
		LOGGER.info("textures: reloading resources with the generated pack");
	}

	private void tickReload(Minecraft minecraft) {
		CompletableFuture<Void> pending = this.reload;
		if (pending == null) {
			return;
		}
		long elapsed = this.clock.millis() - this.reloadStartedMs;
		boolean done = pending.isDone() && !pending.isCompletedExceptionally();
		// A failed reload rolls back without completing the future; the overlay is gone and nothing is pending.
		boolean failed = pending.isCompletedExceptionally() || (!pending.isDone() && minecraft.getOverlay() == null && elapsed > 2_000)
				|| elapsed > RELOAD_LIMIT_MS;
		if (!done && !failed) {
			return;
		}
		this.reload = null;
		this.localRead = false;
		this.sync.reloaded(this.reloadToken, done && PackReload.selected(minecraft));
	}

	private String debugLine() {
		TextureSync.Phase phase = this.sync.phase();
		PackInfo info = this.local;
		RenderSnapshot snapshot = RenderSnapshot.current();
		return I18n.get("kantobridge.debug.textures", phase.name().toLowerCase(java.util.Locale.ROOT),
				this.sync.reason() == null ? "-" : this.sync.reason(), info == null ? "-" : info.usable() ? info.artSet() : "layout " + info.layout(),
				info == null ? 0 : info.textures(), snapshot.keys(), snapshot.missing(), this.sync.regenerations(), this.sync.reports());
	}
}
