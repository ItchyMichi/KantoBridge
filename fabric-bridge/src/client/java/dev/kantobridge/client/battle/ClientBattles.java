package dev.kantobridge.client.battle;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.battle.ClientBattle;

/**
 * Whether this player is in a Cobblemon battle, as Cobblemon's client knows it: from its battle-start packet until its
 * battle-end packet, which the server sends before the {@code battle.result} relayed to gen1-bridge. Spectating someone
 * else's battle doesn't count. Client thread only.
 */
public final class ClientBattles {
	private ClientBattles() {
	}

	public static boolean active() {
		ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
		return battle != null && !battle.getSpectating();
	}
}
