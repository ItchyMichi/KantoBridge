package dev.kantobridge.battle;

import net.minecraft.world.entity.Entity;

/**
 * Battle proxies are temporary Cobblemon {@code NPCEntity}s that stand in for a hidden-game trainer during a
 * Cobblemon battle (docs/PLAN.md §4.4). No player ever sees one: the fighting player's client draws the trainer
 * puppet instead, so the NPC would only be drawn twice.
 */
public final class BattleProxies {
	/** Entity tag on every proxy; also lets a restart discard proxies left over after a crash. */
	public static final String TAG = "kantobridge_battle_proxy";

	private BattleProxies() {
	}

	public static boolean isProxy(Entity entity) {
		return entity.getTags().contains(TAG);
	}
}
