package dev.kantobridge.client;

import dev.kantobridge.client.net.ClientRelay;
import net.fabricmc.api.ClientModInitializer;

public class KantoBridgeClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientRelay.init();
	}
}
