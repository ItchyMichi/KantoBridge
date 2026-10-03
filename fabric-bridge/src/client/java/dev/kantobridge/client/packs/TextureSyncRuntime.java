package dev.kantobridge.client.packs;

import com.google.gson.JsonObject;
import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.plan.PlanException;
import dev.kantobridge.client.bridge.BridgeClient;
import dev.kantobridge.client.bridge.BridgeClock;
import dev.kantobridge.client.bridge.BridgeConfig;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.bridge.ConnectionState;
import dev.kantobridge.client.bridge.ExportClient;
import dev.kantobridge.client.bridge.Transport;
import dev.kantobridge.client.net.ClientRelay;
import dev.kantobridge.client.world.ClientWorldIndex;
import dev.kantobridge.client.world.CreateKantoWorldFlow;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.Hashes;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.WorldIndex;
import java.io.IOException;
import java.nio.file.Path;
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
 * Minecraft glue for {@link TextureSync} (docs/PLAN.md §4.3 "Multiplayer clients", M9): feeds it the world's slot-map
 * hash (the WorldIndex), the slot map from {@code world.slots}, the generated pack's {@code kantobridge.json} and
 * whether the main link is up; runs its export (through {@link ExportClient}, the main link paused as for "Create Kanto
 * world") and pack build ({@link PackGenerator} against the server's slot map) on a worker thread; reloads resources;
 * sends {@code texture.status}; and adds a HUD line. The art is written only into this player's own game folder. Logs
 * carry steps, codes, counts and short hashes.
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
	private boolean reloadOffers;
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
			public void reload(long token, boolean offer) {
				TextureSyncRuntime.this.startReload(token, offer);
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
		boolean connected = this.bridge.client().state() == ConnectionState.CONNECTED;
		TextureSync.Inputs inputs = new TextureSync.Inputs(index == null ? null : index.slotMapHash(), slots == null ? null : slots.hash(),
				this.local, PackReload.selected(minecraft), connected);
		this.sync.tick(this.clock.millis(), inputs, busy);
	}

	// ---- the worker: export and pack ----

	private void startWorker(long token) {
		SlotMap slots = ClientWorldIndex.slotMap();
		if (slots == null) {
			this.sync.finished(token, "no_slots");
			return;
		}
		BridgeClient link = this.bridge.client();
		Catalog catalog = Catalog.bundled();
		ExportClient client = new ExportClient(BridgeConfig.fromCatalog(catalog, link.config().port()), catalog, CreateKantoWorldFlow.builderHello(),
				Transport.socket(), BridgeClock.system(), ExportClient.Options.DEFAULTS);
		this.exporter = client;
		Minecraft minecraft = Minecraft.getInstance();
		LOGGER.info("textures: this pack doesn't fit the world's slot map {}; exporting this player's art to rebuild it", Hashes.shortHash(slots.hash()));
		Thread thread = new Thread(() -> {
			String failure = this.work(link, client, slots);
			minecraft.execute(() -> {
				this.localRead = false;
				this.sync.finished(token, failure);
			});
		}, "kantobridge-textures");
		thread.setDaemon(true);
		thread.start();
	}

	/** Runs on the worker thread; returns null on success, else a failure code. */
	private @Nullable String work(BridgeClient link, ExportClient client, SlotMap slots) {
		Path exportDir = this.gameDir.resolve("kantobridge").resolve("export");
		try {
			ExportClient.Result result;
			try (BridgeClient.Pause pause = link.pauseReconnects("textures", PAUSE_WAIT_MS)) {
				result = client.run(exportDir, ExportClient.Listener.NONE);
			}
			this.sync.work(TextureSync.Work.PACK);
			PackGenerator.Result pack = PackGenerator.generate(result.bundleDir(), GeneratedPack.directory(this.gameDir), slots);
			LOGGER.info("textures: rebuilt this player's pack for slot map {} ({} textures)", Hashes.shortHash(pack.slotMapHash()),
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

	private void cancelWorker() {
		ExportClient running = this.exporter;
		if (running != null) {
			running.cancel();
		}
	}

	// ---- reloads ----

	private void startReload(long token, boolean offer) {
		Minecraft minecraft = Minecraft.getInstance();
		// Held back: the pack source skips the folder, so the jar's placeholders draw the tiles.
		GeneratedPack.setSuppressed(!offer);
		this.reloadToken = token;
		this.reloadOffers = offer;
		this.reloadStartedMs = this.clock.millis();
		this.reload = PackReload.reload(minecraft);
		LOGGER.info("textures: reloading resources with the generated pack {}", offer ? "offered" : "held back");
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
		boolean selected = PackReload.selected(minecraft);
		this.sync.reloaded(this.reloadToken, done && selected == this.reloadOffers);
	}

	private String debugLine() {
		TextureSync.Phase phase = this.sync.phase();
		PackInfo info = this.local;
		return I18n.get("kantobridge.debug.textures", phase.name().toLowerCase(java.util.Locale.ROOT),
				this.sync.reason() == null ? "-" : this.sync.reason(), info == null ? "-" : Hashes.shortHash(info.slotMapHash()),
				info == null ? 0 : info.textures(), this.sync.hidden(), this.sync.regenerations(), this.sync.reports());
	}
}
