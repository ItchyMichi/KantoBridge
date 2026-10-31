package dev.kantobridge.client.move;

import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.client.battle.ClientBattles;
import dev.kantobridge.client.bridge.BridgeClock;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.bridge.ClientBridgeState;
import dev.kantobridge.client.bridge.Links;
import dev.kantobridge.client.bridge.ConnectionState;
import dev.kantobridge.client.bridge.Dispatcher;
import dev.kantobridge.client.bridge.HelloOk;
import dev.kantobridge.client.net.ClientRelay;
import dev.kantobridge.client.net.SessionGate;
import dev.kantobridge.client.net.SessionRuntime;
import dev.kantobridge.client.world.ClientWorldIndex;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.Place;
import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import dev.kantobridge.entity.BikeEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minecraft glue for walking and warps (docs/PLAN.md §4.3 {@code move/}): feeds {@link MoveController} the bridge's
 * messages, the server's {@code warp.done} and each world tick, and carries out what it asks: the forced velocity, the
 * input lock ({@code KeyboardInputMixin}), the warp fade overlay, messages to gen1-bridge and the server.
 *
 * <p>World ticks don't run while the game is paused: {@code START_WORLD_TICK} comes before the player's own movement,
 * {@code END_WORLD_TICK} after it. Bridge messages arrive at the end of the client tick, where {@link BridgeRuntime}
 * drains its dispatcher. Logs carry codes and counters only.
 */
public final class MoveRuntime {
	private static final Logger LOGGER = LoggerFactory.getLogger("kantobridge");
	/** The fade draws before the default phase, so the status line and the F7 overlay stay readable over it. */
	private static final ResourceLocation FADE_PHASE = ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "warp_fade");

	private static @Nullable MoveRuntime instance;

	private final BridgeRuntime bridge;
	private final BridgeClock clock = BridgeClock.system();
	private final MoveController controller;
	private final SlopeBrake brake = new SlopeBrake();

	private MoveRuntime(BridgeRuntime bridge) {
		this.bridge = bridge;
		this.controller = new MoveController(new Port());
	}

	/** Call once from the client entrypoint, after {@link BridgeRuntime#init}. */
	public static void init(BridgeRuntime bridge) {
		if (instance != null) {
			return;
		}
		MoveRuntime runtime = new MoveRuntime(bridge);
		instance = runtime;
		Dispatcher dispatcher = bridge.dispatcher();
		for (String type : new String[] {MsgTypes.SNAPSHOT, MsgTypes.PLAYER_POS, MsgTypes.PLAYER_BLOCKED, MsgTypes.PLAYER_STATE, MsgTypes.WARP,
				MsgTypes.WARP_TAKEN}) {
			dispatcher.on(type, message -> {
				// M9: while the save pairing waits, the hidden game may still run another world's save: its warps move
				// nobody in this world (no warp.request); its positions are only noted.
				if (message.type().equals(MsgTypes.WARP) && pairing()) {
					return;
				}
				runtime.controller.onBridgeMessage(message, runtime.clock.millis());
			});
		}
		ClientRelay.get().register(MsgTypes.WARP_DONE, message -> runtime.controller.onServerMessage(message, runtime.clock.millis()));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> runtime.controller.onJoin());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			runtime.controller.onLeave();
			InputLock.set(false);
			InputLock.setRoam(null);
			InputLock.setNoSneak(false);
			InputLock.setHoldOnLadder(false);
		});
		// A new link has no hold yet: a brake still held is sent again.
		dispatcher.onState((state, detail) -> runtime.brake.reset());
		ClientTickEvents.START_WORLD_TICK.register(runtime::beforeMove);
		ClientTickEvents.END_WORLD_TICK.register(runtime::afterMove);
		HudRenderCallback.EVENT.addPhaseOrdering(FADE_PHASE, Event.DEFAULT_PHASE);
		HudRenderCallback.EVENT.register(FADE_PHASE, (graphics, delta) -> runtime.renderFade(graphics));
		// While the save pairing holds the player, its own line says why.
		bridge.addHudLine(() -> pairing() ? null : runtime.controller.hint());
		// The pause overlay (M10) shows only while the player follows the hidden game in a Kanto world.
		bridge.setFollowing(MoveRuntime::following);
		// MG4: another hidden game is followed: relink from its hidden player's cell (one step at most for a resume; the
		// one accepted teleport for a game just started, MULTI_GAME_PLAN §4.7).
		Links links = Links.get();
		if (links != null) {
			links.onFollow((link, teleport) -> runtime.controller.relink(placeOf(link.state()), teleport));
		}
		bridge.addDebugLine(runtime::debugSteps);
		bridge.addDebugLine(runtime::debugRide);
		bridge.addDebugLine(runtime::debugFixes);
	}

	/**
	 * True while the save pairing holds the player (M9): the hidden game isn't on this world's save yet, or its fresh
	 * snapshot after a switch hasn't come. Its player then isn't followed: no steps, no teleports to where it was.
	 */
	private static boolean pairing() {
		SessionRuntime session = SessionRuntime.get();
		SessionGate gate = session == null ? null : session.gate();
		return gate != null && gate.holdsPlayer();
	}

	/** A link's hidden player as a place, or null while it has no position yet. */
	private static @Nullable Place placeOf(ClientBridgeState state) {
		if (!state.hasPosition() || state.map() == null) {
			return null;
		}
		Dir facing = Dir.byId(state.facing());
		return new Place(state.map(), state.x(), state.y(), facing == null ? Dir.DOWN : facing);
	}

	/** BIKE_PLAN B5: when the local bike last bumped into something (the bridge clock's millis), or Long.MIN_VALUE. */
	public static long bumpAt() {
		MoveRuntime runtime = instance;
		return runtime == null ? Long.MIN_VALUE : runtime.controller.bumpAt();
	}

	/** BIKE_PLAN B2: the ride's controller state for the dev pilot (null before init). */
	public static @Nullable MoveController controller() {
		MoveRuntime runtime = instance;
		return runtime == null ? null : runtime.controller;
	}

	/** The movement sync's counts (the dev client's autopilot logs them), or null before init. */
	public static MovementSync.@Nullable Stats stats() {
		MoveRuntime runtime = instance;
		return runtime == null ? null : runtime.controller.sync().stats();
	}

	/** The movement sync (the dev client's autopilot traces its climbs), or null before init. */
	public static @Nullable MovementSync sync() {
		MoveRuntime runtime = instance;
		return runtime == null ? null : runtime.controller.sync();
	}

	/** True while the movement sync follows the hidden game for this player (a Kanto world, not a free camera). */
	public static boolean following() {
		MoveRuntime runtime = instance;
		return runtime != null && runtime.controller.following();
	}

	private void beforeMove(ClientLevel level) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null || player.level() != level) {
			InputLock.set(false);
			InputLock.setRoam(null);
			InputLock.setNoSneak(false);
			InputLock.setHoldOnLadder(false);
			return;
		}
		// BIKE_PLAN B2: on our bike the seat is what moves (the rider follows it)
		Entity seat = seat(player);
		Entity body = seat != null ? seat : player;
		MoveController.Frame frame = new MoveController.Frame(ClientWorldIndex.current(), player.isSpectator() || player.isCreative(),
				this.bridge.client().state() == ConnectionState.CONNECTED, this.bridge.state().ingame() && !pairing(), this.bridge.state().mode(),
				InputLock.screenHolds(minecraft.screen), ClientBattles.active(), ClientBattles.minimised(), body.getX(), body.getZ());
		ForcedMover.Velocity velocity = this.controller.beforeMove(this.clock.millis(), frame);
		Options options = minecraft.options;
		boolean b = options.keyShift.isDown() || options.keyJump.isDown();
		this.controller.rideTick(this.bridge.state().bike(), new MoveController.RideKeys(options.keyUp.isDown(), options.keyDown.isDown(),
				options.keyLeft.isDown(), options.keyRight.isDown(), player.getYRot(), b));
		if (velocity != null) {
			// Keep the vertical speed, so gravity, landing and step-up work as when walking.
			body.setDeltaMovement(velocity.x(), body.getDeltaMovement().y, velocity.z());
		} else if (seat != null && this.controller.reason() == InputLock.Reason.NONE && !this.controller.rideOn()) {
			// D8: free mode (out in a zone): the rider's keys move the bike like a boat at its kind's base speed
			body.setDeltaMovement(free(player, seat), body.getDeltaMovement().y, freeZ);
		} else if (seat != null && velocity == null && !this.controller.mover().active()) {
			// standing: no drift
			body.setDeltaMovement(0.0, body.getDeltaMovement().y, 0.0);
		}
		if (seat != null && this.controller.sync().riding() && !this.controller.mover().active()) {
			// standing: the bike faces the way the hidden player does (a turn in place, a turn jump)
			Dir facing = Dir.byId(this.bridge.state().facing());
			if (facing != null) {
				seat.setYRot(facing.yaw());
			}
		}
		if (seat != null) {
			// D6: a hop on the step (a bunny hop on, a side jump, a wheelie's ledge hop) lifts the seat; gravity lands it
			String hop = this.controller.takeHop();
			if (hop != null) {
				body.setDeltaMovement(body.getDeltaMovement().x, RideHop.velocity(hop), body.getDeltaMovement().z);
			}
		}
		// BATTLE_ROAM_PLAN: a minimised battle's player walks, fenced (never on our bike: its seat stays held)
		BattleRoam.Anchor anchor = this.controller.roamAnchor();
		boolean roaming = this.controller.reason() == InputLock.Reason.ROAM && anchor != null && seat == null;
		InputLock.setRoam(roaming ? new InputLock.Roam(anchor, body.getX(), body.getZ(), player.getYRot()) : null);
		InputLock.set(this.controller.reason() != InputLock.Reason.NONE && !roaming);
		InputLock.setHoldOnLadder(this.controller.sync().takeHolding() && player.onClimbable());
		this.brake(minecraft);
	}

	/** The local player's seat when it rides our bike, else null. */
	private static @Nullable Entity seat(LocalPlayer player) {
		Entity vehicle = player.getVehicle();
		return vehicle instanceof BikeEntity ? vehicle : null;
	}

	private double freeZ;

	/** Free mode's velocity (x returned, z in {@link #freeZ}) from the rider's own impulses and yaw. */
	private double free(LocalPlayer player, Entity seat) {
		double speed = RideHop.freeSpeed(seat instanceof BikeEntity bike ? bike.kind() : BikeEntity.Kind.BIKE);
		float fwd = player.input.forwardImpulse;
		float side = player.input.leftImpulse;
		double radians = Math.toRadians(player.getYRot());
		double sin = Math.sin(radians);
		double cos = Math.cos(radians);
		double length = Math.max(1.0, Math.hypot(fwd, side));
		double x = (side * cos - fwd * sin) / length * speed;
		this.freeZ = (fwd * cos + side * sin) / length * speed;
		return x;
	}

	/** Cycling Road: sneak holds B while the hidden player is on a slope map (and never crouches there). */
	private void brake(Minecraft minecraft) {
		boolean slope = this.controller.following() && this.bridge.state().slope();
		// BIKE_PLAN D4: sneak is B on the bike (move.ride), never a crouch
		boolean riding = this.controller.sync().riding();
		InputLock.setNoSneak(slope || riding);
		boolean connected = this.bridge.client().state() == ConnectionState.CONNECTED;
		// The key itself: the input lock zeroes the player's sneak during the roll's forced steps.
		// FA8 item 7 (D4): the sprint key runs where the hidden game runs (Gen 3: B held on foot; the game itself says where
		// it may, and player.state.run gives the speed)
		HelloOk hello = this.bridge.state().handshake();
		boolean runs = hello != null && "gen3".equals(hello.family());
		boolean run = runs && this.controller.following() && !slope && !this.bridge.state().bike() && !this.bridge.state().surf()
				&& minecraft.options.keySprint.isDown();
		// BIKE_PLAN B2: on the bike B travels in move.ride; the brake's own hold is let go of
		Boolean hold = this.brake.tick(this.controller.following() && !riding, connected, slope, minecraft.options.keyShift.isDown(), run);
		if (hold != null) {
			JsonObject data = new JsonObject();
			data.addProperty("button", "b");
			data.addProperty("on", hold);
			if (!this.bridge.client().send(MsgTypes.MOVE_HOLD, data)) {
				this.brake.reset();
			}
		}
	}

	private void afterMove(ClientLevel level) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || player.level() != level) {
			return;
		}
		Dir intent = Intent.of(player.input.leftImpulse, player.input.forwardImpulse, player.getYRot());
		// BIKE_PLAN B2: on our bike, the seat's feet (the rider sits above them; a deck's floors overlap, WB4)
		Entity seat = seat(player);
		Entity body = seat != null ? seat : player;
		this.controller.afterMove(this.clock.millis(), body.getX(), body.getY(), body.getZ(), intent, player.getYRot());
	}

	private void renderFade(GuiGraphics graphics) {
		WarpFade fade = this.controller.fade();
		float alpha = fade.alpha(this.clock.millis());
		if (alpha <= 0.0F) {
			return;
		}
		int argb = Math.round(alpha * 255.0F) << 24 | fade.colour();
		// guiOverlay has no depth test, so it covers the hotbar's items too.
		graphics.fill(RenderType.guiOverlay(), 0, 0, graphics.guiWidth(), graphics.guiHeight(), argb);
	}

	private String debugSteps() {
		MovementSync sync = this.controller.sync();
		MovementSync.Stats stats = sync.stats();
		return I18n.get("kantobridge.debug.move", this.controller.reason().name().toLowerCase(Locale.ROOT), stats.steps, stats.pushes,
				stats.faces, sync.pendingSteps(), stats.lost);
	}

	/** BIKE_PLAN B2: the ride line (kind, gear, trick, held dir/b, legs queued, bumps, rides sent), or null off the bike. */
	private @Nullable String debugRide() {
		if (!this.bridge.state().bike()) {
			return null;
		}
		dev.kantobridge.story.StoryRules rules = dev.kantobridge.client.story.ClientStoryView.followedRules();
		RideInput.Ride held = this.controller.ride().sent();
		return I18n.get("kantobridge.debug.ride", rules == null ? "?" : rules.bikeKind(), rules == null ? 0 : rules.gear(),
				rules == null ? "none" : rules.trick(), held.dir() == null ? "-" : held.dir().id(), held.b() ? "+b" : "",
				this.controller.mover().queued(), this.controller.bumps(), this.controller.ridesSent());
	}

	private String debugFixes() {
		MovementSync.Stats stats = this.controller.sync().stats();
		WarpFade fade = this.controller.fade();
		return I18n.get("kantobridge.debug.fixes", stats.forced, stats.blocked, stats.snaps, stats.drifts, stats.teleports,
				fade.phase().name().toLowerCase(Locale.ROOT), fade.warps(), fade.warnings());
	}

	/** The controller's links: gen1-bridge, the server relay, the level renderer and the log. */
	private final class Port implements MoveController.Port {
		@Override
		public boolean toBridge(String type, JsonObject data) {
			return MoveRuntime.this.bridge.client().send(type, data);
		}

		@Override
		public long toBridgeTracked(String type, JsonObject data) {
			return MoveRuntime.this.bridge.client().sendTracked(type, data);
		}

		@Override
		public long toServer(String type, JsonObject data) {
			return ClientRelay.get().post(type, data, null);
		}

		@Override
		public boolean destinationReady(@Nullable Vec3 target) {
			Minecraft minecraft = Minecraft.getInstance();
			LocalPlayer player = minecraft.player;
			if (player == null || minecraft.level == null) {
				return false;
			}
			if (target != null && (Math.abs(player.getX() - target.x) > 1.0 || Math.abs(player.getZ() - target.z) > 1.0
					|| Math.abs(player.getY() - target.y) > 3.0)) {
				// The server's position packet hasn't been applied yet.
				return false;
			}
			// The feet section and the floor's, which differ on the surface (feet at Y 64, ground at 63).
			BlockPos feet = player.blockPosition();
			return minecraft.levelRenderer.isSectionCompiled(feet) && minecraft.levelRenderer.isSectionCompiled(feet.below());
		}

		@Override
		public void warn(String area, String code, long count) {
			LOGGER.warn("{}: {} ({} so far)", area, code, count);
		}
	}
}
