package dev.kantobridge.client.story;

import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.client.bridge.BridgeLink;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.bridge.Dispatcher;
import dev.kantobridge.client.bridge.JsonFields;
import dev.kantobridge.client.bridge.Links;
import dev.kantobridge.client.model.SnapshotPublisher;
import dev.kantobridge.client.net.SessionRuntime;
import dev.kantobridge.client.puppet.Puppet;
import dev.kantobridge.client.puppet.PuppetManager;
import dev.kantobridge.client.puppet.PuppetRuntime;
import dev.kantobridge.client.world.ClientRegions;
import dev.kantobridge.client.world.ClientWorldIndex;
import dev.kantobridge.net.KbJsonPayload;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.story.RouteProgress;
import dev.kantobridge.story.StoryRules;
import dev.kantobridge.story.StoryState;
import dev.kantobridge.story.StoryStateMessages;
import dev.kantobridge.story.StoryStates;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * replaced (respawn); deltas follow. The tile cells drawn closed go to the chunk mesh ({@link SnapshotPublisher#storyChanged},
 * the voxel plan's V5), or to {@link StoryCellRenderer} in a world built before V5.
 *
 * <p>M9: nothing is applied or relayed while the save pairing holds the world's data (the hidden game may still run
 * another world's save); once it opens, a full state goes up.
 *
 * <p>MG4 (several games): one {@link StoryRules} per {@link BridgeLink}, each fed by its own link and its region's index,
 * so a game being followed again has its story ready; the body's blockades, drawn art and the relayed {@code story.state}
 * are the followed link's, and a swap sends a full state (the server keeps the followed region's story only).
 */
public final class ClientStoryView {
	private static @Nullable ClientStoryView instance;

	private final Links links;
	private final Map<BridgeLink, StoryRules> rulesByLink = new LinkedHashMap<>();
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

	private ClientStoryView(Links links) {
		this.links = links;
		for (BridgeLink link : links.all()) {
			this.rulesByLink.put(link, new StoryRules());
		}
	}

	/** Call once from the client entrypoint, after {@link BridgeRuntime#init} and before the puppets. */
	public static void init(BridgeRuntime bridge) {
		if (instance != null) {
			return;
		}
		ClientStoryView view = new ClientStoryView(bridge.links());
		instance = view;
		for (Map.Entry<BridgeLink, StoryRules> entry : view.rulesByLink.entrySet()) {
			BridgeLink link = entry.getKey();
			StoryRules rules = entry.getValue();
			Dispatcher dispatcher = link.dispatcher();
			dispatcher.on(MsgTypes.SNAPSHOT, message -> {
				rules.onSnapshot(message.data());
				if (link == view.links.followed()) {
					view.fullSent = false;
				}
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
			// MG7 (G3C): Gen 3's changed cells, kept for the Gen 3 world
			dispatcher.on(MsgTypes.STORY_CELLS, message -> rules.onCells(message.data()));
			dispatcher.on(MsgTypes.STORY_LIT, message -> rules.onLit(message.data()));
			dispatcher.on(MsgTypes.ROUTE_PROGRESS, message -> rules.onRouteProgress(message.data()));
		}
		// MG4: another link is followed: its story goes up whole (the server cleared the old region's).
		view.links.onFollow((link, teleport) -> view.fullSent = false);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> view.fullSent = false);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			view.fullSent = false;
			view.sentFor = null;
		});
		// After BridgeRuntime's tick, which drains the bridge's messages.
		ClientTickEvents.END_CLIENT_TICK.register(view::tick);
		bridge.addDebugLine(view::debugLine);
	}

	/** The followed link's rules (the body's story). */
	private StoryRules rules() {
		StoryRules rules = this.rulesByLink.get(this.links.followed());
		return rules == null ? this.rulesByLink.values().iterator().next() : rules;
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

	/** The local player's Wild Routes progress (empty before client init). */
	public static RouteProgress routeProgress() {
		ClientStoryView view = instance;
		return view == null ? EMPTY_PROGRESS : view.rules().routeProgress();
	}

	private static final RouteProgress EMPTY_PROGRESS = new RouteProgress();

	/**
	 * MG5: the connecting routes whose pass is open for this player (the far region is built and a connected link runs
	 * its game), from the crossings runtime each tick; every link's rules get it, since each region's pass cells are in
	 * its own index.
	 */
	public static void setOpenPasses(Set<String> routes) {
		ClientStoryView view = instance;
		if (view == null) {
			return;
		}
		for (StoryRules rules : view.rulesByLink.values()) {
			rules.setOpenPasses(routes);
		}
	}

	/** True while the hidden player surfs (the field moves a click means depend on it). */
	public static boolean surfing() {
		ClientStoryView view = instance;
		return view != null && view.rules().surf();
	}

	private void tick(Minecraft minecraft) {
		// Every link's rules read its own region's index (a parked game's story is ready when it is followed again).
		BridgeLink followed = this.links.followed();
		for (Map.Entry<BridgeLink, StoryRules> entry : this.rulesByLink.entrySet()) {
			BridgeLink link = entry.getKey();
			entry.getValue().setIndex(link == followed ? ClientWorldIndex.current() : ClientRegions.indexOf(link.region()));
		}
		StoryRules rules = this.rules();
		this.feedNpcs(rules);
		StoryRules.Cells cells = rules.cells();
		Set<String> before = this.drawn;
		this.drawn = cells.drawn();
		if (!this.drawn.equals(before)) {
			// The chunk mesh draws closed art from the published set (V5): swap it, re-mesh the changed cells.
			SnapshotPublisher.storyChanged(before, this.drawn);
		}
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
		boolean surf = rules.surf();
		boolean bike = rules.bike();
		Set<String> lit = new TreeSet<>(rules.lit());
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
		// MG4: tagged with the followed link's region (a legacy world sends today's bytes).
		if (followed.toServer(MsgTypes.STORY_STATE, message, null)) {
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
	private void feedNpcs(StoryRules rules) {
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
		rules.setNpcs(manager.map(), list);
	}

	private String debugLine() {
		StoryRules rules = this.rules();
		StoryRules.Cells cells = rules.cells();
		return I18n.get("kantobridge.debug.story", cells.active().size(), cells.drawn().size(), rules.surf(), rules.bike(),
				rules.lit().size(), this.relayed);
	}
}
