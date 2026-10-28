package dev.kantobridge.client;

import dev.kantobridge.block.KbBlocks;
import dev.kantobridge.client.act.ActRuntime;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.dev.Autopilot;
import dev.kantobridge.client.fx.BlockadeFade;
import dev.kantobridge.client.fx.SpeechBubbles;
import dev.kantobridge.client.fx.SurfSprite;
import dev.kantobridge.client.model.KbModelPlugin;
import dev.kantobridge.client.model.MeshStatsRuntime;
import dev.kantobridge.client.model.SnapshotPublisher;
import dev.kantobridge.client.move.MoveRuntime;
import dev.kantobridge.client.net.BagSetRuntime;
import dev.kantobridge.client.net.ClientRelay;
import dev.kantobridge.client.net.GameRelayRuntime;
import dev.kantobridge.client.net.SessionRuntime;
import dev.kantobridge.client.net.Versions;
import dev.kantobridge.client.packs.KbPackReload;
import dev.kantobridge.client.packs.PackCommands;
import dev.kantobridge.client.packs.TextureSyncRuntime;
import dev.kantobridge.client.puppet.PuppetRuntime;
import dev.kantobridge.client.shape.ClientShapes;
import dev.kantobridge.client.story.ClientStoryView;
import dev.kantobridge.client.story.StoryCellRenderer;
import dev.kantobridge.client.ui.Notices;
import dev.kantobridge.client.ui.UiRuntime;
import dev.kantobridge.client.weather.WeatherView;
import dev.kantobridge.client.world.AddRegionFlow;
import dev.kantobridge.client.world.ClientRegions;
import dev.kantobridge.client.world.ClientWorldIndex;
import dev.kantobridge.client.world.CreateKantoWorldFlow;
import dev.kantobridge.client.zone.PassRuntime;
import dev.kantobridge.client.zone.ZoneRuntime;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

public class KantoBridgeClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientRelay.init();
		// The server sends its WorldIndex on join; keep the client's copy.
		ClientWorldIndex.init();
		// The voxel plan's runtime model (V1): every tile block's look comes from one model of ours, which draws the world's
		// slots with this player's own art through an immutable render snapshot, rebuilt when the world or the atlas changes.
		KbModelPlugin.init();
		SnapshotPublisher.init();
		// Texture packs (the voxel plan's V6): read on every resource reload; their geometry rebuilds the shape table.
		KbPackReload.init();
		KbPackReload.onChange(() -> {
			ClientShapes.artChanged();
			SnapshotPublisher.request();
			PuppetRuntime puppets = PuppetRuntime.get();
			if (puppets != null) {
				puppets.packsChanged();
			}
		});
		PackCommands.init();
		// The link to this player's hidden Gen1Recomp (docs/PROTOCOL.md); it reconnects on its own.
		BridgeRuntime.init();
		BridgeRuntime bridge = BridgeRuntime.get();
		// Walking and warps: the Minecraft player and the hidden player move together (docs/PLAN.md §4.3 move/).
		MoveRuntime.init(bridge);
		// The player's story cells, surf, bike and lit maps from the hidden game, applied locally and relayed as story.state;
		// tile cells draw their closed art for this player, and a surfer rides the hidden game's surfing sprite (M8).
		ClientStoryView.init(bridge);
		BlockEntityRenderers.register(KbBlocks.STORY_TILE_ENTITY, StoryCellRenderer::new);
		SurfSprite.init();
		// SE6: the followed hidden game's weather (rain, fog, ash, sandstorm, drought, ...), drawn for this player only.
		WeatherView.init(bridge);
		// The hidden game's text boxes, menus, naming and mirror as our screens, and the Game Boy keys (§4.3 ui/).
		UiRuntime.init(bridge);
		// The hidden game's NPCs as client-only puppets (§4.3 puppet/).
		PuppetRuntime.init(bridge);
		dev.kantobridge.client.ride.RideRuntime.init();
		// A, START and right-clicks from the world: act.interact and act.button.
		ActRuntime.init(bridge);
		// Battles and Pokémon between gen1-bridge and the server (battle.start/result, party.*, syncs) and the activity.
		GameRelayRuntime.init(bridge);
		// One trainer economy (MG6): the server's bag.set projections down to each hidden game, their results back up.
		BagSetRuntime.init(bridge);
		// Saving in both games, Bill's PC as Cobblemon's PC, and the world's id for the save pairing (session.bind).
		SessionRuntime.init(bridge);
		// WORLD_SESSIONS_PLAN WS7 (D8, opt-in): Minecraft starts the games a world lists and stops those it started after their quit
		dev.kantobridge.client.engine.EngineRuntime.init(bridge);
		// Wild Routes (WR5): a zone's trainers and balls as puppets, route.* between the server and gen1-bridge, balls taken.
		ZoneRuntime.init(bridge);
		// Crossings (MG5): standing on a connecting route's pass parks the followed game and resumes or starts the far one.
		PassRuntime.init(bridge);
		// The title screen's "Create Kanto world" button: export, pack, new level, build. No pause overlay while it runs (M10).
		CreateKantoWorldFlow.init();
		bridge.pauseExcludedWhile(() -> CreateKantoWorldFlow.active() != null);
		// Several games (MG4): the title screen's "Add a region" button: export another link's game, build it at the next slot.
		AddRegionFlow.init();
		bridge.pauseExcludedWhile(() -> AddRegionFlow.active() != null);
		// Multiplayer (M9): this player's own art built into the world's slots when their pack doesn't fit it; speech
		// bubbles over other players busy with their own hidden game; other players faded inside this player's blockades.
		TextureSyncRuntime.init(bridge);
		SpeechBubbles.init();
		BlockadeFade.init();
		bridge.addDebugLine(SpeechBubbles::debugLine);
		// M10: warning screens wait their turn; the version checks (the relay's handshake, gen1-bridge's engine, the world's
		// game) and the save-pair mismatch warning.
		Notices.init();
		Versions.init(bridge);
		// The world's shapes (the voxel plan's V2): what the shape table holds, or why the world draws cubes.
		bridge.addDebugLine(ClientShapes::debugLine);
		// Several games (MG3): the regions this world holds and the one followed.
		bridge.addDebugLine(ClientRegions::debugLine);
		bridge.addDebugLine(KbPackReload::debugLine);
		// Dev only (-Dkantobridge.meshstats=true): the rendering measurements of the voxel plan (§10).
		MeshStatsRuntime.init(bridge);
		// Dev only (-Dkantobridge.autopilot=<scene>): a scripted scene for recorded showcases (Wild Routes check C5).
		Autopilot.init();
	}
}
