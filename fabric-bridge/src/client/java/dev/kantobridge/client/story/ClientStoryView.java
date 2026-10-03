package dev.kantobridge.client.story;

import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.bridge.JsonFields;
import dev.kantobridge.client.net.ClientRelay;
import dev.kantobridge.net.KbJsonPayload;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.story.StoryState;
import dev.kantobridge.story.StoryStateMessages;
import dev.kantobridge.story.StoryStates;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * This player's story state for blockades and speed (docs/PLAN.md §4.3 {@code story/}), M3's part: the surf and bike
 * flags from the hidden game's {@code player.state}. Each change is applied to the local player's {@link StoryState}
 * first (client-side collision: water opens when surfing) and then relayed as {@code story.state}, on the client
 * thread, so it stays ordered with movement packets (§2.3). A full state goes up on joining a world, after a snapshot,
 * and when the local player object is replaced (respawn); deltas follow. Story cells join in later milestones.
 */
public final class ClientStoryView {
	private static @Nullable ClientStoryView instance;

	private boolean surf;
	private boolean bike;
	private boolean fullSent;
	private boolean sentSurf;
	private boolean sentBike;
	private @Nullable LocalPlayer sentFor;

	private ClientStoryView() {
	}

	/** Call once from the client entrypoint, after {@link BridgeRuntime#init}. */
	public static void init(BridgeRuntime bridge) {
		if (instance != null) {
			return;
		}
		ClientStoryView view = new ClientStoryView();
		instance = view;
		bridge.dispatcher().on(MsgTypes.SNAPSHOT, view::onSnapshot);
		bridge.dispatcher().on(MsgTypes.PLAYER_STATE, message -> view.onState(message.data()));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> view.fullSent = false);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			view.fullSent = false;
			view.sentFor = null;
		});
		// After BridgeRuntime's tick, which drains the bridge's messages.
		ClientTickEvents.END_CLIENT_TICK.register(view::tick);
	}

	private void onSnapshot(Envelope message) {
		JsonObject state = JsonFields.obj(message.data(), "state");
		if (state != null) {
			this.onState(state);
		}
		this.fullSent = false;
	}

	private void onState(JsonObject state) {
		this.surf = JsonFields.bool(state, "surf", this.surf);
		this.bike = JsonFields.bool(state, "bike", this.bike);
	}

	private void tick(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || !ClientPlayNetworking.canSend(KbJsonPayload.TYPE)) {
			return;
		}
		StoryState local = StoryStates.of(player);
		JsonObject message;
		if (!this.fullSent || player != this.sentFor) {
			message = StoryStateMessages.full(local.active(), this.surf, this.bike, local.lit());
		} else if (this.surf != this.sentSurf || this.bike != this.sentBike) {
			message = StoryStateMessages.delta(null, null, this.surf != this.sentSurf ? this.surf : null,
					this.bike != this.sentBike ? this.bike : null, null);
		} else {
			return;
		}
		try {
			StoryStateMessages.apply(local, message);
		} catch (StoryStateMessages.Invalid e) {
			// Built here from valid parts, so this would be a bug; never relay what the local copy refused.
			KantoBridge.LOGGER.error("story.state: built an invalid message ({})", e.code());
			return;
		}
		if (ClientRelay.get().send(MsgTypes.STORY_STATE, message, null)) {
			this.fullSent = true;
			this.sentSurf = this.surf;
			this.sentBike = this.bike;
			this.sentFor = player;
		}
	}
}
