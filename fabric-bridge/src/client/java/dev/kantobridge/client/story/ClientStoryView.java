package dev.kantobridge.client.story;

import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.bridge.Dispatcher;
import dev.kantobridge.client.bridge.JsonFields;
import dev.kantobridge.client.net.ClientRelay;
import dev.kantobridge.client.net.SessionRuntime;
import dev.kantobridge.client.puppet.Puppet;
import dev.kantobridge.client.puppet.PuppetManager;
import dev.kantobridge.client.puppet.PuppetRuntime;
import dev.kantobridge.client.world.ClientWorldIndex;
import dev.kantobridge.net.KbJsonPayload;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.story.StoryRules;
import dev.kantobridge.story.StoryState;
import dev.kantobridge.story.StoryStateMessages;
import dev.kantobridge.story.StoryStates;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.Nullable;

/**
 * This player's story state for blockades, speed and Flash (docs/PLAN.md §4.3 {@code story/}; PROTOCOL.md "Story rules
 * (M8)"). {@link StoryRules} turns the hidden game's story messages, the live NPC list (the puppets) and the
 * WorldIndex's story-cell table into the active cells; each change is applied to the local player's {@link StoryState}
 * first (client-side collision) and then relayed as {@code story.state}, on the client thread, so it stays ordered with
 * movement packets (§2.3). A full state goes up on joining a world, after a snapshot, and when the local player object is
 * replaced (respawn); deltas follow. The tile cells drawn closed feed {@link StoryCellRenderer}.
 *
 * <p>M9: nothing is applied or relayed while the save pairing holds the world's data (the hidden game may still run
 * another world's save); once it opens, a full state goes up.
 */
public final class ClientStoryView {
	private static @Nullable ClientStoryView instance;

	private final StoryRules rules = new StoryRules();
	private boolean fullSent;
	private Set<String> sentActive = Set.of();
	private Set<String> sentLit = Set.of();
	private boolean sentSurf;
	private boolean sentBike;
	private @Nullable LocalPlayer sentFor;
	private Set<String> drawn = Set.of();
	private @Nullable String npcMap;
	private List<StoryRules.Npc> npcs = List.of();
	private long relayed;

	private ClientStoryView() {
	}

	/** Call once from the client entrypoint, after {@link BridgeRuntime#init} and before the puppets. */
	public static void init(BridgeRuntime bridge) {
		if (instance != null) {
			return;
		}
		ClientStoryView view = new ClientStoryView();
		instance = view;
		StoryRules rules = view.rules;
		Dispatcher dispatcher = bridge.dispatcher();
		dispatcher.on(MsgTypes.SNAPSHOT, message -> {
			rules.onSnapshot(message.data());
			view.fullSent = false;
		});
		dispatcher.on(MsgTypes.PLAYER_STATE, message -> rules.onPlayerState(message.data()));
		dispatcher.on(MsgTypes.PLAYER_POS, message -> rules.onPlayerMap(JsonFields.str(message.data(), "map")));
		dispatcher.on(MsgTypes.WARP, message -> {
			if ("arrive".equals(JsonFields.str(message.data(), "phase"))) {
				rules.onPlayerMap(JsonFields.str(JsonFields.obj(message.data(), "to"), "map"));
			}
		});
		dispatcher.on(MsgTypes.STORY_TOGGLES, message -> rules.onToggles(message.data()));
		dispatcher.on(MsgTypes.STORY_BLOCKS, message -> rules.onBlocks(message.data()));
		dispatcher.on(MsgTypes.STORY_LIT, message -> rules.onLit(message.data()));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> view.fullSent = false);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			view.fullSent = false;
			view.sentFor = null;
		});
		// After BridgeRuntime's tick, which drains the bridge's messages.
		ClientTickEvents.END_CLIENT_TICK.register(view::tick);
		bridge.addDebugLine(view::debugLine);
	}

	/** True when the local player sees tile cell {@code key} closed (its closed art is drawn). */
	public static boolean drawn(String key) {
		ClientStoryView view = instance;
		return view != null && view.drawn.contains(key);
	}

	/** Every tile cell the local player sees closed (for the crosshair on closed art). */
	public static Set<String> drawnKeys() {
		ClientStoryView view = instance;
		return view == null ? Set.of() : view.drawn;
	}

	/** True while the hidden player surfs (the field moves a click means depend on it). */
	public static boolean surfing() {
		ClientStoryView view = instance;
		return view != null && view.rules.surf();
	}

	private void tick(Minecraft minecraft) {
		this.rules.setIndex(ClientWorldIndex.current());
		this.feedNpcs();
		StoryRules.Cells cells = this.rules.cells();
		this.drawn = cells.drawn();
		LocalPlayer player = minecraft.player;
		if (player == null || !ClientPlayNetworking.canSend(KbJsonPayload.TYPE)) {
			return;
		}
		SessionRuntime session = SessionRuntime.get();
		if (session != null && !session.gate().serverOpen()) {
			// Not this world's story yet: a full state goes up once the pairing is answered.
			this.fullSent = false;
			return;
		}
		StoryState local = StoryStates.of(player);
		boolean surf = this.rules.surf();
		boolean bike = this.rules.bike();
		Set<String> lit = new TreeSet<>(this.rules.lit());
		JsonObject message;
		if (!this.fullSent || player != this.sentFor) {
			message = StoryStateMessages.full(cells.active(), surf, bike, lit);
		} else {
			Set<String> add = new TreeSet<>(cells.active());
			add.removeAll(this.sentActive);
			Set<String> remove = new TreeSet<>(this.sentActive);
			remove.removeAll(cells.active());
			boolean litChanged = !lit.equals(this.sentLit);
			if (add.isEmpty() && remove.isEmpty() && surf == this.sentSurf && bike == this.sentBike && !litChanged) {
				return;
			}
			message = StoryStateMessages.delta(add, remove, surf != this.sentSurf ? surf : null, bike != this.sentBike ? bike : null,
					litChanged ? lit : null);
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
			this.sentActive = Set.copyOf(cells.active());
			this.sentLit = Set.copyOf(lit);
			this.sentSurf = surf;
			this.sentBike = bike;
			this.sentFor = player;
			this.relayed++;
		}
	}

	/** The puppets are the live NPC list: where each stands (or walks to) on the list's map. */
	private void feedNpcs() {
		PuppetRuntime puppets = PuppetRuntime.get();
		if (puppets == null) {
			return;
		}
		PuppetManager manager = puppets.manager();
		List<StoryRules.Npc> list = new ArrayList<>();
		for (Puppet puppet : manager.all()) {
			if (Objects.equals(puppet.map(), manager.map())) {
				list.add(new StoryRules.Npc(puppet.x(), puppet.y(), puppet.kind()));
			}
		}
		if (Objects.equals(manager.map(), this.npcMap) && new HashSet<>(list).equals(new HashSet<>(this.npcs))) {
			return;
		}
		this.npcMap = manager.map();
		this.npcs = List.copyOf(list);
		this.rules.setNpcs(manager.map(), list);
	}

	private String debugLine() {
		StoryRules.Cells cells = this.rules.cells();
		return I18n.get("kantobridge.debug.story", cells.active().size(), cells.drawn().size(), this.rules.surf(), this.rules.bike(),
				this.rules.lit().size(), this.relayed);
	}
}
