package dev.kantobridge.client.net;

import com.cobblemon.mod.common.client.gui.pc.PCGUI;
import com.cobblemon.mod.common.net.messages.server.storage.pc.UnlinkPlayerFromPCPacket;
import com.google.gson.JsonObject;
import dev.kantobridge.client.bridge.BridgeClock;
import dev.kantobridge.client.bridge.BridgeLink;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.bridge.ConnectionState;
import dev.kantobridge.client.bridge.ConnectionStatus;
import dev.kantobridge.client.bridge.Dispatcher;
import dev.kantobridge.client.bridge.HelloOk;
import dev.kantobridge.client.bridge.JsonFields;
import dev.kantobridge.client.bridge.Links;
import dev.kantobridge.client.move.InputLock;
import dev.kantobridge.client.ui.UiRuntime;
import dev.kantobridge.client.world.ClientRegions;
import dev.kantobridge.client.world.ClientWorldIndex;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.Ids;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.server.SessionService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minecraft glue for saving ({@link SaveRelay}), Bill's PC ({@link PcRelay}) and the save pairing ({@link SessionBinder}):
 * gen1-bridge's messages come through each link's {@link Dispatcher}, the server's through {@link ClientRelay}, both on
 * the client thread. While Bill's PC is open the player is held and our screens wait for Cobblemon's PC screen.
 *
 * <p>The world id for {@code session.bind} comes from the server's {@code session.bind} once the protocol gives that
 * type an {@code s2c} leg; until then only the integrated server's own client (singleplayer, a LAN host) knows it, read
 * in-process from {@link SessionService}. Logs carry codes and counts only.
 *
 * <p>M9: the {@link SessionGate} holds a world's data on both hops until gen1-bridge's {@code session.bound} for this
 * link's bind (the hidden game is on the world's save); the save and PC relays' ports, and {@link GameRelayRuntime}'s,
 * ask it first. It also shows the switch's waiting reason and refusals on the HUD.
 *
 * <p>M10: {@code session.bound.switched} tells the gate whether the hidden game stayed on its save, {@code session.waiting}
 * why a switch waits; {@code save.loaded} (which save the hidden game runs from) goes up through the gate like the other
 * story data; Bill's PC carries an id both ways, and the gate's own answer echoes it.
 *
 * <p>MG4 (several games): the pairing is per hidden game per world, so every {@link BridgeLink} has its own gate, relays
 * and binder ({@link LinkSession}); in a region world a link binds the world when it holds a region built from its game
 * (its {@code session.bind} carries that region), in a legacy world only the followed link does. The player is one: the
 * input hold and the HUD line follow the followed link's PC and gate.
 */
public final class SessionRuntime {
	private static final Logger LOGGER = LoggerFactory.getLogger("kantobridge");
	private static @Nullable SessionRuntime instance;

	private final BridgeClock clock = BridgeClock.system();
	private final BridgeRuntime bridge;
	private final Links links;
	private final Map<BridgeLink, LinkSession> sessions = new LinkedHashMap<>();
	/** The world id the server sent ({@code session.bind} S→C), or null. */
	private @Nullable String serverWorld;
	/** WS2: the recorded choices the server's session.bind named. */
	private RecordedChoices recorded = RecordedChoices.NONE;
	/** WS3: when the player entered the world (-1: not in one), and whether it is a server's (a copy is offered). */
	private long inWorldSince = -1;
	private boolean multiplayerView;

	/** One link's save pairing (MG4): its gate, its save and PC relays and its binder. */
	public final class LinkSession {
		final BridgeLink link;
		final SessionGate gate;
		final SaveRelay saves;
		final PcRelay pc;
		final SessionBinder binder;
		/** WORLD_SESSIONS_PLAN WS3: the first-join screen's answer, a recorded slot the engine lost, the last decision. */
		@Nullable String answer;
		boolean slotLost;
		JoinDecision.@Nullable Result decision;

		LinkSession(BridgeLink link) {
			this.link = link;
			this.gate = new SessionGate(new SessionGate.Port() {
				@Override
				public void replay(Envelope message) {
					LinkSession.this.replay(message);
				}

				@Override
				public void opened(boolean stale) {
					LinkSession.this.opened(stale);
				}

				@Override
				public void worldChanged() {
					GameRelayRuntime relay = GameRelayRuntime.get();
					if (relay != null) {
						relay.relay(link).dropQueue();
						relay.relay(link).dropUses();
					}
				}

				@Override
				public void rebind() {
					LinkSession.this.binder.rebind();
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
					return gate.bridgeOpen() && link.client().send(type, re, data);
				}

				@Override
				public boolean toServer(String type, JsonObject data) {
					return gate.serverOpen() && link.toServer(type, data, null);
				}

				@Override
				public void warn(String code, long count) {
					LOGGER.warn("relay: {} ({} so far)", code, count);
				}
			});
			this.pc = new PcRelay(new PcRelay.Port() {
				@Override
				public boolean toBridge(String type, JsonObject data) {
					return gate.bridgeOpen() && link.client().send(type, data);
				}

				@Override
				public boolean toServer(String type, JsonObject data) {
					return gate.serverOpen() && link.toServer(type, data, null);
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
					long seq = link.client().sendTracked(type, data);
					if (seq > 0 && type.equals(MsgTypes.SESSION_BIND)) {
						gate.bindSent(seq, SessionRuntime.this.now());
					}
					return seq;
				}

				@Override
				public boolean toServer(String type, JsonObject data) {
					return link.toServer(type, data, null);
				}

				@Override
				public void warn(String code, long count) {
					LOGGER.warn("session: {} ({} so far)", code, count);
				}
			}, hasLeg(MsgTypes.SESSION_BOUND, Catalog.Leg.C2S));
		}

		public BridgeLink link() {
			return this.link;
		}

		public SessionGate gate() {
			return this.gate;
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

		/** A message the gate held goes to its relay now (the gate opened for the world it belongs to). */
		private void replay(Envelope message) {
			long now = SessionRuntime.this.now();
			if (message.type().equals(MsgTypes.SAVE_BEGIN)) {
				this.saves.fromBridge(message, now);
			} else if (message.type().equals(MsgTypes.SAVE_LOADED)) {
				this.saves.loaded(message);
			} else if (message.type().equals(MsgTypes.PC_OPEN)) {
				String pc = pcId(message);
				if (pc != null) {
					this.pc.fromBridge(pc, now);
				}
			} else {
				GameRelayRuntime relay = GameRelayRuntime.get();
				if (relay != null) {
					relay.relay(this.link).fromBridge(message, now);
				}
			}
		}

		/**
		 * The gate opened: the world's newest syncs and waiting {@code bag.use}s go to gen1-bridge; after a stale opening (the
		 * hidden game may have switched saves) the offline queue goes and gen1-bridge is asked for a fresh snapshot, which
		 * says whether its game is at the title now and sends again what it still has pending.
		 */
		private void opened(boolean stale) {
			GameRelayRuntime relay = GameRelayRuntime.get();
			if (relay != null) {
				GameRelay ours = relay.relay(this.link);
				if (stale) {
					ours.dropQueue();
				}
				ours.resendSyncs();
				ours.flushUses();
			}
			if (stale && !this.link.client().send(MsgTypes.RESYNC, new JsonObject())) {
				// No link: its next connection starts with a snapshot anyway.
				this.gate.snapshot();
			}
			LOGGER.info("session: this world's data flows ({}{})", stale ? "fresh start" : "same save as before",
					this.gate.unpaired() ? ", unpaired" : "");
		}

		/** WS5: this link as the quit choreography sees it. */
		SessionClose.Target target() {
			LinkSession me = this;
			return new SessionClose.Target() {
				@Override
				public String name() {
					return me.link.game() == null ? "?" : I18n.get("kantobridge.game." + me.link.game());
				}

				@Override
				public boolean requestSave(long now) {
					return me.saves.request("quit", now, data -> me.link.client().sendTracked(MsgTypes.SAVE_REQUEST, data));
				}

				@Override
				public SaveRelay.RequestState saveState() {
					return me.saves.request();
				}

				@Override
				public @Nullable String refusal() {
					return me.saves.refusal();
				}

				@Override
				public void close() {
					if (me.link.client().sendTracked(MsgTypes.SESSION_CLOSE, new JsonObject()) >= 0) {
						SessionRuntime.this.closingLinks.add(me.link);
					}
				}
			};
		}

		/** gen1-bridge's messages for this link (its own dispatcher). */
		void subscribe() {
			Dispatcher dispatcher = this.link.dispatcher();
			dispatcher.on(MsgTypes.SAVE_BEGIN, message -> {
				if (this.gate.admit(message) == SessionGate.Admit.PASS) {
					this.saves.fromBridge(message, SessionRuntime.this.now());
				}
			});
			dispatcher.on(MsgTypes.SAVE_LOADED, message -> {
				if (this.gate.admit(message) == SessionGate.Admit.PASS) {
					this.saves.loaded(message);
				}
			});
			dispatcher.on(MsgTypes.PC_OPEN, message -> {
				String pc = pcId(message);
				if (pc == null) {
					// Nothing to echo: no answer could end gen1-bridge's hold, and the server would refuse it too.
					LOGGER.warn("relay: a pc.open without a usable id");
					return;
				}
				switch (this.gate.admit(message)) {
					case PASS -> this.pc.fromBridge(pc, SessionRuntime.this.now());
					// Not before the pairing: gen1-bridge lets its PC menu go.
					case ANSWER_CLOSED -> this.link.client().send(MsgTypes.PC_CLOSED, PcRelay.closedBody(pc));
					default -> {
					}
				}
			});
			dispatcher.on(MsgTypes.SNAPSHOT, message -> {
				this.pc.bridgeSnapshot(SessionRuntime.this.now());
				this.gate.snapshot();
			});
			dispatcher.on(MsgTypes.SESSION_BOUND, message -> {
				if (this.binder.onBound(message.data(), message.re())) {
					this.gate.bound(message.re(), this.binder.switched(), SessionRuntime.this.now());
				}
			});
			dispatcher.on(MsgTypes.ACT_RESULT, message -> {
				// WS4: the answer to a save.request
				if ("save".equals(JsonFields.str(message.data(), "kind"))) {
					boolean ok = JsonFields.bool(message.data(), "ok", false);
					String why = JsonFields.str(message.data(), "reason");
					if (this.saves.requestResult(message.re(), ok, why) && !ok) {
						SessionRuntime.this.saveRefused(this.link, why);
					}
				}
			});
			dispatcher.on(MsgTypes.SESSION_CLOSED, message -> {
				long home = JsonFields.integer(message.data(), "home", -1);
				SessionRuntime.this.closed(this.link, JsonFields.bool(message.data(), "title", false), home < 0 ? null : (int) home);
			});
			dispatcher.on(MsgTypes.SESSION_WAITING, message -> this.gate.waiting(JsonFields.str(message.data(), "reason"), message.re(),
					SessionRuntime.this.now()));
			dispatcher.on(MsgTypes.ERROR, message -> {
				String code = JsonFields.str(message.data(), "code");
				if ("session.no_slot".equals(code) && message.re() != null && message.re() == this.binder.sentSeq()
						&& this.binder.sentSlot() != null) {
					// WS3: the recorded slot is gone (deleted in the launcher): forget it, ask again (D13), bind afresh
					this.slotLost = true;
					this.binder.rebind();
					LOGGER.warn("session: the recorded save slot is gone; asking again");
					return;
				}
				this.gate.error(code, message.re(), SessionRuntime.this.now());
			});
			dispatcher.onState((state, detail) -> {
				if (state == ConnectionState.CONNECTED) {
					this.saves.linkChanged();
					this.binder.linkUp();
					HelloOk hello = this.link.client().handshake();
					this.gate.linkUp(hello == null ? null : hello.session(), SessionRuntime.this.now());
				} else {
					this.binder.linkDown();
					this.gate.linkDown(SessionRuntime.this.now());
				}
			});
		}

		/** Each client tick (MG4): this link binds the world when it is this link's to bind. */
		void tick(Minecraft minecraft, long now, boolean inWorld, @Nullable String worldId, boolean followed) {
			boolean kanto = inWorld && SessionRuntime.this.bindsWorld(this.link, followed);
			String world = kanto ? worldId : null;
			// The gate learns the world before the binder sends its bind, so the bind is recorded against it.
			this.gate.world(kanto, world, now);
			this.binder.world(world, !minecraft.isLocalServer());
			// FP1: the link's region's choice for members its game lacks (none in a legacy world: visitor)
			dev.kantobridge.world.RegionRecord region = ClientRegions.byId(this.link.region());
			this.binder.incompatible(region == null ? null : region.rules().compat().onIncompatible());
			// WS3: what this link's bind says (the recorded slot, the first-join choice, play/park/title)
			if (kanto) {
				this.decision = SessionRuntime.this.decide(this, now);
				this.binder.decision(this.decision instanceof JoinDecision.Ready ready ? ready.bind() : null);
			} else {
				this.decision = null;
			}
			this.binder.tick();
			this.gate.tick(now);
			this.saves.tick(now);
			this.pc.tick(now, followed && minecraft.screen instanceof PCGUI);
		}
	}

	private SessionRuntime(BridgeRuntime bridge) {
		this.bridge = bridge;
		this.links = bridge.links();
		for (BridgeLink link : this.links.all()) {
			this.sessions.put(link, new LinkSession(link));
		}
	}

	/** Call once from the client entrypoint, after {@link BridgeRuntime#init} and the UI runtime. */
	public static void init(BridgeRuntime bridge) {
		if (instance != null) {
			return;
		}
		SessionRuntime runtime = new SessionRuntime(bridge);
		instance = runtime;
		for (LinkSession session : runtime.sessions.values()) {
			session.subscribe();
		}
		runtime.links.onServer(MsgTypes.SAVE_DONE, false, (link, message) -> runtime.session(link).saves.fromServer(message, runtime.now()));
		runtime.links.onServer(MsgTypes.PC_CLOSED, false, (link, message) -> {
			String pc = pcId(message);
			if (pc != null) {
				runtime.session(link).pc.fromServer(pc, runtime.now());
			} else {
				LOGGER.warn("relay: a pc.closed without a usable id");
			}
		});
		if (hasLeg(MsgTypes.SESSION_BIND, Catalog.Leg.S2C)) {
			runtime.links.onServer(MsgTypes.SESSION_BIND, false, (link, message) -> {
				runtime.serverWorld = JsonFields.str(message.data(), "world");
				// WORLD_SESSIONS_PLAN WS2: the player's recorded choices for this world
				runtime.recorded = RecordedChoices.of(message.data());
			});
		}
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			runtime.serverWorld = null;
			runtime.recorded = RecordedChoices.NONE;
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			long now = runtime.now();
			for (LinkSession session : runtime.sessions.values()) {
				session.saves.serverLeft(now);
				session.pc.serverLeft(now);
				// WS1: a world entered again (the same one too) is bound again on every link
				session.binder.leftWorld();
				// WS3: a declined game is asked again next join; a lost slot is forgotten with the world
				session.answer = null;
				session.slotLost = false;
			}
			runtime.serverWorld = null;
			runtime.recorded = RecordedChoices.NONE;
		});
		// After BridgeRuntime's tick, which drains the bridge's messages.
		ClientTickEvents.END_CLIENT_TICK.register(runtime::tick);
		// Cobblemon's PC screen holds the player by itself; this covers the moments before it opens and after it closes.
		// MG4: the body's one hold follows the followed link's PC.
		InputLock.holdWhile(() -> runtime.pc().pending());
		UiRuntime.suspendWhile(() -> runtime.pc().pending());
		bridge.addHudLine(() -> runtime.gate().hudLine(runtime.now()));
		// WORLD_SESSIONS_PLAN WS4: the server's save.request (Minecraft saves the world) to every bound link
		if (hasLeg(MsgTypes.SAVE_REQUEST, Catalog.Leg.S2C)) {
			runtime.links.onServer(MsgTypes.SAVE_REQUEST, false, (link, message) -> runtime.saveAll(JsonFields.str(message.data(), "reason")));
		}
		bridge.addHudLine(() -> runtime.saveHudLine(runtime.now()));
		bridge.addDebugLine(runtime::debugSaves);
		bridge.addDebugLine(runtime::debugWorld);
		bridge.addDebugLine(runtime::debugGate);
	}

	public static @Nullable SessionRuntime get() {
		return instance;
	}

	/** WS2: the player's recorded save choices for the world they are in (the server's {@code session.bind.regions}). */
	public RecordedChoices recorded() {
		return this.recorded;
	}

	/** {@code link}'s pairing. */
	public LinkSession session(BridgeLink link) {
		LinkSession session = this.sessions.get(link);
		if (session == null) {
			throw new IllegalArgumentException("not one of the configured links");
		}
		return session;
	}

	/** The followed link's pairing (MG4: what one-link callers mean). */
	public LinkSession followedSession() {
		return this.session(this.links.followed());
	}

	public SaveRelay saves() {
		return this.followedSession().saves;
	}

	public PcRelay pc() {
		return this.followedSession().pc;
	}

	public SessionBinder binder() {
		return this.followedSession().binder;
	}

	/** The save pairing's gate (M9) of the followed link: whether a world's data may flow yet. */
	public SessionGate gate() {
		return this.followedSession().gate;
	}

	/** {@code link}'s gate. */
	public SessionGate gate(BridgeLink link) {
		return this.session(link).gate;
	}

	/**
	 * MG4: whether {@code link} binds the world the player is in: in a region world, when the world holds a region built
	 * from the link's game; in a legacy world (no regions), only the followed link, as before.
	 */
	private boolean bindsWorld(BridgeLink link, boolean followed) {
		if (link.wrongGame()) {
			return false;
		}
		if (ClientRegions.regionWorld()) {
			return ClientRegions.byGame(link.game()) != null;
		}
		return followed && ClientWorldIndex.current() != null;
	}

	private long now() {
		return this.clock.millis();
	}

	private void tick(Minecraft minecraft) {
		long now = this.now();
		boolean inWorld = minecraft.level != null && minecraft.player != null;
		String worldId = inWorld ? this.worldId(minecraft) : null;
		if (!inWorld) {
			this.inWorldSince = -1;
		} else if (this.inWorldSince < 0) {
			this.inWorldSince = now;
		}
		this.multiplayerView = inWorld && !minecraft.isLocalServer();
		BridgeLink followed = this.links.followed();
		for (LinkSession session : this.sessions.values()) {
			session.tick(minecraft, now, inWorld, worldId, session.link == followed);
		}
		if (inWorld) {
			this.askFirstJoin(minecraft);
		}
		this.tickQuit();
	}

	// ---- WS5: closing with the world ----

	private final SessionClose closing = new SessionClose();
	/** WS5: the games told session.close and not answered yet (WS7 stops the engines it started once they answer). */
	private final java.util.Set<BridgeLink> closingLinks = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
	private @Nullable Runnable afterSaves;
	private boolean closeAfterLeave;

	/** WS5: the bound games the quit asks for a save (pairing open, link up). */
	private List<SessionClose.Target> quitTargets() {
		List<SessionClose.Target> out = new ArrayList<>();
		for (LinkSession session : this.sessions.values()) {
			if (session.link.connected() && session.binder.bound() && session.gate.serverOpen()) {
				out.add(session.target());
			}
		}
		return out;
	}

	/**
	 * WS5: the pause menu's "Save and Quit to Title". True when the choreography took over (the leave runs once every
	 * bound game has saved, refused or run out of time); false when there is nothing to wait for (the vanilla leave
	 * goes on at once).
	 */
	public boolean quit() {
		return this.quit(() -> Leave.vanilla(Minecraft.getInstance()));
	}

	/** {@link #quit()} with the leave to run afterwards (the dev autopilot passes its own). */
	public boolean quit(Runnable leave) {
		if (this.closing.running()) {
			return true;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			return false;
		}
		this.closeAfterLeave = ClientRegions.record() != null;
		if (!this.closing.begin(this.quitTargets(), this.now(), false)) {
			return false;
		}
		this.afterSaves = leave;
		minecraft.setScreen(new net.minecraft.client.gui.screens.GenericMessageScreen(
				Component.translatable("kantobridge.quit.saving", String.join(", ", this.closing.names()))));
		LOGGER.info("quit: saving {} hidden game(s) first", this.closing.names().size());
		return true;
	}

	/**
	 * WS5: the window's close (Minecraft.stop, on the client thread): the same saves with five seconds, the links'
	 * messages drained here since the client loop no longer runs; then session.close to those that saved, without
	 * waiting for the answers. Minecraft's own stop follows (its integrated server saves the world).
	 */
	public void quitBlocking() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || this.closing.running()) {
			return;
		}
		this.closeAfterLeave = ClientRegions.record() != null;
		if (!this.closing.begin(this.quitTargets(), this.now(), true)) {
			return;
		}
		LOGGER.info("quit: the window closes; saving {} hidden game(s) first", this.closing.names().size());
		while (this.closing.tick(this.now()) == SessionClose.Step.WAIT) {
			this.links.drain(64);
			try {
				Thread.sleep(20);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		this.finishQuit();
	}

	private void tickQuit() {
		if (!this.closing.running()) {
			return;
		}
		if (this.closing.tick(this.now()) == SessionClose.Step.DISCONNECT) {
			Runnable leave = this.afterSaves;
			this.afterSaves = null;
			if (leave != null) {
				leave.run();
			}
			this.finishQuit();
		}
	}

	private void finishQuit() {
		if (this.closeAfterLeave) {
			this.closing.afterDisconnect();
		} else {
			// a legacy world: nothing continues the games at the next join there, so they keep running as saved (WS5)
			this.closing.abandon();
		}
		List<String> failed = this.closing.failed();
		if (!failed.isEmpty()) {
			this.saveNotice = new ConnectionStatus.Line("kantobridge.hud.quit.unsaved", List.of(String.join(", ", failed)), true);
			this.saveNoticeUntil = this.now() + 3 * SAVE_NOTICE_MS;
			LOGGER.warn("quit: {} hidden game(s) didn't save and were left as they are", failed.size());
		}
	}

	/** WS5: a game answered session.close (WS7 stops the engine it started). */
	void closed(BridgeLink link, boolean title, @Nullable Integer home) {
		this.closingLinks.remove(link);
		LOGGER.info("quit: a hidden game is back at its title ({}{})", title, home == null ? "" : ", offline slot " + home);
		for (java.util.function.Consumer<BridgeLink> listener : this.closedListeners) {
			listener.accept(link);
		}
	}

	private final List<java.util.function.Consumer<BridgeLink>> closedListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

	/** WS7: called when a link answers session.close (the launcher stops the engine it started). */
	public void onClosed(java.util.function.Consumer<BridgeLink> listener) {
		this.closedListeners.add(listener);
	}

	/** WS5/WS7: the links whose session.close isn't answered yet. */
	public boolean closing(BridgeLink link) {
		return this.closingLinks.contains(link);
	}

	/** WS4: a refused save's HUD line stays this long. */
	static final long SAVE_NOTICE_MS = 8_000;
	private ConnectionStatus.@Nullable Line saveNotice;
	private long saveNoticeUntil = -1;

	/**
	 * WS4 (D5): asks every bound link's hidden game for a save of its own ({@code world}: Minecraft saved the world;
	 * {@code quit}: WS5's leaving). Held (parked) games are asked too. Returns the links asked.
	 */
	public int saveAll(@Nullable String reason) {
		if (!"world".equals(reason) && !"quit".equals(reason)) {
			return 0;
		}
		long now = this.now();
		int asked = 0;
		for (LinkSession session : this.sessions.values()) {
			if (session.link.connected() && session.binder.bound() && session.gate.serverOpen()
					&& session.saves.request(reason, now, data -> session.link.client().sendTracked(MsgTypes.SAVE_REQUEST, data))) {
				asked++;
			}
		}
		if (asked > 0) {
			LOGGER.info("save: asked {} hidden game(s) for a {} save", asked, reason);
		}
		return asked;
	}

	/** WS4: "Blue didn't save: in a battle", for a while after a refusal. */
	private ConnectionStatus.@Nullable Line saveHudLine(long now) {
		return now < this.saveNoticeUntil ? this.saveNotice : null;
	}

	void saveRefused(BridgeLink link, @Nullable String reason) {
		if ("not_ingame".equals(reason)) {
			return; // a game at its title has nothing to save
		}
		String why = reason == null ? "failed" : reason;
		String game = link.game() == null ? "?" : I18n.get("kantobridge.game." + link.game());
		this.saveNotice = new ConnectionStatus.Line("kantobridge.hud.save.refused." + (SAVE_REFUSALS.contains(why) ? why : "failed"),
				List.of(game), true);
		this.saveNoticeUntil = this.now() + SAVE_NOTICE_MS;
		LOGGER.info("save: a hidden game didn't save ({})", why);
	}

	static final java.util.Set<String> SAVE_REFUSALS = java.util.Set.of("not_idle", "vetoed", "failed", "busy");

	/** WS3: the server's presence is waited for at most this long after entering a world (an older server sends none). */
	static final long PRESENCE_WAIT_MS = 3000;

	/** WS3: one link's join decision. */
	JoinDecision.Result decide(LinkSession session, long now) {
		BridgeLink link = session.link;
		String region = link.region();
		BridgeLink.Status status = link.lastStatus();
		boolean presenceKnown = this.links.presenceHeard() || (this.inWorldSince >= 0 && now - this.inWorldSince > PRESENCE_WAIT_MS);
		return JoinDecision.decide(new JoinDecision.Input(ClientRegions.record(), region, link.game(), this.links.serverFollowed(),
				presenceKnown, this.recorded.forRegion(region), session.slotLost, status != null, status != null && status.save() != null,
				session.answer));
	}

	/** WS3 (D13): the first-join screen for every link whose decision asks, once, over the world. */
	private void askFirstJoin(Minecraft minecraft) {
		if (minecraft.screen != null) {
			return;
		}
		List<FirstJoinScreen.Entry> entries = new ArrayList<>();
		Map<String, LinkSession> byKey = new LinkedHashMap<>();
		for (LinkSession session : this.sessions.values()) {
			if (session.decision instanceof JoinDecision.Ask && session.answer == null) {
				BridgeLink.Status status = session.link.lastStatus();
				BridgeLink.Status.SaveSummary save = status == null ? null : status.save();
				String game = session.link.game();
				String key = session.link.port() + ":" + game;
				byKey.put(key, session);
				entries.add(new FirstJoinScreen.Entry(key, Component.translatable("kantobridge.game." + game), save == null ? null : save.name(),
						save == null ? 0 : save.badges()));
			}
		}
		if (entries.isEmpty()) {
			return;
		}
		LOGGER.info("session: first join here, asking for {} game(s)", entries.size());
		minecraft.setScreen(new FirstJoinScreen(entries, this.multiplayerView, answers -> answers.forEach((key, answer) -> {
			LinkSession session = byKey.get(key);
			if (session != null) {
				session.answer = answer;
				session.slotLost = false;
			}
		})));
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

	/** A {@code pc.open}'s or {@code pc.closed}'s id, or null when it has no usable one. */
	private static @Nullable String pcId(Envelope message) {
		String pc = JsonFields.str(message.data(), "pc");
		return pc != null && Ids.ID.matcher(pc).matches() ? pc : null;
	}

	private static boolean hasLeg(String type, Catalog.Leg leg) {
		Catalog.Entry entry = Catalog.bundled().get(type);
		return entry != null && entry.legs().contains(leg);
	}

	private String debugSaves() {
		LinkSession session = this.followedSession();
		SaveRelay.Stats saves = session.saves.stats();
		PcRelay.Stats pc = session.pc.stats();
		return I18n.get("kantobridge.debug.saves", saves.relayed, session.saves.pending(), saves.answered, saves.failed, saves.answeredHere,
				saves.repeats, session.pc.state().name().toLowerCase(Locale.ROOT), pc.opened, pc.closed, pc.answeredHere, pc.unlinks);
	}

	private String debugGate() {
		SessionGate gate = this.gate();
		SessionGate.Stats stats = gate.stats();
		String waiting = gate.waiting();
		return I18n.get("kantobridge.debug.gate", gate.state().name().toLowerCase(Locale.ROOT), gate.heldCount(), stats.replayed,
				stats.dropped, stats.opened, stats.staleOpenings, stats.refusals, waiting == null ? "-" : waiting);
	}

	private String debugWorld() {
		SessionBinder binder = this.binder();
		String world = binder.world();
		Integer slot = binder.slot();
		return I18n.get("kantobridge.debug.world", world == null ? "-" : SessionService.shortId(world),
				I18n.get(binder.multiplayer() ? "kantobridge.debug.world.online" : "kantobridge.debug.world.offline"),
				binder.bound() ? I18n.get("kantobridge.debug.yes") : I18n.get("kantobridge.debug.no"), binder.stats().binds,
				slot == null ? "-" : slot.toString());
	}
}
