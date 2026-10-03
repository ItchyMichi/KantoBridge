package dev.kantobridge.client;

import dev.kantobridge.client.act.ActRuntime;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.move.MoveRuntime;
import dev.kantobridge.client.net.ClientRelay;
import dev.kantobridge.client.net.GameRelayRuntime;
import dev.kantobridge.client.net.SessionRuntime;
import dev.kantobridge.client.puppet.PuppetRuntime;
import dev.kantobridge.client.story.ClientStoryView;
import dev.kantobridge.client.ui.UiRuntime;
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
		BridgeRuntime bridge = BridgeRuntime.get();
		// Walking and warps: the Minecraft player and the hidden player move together (docs/PLAN.md §4.3 move/).
		MoveRuntime.init(bridge);
		// The surf and bike flags, applied locally and relayed as story.state.
		ClientStoryView.init(bridge);
		// The hidden game's text boxes, menus, naming and mirror as our screens, and the Game Boy keys (§4.3 ui/).
		UiRuntime.init(bridge);
		// The hidden game's NPCs as client-only puppets (§4.3 puppet/).
		PuppetRuntime.init(bridge);
		// A, START and right-clicks from the world: act.interact and act.button.
		ActRuntime.init(bridge);
		// Battles and Pokémon between gen1-bridge and the server (battle.start/result, party.*, syncs) and the activity.
		GameRelayRuntime.init(bridge);
		// Saving in both games, Bill's PC as Cobblemon's PC, and the world's id for the save pairing (session.bind).
		SessionRuntime.init(bridge);
		// The title screen's "Create Kanto world" button: export, pack, new level, build.
		CreateKantoWorldFlow.init();
	}
}
