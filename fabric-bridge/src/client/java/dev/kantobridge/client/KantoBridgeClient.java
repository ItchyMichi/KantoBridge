package dev.kantobridge.client;

import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.net.ClientRelay;
import dev.kantobridge.client.world.ClientWorldIndex;
import dev.kantobridge.client.world.CreateKantoWorldFlow;
import net.fabricmc.api.ClientModInitializer;

public class KantoBridgeClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientRelay.init();
		// The server sends its WorldIndex on join; keep the client's copy.
		ClientWorldIndex.init();
		// The link to this player's hidden Gen1Recomp (docs/PROTOCOL.md); it reconnects on its own.
		BridgeRuntime.init();
		// The title screen's "Create Kanto world" button: export, pack, new level, build.
		CreateKantoWorldFlow.init();
	}
}
