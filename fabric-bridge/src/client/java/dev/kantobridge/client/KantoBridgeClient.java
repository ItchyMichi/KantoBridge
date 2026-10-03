package dev.kantobridge.client;

import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.net.ClientRelay;
import net.fabricmc.api.ClientModInitializer;

public class KantoBridgeClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientRelay.init();
		// The link to this player's hidden Gen1Recomp (docs/PROTOCOL.md); it reconnects on its own.
		BridgeRuntime.init();
	}
}
