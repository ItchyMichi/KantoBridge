package dev.kantobridge.client.world;

import dev.kantobridge.KantoBridge;
import dev.kantobridge.builder.BuildOptions;
import dev.kantobridge.builder.BuildPhase;
import dev.kantobridge.builder.BuildRequest;
import dev.kantobridge.builder.BuildStatus;
import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.plan.PlanException;
import dev.kantobridge.client.bridge.BridgeClient;
import dev.kantobridge.client.bridge.BridgeClock;
import dev.kantobridge.client.bridge.BridgeConfig;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.bridge.ConnectionState;
import dev.kantobridge.client.bridge.ExportClient;
import dev.kantobridge.client.bridge.HelloInfo;
import dev.kantobridge.client.bridge.HelloOk;
import dev.kantobridge.client.bridge.Transport;
import dev.kantobridge.client.packs.GeneratedPack;
import dev.kantobridge.client.packs.KeyIndex;
import dev.kantobridge.client.packs.KeyIndexWriter;
import dev.kantobridge.client.packs.PackGenerator;
import dev.kantobridge.client.packs.PackReload;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.server.KantoGameRules;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.jetbrains.annotations.Nullable;

/**
 * "Create Kanto world" (docs/PLAN.md §4.3 {@code world/}): the title-screen button and the flow behind it.
 *
 * <ol>
 * <li>Check that gen1-bridge is reachable: the main link is connected, or connects within a few seconds.</li>
 * <li>Export through {@link ExportClient} into {@code <gameDir>/kantobridge/export/}. gen1-bridge serves one connection
 * at a time, so the main link is paused meanwhile; it reconnects and resyncs afterwards.</li>
 * <li>Generate the resource pack from the whole export ({@link PackGenerator}) and reload resources.</li>
 * <li>Create a fresh level: folder {@code Kanto} or the next free name (never an existing world), preset
 * {@code kantobridge:kanto}, Adventure, Peaceful, the Appendix D game rules.</li>
 * <li>The integrated server builds every map from the bundle: the flow files a {@link BuildRequest} just before creating
 * the level, the server takes it on {@code SERVER_STARTED} (the headless trigger's code path) and holds the player as a
 * spectator until the world is ready. This screen shows the build's progress, then lets the player in at the spawn.</li>
 * </ol>
 *
 * Failures show a message and remove the world folder this flow created. The flow runs on the client thread, driven by
 * {@code END_CLIENT_TICK} (screen ticks stop while the resource-reload overlay is up); the export and the pack run on a
 * worker thread. Logs carry steps, counts, codes and hashes only.
 */
public final class CreateKantoWorldFlow {
	/** Where the flow is. */
	public enum Step {
		CHECK,
		EXPORT,
		PACK,
		RELOAD,
		CREATE,
		LOADING,
		BUILD,
		CLEANUP,
		FAILED,
		DONE;

		boolean cancellable() {
			return this == CHECK || this == EXPORT || this == PACK || this == BUILD;
		}
	}

	static final ResourceKey<WorldPreset> KANTO_PRESET = ResourceKey.create(Registries.WORLD_PRESET,
			ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "kanto"));
	/** One reconnect cycle of the main link (backoff at most 5 s) plus the connect. */
	private static final long CHECK_WAIT_MS = 7_000;
	/** How long the main link may take to hang up before the export connects anyway. */
	private static final long PAUSE_WAIT_MS = 5_000;
	/** A resource reload that hasn't finished by now (and shows no overlay) failed. */
	private static final long RELOAD_LIMIT_MS = 5 * 60_000L;
	/** From creating the level until the player is in it. */
	private static final long JOIN_LIMIT_MS = 3 * 60_000L;
	/** How long a finished build may take to reach the client (teleport, game mode) before the screen closes anyway. */
	private static final long RELEASE_WAIT_MS = 5_000;

	private static @Nullable CreateKantoWorldFlow active;

	private final Minecraft minecraft;
	private final Path exportDir;
	private final Path packDir;
	private final KantoWorldScreen screen;

	private volatile Step step = Step.CHECK;
	private long stepSinceNanos = System.nanoTime();
	private boolean ticking;
	private volatile boolean cancelled;

	// The worker's view: written on the worker thread, read by the screen and the tick.
	private volatile boolean indexed;
	private volatile int exportDone;
	private volatile int exportTotal;
	private volatile long exportBytes;
	private volatile @Nullable ExportClient exporter;
	private volatile boolean workerDone;
	private volatile @Nullable Component workerError;
	private volatile @Nullable Path bundleDir;

	private @Nullable CompletableFuture<Void> reload;
	private long doneSeenNanos = -1;
	private @Nullable String levelId;
	private @Nullable BuildRequest request;
	private @Nullable Component error;

	private CreateKantoWorldFlow(Minecraft minecraft) {
		this.minecraft = minecraft;
		Path gameDir = FabricLoader.getInstance().getGameDir();
		this.exportDir = gameDir.resolve("kantobridge").resolve("export");
		this.packDir = GeneratedPack.directory(gameDir);
		this.screen = new KantoWorldScreen(this);
	}

	/** Call once from client init: the title-screen button, the tick driver and the shutdown hook. */
	public static void init() {
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
			if (screen instanceof TitleScreen) {
				Screens.getButtons(screen).add(titleButton(minecraft, width, height));
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			CreateKantoWorldFlow flow = active;
			if (flow != null) {
				flow.tick();
			}
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> {
			CreateKantoWorldFlow flow = active;
			if (flow != null) {
				flow.stopWorker();
			}
		});
	}

	/** Below the title screen's buttons when it fits, else in the top-right corner. */
	private static Button titleButton(Minecraft minecraft, int width, int height) {
		int y = height / 4 + 48 + 72 + 12 + 24;
		Button.Builder builder = Button.builder(Component.translatable("kantobridge.create.button"), button -> start(minecraft));
		if (y + 20 <= height - 12) {
			return builder.bounds(width / 2 - 100, y, 200, 20).build();
		}
		return builder.bounds(width - 154, 4, 150, 20).build();
	}

	/** Starts the flow (one at a time) and shows its screen. Client thread. */
	public static void start(Minecraft minecraft) {
		if (active != null) {
			minecraft.setScreen(active.screen);
			return;
		}
		CreateKantoWorldFlow flow = new CreateKantoWorldFlow(minecraft);
		active = flow;
		KantoBridge.LOGGER.info("create: Create Kanto world started");
		minecraft.setScreen(flow.screen);
	}

	/** The running flow, if any. */
	public static @Nullable CreateKantoWorldFlow active() {
		return active;
	}

	// ---- what the screen shows ----

	Step step() {
		return this.step;
	}

	boolean indexed() {
		return this.indexed;
	}

	int exportDone() {
		return this.exportDone;
	}

	int exportTotal() {
		return this.exportTotal;
	}

	long exportBytes() {
		return this.exportBytes;
	}

	@Nullable BuildStatus buildStatus() {
		BuildRequest filed = this.request;
		return filed == null ? null : filed.status();
	}

	@Nullable Component error() {
		return this.error;
	}

	@Nullable String levelId() {
		return this.levelId;
	}

	// ---- buttons ----

	/** The Cancel button: stops the export or the build; the flow then ends on the title screen. */
	void cancel() {
		if (!this.step.cancellable() || this.cancelled) {
			return;
		}
		this.cancelled = true;
		KantoBridge.LOGGER.info("create: cancelled during {}", this.step);
		ExportClient running = this.exporter;
		if (running != null) {
			running.cancel();
		}
		BuildStatus status = this.buildStatus();
		if (this.step == Step.BUILD && status != null) {
			status.cancel();
		}
		if (this.step == Step.CHECK) {
			this.end(null);
		}
	}

	/** The Back button after a failure. */
	void back() {
		this.end(null);
	}

	// ---- the state machine (client thread) ----

	private void tick() {
		if (this.ticking) {
			// Creating and leaving a level tick the client from inside this method; those nested ticks wait.
			return;
		}
		this.ticking = true;
		try {
			switch (this.step) {
				case CHECK -> this.tickCheck();
				case EXPORT, PACK -> this.tickWorker();
				case RELOAD -> this.tickReload();
				case CREATE -> this.createLevel();
				case LOADING -> this.tickLoading();
				case BUILD -> this.tickBuild();
				default -> {
				}
			}
		} catch (RuntimeException e) {
			KantoBridge.LOGGER.error("create: unexpected {} during {}", e.getClass().getName(), this.step, e);
			Component message = Component.translatable("kantobridge.create.error.internal", e.getClass().getSimpleName());
			if (this.step == Step.CLEANUP || this.levelId == null) {
				this.fail(message);
			} else {
				this.cleanup(message);
			}
		} finally {
			this.ticking = false;
		}
	}

	private void moveTo(Step next) {
		KantoBridge.LOGGER.info("create: {} -> {}", this.step, next);
		this.step = next;
		this.stepSinceNanos = System.nanoTime();
	}

	private long stepMillis() {
		return (System.nanoTime() - this.stepSinceNanos) / 1_000_000L;
	}

	private void tickCheck() {
		BridgeRuntime runtime = BridgeRuntime.get();
		if (runtime == null) {
			this.fail(Component.translatable("kantobridge.create.error.not_connected"));
			return;
		}
		BridgeClient link = runtime.client();
		ConnectionState state = link.state();
		if (state == ConnectionState.CONNECTED) {
			HelloOk hello = link.handshake();
			KantoBridge.LOGGER.info("create: gen1-bridge reachable (game {})", hello == null ? "?" : hello.game());
			this.startWorker(link);
		} else if (state == ConnectionState.REJECTED) {
			this.fail(Component.translatable("kantobridge.create.error.rejected", String.valueOf(link.detail())));
		} else if (this.stepMillis() > CHECK_WAIT_MS) {
			this.fail(Component.translatable("kantobridge.create.error.not_connected"));
		}
	}

	/** Steps 2 and 3 on a worker thread: the export (main link paused), then the pack. */
	private void startWorker(BridgeClient link) {
		Catalog catalog = Catalog.bundled();
		BridgeConfig config = BridgeConfig.fromCatalog(catalog, link.config().port());
		ExportClient client = new ExportClient(config, catalog, builderHello(), Transport.socket(), BridgeClock.system(),
				ExportClient.Options.DEFAULTS);
		this.exporter = client;
		this.moveTo(Step.EXPORT);
		Thread thread = new Thread(() -> this.work(link, client), "kantobridge-create-world");
		thread.setDaemon(true);
		thread.start();
	}

	private void work(BridgeClient link, ExportClient client) {
		try {
			ExportClient.Result result;
			try (BridgeClient.Pause pause = link.pauseReconnects("export", PAUSE_WAIT_MS)) {
				result = client.run(this.exportDir, new ExportClient.Listener() {
					@Override
					public void indexed(String game, int resources, long bytes) {
						CreateKantoWorldFlow.this.exportTotal = resources;
						CreateKantoWorldFlow.this.indexed = true;
					}

					@Override
					public void progress(int done, int total, long bytesDone, long bytesTotal) {
						CreateKantoWorldFlow.this.exportDone = done;
						CreateKantoWorldFlow.this.exportBytes = bytesDone;
					}
				});
			}
			this.exporter = null;
			this.bundleDir = result.bundleDir();
			if (this.cancelled) {
				return;
			}
			this.moveTo(Step.PACK);
			PackGenerator.generate(result.bundleDir(), this.packDir);
			writeKeys(result.bundleDir());
		} catch (ExportClient.ExportException e) {
			this.workerError = e.code().equals("rejected")
					? Component.translatable("kantobridge.create.error.rejected", String.valueOf(e.detail()))
					: Component.translatable("kantobridge.create.error.export", e.getMessage());
		} catch (BundleException e) {
			this.workerError = Component.translatable("kantobridge.create.error.pack", e.code());
		} catch (PlanException e) {
			this.workerError = Component.translatable("kantobridge.create.error.pack", e.code());
		} catch (IOException e) {
			this.workerError = Component.translatable("kantobridge.create.error.pack", "io." + e.getClass().getSimpleName());
		} catch (InterruptedException e) {
			this.workerError = Component.translatable("kantobridge.create.error.internal", "interrupted");
		} catch (RuntimeException e) {
			KantoBridge.LOGGER.error("create: unexpected {} while exporting", e.getClass().getName(), e);
			this.workerError = Component.translatable("kantobridge.create.error.internal", e.getClass().getSimpleName());
		} finally {
			this.exporter = null;
			this.workerDone = true;
		}
	}

	/** The key index for pack makers (the voxel plan's V6); a failure only costs the pack check its unknown keys. */
	private static void writeKeys(Path bundleDir) {
		try {
			KeyIndex keys = KeyIndexWriter.write(FabricLoader.getInstance().getGameDir(), ExportBundle.open(bundleDir));
			KantoBridge.LOGGER.info("create: key index written ({} tiles, {} cells)", keys.tiles(), keys.cells());
		} catch (BundleException e) {
			KantoBridge.LOGGER.warn("create: no key index ({})", e.code());
		} catch (PlanException e) {
			KantoBridge.LOGGER.warn("create: no key index ({})", e.code());
		} catch (IOException | RuntimeException e) {
			KantoBridge.LOGGER.warn("create: no key index ({})", e.getClass().getSimpleName());
		}
	}

	private void tickWorker() {
		if (!this.workerDone) {
			return;
		}
		if (this.cancelled) {
			this.end(null);
			return;
		}
		Component failure = this.workerError;
		if (failure != null) {
			this.fail(failure);
			return;
		}
		this.moveTo(Step.RELOAD);
		this.reload = PackReload.reload(this.minecraft);
	}

	private void tickReload() {
		CompletableFuture<Void> pending = this.reload;
		if (pending != null && pending.isDone() && !pending.isCompletedExceptionally()) {
			if (!PackReload.selected(this.minecraft)) {
				this.fail(Component.translatable("kantobridge.create.error.reload"));
				return;
			}
			KantoBridge.LOGGER.info("create: generated pack loaded");
			this.moveTo(Step.CREATE);
			return;
		}
		// A failed reload rolls back without completing the future; the overlay is gone and nothing is pending.
		boolean overlayGone = this.minecraft.getOverlay() == null && this.stepMillis() > 2_000;
		if ((pending != null && pending.isCompletedExceptionally()) || overlayGone || this.stepMillis() > RELOAD_LIMIT_MS) {
			this.fail(Component.translatable("kantobridge.create.error.reload"));
		}
	}

	/** Step 4: a fresh level, with the build request filed first so its server finds it on start. */
	private void createLevel() {
		Path bundle = this.bundleDir;
		if (bundle == null) {
			this.fail(Component.translatable("kantobridge.create.error.internal", "no_bundle"));
			return;
		}
		LevelStorageSource saves = this.minecraft.getLevelSource();
		String folder = KantoWorldNames.free(saves.getBaseDir());
		this.levelId = folder;
		// Commands stay available so the build can be inspected (e.g. /gamemode spectator) during play-testing.
		LevelSettings settings = new LevelSettings(folder, GameType.ADVENTURE, false, Difficulty.PEACEFUL, true, KantoGameRules.create(),
				WorldDataConfiguration.DEFAULT);
		WorldOptions options = new WorldOptions(WorldOptions.randomSeed(), false, false);
		// The client already wrote the pack (step 3); the build must not replace it while it is in use.
		this.request = BuildRequest.submit(folder, bundle, null, BuildOptions.defaults().withWritePack(false));
		KantoBridge.LOGGER.info("create: creating level folder \"{}\" with preset {}", folder, KANTO_PRESET.location());
		this.moveTo(Step.LOADING);
		this.minecraft.createWorldOpenFlows().createFreshLevel(folder, settings, options, CreateKantoWorldFlow::kantoDimensions, this.screen);
		if (this.minecraft.getSingleplayerServer() == null) {
			// createFreshLevel logged why and went back to this screen; nothing runs in the folder.
			this.request.withdraw();
			this.removeWorldFolder();
			this.fail(Component.translatable("kantobridge.create.error.create"));
		}
	}

	private static WorldDimensions kantoDimensions(RegistryAccess registries) {
		return registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(KANTO_PRESET).value().createWorldDimensions();
	}

	private void tickLoading() {
		BuildStatus status = this.buildStatus();
		boolean inWorld = this.minecraft.level != null && this.minecraft.player != null;
		if (!inWorld) {
			if (this.minecraft.getSingleplayerServer() == null && this.stepMillis() > 5_000) {
				// The server is gone before the player got in (it stopped or failed to start).
				this.removeWorldFolder();
				this.fail(Component.translatable("kantobridge.create.error.create"));
			} else if (this.stepMillis() > JOIN_LIMIT_MS) {
				this.fail(Component.translatable("kantobridge.create.error.create"));
			}
			return;
		}
		if (status == null || status.state() == BuildStatus.State.WAITING) {
			// SERVER_STARTED runs before anyone can join: a request still waiting was never taken.
			this.cleanup(Component.translatable("kantobridge.create.error.build", "not_started"));
			return;
		}
		this.moveTo(Step.BUILD);
		this.minecraft.setScreen(this.screen);
		this.tickBuild();
	}

	private void tickBuild() {
		BuildStatus status = this.buildStatus();
		if (status == null || this.minecraft.level == null) {
			// The world closed without us (e.g. its server stopped): the build can't finish.
			this.cleanup(Component.translatable("kantobridge.create.error.build", "left"));
			return;
		}
		switch (status.state()) {
			case DONE -> {
				// The server moved the player to the spawn, then ended spectator mode; once that packet is in, so is the move.
				boolean arrived = this.minecraft.gameMode != null && this.minecraft.gameMode.getPlayerMode() != GameType.SPECTATOR;
				if (this.doneSeenNanos < 0) {
					this.doneSeenNanos = System.nanoTime();
				}
				if (!arrived && System.nanoTime() - this.doneSeenNanos < RELEASE_WAIT_MS * 1_000_000L) {
					return;
				}
				KantoBridge.LOGGER.info("create: built {} maps ({} warnings); entering the world", status.mapsBuilt(), status.warnings());
				this.moveTo(Step.DONE);
				active = null;
				this.minecraft.setScreen(null);
			}
			case FAILED -> this.cleanup(this.cancelled ? null
					: Component.translatable("kantobridge.create.error.build", String.valueOf(status.error())));
			default -> {
				// Keep the progress screen up: a pausing screen would pause the integrated server and the build.
				if (this.minecraft.screen != this.screen && (this.minecraft.screen == null || this.minecraft.screen instanceof PauseScreen)) {
					this.minecraft.setScreen(this.screen);
				}
			}
		}
	}

	/** Leaves the unfinished world like "Save and Quit to Title", removes its folder, then shows the failure. */
	private void cleanup(@Nullable Component message) {
		this.moveTo(Step.CLEANUP);
		if (this.minecraft.level != null) {
			this.minecraft.level.disconnect();
			// Waits until the integrated server has stopped and released the world folder.
			this.minecraft.disconnect(new GenericMessageScreen(Component.translatable("kantobridge.create.cleanup")));
		}
		boolean removed = this.removeWorldFolder();
		if (message == null) {
			this.end(null);
			return;
		}
		this.fail(Component.translatable(removed ? "kantobridge.create.error.removed" : "kantobridge.create.error.kept", message,
				String.valueOf(this.levelId)));
	}

	/** Deletes the folder this flow created, through Minecraft's own world deletion. Returns false if it is still there. */
	private boolean removeWorldFolder() {
		String folder = this.levelId;
		if (folder == null) {
			return true;
		}
		LevelStorageSource saves = this.minecraft.getLevelSource();
		if (!Files.exists(saves.getLevelPath(folder), LinkOption.NOFOLLOW_LINKS)) {
			return true;
		}
		try (LevelStorageSource.LevelStorageAccess access = saves.createAccess(folder)) {
			access.deleteLevel();
			KantoBridge.LOGGER.info("create: removed the unfinished world folder \"{}\"", folder);
			return true;
		} catch (IOException e) {
			KantoBridge.LOGGER.warn("create: could not remove the unfinished world folder \"{}\" ({})", folder, e.getClass().getSimpleName());
			return false;
		}
	}

	/** Shows a failure on the flow's screen; Back ends the flow. */
	private void fail(Component message) {
		KantoBridge.LOGGER.warn("create: failed during {}", this.step);
		this.stopWorker();
		BuildRequest filed = this.request;
		if (filed != null) {
			filed.withdraw();
		}
		this.error = message;
		this.moveTo(Step.FAILED);
		if (this.minecraft.screen != this.screen) {
			this.minecraft.setScreen(this.screen);
		}
	}

	/** Ends the flow on the title screen (or {@code next}). */
	private void end(@Nullable TitleScreen next) {
		this.stopWorker();
		BuildRequest filed = this.request;
		if (filed != null) {
			filed.withdraw();
		}
		if (active == this) {
			active = null;
		}
		this.step = Step.DONE;
		if (this.minecraft.level != null) {
			// Never leave the title screen on top of a running world.
			this.minecraft.level.disconnect();
			this.minecraft.disconnect(new GenericMessageScreen(Component.translatable("kantobridge.create.cleanup")));
		}
		this.minecraft.setScreen(next != null ? next : new TitleScreen());
	}

	private void stopWorker() {
		ExportClient running = this.exporter;
		if (running != null) {
			running.cancel();
		}
	}

	/** The builder's hello: versions only; the export needs no player (also the texture flow's export, M9). */
	public static HelloInfo builderHello() {
		String mod = FabricLoader.getInstance().getModContainer(KantoBridge.MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
		String minecraftVersion = null;
		try {
			minecraftVersion = SharedConstants.getCurrentVersion().getName();
		} catch (RuntimeException ignored) {
			// Optional in hello.
		}
		return new HelloInfo(mod, minecraftVersion, null, null);
	}

	/** Build progress as a fraction, for the bar (planning and finishing count as 0 and 1). */
	static float buildFraction(@Nullable BuildStatus status) {
		if (status == null || status.phase() == null || status.phase() == BuildPhase.PLANNING) {
			return 0.0F;
		}
		if (status.phase() != BuildPhase.PLACING) {
			return 1.0F;
		}
		int total = status.totalChunks();
		return total <= 0 ? 0.0F : Math.min(1.0F, status.doneChunks() / (float) total);
	}
}
