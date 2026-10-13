package dev.kantobridge.client.zone;

import com.google.gson.JsonObject;
import dev.kantobridge.client.battle.ClientBattles;
import dev.kantobridge.client.bridge.BridgeClock;
import dev.kantobridge.client.bridge.BridgeLink;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.bridge.ConnectionState;
import dev.kantobridge.client.bridge.ConnectionStatus;
import dev.kantobridge.client.bridge.JsonFields;
import dev.kantobridge.client.bridge.Links;
import dev.kantobridge.client.move.MoveRuntime;
import dev.kantobridge.client.net.ClientRelay;
import dev.kantobridge.client.story.ClientStoryView;
import dev.kantobridge.client.ui.UiRuntime;
import dev.kantobridge.client.world.ClientRegions;
import dev.kantobridge.client.world.ClientWorldIndex;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.Games;
import dev.kantobridge.world.RegionRecord;
import dev.kantobridge.world.WorldIndex;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.Nullable;

/**
 * Minecraft glue for crossings (MG5, docs/specs/MULTI_GAME_PLAN.md §4.8): each tick, the pass under the player's feet
 * (a connecting route half's gate-house doorway, {@link WorldIndex.Pass}) goes to {@link PassChoreography}, which asks
 * the server for the crossing and, under the dungeon fade it answers with, parks the followed hidden game, resumes or
 * starts the far one and follows its link. The open passes ({@code pass_closed} lifted where the far region is built and
 * a link runs its game) go to the story rules every tick. Client thread only; the F3 line counts.
 */
public final class PassRuntime {
	/** A HUD notice stays this long. */
	static final long NOTICE_MS = 6_000;

	private static @Nullable PassRuntime instance;

	private final Links links;
	private final BridgeClock clock = BridgeClock.system();
	private final PassChoreography choreography;
	private final Map<BridgeLink, LinkPort> ports = new LinkedHashMap<>();
	private ConnectionStatus.@Nullable Line notice;
	private long noticeUntil;
	private Set<String> openPasses = Set.of();

	/** A link as the choreography sees it. */
	private static final class LinkPort implements PassChoreography.LinkPort {
		final BridgeLink link;

		LinkPort(BridgeLink link) {
			this.link = link;
		}

		@Override
		public @Nullable String region() {
			return this.link.region();
		}

		@Override
		public boolean connected() {
			return this.link.connected();
		}

		@Override
		public @Nullable String playthrough() {
			BridgeLink.Status status = this.link.lastStatus();
			return status == null ? null : status.playthrough();
		}

		@Override
		public @Nullable String name() {
			BridgeLink.Status status = this.link.lastStatus();
			return status == null ? null : status.name();
		}

		@Override
		public long sendTracked(String type, JsonObject data) {
			return this.link.client().sendTracked(type, data);
		}
	}

	private PassRuntime(BridgeRuntime bridge) {
		this.links = bridge.links();
		for (BridgeLink link : this.links.all()) {
			this.ports.put(link, new LinkPort(link));
		}
		this.choreography = new PassChoreography(new PassChoreography.Host() {
			@Override
			public PassChoreography.@Nullable LinkPort followed() {
				return PassRuntime.this.port(PassRuntime.this.links.followed());
			}

			@Override
			public PassChoreography.@Nullable LinkPort forRegion(String region) {
				BridgeLink link = PassRuntime.this.links.forRegion(region);
				return link == null ? null : PassRuntime.this.port(link);
			}

			@Override
			public long toServer(String type, JsonObject data) {
				return ClientRelay.get().post(type, data, null);
			}

			@Override
			public boolean follow(String region, boolean allowTeleport) {
				BridgeLink link = PassRuntime.this.links.forRegion(region);
				String game = link == null ? null : link.game();
				return game != null && PassRuntime.this.links.follow(game, allowTeleport);
			}

			@Override
			public void armName(@Nullable String name) {
				UiRuntime ui = UiRuntime.get();
				if (ui != null) {
					ui.router().setAutoName(name == null ? null : () -> name);
				}
			}

			@Override
			public void fade(PassChoreography.Fade action) {
				ZoneRuntime zones = ZoneRuntime.get();
				if (zones == null) {
					return;
				}
				switch (action) {
					case LIFT -> zones.liftFade();
					case EXTEND -> zones.extendFade();
					case RESTART -> zones.restartFade();
				}
			}

			@Override
			public void notice(String key, List<String> args, boolean warning) {
				PassRuntime.this.notice = new ConnectionStatus.Line(key, args, warning);
				PassRuntime.this.noticeUntil = PassRuntime.this.clock.millis() + NOTICE_MS;
			}

			@Override
			public String label(String region) {
				return PassRuntime.label(region);
			}
		});
	}

	/** Call once from the client entrypoint, after {@link ZoneRuntime#init} (the fade) and the UI runtime. */
	public static void init(BridgeRuntime bridge) {
		if (instance != null) {
			return;
		}
		PassRuntime runtime = new PassRuntime(bridge);
		instance = runtime;
		ZoneRuntime zones = ZoneRuntime.get();
		if (zones != null) {
			zones.onFadeStarted(() -> runtime.choreography.fadeStarted(runtime.now()));
		}
		for (BridgeLink link : runtime.links.all()) {
			LinkPort port = runtime.port(link);
			link.dispatcher().on(MsgTypes.ACT_RESULT, message -> runtime.choreography.linkResult(port, message.re(),
					JsonFields.bool(message.data(), "ok", false), JsonFields.str(message.data(), "reason"), runtime.now()));
			link.dispatcher().on(MsgTypes.GAME_STATUS,
					message -> runtime.choreography.gameStatus(port, JsonFields.bool(message.data(), "ingame", false), runtime.now()));
			link.dispatcher().onState((state, detail) -> {
				if (state != ConnectionState.CONNECTED) {
					runtime.choreography.linkDown(port, runtime.now());
				}
			});
		}
		// The body's screens closing (the far game's intro, after a start).
		bridge.dispatcher().on(MsgTypes.UI_CLOSE, message -> runtime.choreography.uiClosed(runtime.now()));
		// The server's answers: act.result{kind:"stash"} to party.stash, error{pass.*, stash.pc_full}. No other client
		// runtime takes these server-to-client types; the relay keeps one handler per type.
		runtime.links.onServer(MsgTypes.ACT_RESULT, false, (link, message) -> runtime.choreography.serverResult(message.re(),
				JsonFields.bool(message.data(), "ok", false), JsonFields.str(message.data(), "kind"), runtime.now()));
		runtime.links.onServer(MsgTypes.ERROR, false,
				(link, message) -> runtime.choreography.serverError(JsonFields.str(message.data(), "code"), message.re(), runtime.now()));
		// After BridgeRuntime's tick (the links' messages) and ZoneRuntime's (the fade).
		ClientTickEvents.END_CLIENT_TICK.register(runtime::tick);
		bridge.addHudLine(runtime::hudLine);
		bridge.addDebugLine(runtime::debugLine);
	}

	public static @Nullable PassRuntime get() {
		return instance;
	}

	private LinkPort port(BridgeLink link) {
		return this.ports.computeIfAbsent(link, LinkPort::new);
	}

	private long now() {
		return this.clock.millis();
	}

	// ---- each tick ----

	private void tick(Minecraft minecraft) {
		long now = this.now();
		LocalPlayer player = minecraft.player;
		PassChoreography.PassAt here = null;
		WorldIndex current = ClientWorldIndex.current();
		RegionRecord region = ClientRegions.followed();
		if (player != null && current != null && region != null && ClientRegions.regionWorld()) {
			WorldIndex.Zone zone = ZoneView.zoneAt(current, player.getX(), player.getZ());
			WorldIndex.Pass pass = zone == null ? null : zone.pass();
			if (pass != null && Math.abs(player.getY() - zone.y()) < 2) {
				int[] cell = ZoneView.cellAt(current, zone, player.getX(), player.getZ());
				if (cell != null && zone.passCell(cell[0], cell[1])) {
					WorldIndex.Zone far = farHalf(pass);
					here = new PassChoreography.PassAt(pass.route(), pass.dir(), region.id(), pass.toRegion(), far == null ? null : far.routeMap());
				}
			}
		}
		UiRuntime ui = UiRuntime.get();
		ZoneRuntime zones = ZoneRuntime.get();
		boolean idle = MoveRuntime.following() && !ClientBattles.active() && (ui == null || !ui.open()) && (zones == null || !zones.fading());
		this.choreography.standing(here, idle, now);
		this.choreography.tick(now);
		this.publishOpenPasses();
	}

	/** The other half of {@code pass}'s route in the far region's index, or null while it isn't known. */
	private static WorldIndex.@Nullable Zone farHalf(WorldIndex.Pass pass) {
		WorldIndex far = ClientRegions.indexOf(pass.toRegion());
		if (far == null) {
			return null;
		}
		for (WorldIndex.Zone zone : far.zones()) {
			WorldIndex.Pass other = zone.pass();
			if (other != null && other.route().equals(pass.route())) {
				return zone;
			}
		}
		return null;
	}

	/**
	 * {@code pass_closed} lifts for a route when its far region is built (an index with the route's other half) and a
	 * connected link runs that region's game.
	 */
	private void publishOpenPasses() {
		Set<String> open = new TreeSet<>();
		for (String id : ClientRegions.indexed()) {
			WorldIndex index = ClientRegions.indexOf(id);
			if (index == null) {
				continue;
			}
			for (WorldIndex.Zone zone : index.zones()) {
				WorldIndex.Pass pass = zone.pass();
				if (pass == null || farHalf(pass) == null) {
					continue;
				}
				BridgeLink link = this.links.forRegion(pass.toRegion());
				if (link != null && link.connected()) {
					open.add(pass.route());
				}
			}
		}
		if (!open.equals(this.openPasses)) {
			this.openPasses = open;
			ClientStoryView.setOpenPasses(open);
		}
	}

	// ---- views ----

	/** A region's game's label for the HUD ("Blue"), else the game id, else the region id. */
	static String label(String region) {
		RegionRecord record = ClientRegions.byId(region);
		String game = record == null ? null : record.game();
		String label = Games.label(game);
		return label != null ? label : game != null ? game : region;
	}

	private ConnectionStatus.@Nullable Line hudLine() {
		long now = this.now();
		if (this.notice != null && now < this.noticeUntil) {
			return this.notice;
		}
		this.notice = null;
		if (this.choreography.crossing() && this.choreography.state() != PassChoreography.State.AWAIT_INGAME) {
			return new ConnectionStatus.Line("kantobridge.pass.crossing", List.of(), false);
		}
		return null;
	}

	/** The crossing under way, for the pause overlay and the dev client: its state, or null while idle. */
	public @Nullable String crossing() {
		return this.choreography.crossing() ? this.choreography.state().name().toLowerCase(Locale.ROOT) : null;
	}

	/** The choreography's counters. */
	public PassChoreography.Stats stats() {
		return this.choreography.stats();
	}

	private String debugLine() {
		PassChoreography.Stats stats = this.choreography.stats();
		String route = this.choreography.route();
		return I18n.get("kantobridge.debug.pass", route == null ? "-" : route, this.choreography.state().name().toLowerCase(Locale.ROOT),
				stats.crossings, stats.resumes, stats.starts, stats.aborts, stats.timeouts, this.openPasses.size());
	}
}
