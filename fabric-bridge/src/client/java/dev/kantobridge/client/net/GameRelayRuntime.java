package dev.kantobridge.client.net;

import com.google.gson.JsonObject;
import dev.kantobridge.client.battle.ClientBattles;
import dev.kantobridge.client.bridge.BridgeClock;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.bridge.Dispatcher;
import dev.kantobridge.client.ui.UiRuntime;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minecraft glue for {@link GameRelay} (battles and Pokémon between gen1-bridge and the server) and the
 * {@link ActivityTracker}: gen1-bridge's messages come through the {@link Dispatcher}, the server's through
 * {@link ClientRelay}, both on the client thread. Logs carry codes and counts only.
 *
 * <p>M9: the save pairing's {@link SessionGate} (in {@link SessionRuntime}) is asked first: gen1-bridge's requests wait in
 * it while a world's pairing isn't answered, and the ports send nothing to the server, or to gen1-bridge, meanwhile.
 */
public final class GameRelayRuntime {
	private static final Logger LOGGER = LoggerFactory.getLogger("kantobridge");
	private static @Nullable GameRelayRuntime instance;

	private final BridgeRuntime bridge;
	private final BridgeClock clock = BridgeClock.system();
	private final GameRelay relay;
	private final ActivityTracker activity = new ActivityTracker();

	private GameRelayRuntime(BridgeRuntime bridge) {
		this.bridge = bridge;
		this.relay = new GameRelay(new GameRelay.Port() {
			@Override
			public boolean toBridge(String type, JsonObject data) {
				SessionGate gate = gate();
				return (gate == null || gate.bridgeOpen()) && bridge.client().send(type, data);
			}

			@Override
			public boolean toServer(String type, JsonObject data) {
				SessionGate gate = gate();
				return (gate == null || gate.serverOpen()) && ClientRelay.get().send(type, data, null);
			}

			@Override
			public void warn(String code, long count) {
				LOGGER.warn("relay: {} ({} so far)", code, count);
			}
		}, dexUp());
	}

	/** Call once from the client entrypoint, after {@link BridgeRuntime#init} and the UI runtime. */
	public static void init(BridgeRuntime bridge) {
		if (instance != null) {
			return;
		}
		GameRelayRuntime runtime = new GameRelayRuntime(bridge);
		instance = runtime;
		Dispatcher dispatcher = bridge.dispatcher();
		dispatcher.on(MsgTypes.BATTLE_START, runtime::fromBridge);
		for (String type : GameRelay.PARTY_REQUESTS) {
			dispatcher.on(type, runtime::fromBridge);
		}
		dispatcher.on(MsgTypes.BAG_SYNC, runtime::fromBridge);
		dispatcher.on(MsgTypes.SNAPSHOT, message -> runtime.relay.bridgeSnapshot(message.data(), runtime.now()));
		ClientRelay server = ClientRelay.get();
		server.register(MsgTypes.BATTLE_RESULT, message -> runtime.relay.fromServer(message, runtime.now()));
		for (String type : GameRelay.SERVER_SYNCS) {
			server.register(type, message -> runtime.relay.fromServer(message, runtime.now()));
		}
		server.register(MsgTypes.BAG_USE, message -> runtime.relay.fromServer(message, runtime.now()));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			runtime.activity.reset();
			runtime.relay.serverJoined(runtime.now());
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			runtime.activity.reset();
			runtime.relay.serverLeft(runtime.now());
		});
		// After BridgeRuntime's tick, which drains the bridge's messages.
		ClientTickEvents.END_CLIENT_TICK.register(runtime::tick);
		bridge.addDebugLine(runtime::debugLine);
	}

	/** The save pairing's gate, once the session glue exists (it is set up after this runtime). */
	private static @Nullable SessionGate gate() {
		SessionRuntime session = SessionRuntime.get();
		return session == null ? null : session.gate();
	}

	/** One of gen1-bridge's requests for the server: to the relay, unless the gate holds it until the world is paired. */
	private void fromBridge(Envelope message) {
		SessionGate gate = gate();
		if (gate != null && gate.admit(message) != SessionGate.Admit.PASS) {
			return;
		}
		this.relay.fromBridge(message, this.now());
	}

	/** The hidden game's Pokédex goes up only when the protocol gives {@code dex.sync} a client-to-server leg. */
	private static boolean dexUp() {
		Catalog.Entry entry = Catalog.bundled().get(MsgTypes.DEX_SYNC);
		return entry != null && entry.legs().contains(Catalog.Leg.C2S);
	}

	public static @Nullable GameRelayRuntime get() {
		return instance;
	}

	public GameRelay relay() {
		return this.relay;
	}

	private long now() {
		return this.clock.millis();
	}

	private void tick(Minecraft minecraft) {
		this.relay.tick(this.now());
		if (minecraft.player == null) {
			return;
		}
		UiRuntime ui = UiRuntime.get();
		// A special battle running natively (P9) is a battle too, not a dialogue.
		boolean battle = ClientBattles.active() || (ui != null && ui.nativeBattleActive());
		String current = ActivityTracker.of(battle, ui != null && ui.open(), this.bridge.state().mode());
		String next = this.activity.next(current);
		if (next != null) {
			JsonObject data = new JsonObject();
			data.addProperty("activity", next);
			if (ClientRelay.get().send(MsgTypes.ACTIVITY, data, null)) {
				this.activity.sent(next);
			}
		}
	}

	private String debugLine() {
		GameRelay.Stats stats = this.relay.stats();
		String last = this.activity.last();
		return I18n.get("kantobridge.debug.relay", stats.battlesRelayed, this.relay.pendingBattles(), stats.resultsForwarded, stats.resultsResent,
				stats.abortedHere, stats.partyRelayed, this.relay.queued(), stats.syncsForwarded, stats.bagSyncsRelayed, stats.bagUsesForwarded,
				this.relay.queuedUses(), last == null ? "-" : last);
	}
}
