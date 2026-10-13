package dev.kantobridge.client.bridge;

import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.Envelope;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The body's dispatcher (MG4, docs/specs/MULTI_GAME_PLAN.md §4.5): the player has one body, so the runtimes that move,
 * draw and answer for it (movement, screens, puppets, acts, zones) subscribe here once and hear only the <b>followed</b>
 * link. A {@link Dispatcher} has no way to unsubscribe, so this one registers a forwarding handler on every link's own
 * dispatcher and lets a message through only when its link is the followed one; a swap ({@link Links#follow}) tells the
 * state handlers {@code DISCONNECTED("swap")} and then the new link's state, which is how the screens, puppets and acts
 * already clear themselves when a link drops.
 *
 * <p>It is drained through the links' own dispatchers ({@link Links#drain}); its own {@link #drain} has nothing to do.
 */
public final class FollowedDispatcher extends Dispatcher {
	private static final Logger LOGGER = LoggerFactory.getLogger("kantobridge");
	/** The detail of the state change a swap announces to the body. */
	public static final String SWAP = "swap";

	private final Catalog catalog;
	private final Map<String, List<Consumer<Envelope>>> handlers = new ConcurrentHashMap<>();
	private final List<BiConsumer<ConnectionState, @Nullable String>> stateHandlers = new CopyOnWriteArrayList<>();
	private final List<BridgeLink> links = new CopyOnWriteArrayList<>();
	private volatile @Nullable BridgeLink followed;

	public FollowedDispatcher(Catalog catalog) {
		super(catalog);
		this.catalog = catalog;
	}

	/** A link whose traffic may be followed; every type subscribed so far (and later) gets a forwarder on it. */
	void attach(BridgeLink link) {
		this.links.add(link);
		for (String type : this.handlers.keySet()) {
			this.forward(link, type);
		}
		link.dispatcher().onState((state, detail) -> {
			if (link == this.followed) {
				this.emitState(state, detail);
			}
		});
	}

	/** The link the body hears now. */
	void setFollowed(@Nullable BridgeLink link) {
		this.followed = link;
	}

	public @Nullable BridgeLink followed() {
		return this.followed;
	}

	/** Adds a handler for a gen1-bridge → client message type, heard from the followed link only. */
	@Override
	public void on(String type, Consumer<Envelope> handler) {
		Catalog.Entry entry = this.catalog.get(type);
		if (entry == null || !entry.legs().contains(Catalog.Leg.G2M)) {
			throw new IllegalArgumentException("not a gen1-bridge-to-client message type: " + type);
		}
		List<Consumer<Envelope>> list = this.handlers.get(type);
		if (list == null) {
			list = new CopyOnWriteArrayList<>();
			this.handlers.put(type, list);
			for (BridgeLink link : this.links) {
				this.forward(link, type);
			}
		}
		list.add(handler);
	}

	/** Adds a handler for the followed link's state changes (and the swap's). */
	@Override
	public void onState(BiConsumer<ConnectionState, @Nullable String> handler) {
		this.stateHandlers.add(handler);
	}

	private void forward(BridgeLink link, String type) {
		link.dispatcher().on(type, message -> {
			if (link != this.followed) {
				return;
			}
			List<Consumer<Envelope>> list = this.handlers.get(type);
			if (list != null) {
				for (Consumer<Envelope> handler : list) {
					this.run(type, () -> handler.accept(message));
				}
			}
		});
	}

	/**
	 * The followed link changed (client thread): the body hears {@code DISCONNECTED(swap)}, so every runtime clears what it
	 * showed for the old link, then the new link's state.
	 */
	void swap(BridgeLink next) {
		this.followed = next;
		this.emitState(ConnectionState.DISCONNECTED, SWAP);
		this.emitState(next.client().state(), next.client().detail());
	}

	private void emitState(ConnectionState state, @Nullable String detail) {
		for (BiConsumer<ConnectionState, @Nullable String> handler : this.stateHandlers) {
			this.run("state", () -> handler.accept(state, detail));
		}
	}

	/** Nothing of its own to drain: the links' dispatchers forward into it. */
	@Override
	public int drain(int max) {
		return 0;
	}

	/** Events waiting on every link's dispatcher. */
	@Override
	public int pending() {
		int total = 0;
		for (BridgeLink link : this.links) {
			total += link.dispatcher().pending();
		}
		return total;
	}

	/** Never posted to directly; a link's dispatcher is the listener. */
	@Override
	public void stateChanged(ConnectionState state, @Nullable String detail) {
		throw new UnsupportedOperationException("the body dispatcher only forwards its links' events");
	}

	@Override
	public void message(Envelope message) {
		throw new UnsupportedOperationException("the body dispatcher only forwards its links' events");
	}

	private void run(String what, Runnable handler) {
		try {
			handler.run();
		} catch (RuntimeException e) {
			// One bad handler must not stall the others. Only the class is logged: exception text could quote data.
			LOGGER.warn("bridge: body handler for {} failed with {}", what, e.getClass().getName());
		}
	}
}
