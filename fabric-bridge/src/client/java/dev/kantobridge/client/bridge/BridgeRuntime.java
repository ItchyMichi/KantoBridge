package dev.kantobridge.client.bridge;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.client.net.ClientRelay;
import dev.kantobridge.client.world.ClientRegions;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.RegionRecord;
import dev.kantobridge.world.Regions;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minecraft glue for the bridge (the classes it wires are plain Java): reads the config, starts the {@link Links} (one
 * {@link BridgeClient} per hidden game, MG4) at client init and closes them when the game stops, drains their
 * {@link Dispatcher}s every client tick, and draws the connection status and the debug overlay.
 *
 * <p>M10: it also runs the pause overlay ({@link LinkPause}, {@link LinkPauseScreen}): in a Kanto world, with the followed
 * link down for more than a moment and no export or world build running, the game waits behind it; it replaces our own
 * screens of the hidden game's UIs ({@link LinkScreen}), waits for any other screen to close, and goes once the link is
 * ready. A parked link's drop never pauses: it is one HUD line and a refusal at a pass. The HUD's lines are worked out
 * once per tick, so a frame allocates nothing of ours to draw them.
 *
 * <p>MG4: {@link #client()}, {@link #dispatcher()}, {@link #state()} and {@link #port()} are the <b>followed</b> link's
 * (the body's), so the runtimes written for one link keep working; what is per hidden game asks {@link #links()}.
 */
public final class BridgeRuntime {
	private static final Logger LOGGER = LoggerFactory.getLogger("kantobridge");
	/** Events handled per client tick and link; the rest wait for the next tick rather than stalling a frame. */
	private static final int EVENTS_PER_TICK = 512;
	/** F7 is unused by vanilla, Cobblemon and the keys PLAN P20 reserves. */
	private static final int DEBUG_KEY = GLFW.GLFW_KEY_F7;

	private static @Nullable BridgeRuntime instance;

	private final BridgeClock clock = BridgeClock.system();
	private final Catalog catalog;
	private final ClientConfig config;
	private final Links links;
	private final ConnectionHud hud = new ConnectionHud();
	private final LinkPause pause = new LinkPause();
	private final KeyMapping debugKey;
	private final DebugOverlay overlay = new DebugOverlay();
	/** Extra lines under the status line (why the player is held, …); each returns null when it has nothing to say. */
	private final List<Supplier<ConnectionStatus.@Nullable Line>> hudLines = new CopyOnWriteArrayList<>();
	/** While any of these holds, the pause overlay stays away (the export and world-build flows). */
	private final List<BooleanSupplier> pauseExclusions = new CopyOnWriteArrayList<>();
	/** Whether the player follows the hidden game in a Kanto world (set by the movement glue). */
	private volatile BooleanSupplier following = () -> false;

	private BridgeRuntime(Catalog catalog, ClientConfig config) {
		this.catalog = catalog;
		this.config = config;
		Links.RegionView regions = new Links.RegionView() {
			@Override
			public @Nullable String regionOf(@Nullable String game) {
				RegionRecord record = ClientRegions.byGame(game);
				return record == null ? null : record.id();
			}

			@Override
			public @Nullable String legacy() {
				Regions all = ClientRegions.regions();
				RegionRecord record = all == null ? null : all.legacy();
				return record == null ? null : record.id();
			}

			@Override
			public boolean regionWorld() {
				return ClientRegions.regionWorld();
			}

			@Override
			public void followed(@Nullable String region) {
				ClientRegions.setFollowed(region);
			}
		};
		Links.ServerPort server = new Links.ServerPort() {
			@Override
			public long post(String type, JsonObject data, @Nullable Long re, @Nullable String region) {
				return ClientRelay.get().post(type, data, re, region);
			}

			@Override
			public boolean canSend() {
				return ClientRelay.canSend();
			}
		};
		this.links = new Links(catalog, config.links(),
				(linkConfig, listener) -> new BridgeClient(linkConfig, catalog, BridgeRuntime::helloInfo, Transport.socket(), this.clock, listener),
				this.clock, regions, server);
		// The pause overlay follows the followed link only (a swap announces DISCONNECTED then the new link's state).
		this.links.body().onState((next, detail) -> {
			long now = this.clock.millis();
			this.pause.state(next, detail, this.links.followed().client().rejectProtocol(), now);
		});
		this.debugKey = KeyBindingHelper.registerKeyBinding(
				new KeyMapping("key.kantobridge.debug_overlay", InputConstants.Type.KEYSYM, DEBUG_KEY, "key.categories.kantobridge"));
	}

	/** Called once from the client entrypoint. */
	public static void init() {
		if (instance != null) {
			return;
		}
		Catalog catalog = Catalog.bundled();
		ClientConfig config = ClientConfig.load(FabricLoader.getInstance().getConfigDir(), System.getProperties(),
				warning -> LOGGER.warn("config: {}", warning));
		BridgeRuntime runtime = new BridgeRuntime(catalog, config);
		instance = runtime;
		Links.install(runtime.links);
		ClientTickEvents.END_CLIENT_TICK.register(runtime::tick);
		HudRenderCallback.EVENT.register((graphics, delta) -> runtime.renderHud(graphics, Minecraft.getInstance()));
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
			if (screen instanceof TitleScreen) {
				ScreenEvents.afterRender(screen).register((shown, graphics, mouseX, mouseY, delta) -> runtime.hud.render(graphics, minecraft, false));
			}
		});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> runtime.links.serverChanged());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> runtime.links.serverChanged());
		ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> runtime.links.closeAll());
		runtime.overlay.addLine(runtime::debugPause);
		// FP2 (FOREIGN_POKEMON_PLAN §4.1): the followed game's visitors, when it shows any
		runtime.hudLines.add(() -> {
			BridgeLink followed = runtime.links.followed();
			return followed == null ? null : followed.visitorLine();
		});
		// FP5 (§4.12, Q1): a facility desk the followed game refused just now
		runtime.hudLines.add(() -> {
			BridgeLink followed = runtime.links.followed();
			return followed == null ? null : followed.refusedLine();
		});
		// MG4: every link but the followed one has a HUD line when something is wrong with it (never followed: wrong game;
		// parked and down), and F7 lists them all.
		if (runtime.links.all().size() > 1) {
			for (BridgeLink link : runtime.links.all()) {
				runtime.hudLines.add(() -> link == runtime.links.followed() ? null : link.hudLine());
			}
			runtime.overlay.addLine(() -> I18n.get("kantobridge.debug.links", runtime.links.debugLine()));
		}
		// The server's stored presence on join: the region it names is followed when a link runs its game (MG5's crossings
		// end with the same message, after the server moved the body into the far gate house).
		ClientRelay.get().register(MsgTypes.REGION_STATE, message -> runtime.links.serverPresence(message.data()));
		runtime.links.start();
		for (BridgeLink link : runtime.links.all()) {
			LOGGER.info("bridge: connecting to gen1-bridge on {}:{}{}", BridgeConfig.LOOPBACK, link.port(),
					link.configuredGame() == null ? "" : " (" + link.configuredGame() + ")");
		}
	}

	/** The running bridge, or null before client init. */
	public static @Nullable BridgeRuntime get() {
		return instance;
	}

	/** The followed link's client (MG4: what every one-link caller means). */
	public BridgeClient client() {
		return this.links.followed().client();
	}

	/** The client's settings ({@code kantobridge-client.json}), as read at start-up. */
	public ClientConfig config() {
		return this.config;
	}

	/** The body's dispatcher: the followed link's traffic (MG4). */
	public Dispatcher dispatcher() {
		return this.links.body();
	}

	/** The followed link's state. */
	public ClientBridgeState state() {
		return this.links.followed().state();
	}

	/** The links to the hidden games (MG4). */
	public Links links() {
		return this.links;
	}

	public BridgeClock clock() {
		return this.clock;
	}

	/** Adds a HUD line drawn under the connection status (in game only, hidden with F1 and F3 like it). */
	public void addHudLine(Supplier<ConnectionStatus.@Nullable Line> line) {
		this.hudLines.add(line);
	}

	/** Adds a line to the F7 debug overlay (symbols and counters only). */
	public void addDebugLine(Supplier<String> line) {
		this.overlay.addLine(line);
	}

	/** Keeps the pause overlay away while {@code exclusion} holds (an export or a world build runs). */
	public void pauseExcludedWhile(BooleanSupplier exclusion) {
		this.pauseExclusions.add(exclusion);
	}

	/** Tells the pause overlay whether the player follows the hidden game in a Kanto world (only then does it show). */
	public void setFollowing(BooleanSupplier following) {
		this.following = following;
	}

	/** True while the pause overlay is up (or due: another screen keeps it waiting): the player's game waits. */
	public boolean pauseShown() {
		return this.pause.shown();
	}

	/** What the pause overlay says now, or null. */
	@Nullable LinkPause.View pauseView() {
		return this.pause.view(this.clock.millis(), this.links.followed().client().retryAt());
	}

	/** Our protocol number. */
	public int protocol() {
		return this.catalog.protocol();
	}

	/** The port the followed link's gen1-bridge is expected on. */
	public int port() {
		return this.links.followed().port();
	}

	private void tick(Minecraft minecraft) {
		this.links.drain(EVENTS_PER_TICK);
		this.links.tick();
		while (this.debugKey.consumeClick()) {
			this.overlay.toggle();
		}
		long now = this.clock.millis();
		this.tickPause(minecraft, now);
		BridgeLink followed = this.links.followed();
		this.hud.refresh(minecraft.font, followed.status().line(now), this.hudLines);
		this.overlay.tick(minecraft, followed.client(), followed.state(), now);
	}

	/** The pause overlay: up when due (over nothing, or over one of our link screens), gone once the link is ready. */
	private void tickPause(Minecraft minecraft, long now) {
		boolean kanto = minecraft.level != null && minecraft.player != null && this.following.getAsBoolean();
		BridgeClient followed = this.links.followed().client();
		boolean excluded = followed.state() == ConnectionState.PAUSED;
		for (BooleanSupplier exclusion : this.pauseExclusions) {
			excluded |= exclusion.getAsBoolean();
		}
		boolean show = this.pause.tick(now, kanto, excluded);
		Screen current = minecraft.screen;
		if (show) {
			if (current == null || current instanceof LinkScreen) {
				LOGGER.info("bridge: the game waits for Gen1Recomp ({})", followed.state().name().toLowerCase(Locale.ROOT));
				minecraft.setScreen(new LinkPauseScreen(this));
			}
		} else if (current instanceof LinkPauseScreen) {
			minecraft.setScreen(null);
		}
	}

	private void renderHud(GuiGraphics graphics, Minecraft minecraft) {
		int below = this.hud.render(graphics, minecraft, true);
		this.overlay.render(graphics, minecraft, below);
	}

	private String debugPause() {
		LinkPause.View view = this.pauseView();
		long retry = this.links.followed().client().retryAt();
		return I18n.get("kantobridge.debug.pause", view == null ? "-" : view.kind().name().toLowerCase(Locale.ROOT), this.pause.shows(),
				retry < 0 ? "-" : Long.toString(Math.max(0, retry - this.clock.millis())), this.port());
	}

	/** Collected on the socket thread when a connection opens; every value is a version, a UUID or a name. */
	private static HelloInfo helloInfo() {
		String mod = FabricLoader.getInstance().getModContainer(KantoBridge.MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
		String minecraftVersion = null;
		try {
			minecraftVersion = SharedConstants.getCurrentVersion().getName();
		} catch (RuntimeException ignored) {
			// Not detected yet; the field is optional.
		}
		Minecraft minecraft = Minecraft.getInstance();
		User user = minecraft == null ? null : minecraft.getUser();
		return new HelloInfo(mod, minecraftVersion, user == null ? null : user.getProfileId(), user == null ? null : user.getName());
	}
}
