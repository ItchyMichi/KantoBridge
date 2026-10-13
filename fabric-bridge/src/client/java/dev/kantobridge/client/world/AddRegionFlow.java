package dev.kantobridge.client.world;

import dev.kantobridge.KantoBridge;
import dev.kantobridge.builder.BuildOptions;
import dev.kantobridge.builder.BuildRequest;
import dev.kantobridge.builder.BuildStatus;
import dev.kantobridge.client.bridge.BridgeClient;
import dev.kantobridge.client.bridge.BridgeClock;
import dev.kantobridge.client.bridge.BridgeConfig;
import dev.kantobridge.client.bridge.BridgeLink;
import dev.kantobridge.client.bridge.ExportClient;
import dev.kantobridge.client.bridge.JsonFields;
import dev.kantobridge.client.bridge.Links;
import dev.kantobridge.client.bridge.Transport;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.proto.ProtocolException;
import dev.kantobridge.world.DataException;
import dev.kantobridge.world.Games;
import dev.kantobridge.world.RegionTable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.jetbrains.annotations.Nullable;

/**
 * "Add a region" (MG4, docs/specs/MULTI_GAME_PLAN.md §4.5 "Exports and builds per link", §4.6, §4.12): the title-screen
 * button beside "Create Kanto world" and the flow behind it.
 *
 * <ol>
 * <li>Pick an existing Kanto world (a save with {@code kantobridge/world_index.json} or {@code kantobridge/regions/}), a
 * connected link whose game isn't the wrong one, a region id (the catalogue's default region for the game) and the next
 * free slot of the world's region table (a legacy world: slot 1, Kanto implicit at 0). A second region of one world
 * space is refused (D4: Red and Blue are one {@code kanto}; {@code -Dkantobridge.testRegions=true} allows a test world's
 * second Kanto under another id).</li>
 * <li>Export that link's bundle exactly as "Create Kanto world" does, on that link alone (it is paused meanwhile; the
 * followed link stays up).</li>
 * <li>File a {@link BuildRequest} for the region ({@link BuildOptions#withRegion}) and open the existing world; the
 * integrated server takes the request on {@code SERVER_STARTED}, holds the player and builds the region at its slot
 * ({@code regions/<id>/}), merging the world's datapack. The world's texture pack is left as it is: the generated pack
 * holds one art set, so the new region's tiles are placeholders until the pack can carry several (a later item); the
 * region's shapes are drawn from this player's export of its game.</li>
 * </ol>
 *
 * Failures show a message; the world is left as it was (the builder clears only its own region). Logs carry steps,
 * ids, counts and codes only.
 */
public final class AddRegionFlow {
	/** Where the flow is. */
	public enum Step {
		PICK,
		CHECK,
		EXPORT,
		OPEN,
		LOADING,
		BUILD,
		FAILED,
		DONE;

		boolean cancellable() {
			return this == PICK || this == CHECK || this == EXPORT || this == BUILD;
		}
	}

	/** One world the flow may add a region to. */
	public record WorldChoice(String folder, @Nullable RegionTable table, @Nullable String legacyGame) {
		/** The slot the next region takes: the table's next free one, or 1 beside the implicit legacy Kanto. */
		public int nextSlot() {
			return this.table == null ? 1 : this.table.nextFreeSlot();
		}
	}

	/** {@code -Dkantobridge.testRegions=true}: a second region of a world space under another id (the e2e test world). */
	public static final String TEST_REGIONS_PROPERTY = "kantobridge.testRegions";
	private static final long CHECK_WAIT_MS = 7_000;
	private static final long PAUSE_WAIT_MS = 5_000;
	private static final long JOIN_LIMIT_MS = 3 * 60_000L;
	private static final long RELEASE_WAIT_MS = 5_000;

	private static @Nullable AddRegionFlow active;

	private final Minecraft minecraft;
	private final Path exportDir;
	private final AddRegionScreen screen;
	private final List<WorldChoice> worlds;
	private final List<BridgeLink> links;

	private volatile Step step = Step.PICK;
	private long stepSinceNanos = System.nanoTime();
	private boolean ticking;
	private volatile boolean cancelled;

	private @Nullable WorldChoice world;
	private @Nullable BridgeLink link;
	private String regionId = "";
	private int slot = 1;

	private volatile boolean indexed;
	private volatile int exportDone;
	private volatile int exportTotal;
	private volatile long exportBytes;
	private volatile @Nullable ExportClient exporter;
	private volatile boolean workerDone;
	private volatile @Nullable Component workerError;
	private volatile @Nullable Path bundleDir;

	private long doneSeenNanos = -1;
	private @Nullable BuildRequest request;
	private @Nullable Component error;

	private AddRegionFlow(Minecraft minecraft, List<WorldChoice> worlds, List<BridgeLink> links) {
		this.minecraft = minecraft;
		this.exportDir = FabricLoader.getInstance().getGameDir().resolve("kantobridge").resolve("export");
		this.worlds = worlds;
		this.links = links;
		this.world = worlds.isEmpty() ? null : worlds.get(0);
		this.link = links.isEmpty() ? null : links.get(0);
		this.regionId = this.link == null ? "" : Objects.requireNonNullElse(Games.region(this.link.game()), "");
		this.slot = this.world == null ? 1 : this.world.nextSlot();
		this.screen = new AddRegionScreen(this);
	}

	/** Call once from client init: the title-screen button, the tick driver and the shutdown hook. */
	public static void init() {
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
			if (screen instanceof TitleScreen) {
				Screens.getButtons(screen).add(titleButton(minecraft, width, height));
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			AddRegionFlow flow = active;
			if (flow != null) {
				flow.tick();
			}
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> {
			AddRegionFlow flow = active;
			if (flow != null) {
				flow.stopWorker();
			}
		});
	}

	/** Under "Create Kanto world" when it fits, else in the top-right corner below it. */
	private static Button titleButton(Minecraft minecraft, int width, int height) {
		int y = height / 4 + 48 + 72 + 12 + 24 + 24;
		Button.Builder builder = Button.builder(Component.translatable("kantobridge.add_region.button"), button -> start(minecraft));
		if (y + 20 <= height - 12) {
			return builder.bounds(width / 2 - 100, y, 200, 20).build();
		}
		return builder.bounds(width - 154, 28, 150, 20).build();
	}

	/** Starts the flow (one at a time) and shows its screen. Client thread. */
	public static void start(Minecraft minecraft) {
		if (active != null) {
			minecraft.setScreen(active.screen);
			return;
		}
		if (CreateKantoWorldFlow.active() != null) {
			return;
		}
		AddRegionFlow flow = new AddRegionFlow(minecraft, worlds(minecraft.getLevelSource()), candidateLinks());
		active = flow;
		KantoBridge.LOGGER.info("add_region: Add a region started ({} worlds, {} links)", flow.worlds.size(), flow.links.size());
		minecraft.setScreen(flow.screen);
	}

	/** The running flow, if any. */
	public static @Nullable AddRegionFlow active() {
		return active;
	}

	/** The saves that hold a Kanto world: the root index (a legacy world) or a region table. Names and small files only. */
	static List<WorldChoice> worlds(LevelStorageSource saves) {
		List<WorldChoice> out = new ArrayList<>();
		Path base = saves.getBaseDir();
		if (!Files.isDirectory(base)) {
			return out;
		}
		try (Stream<Path> folders = Files.list(base)) {
			for (Path folder : folders.sorted().toList()) {
				Path data = folder.resolve("kantobridge");
				Path table = data.resolve(RegionTable.FILE);
				Path index = data.resolve("world_index.json");
				if (Files.isRegularFile(table)) {
					try {
						out.add(new WorldChoice(folder.getFileName().toString(), RegionTable.fromJson(Files.readString(table, StandardCharsets.UTF_8)),
								null));
					} catch (IOException | DataException e) {
						KantoBridge.LOGGER.warn("add_region: a world's region table is unreadable ({})", e.getClass().getSimpleName());
					}
				} else if (Files.isRegularFile(index)) {
					String game = null;
					try {
						// Only the game is needed here; the whole index is the server's to read.
						game = JsonFields.str(ProtoJson.parseObject(Files.readString(index, StandardCharsets.UTF_8)), "game");
					} catch (IOException | ProtocolException e) {
						KantoBridge.LOGGER.warn("add_region: a world's index is unreadable ({})", e.getClass().getSimpleName());
					}
					out.add(new WorldChoice(folder.getFileName().toString(), null, game));
				}
			}
		} catch (IOException e) {
			KantoBridge.LOGGER.warn("add_region: the saves folder could not be listed ({})", e.getClass().getSimpleName());
		}
		return out;
	}

	/** The links a region can be built from: connected, the right game, a game the catalogue knows. */
	static List<BridgeLink> candidateLinks() {
		Links links = Links.get();
		List<BridgeLink> out = new ArrayList<>();
		if (links == null) {
			return out;
		}
		for (BridgeLink link : links.all()) {
			if (link.connected() && !link.wrongGame() && link.game() != null && Games.known(link.game())) {
				out.add(link);
			}
		}
		return out;
	}

	// ---- what the screen shows and sets ----

	public Step step() {
		return this.step;
	}

	List<WorldChoice> worlds() {
		return this.worlds;
	}

	List<BridgeLink> links() {
		return this.links;
	}

	@Nullable WorldChoice world() {
		return this.world;
	}

	@Nullable BridgeLink link() {
		return this.link;
	}

	String regionId() {
		return this.regionId;
	}

	int slot() {
		return this.slot;
	}

	void nextWorld() {
		if (this.worlds.isEmpty()) {
			return;
		}
		int index = this.world == null ? -1 : this.worlds.indexOf(this.world);
		this.world = this.worlds.get((index + 1) % this.worlds.size());
		this.slot = this.world.nextSlot();
	}

	void nextLink() {
		if (this.links.isEmpty()) {
			return;
		}
		int index = this.link == null ? -1 : this.links.indexOf(this.link);
		this.link = this.links.get((index + 1) % this.links.size());
		this.regionId = Objects.requireNonNullElse(Games.region(this.link.game()), "");
	}

	void setRegionId(String id) {
		this.regionId = id.trim();
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

	// ---- buttons ----

	/** The Start button: checks the choices, then exports. */
	void begin() {
		if (this.step != Step.PICK) {
			return;
		}
		Component refusal = this.refusal();
		if (refusal != null) {
			this.fail(refusal);
			return;
		}
		KantoBridge.LOGGER.info("add_region: game {}, region {}, slot {}", this.link.game(), this.regionId, this.slot);
		this.moveTo(Step.CHECK);
	}

	/** Why the choices can't be built, or null when they can. */
	@Nullable Component refusal() {
		if (this.world == null) {
			return Component.translatable("kantobridge.add_region.no_world");
		}
		if (this.link == null || !this.link.connected()) {
			return Component.translatable("kantobridge.add_region.error.not_connected");
		}
		if (this.link.wrongGame()) {
			return Component.translatable("kantobridge.add_region.error.wrong_game");
		}
		if (!Games.REGION_ID.matcher(this.regionId).matches()) {
			return Component.translatable("kantobridge.add_region.error.bad_id");
		}
		String space = Games.region(this.link.game());
		boolean testRegions = Boolean.getBoolean(TEST_REGIONS_PROPERTY);
		RegionTable table = this.world.table();
		if (table != null) {
			if (table.byId(this.regionId) != null) {
				return Component.translatable("kantobridge.add_region.error.region_exists", this.regionId);
			}
			for (RegionTable.Entry entry : table.entries()) {
				if (!testRegions && space != null && space.equals(Games.region(entry.game()))) {
					return Component.translatable("kantobridge.add_region.error.region_exists", entry.id());
				}
			}
		} else if (!testRegions && space != null && this.world.legacyGame() != null && space.equals(Games.region(this.world.legacyGame()))) {
			// A legacy world is its game's world space already (the implicit kanto at slot 0).
			return Component.translatable("kantobridge.add_region.error.region_exists", "kanto");
		}
		return null;
	}

	/** The Cancel button: stops the export or the build; the flow then ends on the title screen. */
	void cancel() {
		if (!this.step.cancellable() || this.cancelled) {
			return;
		}
		this.cancelled = true;
		KantoBridge.LOGGER.info("add_region: cancelled during {}", this.step);
		ExportClient running = this.exporter;
		if (running != null) {
			running.cancel();
		}
		BuildStatus status = this.buildStatus();
		if (this.step == Step.BUILD && status != null) {
			status.cancel();
		}
		if (this.step == Step.PICK || this.step == Step.CHECK) {
			this.end();
		}
	}

	/** The Back button after a failure. */
	void back() {
		this.end();
	}

	// ---- the state machine (client thread) ----

	private void tick() {
		if (this.ticking) {
			return;
		}
		this.ticking = true;
		try {
			switch (this.step) {
				case CHECK -> this.tickCheck();
				case EXPORT -> this.tickWorker();
				case OPEN -> this.openWorld();
				case LOADING -> this.tickLoading();
				case BUILD -> this.tickBuild();
				default -> {
				}
			}
		} catch (RuntimeException e) {
			KantoBridge.LOGGER.error("add_region: unexpected {} during {}", e.getClass().getName(), this.step, e);
			this.fail(Component.translatable("kantobridge.add_region.error.internal", e.getClass().getSimpleName()));
		} finally {
			this.ticking = false;
		}
	}

	private void moveTo(Step next) {
		KantoBridge.LOGGER.info("add_region: {} -> {}", this.step, next);
		this.step = next;
		this.stepSinceNanos = System.nanoTime();
	}

	private long stepMillis() {
		return (System.nanoTime() - this.stepSinceNanos) / 1_000_000L;
	}

	private void tickCheck() {
		BridgeLink chosen = this.link;
		if (chosen == null) {
			this.fail(Component.translatable("kantobridge.add_region.error.not_connected"));
			return;
		}
		if (chosen.connected()) {
			this.startWorker(chosen.client());
		} else if (this.stepMillis() > CHECK_WAIT_MS) {
			this.fail(Component.translatable("kantobridge.add_region.error.not_connected"));
		}
	}

	/** The export on a worker thread, on the chosen link alone (paused meanwhile; the followed link stays up). */
	private void startWorker(BridgeClient client) {
		Catalog catalog = Catalog.bundled();
		BridgeConfig config = BridgeConfig.fromCatalog(catalog, client.config().port());
		ExportClient exportClient = new ExportClient(config, catalog, CreateKantoWorldFlow.builderHello(), Transport.socket(),
				BridgeClock.system(), ExportClient.Options.DEFAULTS);
		this.exporter = exportClient;
		this.moveTo(Step.EXPORT);
		Thread thread = new Thread(() -> this.work(client, exportClient), "kantobridge-add-region");
		thread.setDaemon(true);
		thread.start();
	}

	private void work(BridgeClient client, ExportClient exportClient) {
		try {
			ExportClient.Result result;
			try (BridgeClient.Pause pause = client.pauseReconnects("export", PAUSE_WAIT_MS)) {
				result = exportClient.run(this.exportDir, new ExportClient.Listener() {
					@Override
					public void indexed(String game, int resources, long bytes) {
						AddRegionFlow.this.exportTotal = resources;
						AddRegionFlow.this.indexed = true;
					}

					@Override
					public void progress(int done, int total, long bytesDone, long bytesTotal) {
						AddRegionFlow.this.exportDone = done;
						AddRegionFlow.this.exportBytes = bytesDone;
					}
				});
			}
			this.bundleDir = result.bundleDir();
		} catch (ExportClient.ExportException e) {
			this.workerError = Component.translatable("kantobridge.add_region.error.export", e.code());
		} catch (InterruptedException e) {
			this.workerError = Component.translatable("kantobridge.add_region.error.internal", "interrupted");
		} catch (RuntimeException e) {
			KantoBridge.LOGGER.error("add_region: unexpected {} while exporting", e.getClass().getName(), e);
			this.workerError = Component.translatable("kantobridge.add_region.error.internal", e.getClass().getSimpleName());
		} finally {
			this.exporter = null;
			this.workerDone = true;
		}
	}

	private void tickWorker() {
		if (!this.workerDone) {
			return;
		}
		if (this.cancelled) {
			this.end();
			return;
		}
		Component failure = this.workerError;
		if (failure != null) {
			this.fail(failure);
			return;
		}
		this.moveTo(Step.OPEN);
	}

	/** The build request filed first, so the world's server finds it on start; then the existing world is opened. */
	private void openWorld() {
		Path bundle = this.bundleDir;
		WorldChoice chosen = this.world;
		if (bundle == null || chosen == null) {
			this.fail(Component.translatable("kantobridge.add_region.error.internal", "no_bundle"));
			return;
		}
		// The client's pack is left alone (one art set): the build must not replace it while it is in use.
		this.request = BuildRequest.submit(chosen.folder(), bundle, null,
				BuildOptions.defaults().withWritePack(false).withRegion(this.regionId, this.slot));
		this.moveTo(Step.LOADING);
		this.minecraft.createWorldOpenFlows().openWorld(chosen.folder(), () -> {
			AddRegionFlow flow = active;
			if (flow == this) {
				this.fail(Component.translatable("kantobridge.add_region.error.open"));
			}
		});
	}

	private void tickLoading() {
		BuildStatus status = this.buildStatus();
		boolean inWorld = this.minecraft.level != null && this.minecraft.player != null;
		if (!inWorld) {
			if (this.step == Step.FAILED) {
				return;
			}
			if (this.minecraft.getSingleplayerServer() == null && this.stepMillis() > 5_000) {
				this.fail(Component.translatable("kantobridge.add_region.error.open"));
			} else if (this.stepMillis() > JOIN_LIMIT_MS) {
				this.fail(Component.translatable("kantobridge.add_region.error.open"));
			}
			return;
		}
		if (status == null || status.state() == BuildStatus.State.WAITING) {
			// SERVER_STARTED runs before anyone can join: a request still waiting was never taken.
			this.fail(Component.translatable("kantobridge.add_region.error.build", "not_started"));
			return;
		}
		this.moveTo(Step.BUILD);
		this.minecraft.setScreen(this.screen);
		this.tickBuild();
	}

	private void tickBuild() {
		BuildStatus status = this.buildStatus();
		if (status == null || this.minecraft.level == null) {
			this.fail(Component.translatable("kantobridge.add_region.error.build", "left"));
			return;
		}
		switch (status.state()) {
			case DONE -> {
				boolean arrived = this.minecraft.gameMode != null && this.minecraft.gameMode.getPlayerMode() != GameType.SPECTATOR;
				if (this.doneSeenNanos < 0) {
					this.doneSeenNanos = System.nanoTime();
				}
				if (!arrived && System.nanoTime() - this.doneSeenNanos < RELEASE_WAIT_MS * 1_000_000L) {
					return;
				}
				KantoBridge.LOGGER.info("add_region: built region {} at slot {} ({} maps, {} warnings)", this.regionId, this.slot, status.mapsBuilt(),
						status.warnings());
				this.moveTo(Step.DONE);
				active = null;
				this.minecraft.setScreen(null);
			}
			case FAILED -> {
				if (this.cancelled) {
					this.end();
				} else {
					this.fail(buildError(String.valueOf(status.error())));
				}
			}
			default -> {
				if (this.minecraft.screen != this.screen && (this.minecraft.screen == null || this.minecraft.screen instanceof PauseScreen)) {
					this.minecraft.setScreen(this.screen);
				}
			}
		}
	}

	/** A build failure's message: the region codes have their own text, the rest shows the code. */
	static Component buildError(String code) {
		return switch (code) {
			case "build.region_overlap", "build.region_exists", "build.slot_map_full" -> Component.translatable("kantobridge.add_region.error." + code);
			default -> Component.translatable("kantobridge.add_region.error.build", code);
		};
	}

	/** Shows a failure on the flow's screen; Back ends the flow. */
	private void fail(Component message) {
		KantoBridge.LOGGER.warn("add_region: failed during {}", this.step);
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

	/** Ends the flow on the title screen (a world left half-built keeps its other regions; this one is cleared next time). */
	private void end() {
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
			this.minecraft.level.disconnect();
			this.minecraft.disconnect(new GenericMessageScreen(Component.translatable("kantobridge.create.cleanup")));
		}
		this.minecraft.setScreen(new TitleScreen());
	}

	private void stopWorker() {
		ExportClient running = this.exporter;
		if (running != null) {
			running.cancel();
		}
	}
}
