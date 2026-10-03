package dev.kantobridge.client.bridge;

import com.mojang.blaze3d.platform.InputConstants;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.proto.Catalog;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
import net.minecraft.client.gui.screens.TitleScreen;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minecraft glue for the bridge (the classes it wires are plain Java): reads the config, starts {@link BridgeClient}
 * at client init and closes it when the game stops, drains the {@link Dispatcher} every client tick, and draws the
 * connection status and the debug overlay.
 */
public final class BridgeRuntime {
	private static final Logger LOGGER = LoggerFactory.getLogger("kantobridge");
	/** Events handled per client tick; the rest wait for the next tick rather than stalling a frame. */
	private static final int EVENTS_PER_TICK = 512;
	/** F7 is unused by vanilla, Cobblemon and the keys PLAN P20 reserves. */
	private static final int DEBUG_KEY = GLFW.GLFW_KEY_F7;

	private static @Nullable BridgeRuntime instance;

	private final BridgeClock clock = BridgeClock.system();
	private final Dispatcher dispatcher;
	private final ClientBridgeState state = new ClientBridgeState();
	private final ConnectionStatus status = new ConnectionStatus();
	private final BridgeClient client;
	private final KeyMapping debugKey;
	private final DebugOverlay overlay = new DebugOverlay();
	/** Extra lines under the status line (why the player is held, …); each returns null when it has nothing to say. */
	private final List<Supplier<ConnectionStatus.@Nullable Line>> hudLines = new CopyOnWriteArrayList<>();

	private BridgeRuntime(Catalog catalog, ClientConfig config) {
		this.dispatcher = new Dispatcher(catalog);
		this.state.attach(this.dispatcher);
		this.dispatcher.onState((next, detail) -> this.status.update(next, detail, this.clock.millis()));
		this.client = new BridgeClient(BridgeConfig.fromCatalog(catalog, config.port()), catalog, BridgeRuntime::helloInfo,
				Transport.socket(), this.clock, this.dispatcher);
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
				ScreenEvents.afterRender(screen).register((shown, graphics, mouseX, mouseY, delta) ->
						ConnectionHud.render(graphics, minecraft, runtime.status, runtime.clock.millis()));
			}
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> runtime.client.close());
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

	public Dispatcher dispatcher() {
		return this.dispatcher;
	}

	public ClientBridgeState state() {
		return this.state;
	}

	/** Adds a HUD line drawn under the connection status (in game only, hidden with F1 and F3 like it). */
	public void addHudLine(Supplier<ConnectionStatus.@Nullable Line> line) {
		this.hudLines.add(line);
	}

	/** Adds a line to the F7 debug overlay (symbols and counters only). */
	public void addDebugLine(Supplier<String> line) {
		this.overlay.addLine(line);
	}

	private void tick(Minecraft minecraft) {
		this.dispatcher.drain(EVENTS_PER_TICK);
		while (this.debugKey.consumeClick()) {
			this.overlay.toggle();
		}
	}

	private void renderHud(GuiGraphics graphics, Minecraft minecraft) {
		long now = this.clock.millis();
		int below = ConnectionHud.render(graphics, minecraft, this.status, now);
		if (!minecraft.options.hideGui && !minecraft.getDebugOverlay().showDebugScreen()) {
			for (Supplier<ConnectionStatus.@Nullable Line> supplier : this.hudLines) {
				ConnectionStatus.Line line = supplier.get();
				if (line != null) {
					below = ConnectionHud.renderLine(graphics, minecraft, line, below);
				}
			}
		}
		this.overlay.render(graphics, minecraft, this.client, this.state, now, below);
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
