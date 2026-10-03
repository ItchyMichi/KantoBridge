package dev.kantobridge.client.ui;

import com.google.gson.JsonObject;
import dev.kantobridge.client.battle.ClientBattles;
import dev.kantobridge.client.bridge.BridgeClock;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.bridge.ConnectionState;
import dev.kantobridge.client.bridge.Dispatcher;
import dev.kantobridge.client.bridge.JsonFields;
import dev.kantobridge.client.move.InputLock;
import dev.kantobridge.client.move.MoveRuntime;
import dev.kantobridge.proto.MsgTypes;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minecraft glue for the hidden game's UIs (docs/PLAN.md §2.4 flows 2–5, §4.3 "UI"): feeds {@link UiRouter} the
 * bridge's {@code ui.*}, {@code snapshot}, {@code player.state} and connection changes, and keeps exactly one of our
 * screens showing the router's {@link UiRouter.View} while the player follows the hidden game (a Kanto world, not
 * creative or spectator). Another screen over ours (the pause menu) is left alone; ours comes back when it closes.
 * Everything runs on the client thread, after the bridge's messages are drained each tick. Logs carry codes, kinds and
 * counts only.
 */
public final class UiRuntime {
	private static final Logger LOGGER = LoggerFactory.getLogger("kantobridge");
	/**
	 * Our screen stays this long after its UI closed, so a text box that follows another a few frames later (a cutscene's
	 * next line) replaces it without the screen closing and grabbing the mouse in between. It takes no input meanwhile.
	 */
	static final long CLOSE_GRACE_MS = 150;

	private static @Nullable UiRuntime instance;

	private final BridgeRuntime bridge;
	private final BridgeClock clock = BridgeClock.system();
	private final UiRouter router;
	private final MirrorLink mirror;
	private long buttons;
	/** When the shown screen's UI went away, or -1. */
	private long closingSince = -1;

	private UiRuntime(BridgeRuntime bridge) {
		this.bridge = bridge;
		this.router = new UiRouter(new UiRouter.Port() {
			@Override
			public boolean send(String type, JsonObject data) {
				return bridge.client().send(type, data);
			}

			@Override
			public void warn(String code, long count) {
				LOGGER.warn("ui: {} ({} so far)", code, count);
			}
		});
		this.mirror = new MirrorLink((type, data) -> bridge.client().send(type, data));
	}

	/** Call once from the client entrypoint, after {@link BridgeRuntime#init}. */
	public static void init(BridgeRuntime bridge) {
		if (instance != null) {
			return;
		}
		Keybinds.register();
		UiRuntime runtime = new UiRuntime(bridge);
		instance = runtime;
		Dispatcher dispatcher = bridge.dispatcher();
		dispatcher.on(MsgTypes.UI_OPEN, message -> runtime.router.onOpen(message.data(), runtime.now()));
		dispatcher.on(MsgTypes.UI_UPDATE, message -> runtime.router.onUpdate(message.data(), runtime.now()));
		dispatcher.on(MsgTypes.UI_CLOSE, message -> runtime.router.onClose(message.data()));
		dispatcher.on(MsgTypes.SNAPSHOT, message -> runtime.router.onSnapshot(message.data(), runtime.now()));
		dispatcher.on(MsgTypes.PLAYER_STATE, message -> {
			runtime.router.onMode(JsonFields.str(message.data(), "mode"), runtime.now());
		});
		dispatcher.on(MsgTypes.MIRROR_FRAME, message -> runtime.mirror.onFrame(message.data()));
		dispatcher.onState((state, detail) -> {
			if (state != ConnectionState.CONNECTED) {
				runtime.router.onDisconnected();
				runtime.mirror.connectionLost();
				MirrorScreen.Picture.reset();
			}
		});
		// Registered after BridgeRuntime's tick, which drains the bridge's messages first.
		ClientTickEvents.END_CLIENT_TICK.register(runtime::tick);
		// A hidden UI holds the player even while another screen covers ours.
		InputLock.holdWhile(runtime.router::open);
		bridge.addDebugLine(runtime::debugLine);
	}

	/** The running glue, or null before client init. */
	public static @Nullable UiRuntime get() {
		return instance;
	}

	public UiRouter router() {
		return this.router;
	}

	public MirrorLink mirror() {
		return this.mirror;
	}

	public long now() {
		return this.clock.millis();
	}

	/** True while a hidden-game UI is open on our side. */
	public boolean open() {
		return this.router.open();
	}

	/** A Game Boy button straight to the hidden game ({@code act.button} tap), for the mirror screen. */
	public void button(Keybinds.Button button) {
		JsonObject data = new JsonObject();
		data.addProperty("button", button.id());
		data.addProperty("how", "tap");
		if (this.bridge.client().send(MsgTypes.ACT_BUTTON, data)) {
			this.buttons++;
		}
	}

	/** Esc on one of our screens: the pause menu; our screen returns when it closes. */
	void pause(BridgeScreen screen) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.screen == screen) {
			minecraft.setScreen(null);
			minecraft.pauseGame(false);
		}
	}

	private void tick(Minecraft minecraft) {
		this.router.tick(this.now());
		this.reconcile(minecraft);
	}

	/**
	 * Shows the router's view in one screen of ours, or none. Never during a Cobblemon battle: its own screens are up, and
	 * the hidden game waits for the result (a UI still open meanwhile holds the player and shows after the battle).
	 */
	private void reconcile(Minecraft minecraft) {
		boolean allowed = minecraft.level != null && minecraft.player != null && MoveRuntime.following() && !ClientBattles.active();
		UiRouter.View view = allowed ? this.router.view() : null;
		Screen current = minecraft.screen;
		if (view == null) {
			if (current instanceof BridgeScreen) {
				long now = this.now();
				if (this.closingSince < 0) {
					this.closingSince = now;
				}
				if (!allowed || now - this.closingSince >= CLOSE_GRACE_MS) {
					this.closingSince = -1;
					minecraft.setScreen(null);
				}
			}
			return;
		}
		this.closingSince = -1;
		if (current instanceof BridgeScreen screen) {
			if (screen.accepts(view)) {
				// The same main UI: a choice may have come or gone over it.
				screen.setView(view);
				return;
			}
			minecraft.setScreen(this.create(view));
			return;
		}
		if (current == null) {
			minecraft.setScreen(this.create(view));
		}
		// Another screen (the pause menu, …) is open: ours comes back once it closes.
	}

	private BridgeScreen create(UiRouter.View view) {
		return switch (view.main().kind()) {
			case TEXT, CHOICE -> new TextBoxScreen(this, view);
			case MENU -> new MenuScreen(this, view);
			case LIST -> new ListScreen(this, view);
			case QUANTITY -> new QuantityScreen(this, view);
			case NAMING -> new NamingScreen(this, view);
			case MIRROR -> new MirrorScreen(this, view);
		};
	}

	private String debugLine() {
		UiRouter.Stats stats = this.router.stats();
		UiSession top = this.router.top();
		return I18n.get("kantobridge.debug.ui", this.router.sessions().size(), top == null ? "-" : top.toString(), stats.opened, stats.closed,
				stats.advances, stats.picks + stats.backs + stats.quantities + stats.names, stats.stale + stats.refused,
				this.mirror.subscribed() ? I18n.get("kantobridge.debug.yes") : I18n.get("kantobridge.debug.no"), this.mirror.frames(),
				this.mirror.badFrames(), this.buttons);
	}
}
