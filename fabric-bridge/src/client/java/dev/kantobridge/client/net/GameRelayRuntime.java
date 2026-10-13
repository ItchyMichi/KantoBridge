package dev.kantobridge.client.net;

import com.google.gson.JsonObject;
import dev.kantobridge.client.battle.ClientBattles;
import dev.kantobridge.client.bridge.BridgeClock;
import dev.kantobridge.client.bridge.BridgeLink;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.bridge.Links;
import dev.kantobridge.client.ui.UiRuntime;
import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minecraft glue for {@link GameRelay} (battles and Pokémon between gen1-bridge and the server) and the
 * {@link ActivityTracker}: gen1-bridge's messages come through each link's {@code Dispatcher}, the server's through
 * {@link ClientRelay}, both on the client thread. Logs carry codes and counts only.
 *
 * <p>M9: the save pairing's {@link SessionGate} (in {@link SessionRuntime}) is asked first: gen1-bridge's requests wait in
 * it while a world's pairing isn't answered, and the ports send nothing to the server, or to gen1-bridge, meanwhile.
 *
 * <p>MG4 (several games): one relay per {@link BridgeLink}, since battles, party requests and the bag are each hidden
 * game's; the server's messages are routed by their {@code region} to that region's link ({@code party.sync} and
 * {@code dex.sync} without one fan out to every link: Cobblemon's state is every hidden game's). The activity is the
 * body's: one, from the followed link.
 */
public final class GameRelayRuntime {
	private static final Logger LOGGER = LoggerFactory.getLogger("kantobridge");
	private static @Nullable GameRelayRuntime instance;

	private final BridgeRuntime bridge;
	private final Links links;
	private final BridgeClock clock = BridgeClock.system();
	private final Map<BridgeLink, GameRelay> relays = new LinkedHashMap<>();
	private final ActivityTracker activity = new ActivityTracker();

	private GameRelayRuntime(BridgeRuntime bridge) {
		this.bridge = bridge;
		this.links = bridge.links();
		boolean dexUp = dexUp();
		for (BridgeLink link : this.links.all()) {
			this.relays.put(link, new GameRelay(new GameRelay.Port() {
				@Override
				public boolean toBridge(String type, JsonObject data) {
					SessionGate gate = gate(link);
					return (gate == null || gate.bridgeOpen()) && link.client().send(type, data);
				}

				@Override
				public boolean toServer(String type, JsonObject data) {
					SessionGate gate = gate(link);
					return (gate == null || gate.serverOpen()) && link.toServer(type, data, null);
				}

				@Override
				public void warn(String code, long count) {
					LOGGER.warn("relay: {} ({} so far)", code, count);
				}
			}, dexUp));
		}
	}

	/** Call once from the client entrypoint, after {@link BridgeRuntime#init} and the UI runtime. */
	public static void init(BridgeRuntime bridge) {
		if (instance != null) {
			return;
		}
		GameRelayRuntime runtime = new GameRelayRuntime(bridge);
		instance = runtime;
		for (BridgeLink link : runtime.links.all()) {
			link.dispatcher().on(MsgTypes.BATTLE_START, message -> runtime.fromBridge(link, message));
			for (String type : GameRelay.PARTY_REQUESTS) {
				link.dispatcher().on(type, message -> runtime.fromBridge(link, message));
			}
			link.dispatcher().on(MsgTypes.BAG_SYNC, message -> runtime.fromBridge(link, message));
			// MG3: the hidden game's story progress goes up for the server's per-region progress record (MULTI_GAME_PLAN §4.7).
			link.dispatcher().on(MsgTypes.GAME_STATUS, message -> runtime.fromBridge(link, message));
			link.dispatcher().on(MsgTypes.SNAPSHOT, message -> runtime.relay(link).bridgeSnapshot(message.data(), runtime.now()));
		}
		runtime.links.onServer(MsgTypes.BATTLE_RESULT, false, (link, message) -> runtime.relay(link).fromServer(message, runtime.now()));
		for (String type : GameRelay.SERVER_SYNCS) {
			runtime.links.onServer(type, true, (link, message) -> runtime.relay(link).fromServer(message, runtime.now()));
		}
		runtime.links.onServer(MsgTypes.BAG_USE, false, (link, message) -> runtime.relay(link).fromServer(message, runtime.now()));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			runtime.activity.reset();
			for (GameRelay relay : runtime.relays.values()) {
				relay.serverJoined(runtime.now());
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			runtime.activity.reset();
			for (GameRelay relay : runtime.relays.values()) {
				relay.serverLeft(runtime.now());
			}
		});
		// After BridgeRuntime's tick, which drains the bridge's messages.
		ClientTickEvents.END_CLIENT_TICK.register(runtime::tick);
		bridge.addDebugLine(runtime::debugLine);
	}

	/** The save pairing's gate of {@code link}, once the session glue exists (it is set up after this runtime). */
	private static @Nullable SessionGate gate(BridgeLink link) {
		SessionRuntime session = SessionRuntime.get();
		return session == null ? null : session.gate(link);
	}

	/** One of gen1-bridge's requests for the server: to its link's relay, unless the gate holds it until the world is paired. */
	private void fromBridge(BridgeLink link, Envelope message) {
		SessionGate gate = gate(link);
		if (gate != null && gate.admit(message) != SessionGate.Admit.PASS) {
			return;
		}
		this.relay(link).fromBridge(message, this.now());
	}

	/** The hidden game's Pokédex goes up only when the protocol gives {@code dex.sync} a client-to-server leg. */
	private static boolean dexUp() {
		Catalog.Entry entry = Catalog.bundled().get(MsgTypes.DEX_SYNC);
		return entry != null && entry.legs().contains(Catalog.Leg.C2S);
	}

	public static @Nullable GameRelayRuntime get() {
		return instance;
	}

	/** The followed link's relay (what one-link callers mean). */
	public GameRelay relay() {
		return this.relay(this.links.followed());
	}

	/** {@code link}'s relay. */
	public GameRelay relay(BridgeLink link) {
		GameRelay relay = this.relays.get(link);
		if (relay == null) {
			throw new IllegalArgumentException("not one of the configured links");
		}
		return relay;
	}

	private long now() {
		return this.clock.millis();
	}

	private void tick(Minecraft minecraft) {
		long now = this.now();
		for (GameRelay relay : this.relays.values()) {
			relay.tick(now);
		}
		if (minecraft.player == null) {
			return;
		}
		UiRuntime ui = UiRuntime.get();
		// A special battle running natively (P9) is a battle too, not a dialogue.
		boolean battle = ClientBattles.active() || (ui != null && ui.nativeBattleActive());
		// M10: while the pause overlay is up, other players see this player's game is paused.
		String current = ActivityTracker.of(battle, this.bridge.pauseShown(), ui != null && ui.open(), this.bridge.state().mode());
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
		GameRelay relay = this.relay();
		GameRelay.Stats stats = relay.stats();
		String last = this.activity.last();
		return I18n.get("kantobridge.debug.relay", stats.battlesRelayed, relay.pendingBattles(), stats.resultsForwarded, stats.resultsResent,
				stats.abortedHere, stats.partyRelayed, relay.queued(), stats.syncsForwarded, stats.bagSyncsRelayed, stats.bagUsesForwarded,
				relay.queuedUses(), last == null ? "-" : last);
	}
}
