package dev.kantobridge.client.net;

import com.cobblemon.mod.common.client.gui.pc.PCGUI;
import com.cobblemon.mod.common.net.messages.server.storage.pc.UnlinkPlayerFromPCPacket;
import com.google.gson.JsonObject;
import dev.kantobridge.client.bridge.BridgeClock;
import dev.kantobridge.client.bridge.BridgeLink;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.bridge.ConnectionState;
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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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

	/** One link's save pairing (MG4): its gate, its save and PC relays and its binder. */
	public final class LinkSession {
		final BridgeLink link;
		final SessionGate gate;
		final SaveRelay saves;
		final PcRelay pc;
		final SessionBinder binder;

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
			dispatcher.on(MsgTypes.SESSION_WAITING, message -> this.gate.waiting(JsonFields.str(message.data(), "reason"), message.re(),
					SessionRuntime.this.now()));
			dispatcher.on(MsgTypes.ERROR, message -> this.gate.error(JsonFields.str(message.data(), "code"), message.re(),
					SessionRuntime.this.now()));
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
			runtime.links.onServer(MsgTypes.SESSION_BIND, false, (link, message) -> runtime.serverWorld = JsonFields.str(message.data(), "world"));
		}
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> runtime.serverWorld = null);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			long now = runtime.now();
			for (LinkSession session : runtime.sessions.values()) {
				session.saves.serverLeft(now);
				session.pc.serverLeft(now);
				session.binder.world(null, false);
			}
			runtime.serverWorld = null;
		});
		// After BridgeRuntime's tick, which drains the bridge's messages.
		ClientTickEvents.END_CLIENT_TICK.register(runtime::tick);
		// Cobblemon's PC screen holds the player by itself; this covers the moments before it opens and after it closes.
		// MG4: the body's one hold follows the followed link's PC.
		InputLock.holdWhile(() -> runtime.pc().pending());
		UiRuntime.suspendWhile(() -> runtime.pc().pending());
		bridge.addHudLine(() -> runtime.gate().hudLine(runtime.now()));
		bridge.addDebugLine(runtime::debugSaves);
		bridge.addDebugLine(runtime::debugWorld);
		bridge.addDebugLine(runtime::debugGate);
	}

	public static @Nullable SessionRuntime get() {
		return instance;
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
		BridgeLink followed = this.links.followed();
		for (LinkSession session : this.sessions.values()) {
			session.tick(minecraft, now, inWorld, worldId, session.link == followed);
		}
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
