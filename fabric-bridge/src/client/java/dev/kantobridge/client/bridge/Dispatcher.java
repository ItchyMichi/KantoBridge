package dev.kantobridge.client.bridge;

import dev.kantobridge.proto.Catalog;
import dev.kantobridge.proto.Envelope;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands the socket thread's events to the client thread (PLAN §2.3): {@link BridgeClient} posts from its thread,
 * and the game drains the queue once per client tick, where every handler runs. Order is kept: a state change and
 * the messages around it arrive in the order they happened.
 *
 * <p>MG4 (several games): every hidden game's link has its own dispatcher; the body's runtimes subscribe to the
 * {@link FollowedDispatcher}, which forwards only the followed link's traffic.
 */
public class Dispatcher implements BridgeClient.Listener {
	private static final Logger LOGGER = LoggerFactory.getLogger("kantobridge");

	private record StateChange(ConnectionState state, @Nullable String detail) {
	}

	private final Catalog catalog;
	private final ConcurrentLinkedQueue<Object> queue = new ConcurrentLinkedQueue<>();
	private final Map<String, List<Consumer<Envelope>>> handlers = new ConcurrentHashMap<>();
	private final List<BiConsumer<ConnectionState, @Nullable String>> stateHandlers = new CopyOnWriteArrayList<>();

	public Dispatcher(Catalog catalog) {
		this.catalog = catalog;
	}

	/** Adds a handler for a gen1-bridge → client message type. */
	public void on(String type, Consumer<Envelope> handler) {
		Catalog.Entry entry = this.catalog.get(type);
		if (entry == null || !entry.legs().contains(Catalog.Leg.G2M)) {
			throw new IllegalArgumentException("not a gen1-bridge-to-client message type: " + type);
		}
		this.handlers.computeIfAbsent(type, key -> new CopyOnWriteArrayList<>()).add(handler);
	}

	/** Adds a handler for connection state changes. */
	public void onState(BiConsumer<ConnectionState, @Nullable String> handler) {
		this.stateHandlers.add(handler);
	}

	@Override
	public void stateChanged(ConnectionState state, @Nullable String detail) {
		this.queue.add(new StateChange(state, detail));
	}

	@Override
	public void message(Envelope message) {
		this.queue.add(message);
	}

	/** Runs up to {@code max} queued events on the calling (client) thread; returns how many ran. */
	public int drain(int max) {
		int count = 0;
		Object event;
		while (count < max && (event = this.queue.poll()) != null) {
			count++;
			if (event instanceof Envelope message) {
				List<Consumer<Envelope>> list = this.handlers.get(message.type());
				if (list != null) {
					for (Consumer<Envelope> handler : list) {
						this.run(message.type(), () -> handler.accept(message));
					}
				}
			} else if (event instanceof StateChange change) {
				for (BiConsumer<ConnectionState, @Nullable String> handler : this.stateHandlers) {
					this.run("state", () -> handler.accept(change.state(), change.detail()));
				}
			}
		}
		return count;
	}

	/** Events waiting for the client thread. */
	public int pending() {
		return this.queue.size();
	}

	private void run(String what, Runnable handler) {
		try {
			handler.run();
		} catch (RuntimeException e) {
			// One bad handler must not stall the queue. Only the class is logged: exception text could quote data.
			LOGGER.warn("bridge: handler for {} failed with {}", what, e.getClass().getName());
		}
	}
}
