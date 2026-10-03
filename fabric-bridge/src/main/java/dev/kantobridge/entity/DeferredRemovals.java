package dev.kantobridge.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.world.entity.Entity;

/**
 * Entity removals that can't happen where they are asked for. The server's entity tracking ({@code ChunkMap}) calls
 * {@code Entity.stopSeenByPlayer} from inside its loops over every tracked entity: while a leaving (respawning,
 * dimension-changing) player is untracked, and while players move out of entities' range each tick. Removing an
 * entity there takes it out of the very map being iterated, and fastutil's iterator then skips entries or crashes the
 * server ({@code NullPointerException} in {@code Int2ObjectOpenHashMap$MapIterator}). Such removals are queued here
 * and done at the end of the server tick, outside every loop. Server thread only.
 */
public final class DeferredRemovals {
	private static final List<Entity> PENDING = new ArrayList<>();
	private static final AtomicLong DONE = new AtomicLong();

	private DeferredRemovals() {
	}

	/** Call once from common init. */
	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> drain());
		// A stopping server never ticks again; what it didn't discard isn't saved either (see PokemonEntityMixin).
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> PENDING.clear());
	}

	/** Discards {@code entity} at the end of this server tick (once, however often it is asked). */
	public static void discardLater(Entity entity) {
		for (Entity pending : PENDING) {
			if (pending == entity) {
				return;
			}
		}
		PENDING.add(entity);
	}

	static void drain() {
		if (PENDING.isEmpty()) {
			return;
		}
		List<Entity> due = new ArrayList<>(PENDING);
		PENDING.clear();
		for (Entity entity : due) {
			if (!entity.isRemoved()) {
				entity.discard();
				DONE.incrementAndGet();
			}
		}
	}

	/** Removals waiting for the end of the tick. */
	public static int pending() {
		return PENDING.size();
	}

	/** True while {@code entity} waits to be discarded at the end of the tick. */
	public static boolean isPending(Entity entity) {
		for (Entity pending : PENDING) {
			if (pending == entity) {
				return true;
			}
		}
		return false;
	}

	/** Removals done so far. */
	public static long done() {
		return DONE.get();
	}
}
