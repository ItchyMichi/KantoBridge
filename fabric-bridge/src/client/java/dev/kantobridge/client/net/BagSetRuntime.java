package dev.kantobridge.client.net;

import com.google.gson.JsonObject;
import dev.kantobridge.client.bridge.BridgeLink;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.bridge.ConnectionState;
import dev.kantobridge.client.bridge.Links;
import dev.kantobridge.proto.MsgTypes;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.Nullable;

/**
 * Minecraft glue for {@link BagSetRelay} (MG6): one relay per {@link BridgeLink}. The server's {@code bag.set} is routed
 * by its {@code region} to that region's link (else the followed one) and goes down once the link's save pairing lets
 * the world's data flow; the link's {@code act.result} of kind {@code bag.set} goes back up tagged with the link's
 * region. The F7 line shows the followed link's wallet and what its last projection left in the trunk.
 */
public final class BagSetRuntime {
	private static @Nullable BagSetRuntime instance;

	private final Links links;
	private final Map<BridgeLink, BagSetRelay> relays = new LinkedHashMap<>();

	private BagSetRuntime(BridgeRuntime bridge) {
		this.links = bridge.links();
		for (BridgeLink link : this.links.all()) {
			this.relays.put(link, new BagSetRelay(new BagSetRelay.Port() {
				@Override
				public long toBridge(String type, JsonObject data) {
					SessionGate gate = gate(link);
					return gate != null && !gate.bridgeOpen() ? -1 : link.client().sendTracked(type, data);
				}

				@Override
				public boolean toServer(String type, JsonObject data, @Nullable Long re) {
					SessionGate gate = gate(link);
					return (gate == null || gate.serverOpen()) && link.toServer(type, data, re);
				}
			}));
		}
	}

	/** Call once from the client entrypoint, after {@link GameRelayRuntime#init}. */
	public static void init(BridgeRuntime bridge) {
		if (instance != null) {
			return;
		}
		BagSetRuntime runtime = new BagSetRuntime(bridge);
		instance = runtime;
		for (BridgeLink link : runtime.links.all()) {
			BagSetRelay relay = runtime.relay(link);
			link.dispatcher().on(MsgTypes.ACT_RESULT, relay::onResult);
			link.dispatcher().on(MsgTypes.BAG_SYNC, message -> relay.onBagSync(message.data()));
			link.dispatcher().onState((state, detail) -> {
				if (state != ConnectionState.CONNECTED) {
					relay.disconnected();
				}
			});
		}
		runtime.links.onServer(MsgTypes.BAG_SET, false, (link, message) -> runtime.relay(link).fromServer(message));
		bridge.addDebugLine(runtime::debugLine);
	}

	/** The save pairing's gate of {@code link}, once the session glue exists (it is set up after this runtime). */
	private static @Nullable SessionGate gate(BridgeLink link) {
		SessionRuntime session = SessionRuntime.get();
		return session == null ? null : session.gate(link);
	}

	public static @Nullable BagSetRuntime get() {
		return instance;
	}

	/** {@code link}'s relay. */
	public BagSetRelay relay(BridgeLink link) {
		BagSetRelay relay = this.relays.get(link);
		if (relay == null) {
			throw new IllegalArgumentException("not one of the configured links");
		}
		return relay;
	}

	/** The followed link's relay. */
	public BagSetRelay relay() {
		return this.relay(this.links.followed());
	}

	private String debugLine() {
		BagSetRelay relay = this.relay();
		BagSetRelay.Stats stats = relay.stats();
		String reason = relay.lastReason();
		return I18n.get("kantobridge.debug.bag", relay.money() < 0 ? "-" : Long.toString(relay.money()),
				relay.left() < 0 ? "-" : Long.toString(relay.left()), stats.projections, stats.resultsForwarded, relay.pending(), stats.dropped,
				reason == null ? "-" : reason);
	}
}
