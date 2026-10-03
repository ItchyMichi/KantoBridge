package dev.kantobridge.battle;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.Entity;

/**
 * Battle proxies are temporary Cobblemon {@code NPCEntity}s that stand in for a hidden-game trainer during a
 * Cobblemon battle (docs/PLAN.md §4.4). No player ever sees one: the fighting player's client draws the trainer
 * puppet instead, so the NPC would only be drawn twice.
 *
 * <p>Every proxy carries {@link #TAG}. {@link BattleService} registers the ones it manages as live before they enter the
 * level; a tagged entity that loads without being live was left in a save by a crash and is discarded.
 */
public final class BattleProxies {
	/** Entity tag on every proxy; also lets a restart discard proxies left over after a crash. */
	public static final String TAG = "kantobridge_battle_proxy";

	private static final Set<UUID> LIVE = ConcurrentHashMap.newKeySet();

	private BattleProxies() {
	}

	public static boolean isProxy(Entity entity) {
		return entity.getTags().contains(TAG);
	}

	/** Marks a proxy as managed (call before adding it to the level). */
	static void live(Entity proxy) {
		LIVE.add(proxy.getUUID());
	}

	/** The proxy is gone or no longer managed. */
	static void dead(Entity proxy) {
		LIVE.remove(proxy.getUUID());
	}

	/** True for a proxy that loaded without being managed: a leftover from a crash. */
	static boolean leftover(Entity entity) {
		return isProxy(entity) && !LIVE.contains(entity.getUUID());
	}

	static void clear() {
		LIVE.clear();
	}
}
