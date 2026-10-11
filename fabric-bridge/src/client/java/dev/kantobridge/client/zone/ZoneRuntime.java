package dev.kantobridge.client.zone;

import com.google.gson.JsonObject;
import dev.kantobridge.client.bridge.BridgeClock;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.move.InputLock;
import dev.kantobridge.client.net.ClientRelay;
import dev.kantobridge.client.net.SessionGate;
import dev.kantobridge.client.net.SessionRuntime;
import dev.kantobridge.client.puppet.Puppet;
import dev.kantobridge.client.puppet.PuppetManager;
import dev.kantobridge.client.puppet.PuppetRuntime;
import dev.kantobridge.client.story.ClientStoryView;
import dev.kantobridge.client.world.ClientWorldIndex;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.story.RouteProgress;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.WorldIndex;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.Nullable;

/**
 * Minecraft glue for Wild Routes zones on the client (docs/specs/WILD_ROUTES_PLAN.md §4.6, WR5):
 *
 * <ul>
 * <li><b>Puppets:</b> the zone the player stands in shows its trainers at their posts and the balls this player hasn't
 * taken ({@link ZoneView#specs}), kept in step with the progress the hidden game reports.</li>
 * <li><b>Relay:</b> the server's {@code route.give}, {@code route.battle} and {@code route.blackout} go to gen1-bridge;
 * gen1-bridge's answers to them ({@code route.result}, carrying the server's {@code seq} as {@code re}) and every
 * {@code route.progress} go to the server. The save pairing's gate decides, as for the other relays: while it holds,
 * nothing passes (the server asks again).</li>
 * <li><b>Balls:</b> an A press at a ball in front of the player, or a click on a ball's puppet from the cell beside it,
 * sends {@code route.give} straight to gen1-bridge; the {@code route.progress} that follows takes the puppet away and
 * opens the ball's story cell.</li>
 * <li><b>Trainers:</b> {@code route.sight} shows "!" over the trainer's puppet, holds the player and walks the puppet up
 * ({@link WalkUps}); when the battle's answer goes back to the server, the puppet walks back to its post.</li>
 * </ul>
 * Client thread only. Logs nothing itself; the F3 line counts.
 */
public final class ZoneRuntime {
	/** A ball asked for and not answered is asked again after this long. */
	static final long GIVE_TIMEOUT_MS = 5_000;
	/** The most server requests waiting for gen1-bridge's answer. */
	static final int MAX_PENDING = 32;

	private static @Nullable ZoneRuntime instance;

	private final BridgeRuntime bridge;
	private final BridgeClock clock = BridgeClock.system();
	/** gen1-bridge's seq of a forwarded server request → the server's seq, for the answer's {@code re}. */
	private final Map<Long, Long> forwarded = new LinkedHashMap<>();
	/** Balls asked for (puppet id → when). */
	private final Map<String, Long> giving = new HashMap<>();
	private final WalkUps walkUps = new WalkUps();
	private @Nullable WorldIndex index;
	private @Nullable String zone;
	private long progressVersion = -1;
	private long down;
	private long up;
	private long gives;
	private long sights;
	private long dropped;

	private ZoneRuntime(BridgeRuntime bridge) {
		this.bridge = bridge;
	}

	/** Call once from the client entrypoint, after the story view, the puppets and the relays. */
	public static void init(BridgeRuntime bridge) {
		if (instance != null) {
			return;
		}
		ZoneRuntime runtime = new ZoneRuntime(bridge);
		instance = runtime;
		ClientRelay server = ClientRelay.get();
		for (String type : List.of(MsgTypes.ROUTE_GIVE, MsgTypes.ROUTE_BATTLE, MsgTypes.ROUTE_BLACKOUT)) {
			server.register(type, runtime::fromServer);
		}
		server.register(MsgTypes.ROUTE_SIGHT, runtime::onSight);
		InputLock.holdWhile(() -> runtime.walkUps.holding(runtime.clock.millis()));
		bridge.dispatcher().on(MsgTypes.ROUTE_RESULT, runtime::onResult);
		bridge.dispatcher().on(MsgTypes.ROUTE_PROGRESS, runtime::onProgress);
		ClientTickEvents.END_CLIENT_TICK.register(runtime::tick);
		bridge.addDebugLine(runtime::debugLine);
	}

	public static @Nullable ZoneRuntime get() {
		return instance;
	}

	// ---- relay ----

	private void fromServer(Envelope message) {
		SessionGate gate = gate();
		if (gate != null && !gate.bridgeOpen()) {
			this.dropped++;
			return;
		}
		long seq = this.bridge.client().sendTracked(message.type(), message.data().deepCopy());
		if (seq <= 0) {
			this.dropped++;
			return;
		}
		this.down++;
		this.forwarded.put(seq, message.seq());
		Iterator<Long> oldest = this.forwarded.keySet().iterator();
		while (this.forwarded.size() > MAX_PENDING && oldest.hasNext()) {
			oldest.next();
			oldest.remove();
		}
	}

	private void onResult(Envelope message) {
		Long serverSeq = message.re() == null ? null : this.forwarded.remove(message.re());
		if (serverSeq != null) {
			this.toServer(MsgTypes.ROUTE_RESULT, message.data(), serverSeq);
			if ("battle".equals(stringField(message.data(), "op"))) {
				this.walkBack(stringField(message.data(), "route"), stringField(message.data(), "id"));
			}
			return;
		}
		// one of this client's own balls: answered, so it may be asked again (the progress decides whether it's gone)
		if ("give".equals(stringField(message.data(), "op"))) {
			String route = stringField(message.data(), "route");
			String id = stringField(message.data(), "id");
			if (route != null && id != null) {
				this.giving.remove(PuppetManager.ZONE_PREFIX + route + ":" + id);
			}
		}
	}

	private void onProgress(Envelope message) {
		this.toServer(MsgTypes.ROUTE_PROGRESS, message.data(), null);
	}

	private void toServer(String type, JsonObject data, @Nullable Long re) {
		SessionGate gate = gate();
		if ((gate == null || gate.serverOpen()) && ClientRelay.get().send(type, data.deepCopy(), re)) {
			this.up++;
		} else {
			this.dropped++;
		}
	}

	private static @Nullable SessionGate gate() {
		SessionRuntime session = SessionRuntime.get();
		return session == null ? null : session.gate();
	}

	private static @Nullable String stringField(JsonObject data, String key) {
		return data.has(key) && data.get(key).isJsonPrimitive() ? data.get(key).getAsString() : null;
	}

	// ---- trainers ----

	/** {@code route.sight}: the "!" now, the walk once it's over, the player held meanwhile. */
	private void onSight(Envelope message) {
		JsonObject data = message.data();
		WorldIndex current = ClientWorldIndex.current();
		WorldIndex.Zone zone = current == null ? null : zoneById(current, stringField(data, "route"));
		String id = stringField(data, "id");
		WorldIndex.ZoneTrainer trainer = zone == null || id == null ? null : zone.trainer(id);
		PuppetRuntime puppets = PuppetRuntime.get();
		if (trainer == null || puppets == null || !data.has("x") || !data.has("y") || !data.has("frames")) {
			return;
		}
		this.sights++;
		String puppet = ZoneView.puppetId(zone, trainer.id());
		puppets.manager().zoneFace(puppet, trainer.facing());
		puppets.manager().zoneEmote(puppet, "!", (int) (WalkUps.EXCLAIM_MS * 60 / 1000));
		this.walkUps.sighted(puppet, data.get("x").getAsInt(), data.get("y").getAsInt(), trainer.facing(),
				Math.max(0, Math.min(data.get("frames").getAsInt(), 3600)), this.clock.millis());
	}

	/** After a battle's answer: the trainer's puppet walks back to its post. */
	private void walkBack(@Nullable String route, @Nullable String id) {
		WorldIndex current = ClientWorldIndex.current();
		WorldIndex.Zone zone = current == null ? null : zoneById(current, route);
		WorldIndex.ZoneTrainer trainer = zone == null || id == null ? null : zone.trainer(id);
		PuppetRuntime puppets = PuppetRuntime.get();
		Puppet puppet = trainer == null || puppets == null ? null : puppets.manager().get(ZoneView.puppetId(zone, trainer.id()));
		if (puppet == null) {
			return;
		}
		int steps = Math.abs(puppet.x() - trainer.x()) + Math.abs(puppet.y() - trainer.y());
		puppets.manager().zoneMove(puppet.id(), trainer.x(), trainer.y(), trainer.facing(), steps * 16);
	}

	private static WorldIndex.@Nullable Zone zoneById(WorldIndex index, @Nullable String id) {
		for (WorldIndex.Zone zone : index.zones()) {
			if (zone.id().equals(id)) {
				return zone;
			}
		}
		return null;
	}

	// ---- puppets ----

	private void tick(Minecraft minecraft) {
		PuppetRuntime puppets = PuppetRuntime.get();
		if (puppets == null) {
			return;
		}
		WorldIndex current = ClientWorldIndex.current();
		LocalPlayer player = minecraft.player;
		WorldIndex.Zone here = current == null || player == null ? null : ZoneView.zoneAt(current, player.getX(), player.getZ());
		RouteProgress progress = ClientStoryView.routeProgress();
		String id = here == null ? null : here.id();
		if (current != this.index || !Objects.equals(id, this.zone) || progress.version() != this.progressVersion) {
			if (!Objects.equals(id, this.zone)) {
				this.walkUps.clear();
			}
			this.index = current;
			this.zone = id;
			this.progressVersion = progress.version();
			puppets.manager().setZone(here == null ? List.of() : ZoneView.specs(here, progress));
		}
		long now = this.clock.millis();
		this.giving.values().removeIf(at -> now - at > GIVE_TIMEOUT_MS);
		for (WalkUps.Walk walk : this.walkUps.due(now)) {
			puppets.manager().zoneMove(walk.puppet(), walk.x(), walk.y(), walk.facing(), walk.frames());
		}
	}

	// ---- balls ----

	/**
	 * An A press while the player isn't on the hidden player's cell: in a zone, the ball in front of them is asked for.
	 * Returns whether it was a zone's press.
	 */
	public boolean pressA(LocalPlayer player) {
		WorldIndex current = ClientWorldIndex.current();
		WorldIndex.Zone here = current == null ? null : ZoneView.zoneAt(current, player.getX(), player.getZ());
		if (here == null) {
			return false;
		}
		WorldIndex.ZoneBall ball = ZoneView.facedBall(current, here, ClientStoryView.routeProgress(), player.getX(), player.getZ(),
				Dir.fromYaw(player.getYRot()));
		if (ball != null) {
			this.give(here, ball);
		}
		return true;
	}

	/** A click on a zone puppet: a ball is asked for when the player stands beside it; a trainer does nothing. */
	public void clickPuppet(LocalPlayer player, Puppet puppet) {
		WorldIndex current = ClientWorldIndex.current();
		WorldIndex.Zone here = current == null ? null : ZoneView.zoneAt(current, player.getX(), player.getZ());
		String object = here == null ? null : ZoneView.objectOf(here, puppet.id());
		WorldIndex.ZoneBall ball = object == null ? null : here.ball(object);
		if (ball != null && ZoneView.nextTo(current, here, player.getX(), player.getZ(), ball.x(), ball.y())
				&& !ClientStoryView.routeProgress().taken(here.id(), ball.id())) {
			this.give(here, ball);
		}
	}

	private void give(WorldIndex.Zone zone, WorldIndex.ZoneBall ball) {
		String key = ZoneView.puppetId(zone, ball.id());
		if (this.giving.containsKey(key)) {
			return;
		}
		if (this.bridge.client().send(MsgTypes.ROUTE_GIVE, ZoneView.give(zone, ball))) {
			this.giving.put(key, this.clock.millis());
			this.gives++;
		}
	}

	private String debugLine() {
		return I18n.get("kantobridge.debug.zone", this.zone == null ? "-" : this.zone, this.down, this.up, this.gives, this.giving.size(),
				this.sights, this.dropped);
	}
}
