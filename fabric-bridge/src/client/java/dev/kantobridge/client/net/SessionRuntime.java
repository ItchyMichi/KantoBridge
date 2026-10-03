package dev.kantobridge.client.net;

import com.cobblemon.mod.common.client.gui.pc.PCGUI;
import com.cobblemon.mod.common.net.messages.server.storage.pc.UnlinkPlayerFromPCPacket;
import com.google.gson.JsonObject;
import dev.kantobridge.client.bridge.BridgeClock;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.bridge.ConnectionState;
import dev.kantobridge.client.bridge.Dispatcher;
import dev.kantobridge.client.bridge.HelloOk;
import dev.kantobridge.client.bridge.JsonFields;
import dev.kantobridge.client.move.InputLock;
import dev.kantobridge.client.ui.UiRuntime;
import dev.kantobridge.client.world.ClientWorldIndex;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.server.SessionService;
import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.server.IntegratedServer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minecraft glue for saving ({@link SaveRelay}), Bill's PC ({@link PcRelay}) and the save pairing ({@link SessionBinder}):
 * gen1-bridge's messages come through the {@link Dispatcher}, the server's through {@link ClientRelay}, both on the
 * client thread. While Bill's PC is open the player is held and our screens wait for Cobblemon's PC screen.
 *
 * <p>The world id for {@code session.bind} comes from the server's {@code session.bind} once the protocol gives that
 * type an {@code s2c} leg; until then only the integrated server's own client (singleplayer, a LAN host) knows it, read
 * in-process from {@link SessionService}. Logs carry codes and counts only.
 *
 * <p>M9: the {@link SessionGate} holds a world's data on both hops until gen1-bridge's {@code session.bound} for this
 * link's bind (the hidden game is on the world's save); the save and PC relays' ports, and {@link GameRelayRuntime}'s,
 * ask it first. It also shows the switch's waiting reason and refusals on the HUD.
 */
public final class SessionRuntime {
	private static final Logger LOGGER = LoggerFactory.getLogger("kantobridge");
	private static @Nullable SessionRuntime instance;

	private final BridgeClock clock = BridgeClock.system();
	private final BridgeRuntime bridge;
	private final SessionGate gate;
	private final SaveRelay saves;
	private final PcRelay pc;
	private final SessionBinder binder;
	/** The world id the server sent ({@code session.bind} S→C), or null. */
	private @Nullable String serverWorld;

	private SessionRuntime(BridgeRuntime bridge) {
		this.bridge = bridge;
		this.gate = new SessionGate(new SessionGate.Port() {
			@Override
			public void replay(Envelope message) {
				SessionRuntime.this.replay(message);
			}

			@Override
			public void opened(boolean stale) {
				SessionRuntime.this.opened(stale);
			}

			@Override
			public void worldChanged() {
				GameRelayRuntime relay = GameRelayRuntime.get();
				if (relay != null) {
					relay.relay().dropQueue();
					relay.relay().dropUses();
				}
			}

			@Override
			public void rebind() {
				SessionRuntime.this.binder.rebind();
			}

			@Override
			public void warn(String code, long count) {
				LOGGER.warn("session: {} ({} so far)", code, count);
			}
		});
		SessionGate gate = this.gate;
		this.saves = new SaveRelay(new SaveRelay.Port() {
			@Override
			public boolean toBridge(String type, @Nullable Long re, JsonObject data) {
				return gate.bridgeOpen() && bridge.client().send(type, re, data);
			}

			@Override
			public boolean toServer(String type, JsonObject data) {
				return gate.serverOpen() && ClientRelay.get().send(type, data, null);
			}

			@Override
			public void warn(String code, long count) {
				LOGGER.warn("relay: {} ({} so far)", code, count);
			}
		});
		this.pc = new PcRelay(new PcRelay.Port() {
			@Override
			public boolean toBridge(String type, JsonObject data) {
				return gate.bridgeOpen() && bridge.client().send(type, data);
			}

			@Override
			public boolean toServer(String type, JsonObject data) {
				return gate.serverOpen() && ClientRelay.get().send(type, data, null);
			}

			@Override
			public void unlink() {
				SessionRuntime.unlinkPc();
			}

			@Override
			public void warn(String code, long count) {
				LOGGER.warn("relay: {} ({} so far)", code, count);
			}
		});
		this.binder = new SessionBinder(new SessionBinder.Port() {
			@Override
			public long toBridge(String type, JsonObject data) {
				long seq = bridge.client().sendTracked(type, data);
				if (seq > 0 && type.equals(MsgTypes.SESSION_BIND)) {
					gate.bindSent(seq, SessionRuntime.this.now());
				}
				return seq;
			}

			@Override
			public boolean toServer(String type, JsonObject data) {
				return ClientRelay.get().send(type, data, null);
			}

			@Override
			public void warn(String code, long count) {
				LOGGER.warn("session: {} ({} so far)", code, count);
			}
		}, hasLeg(MsgTypes.SESSION_BOUND, Catalog.Leg.C2S));
	}

	/** Call once from the client entrypoint, after {@link BridgeRuntime#init} and the UI runtime. */
	public static void init(BridgeRuntime bridge) {
		if (instance != null) {
			return;
		}
		SessionRuntime runtime = new SessionRuntime(bridge);
		instance = runtime;
		Dispatcher dispatcher = bridge.dispatcher();
		dispatcher.on(MsgTypes.SAVE_BEGIN, message -> {
			if (runtime.gate.admit(message) == SessionGate.Admit.PASS) {
				runtime.saves.fromBridge(message, runtime.now());
			}
		});
		dispatcher.on(MsgTypes.PC_OPEN, message -> {
			switch (runtime.gate.admit(message)) {
				case PASS -> runtime.pc.fromBridge(runtime.now());
				// Not before the pairing: gen1-bridge lets its PC menu go.
				case ANSWER_CLOSED -> bridge.client().send(MsgTypes.PC_CLOSED, new JsonObject());
				default -> {
				}
			}
		});
		dispatcher.on(MsgTypes.SNAPSHOT, message -> {
			runtime.pc.bridgeSnapshot(runtime.now());
			runtime.gate.snapshot();
		});
		dispatcher.on(MsgTypes.SESSION_BOUND, message -> {
			if (runtime.binder.onBound(message.data(), message.re())) {
				runtime.gate.bound(message.re(), runtime.now());
			}
		});
		dispatcher.on(MsgTypes.ERROR, message -> runtime.gate.error(JsonFields.str(message.data(), "code"), message.re(), runtime.now()));
		dispatcher.on(MsgTypes.LOG, message -> runtime.gate.log(JsonFields.str(message.data(), "code"), JsonFields.str(message.data(), "msg"),
				runtime.now()));
		dispatcher.onState((state, detail) -> {
			if (state == ConnectionState.CONNECTED) {
				runtime.saves.linkChanged();
				runtime.binder.linkUp();
				HelloOk hello = bridge.client().handshake();
				runtime.gate.linkUp(hello == null ? null : hello.session(), runtime.now());
			} else {
				runtime.binder.linkDown();
				runtime.gate.linkDown(runtime.now());
			}
		});
		ClientRelay server = ClientRelay.get();
		server.register(MsgTypes.SAVE_DONE, message -> runtime.saves.fromServer(message, runtime.now()));
		server.register(MsgTypes.PC_CLOSED, message -> runtime.pc.fromServer(runtime.now()));
		if (hasLeg(MsgTypes.SESSION_BIND, Catalog.Leg.S2C)) {
			server.register(MsgTypes.SESSION_BIND, message -> runtime.serverWorld = JsonFields.str(message.data(), "world"));
		}
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> runtime.serverWorld = null);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			runtime.saves.serverLeft(runtime.now());
			runtime.pc.serverLeft(runtime.now());
			runtime.serverWorld = null;
			runtime.binder.world(null, false);
		});
		// After BridgeRuntime's tick, which drains the bridge's messages.
		ClientTickEvents.END_CLIENT_TICK.register(runtime::tick);
		// Cobblemon's PC screen holds the player by itself; this covers the moments before it opens and after it closes.
		InputLock.holdWhile(runtime.pc::pending);
		UiRuntime.suspendWhile(runtime.pc::pending);
		bridge.addHudLine(() -> runtime.gate.hudLine(runtime.now()));
		bridge.addDebugLine(runtime::debugSaves);
		bridge.addDebugLine(runtime::debugWorld);
		bridge.addDebugLine(runtime::debugGate);
	}

	public static @Nullable SessionRuntime get() {
		return instance;
	}

	public SaveRelay saves() {
		return this.saves;
	}

	public PcRelay pc() {
		return this.pc;
	}

	public SessionBinder binder() {
		return this.binder;
	}

	/** The save pairing's gate (M9): whether a world's data may flow yet. */
	public SessionGate gate() {
		return this.gate;
	}

	/** A message the gate held goes to its relay now (the gate opened for the world it belongs to). */
	private void replay(Envelope message) {
		long now = this.now();
		if (message.type().equals(MsgTypes.SAVE_BEGIN)) {
			this.saves.fromBridge(message, now);
		} else if (message.type().equals(MsgTypes.PC_OPEN)) {
			this.pc.fromBridge(now);
		} else {
			GameRelayRuntime relay = GameRelayRuntime.get();
			if (relay != null) {
				relay.relay().fromBridge(message, now);
			}
		}
	}

	/**
	 * The gate opened: the world's newest syncs and waiting {@code bag.use}s go to gen1-bridge; after a stale opening (the
	 * hidden game may have switched saves) the offline queue goes and gen1-bridge is asked for a fresh snapshot, which says
	 * whether its game is at the title now and sends again what it still has pending.
	 */
	private void opened(boolean stale) {
		GameRelayRuntime relay = GameRelayRuntime.get();
		if (relay != null) {
			if (stale) {
				relay.relay().dropQueue();
			}
			relay.relay().resendSyncs();
			relay.relay().flushUses();
		}
		if (stale && !this.bridge.client().send(MsgTypes.RESYNC, new JsonObject())) {
			// No link: its next connection starts with a snapshot anyway.
			this.gate.snapshot();
		}
		LOGGER.info("session: this world's data flows ({}{})", stale ? "fresh start" : "same save as before",
				this.gate.unpaired() ? ", unpaired" : "");
	}

	private long now() {
		return this.clock.millis();
	}

	private void tick(Minecraft minecraft) {
		long now = this.now();
		this.saves.tick(now);
		this.pc.tick(now, minecraft.screen instanceof PCGUI);
		boolean kanto = minecraft.level != null && minecraft.player != null && ClientWorldIndex.current() != null;
		String world = kanto ? this.worldId(minecraft) : null;
		// The gate learns the world before the binder sends its bind, so the bind is recorded against it.
		this.gate.world(kanto, world, now);
		this.binder.world(world, !minecraft.isLocalServer());
		this.binder.tick();
		this.gate.tick(now);
	}

	/** The server's world id: from its {@code session.bind}, or read in-process on our own integrated server. */
	private @Nullable String worldId(Minecraft minecraft) {
		if (this.serverWorld != null) {
			return this.serverWorld;
		}
		IntegratedServer server = minecraft.getSingleplayerServer();
		return server == null ? null : SessionService.worldId(server);
	}

	/** Cobblemon's own "PC closed" packet, which makes the server drop the PC link (and answer {@code pc.closed}). */
	private static void unlinkPc() {
		if (Minecraft.getInstance().getConnection() == null) {
			return;
		}
		try {
			new UnlinkPlayerFromPCPacket().sendToServer();
		} catch (RuntimeException e) {
			LOGGER.warn("relay: the PC unlink couldn't be sent ({})", e.getClass().getName());
		}
	}

	private static boolean hasLeg(String type, Catalog.Leg leg) {
		Catalog.Entry entry = Catalog.bundled().get(type);
		return entry != null && entry.legs().contains(leg);
	}

	private String debugSaves() {
		SaveRelay.Stats saves = this.saves.stats();
		PcRelay.Stats pc = this.pc.stats();
		return I18n.get("kantobridge.debug.saves", saves.relayed, this.saves.pending(), saves.answered, saves.failed, saves.answeredHere,
				saves.repeats, this.pc.state().name().toLowerCase(Locale.ROOT), pc.opened, pc.closed, pc.answeredHere, pc.unlinks);
	}

	private String debugGate() {
		SessionGate.Stats stats = this.gate.stats();
		String waiting = this.gate.waiting();
		return I18n.get("kantobridge.debug.gate", this.gate.state().name().toLowerCase(Locale.ROOT), this.gate.heldCount(), stats.replayed,
				stats.dropped, stats.opened, stats.staleOpenings, stats.refusals, waiting == null ? "-" : waiting);
	}

	private String debugWorld() {
		String world = this.binder.world();
		Integer slot = this.binder.slot();
		return I18n.get("kantobridge.debug.world", world == null ? "-" : SessionService.shortId(world),
				I18n.get(this.binder.multiplayer() ? "kantobridge.debug.world.online" : "kantobridge.debug.world.offline"),
				this.binder.bound() ? I18n.get("kantobridge.debug.yes") : I18n.get("kantobridge.debug.no"), this.binder.stats().binds,
				slot == null ? "-" : slot.toString());
	}
}
