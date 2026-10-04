package dev.kantobridge.client.bridge;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.proto.Catalog;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
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
 * Minecraft glue for the bridge (the classes it wires are plain Java): reads the config, starts {@link BridgeClient}
 * at client init and closes it when the game stops, drains the {@link Dispatcher} every client tick, and draws the
 * connection status and the debug overlay.
 *
 * <p>M10: it also runs the pause overlay ({@link LinkPause}, {@link LinkPauseScreen}): in a Kanto world, with the link
 * down for more than a moment and no export or world build running, the game waits behind it; it replaces our own
 * screens of the hidden game's UIs ({@link LinkScreen}), waits for any other screen to close, and goes once the link is
 * ready. The HUD's lines are worked out once per tick, so a frame allocates nothing of ours to draw them.
 */
public final class BridgeRuntime {
	private static final Logger LOGGER = LoggerFactory.getLogger("kantobridge");
	/** Events handled per client tick; the rest wait for the next tick rather than stalling a frame. */
	private static final int EVENTS_PER_TICK = 512;
	/** F7 is unused by vanilla, Cobblemon and the keys PLAN P20 reserves. */
	private static final int DEBUG_KEY = GLFW.GLFW_KEY_F7;

	private static @Nullable BridgeRuntime instance;

	private final BridgeClock clock = BridgeClock.system();
	private final Catalog catalog;
	private final ClientConfig config;
	private final Dispatcher dispatcher;
	private final ClientBridgeState state = new ClientBridgeState();
	private final ConnectionStatus status = new ConnectionStatus();
	private final ConnectionHud hud = new ConnectionHud();
	private final LinkPause pause = new LinkPause();
	private final BridgeClient client;
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
		this.dispatcher = new Dispatcher(catalog);
		this.state.attach(this.dispatcher);
		this.client = new BridgeClient(BridgeConfig.fromCatalog(catalog, config.port()), catalog, BridgeRuntime::helloInfo,
				Transport.socket(), this.clock, this.dispatcher);
		this.dispatcher.onState((next, detail) -> {
			long now = this.clock.millis();
			this.status.update(next, detail, now);
			this.pause.state(next, detail, this.client.rejectProtocol(), now);
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
		ClientTickEvents.END_CLIENT_TICK.register(runtime::tick);
		HudRenderCallback.EVENT.register((graphics, delta) -> runtime.renderHud(graphics, Minecraft.getInstance()));
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
			if (screen instanceof TitleScreen) {
				ScreenEvents.afterRender(screen).register((shown, graphics, mouseX, mouseY, delta) -> runtime.hud.render(graphics, minecraft, false));
			}
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> runtime.client.close());
		runtime.overlay.addLine(runtime::debugPause);
		runtime.client.start();
		LOGGER.info("bridge: connecting to gen1-bridge on {}:{}", BridgeConfig.LOOPBACK, config.port());
	}

	/** The running bridge, or null before client init. */
	public static @Nullable BridgeRuntime get() {
		return instance;
	}

	public BridgeClient client() {
		return this.client;
	}

	/** The client's settings ({@code kantobridge-client.json}), as read at start-up. */
	public ClientConfig config() {
		return this.config;
	}

	public Dispatcher dispatcher() {
		return this.dispatcher;
	}

	public ClientBridgeState state() {
		return this.state;
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
		return this.pause.view(this.clock.millis(), this.client.retryAt());
	}

	/** Our protocol number. */
	public int protocol() {
		return this.catalog.protocol();
	}

	/** The port gen1-bridge is expected on. */
	public int port() {
		return this.client.config().port();
	}

	private void tick(Minecraft minecraft) {
		this.dispatcher.drain(EVENTS_PER_TICK);
		while (this.debugKey.consumeClick()) {
			this.overlay.toggle();
		}
		long now = this.clock.millis();
		this.tickPause(minecraft, now);
		this.hud.refresh(minecraft.font, this.status.line(now), this.hudLines);
		this.overlay.tick(minecraft, this.client, this.state, now);
	}

	/** The pause overlay: up when due (over nothing, or over one of our link screens), gone once the link is ready. */
	private void tickPause(Minecraft minecraft, long now) {
		boolean kanto = minecraft.level != null && minecraft.player != null && this.following.getAsBoolean();
		boolean excluded = this.client.state() == ConnectionState.PAUSED;
		for (BooleanSupplier exclusion : this.pauseExclusions) {
			excluded |= exclusion.getAsBoolean();
		}
		boolean show = this.pause.tick(now, kanto, excluded);
		Screen current = minecraft.screen;
		if (show) {
			if (current == null || current instanceof LinkScreen) {
				LOGGER.info("bridge: the game waits for Gen1Recomp ({})", this.client.state().name().toLowerCase(Locale.ROOT));
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
		long retry = this.client.retryAt();
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
