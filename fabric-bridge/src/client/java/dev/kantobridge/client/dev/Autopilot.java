package dev.kantobridge.client.dev;

import dev.kantobridge.KantoBridge;
import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.ExportMap;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.bridge.ClientBridgeState;
import dev.kantobridge.client.bridge.ConnectionState;
import dev.kantobridge.client.move.InputLock;
import dev.kantobridge.client.move.MoveRuntime;
import dev.kantobridge.client.move.MovementSync;
import dev.kantobridge.client.world.ClientWorldIndex;
import dev.kantobridge.client.world.CreateKantoWorldFlow;
import dev.kantobridge.client.zone.ZoneView;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.Scale;
import dev.kantobridge.world.WorldIndex;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The dev client's autopilot, for recorded showcases and Wild Routes check C5 (docs/specs/WILD_ROUTES_PLAN.md §8): with
 * {@code -Dkantobridge.autopilot=wild_zone} (the dev client: {@code ./gradlew runClient -Pkb.autopilot=wild_zone}, see
 * build.gradle) the client plays one short scene by itself.
 *
 * <ol>
 * <li>From the title screen, once gen1-bridge is connected, it opens the world {@code kantobridge.autopilot.world} when
 * that exists, else runs "Create Kanto world" (whose export waits two minutes for gen1-bridge's index: on a slow disk,
 * warm the export first).</li>
 * <li>It waits until the player stands on the hidden player's cell (a test driver put the hidden player on a zone's
 * clearing, facing its trees) and the view has loaded, then starts recording when {@code kantobridge.autopilot.record}
 * names an .mp4 file: ffmpeg grabs the window's area of the X display.</li>
 * <li>Third-person camera, no HUD: it walks forward through the clearing until the warp has taken it to the zone's
 * trailhead, then walks the maze to the farthest lane within {@value #WALK_STEPS} block steps, keeping out of the
 * trainers' sight, the balls, the dungeon entrance and the trailhead's way home, and turns the camera once around.</li>
 * <li>It stops recording and, with {@code kantobridge.autopilot.quit=true}, quits the game.</li>
 * </ol>
 * Every step is logged ({@code autopilot: <event>} with map ids, cells, counts and times) and appended to the file
 * {@code kantobridge.autopilot.log} names. Nothing runs without the property. Client thread only.
 *
 * <p>Scene {@code tour} (the caves and woods plan's CW5 video, docs/specs/CAVES_AND_WOODS_PLAN.md §6): a test driver
 * (tools/e2e/drivers/kb_tour.lua) warps the hidden player from stop to stop; at each one the autopilot looks round in
 * first person, tilted up at the roof or the canopy, recording each look round into its own file (the record file
 * with {@code -stop<n>}; {@code kantobridge.autopilot.stops}, default 2).
 *
 * <p>WORLD_SESSIONS_PLAN WS0 (the user's save/reload bug in a wild route): with {@code kantobridge.autopilot.rejoin}
 * ({@code reconnect} or {@code relaunch}) the wild_zone scene, once in the zone, walks a few lanes, stands while the test
 * driver saves the hidden game on the route map, leaves the world as the pause menu's "Save and Quit to Title" does, and
 * opens it again: with {@code relaunch} only after the link dropped and came back (tools/e2e/showcase.py relaunches
 * Gen1Recomp, which CONTINUEs the save). Then it watches for {@value #REJOIN_WATCH_SECONDS} seconds and walks
 * forward, logging the body's block, whether it is in the zone, {@code away}, the lock's reason, the hidden player's map
 * and cell, the movement counters (syncs are the join's teleports) and every jump of the body.
 *
 * <p>Scene {@code remote} (MG8's story video): {@link RemotePilot}, the client steered by a script outside it.
 *
 * <p>Scene {@code pilot} (the caves in 3D plan's H6 video, docs/specs/CAVES_3D_PLAN.md): it plans two shots from the
 * world index and the export's cells ({@link PilotPlan}): up a cave's stairs to its raised ground and on to the cliff's
 * edge, then down a ladder's shaft and back up. For each it asks the test driver (tools/e2e/drivers/kb_pilot.lua) to
 * put the hidden player on the shot's first cell, through the files in {@code kantobridge.autopilot.pilotDir}, and
 * records the shot in first person into its own file (as the tour's stops). With {@code kantobridge.autopilot.shots}
 * (H7's video) it films the shots that names instead, {@code stairs:<map>} or {@code ladder:<map>}, comma-separated.
 */
public final class Autopilot {
	static final String PROPERTY = "kantobridge.autopilot";
	static final String SCENE_WILD_ZONE = "wild_zone";
	static final String SCENE_TOUR = "tour";
	static final String SCENE_PILOT = "pilot";
	/** The pilot's pitches: looking where it walks, down over the edge, down the shaft and up it. */
	static final float PILOT_WALK_PITCH = 14f;
	static final float PILOT_EDGE_PITCH = 55f;
	static final float PILOT_DOWN_PITCH = 62f;
	static final float PILOT_UP_PITCH = -58f;
	static final float PILOT_SWAY = 40f;
	static final double PILOT_REACHED = 0.3;
	/** On the ladder: the player's middle this close to its wall, the body clear of the floor kept beside it. */
	static final double PILOT_ON_LADDER = 0.62;
	/** A tour's look round at a stop, in seconds, and its tilt: looking up by this much, swaying by that much. */
	static final float TOUR_PAN_SECONDS = 16f;
	static final float TOUR_PITCH = -22f;
	static final float TOUR_SWAY = 16f;
	static final int WALK_STEPS = 220;
	static final double HALF_WIDTH = 0.35;
	static final float TURN_PER_TICK = 6f;
	static final float CAMERA_PITCH = 22f;
	static final float INTRO_SWING = 60f;
	static final int TICKS = 20;
	static final int MAX_REPLANS = 3;
	/** WS0: seconds watched after the rejoin, the walk's ticks before and after it, and a body jump worth logging. */
	static final int REJOIN_WATCH_SECONDS = 20;
	static final int REJOIN_WALK_TICKS = 4 * 20;
	static final double REJOIN_JUMP = 3.0;

	private enum Phase {
		TITLE,
		WORLD,
		LINK,
		SETTLE,
		INTRO,
		WALK_IN,
		ARRIVE,
		WALK,
		PAN,
		OUTRO,
		STOP,
		DONE,
		FAILED,
		TOUR_SETTLE,
		TOUR_PAN,
		TOUR_WAIT,
		PILOT_PLAN,
		PILOT_PLACE,
		PILOT_SETTLE,
		PILOT_STAIRS,
		PILOT_EDGE,
		PILOT_LADDER,
		REJOIN_SAVE,
		REJOIN_LEAVE,
		REJOIN_TITLE,
		REJOIN_WORLD,
		REJOIN_WATCH,
		REJOIN_WALK
	}

	private final Minecraft minecraft;
	private final @Nullable String world;
	private final @Nullable Path record;
	private final @Nullable Path log;
	private final boolean quit;
	private final boolean tour;
	private final int stops;
	private int stopsSeen;
	private final boolean pilot;
	private final @Nullable Path pilotDir;
	private final @Nullable String shotList;
	/** WS0: null, "reconnect" or "relaunch". */
	private final @Nullable String rejoin;
	private @Nullable String levelId;
	private boolean linkDropped;
	private double rejoinX;
	private double rejoinZ;
	private long syncsBefore;
	private final List<PilotPlan.Shot> shots = new ArrayList<>();
	private int shot;
	private int requestSeq;
	private PilotPlan.@Nullable Stairs stairs;
	private PilotPlan.@Nullable Ladder ladder;
	private int option;
	private int step;
	private int stepTicks;

	private Phase phase = Phase.TITLE;
	private int phaseTicks;
	private int stableTicks;
	private boolean optionsSet;
	private @Nullable String startMap;
	private float walkYaw;
	private float panFrom;
	private @Nullable WorldIndex.Zone zone;
	private List<double[]> waypoints = List.of();
	private int waypoint;
	private double lastX;
	private double lastZ;
	private int stuckTicks;
	private int replans;
	private @Nullable Process ffmpeg;
	private @Nullable Path recording;
	private long recordStartNanos;
	private int fpsSamples;
	private long fpsTotal;
	private int fpsMin = Integer.MAX_VALUE;

	private Autopilot(Minecraft minecraft, @Nullable String world, @Nullable Path record, @Nullable Path log, boolean quit, boolean tour,
			int stops, boolean pilot, @Nullable Path pilotDir, @Nullable String shotList, @Nullable String rejoin) {
		this.minecraft = minecraft;
		this.world = world;
		this.record = record;
		this.log = log;
		this.quit = quit;
		this.tour = tour;
		this.stops = stops;
		this.pilot = pilot;
		this.pilotDir = pilotDir;
		this.shotList = shotList;
		this.rejoin = rejoin;
	}

	/** Call once from client init; does nothing without {@value #PROPERTY}. */
	public static void init() {
		String scene = System.getProperty(PROPERTY);
		if (scene == null || scene.isBlank()) {
			return;
		}
		if (RemotePilot.SCENE.equals(scene)) {
			// MG8's story video: a script outside the client says what to do (RemotePilot)
			String dir = System.getProperty(PROPERTY + ".pilotDir");
			String log = System.getProperty(PROPERTY + ".log");
			RemotePilot.init(Minecraft.getInstance(), System.getProperty(PROPERTY + ".world"), dir == null || dir.isBlank() ? null : Path.of(dir),
					log == null || log.isBlank() ? null : Path.of(log), Boolean.getBoolean(PROPERTY + ".quit"), System.getProperty(PROPERTY + ".follow"));
			return;
		}
		if (!SCENE_WILD_ZONE.equals(scene) && !SCENE_TOUR.equals(scene) && !SCENE_PILOT.equals(scene)) {
			KantoBridge.LOGGER.warn("autopilot: unknown scene");
			return;
		}
		String world = System.getProperty(PROPERTY + ".world");
		String record = System.getProperty(PROPERTY + ".record");
		String log = System.getProperty(PROPERTY + ".log");
		String pilotDir = System.getProperty(PROPERTY + ".pilotDir");
		String shots = System.getProperty(PROPERTY + ".shots");
		String rejoin = System.getProperty(PROPERTY + ".rejoin");
		Autopilot pilot = new Autopilot(Minecraft.getInstance(), world == null || world.isBlank() ? null : world,
				record == null || record.isBlank() ? null : Path.of(record), log == null || log.isBlank() ? null : Path.of(log),
				Boolean.getBoolean(PROPERTY + ".quit"), SCENE_TOUR.equals(scene), Math.max(1, Integer.getInteger(PROPERTY + ".stops", 2)),
				SCENE_PILOT.equals(scene), pilotDir == null || pilotDir.isBlank() ? null : Path.of(pilotDir),
				shots == null || shots.isBlank() ? null : shots.trim(), rejoin == null || rejoin.isBlank() ? null : rejoin.trim());
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> pilot.tick());
		pilot.mark("armed scene=" + scene + " record=" + (pilot.record != null));
	}

	private void tick() {
		this.phaseTicks++;
		if (this.recordStartNanos != 0 && this.phaseTicks % TICKS == 0) {
			int fps = this.minecraft.getFps();
			this.fpsSamples++;
			this.fpsTotal += fps;
			this.fpsMin = Math.min(this.fpsMin, fps);
		}
		try {
			switch (this.phase) {
				case TITLE -> this.title();
				case WORLD -> this.world();
				case LINK -> this.link();
				case SETTLE -> this.settle();
				case INTRO -> this.intro();
				case WALK_IN -> this.walkIn();
				case ARRIVE -> this.arrive();
				case WALK -> this.walk();
				case PAN -> this.pan();
				case OUTRO -> this.outro();
				case STOP -> this.stop();
				case TOUR_SETTLE -> this.tourSettle();
				case TOUR_PAN -> this.tourPan();
				case TOUR_WAIT -> this.tourWait();
				case PILOT_PLAN -> this.pilotPlan();
				case PILOT_PLACE -> this.pilotPlace();
				case PILOT_SETTLE -> this.pilotSettle();
				case PILOT_STAIRS -> this.pilotStairs();
				case PILOT_EDGE -> this.pilotEdge();
				case PILOT_LADDER -> this.pilotLadder();
				case REJOIN_SAVE -> this.rejoinSave();
				case REJOIN_LEAVE -> this.rejoinLeave();
				case REJOIN_TITLE -> this.rejoinTitle();
				case REJOIN_WORLD -> this.rejoinWorld();
				case REJOIN_WATCH -> this.rejoinWatch();
				case REJOIN_WALK -> this.rejoinWalk();
				case DONE, FAILED -> {
				}
			}
		} catch (RuntimeException e) {
			this.fail("error " + e.getClass().getSimpleName());
		}
	}

	// ---- phases ----

	private void title() {
		if (!(this.minecraft.screen instanceof TitleScreen)) {
			this.limit(10 * 60, "title");
			return;
		}
		if (!this.optionsSet) {
			this.options();
		}
		BridgeRuntime bridge = BridgeRuntime.get();
		boolean connected = bridge != null && bridge.client().state() == ConnectionState.CONNECTED;
		this.stableTicks = connected ? this.stableTicks + 1 : 0;
		if (this.stableTicks < 2 * TICKS) {
			this.limit(10 * 60, "bridge");
			return;
		}
		if (this.world != null && this.minecraft.getLevelSource().levelExists(this.world)) {
			this.mark("open_world");
			this.minecraft.createWorldOpenFlows().openWorld(this.world, () -> this.minecraft.setScreen(new TitleScreen()));
		} else {
			this.mark("create_world");
			CreateKantoWorldFlow.start(this.minecraft);
		}
		this.next(Phase.WORLD);
	}

	private void world() {
		if (this.rejoin != null && this.minecraft.level != null && this.dismissNotice()) {
			return;
		}
		CreateKantoWorldFlow flow = CreateKantoWorldFlow.active();
		if (flow != null && flow.step() == CreateKantoWorldFlow.Step.FAILED) {
			this.fail("create_failed");
			return;
		}
		boolean in = this.minecraft.level != null && this.minecraft.player != null && this.minecraft.screen == null
				&& ClientWorldIndex.current() != null && (flow == null || flow.step() == CreateKantoWorldFlow.Step.DONE);
		if (in) {
			this.mark("in_world ticks=" + this.phaseTicks);
			this.next(Phase.LINK);
		} else {
			this.limit(60 * 60, "world");
		}
	}

	/** The player stands on the hidden player's cell, linked and free to move, for two seconds. */
	private void link() {
		LocalPlayer player = this.minecraft.player;
		WorldIndex index = ClientWorldIndex.current();
		ClientBridgeState state = this.state();
		boolean ok = player != null && index != null && state != null && state.ingame() && state.map() != null
				&& !player.isSpectator() && !InputLock.locked() && this.minecraft.screen == null
				&& this.onHiddenCell(player, index, state);
		this.stableTicks = ok ? this.stableTicks + 1 : 0;
		if (this.stableTicks >= 2 * TICKS) {
			this.startMap = state.map();
			this.walkYaw = yawOf(state.facing());
			this.mark("linked map=" + state.map() + " x=" + state.x() + " y=" + state.y() + " facing=" + state.facing());
			this.next(this.pilot ? Phase.PILOT_PLAN : this.tour ? Phase.TOUR_SETTLE : Phase.SETTLE);
		} else {
			this.limit(5 * 60, "link");
		}
	}

	/** Camera behind the player, no HUD, a few seconds for the view to load; then the recording starts. */
	private void settle() {
		LocalPlayer player = this.minecraft.player;
		if (player == null) {
			this.fail("no_player");
			return;
		}
		if (this.phaseTicks == 1) {
			this.minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			this.minecraft.options.hideGui = true;
		}
		this.look(player, this.walkYaw + INTRO_SWING, CAMERA_PITCH);
		this.minecraft.getToasts().clear();
		if (this.phaseTicks >= 8 * TICKS) {
			this.startRecording();
			this.next(Phase.INTRO);
		}
	}

	/** Five seconds in town, the camera swinging round to face the clearing. */
	private void intro() {
		LocalPlayer player = this.minecraft.player;
		if (player == null) {
			this.fail("no_player");
			return;
		}
		float t = Math.min(1f, this.phaseTicks / (4.5f * TICKS));
		this.look(player, this.walkYaw + INTRO_SWING * (1f - smoothstep(t)), CAMERA_PITCH);
		if (this.phaseTicks >= 5 * TICKS) {
			this.mark("walk_in");
			this.next(Phase.WALK_IN);
		}
	}

	/** Forward through the clearing until the hidden player has left the town (the warp starts). */
	private void walkIn() {
		LocalPlayer player = this.minecraft.player;
		ClientBridgeState state = this.state();
		if (player == null || state == null) {
			this.fail("no_player");
			return;
		}
		this.look(player, this.walkYaw, CAMERA_PITCH);
		boolean left = state.map() != null && !state.map().equals(this.startMap);
		if (left || (InputLock.locked() && this.phaseTicks > 10)) {
			this.forward(false);
			this.mark("warp map=" + state.map() + " ticks=" + this.phaseTicks);
			this.next(Phase.ARRIVE);
			return;
		}
		this.forward(true);
		this.limit(25, "walk_in");
	}

	/** In the zone's box, the fade over and the player free again; then a walk is planned. */
	private void arrive() {
		LocalPlayer player = this.minecraft.player;
		WorldIndex index = ClientWorldIndex.current();
		if (player == null || index == null) {
			this.fail("no_player");
			return;
		}
		this.look(player, this.walkYaw, CAMERA_PITCH);
		WorldIndex.Zone here = ZoneView.zoneAt(index, player.getX(), player.getZ());
		boolean ok = here != null && !InputLock.locked() && this.minecraft.screen == null;
		this.stableTicks = ok ? this.stableTicks + 1 : 0;
		if (this.stableTicks < 30) {
			this.limit(40, "arrive");
			return;
		}
		this.zone = here;
		int[] cell = ZoneView.cellAt(index, here, player.getX(), player.getZ());
		this.mark("arrived zone=" + here.id() + (cell == null ? "" : " cx=" + cell[0] + " cy=" + cell[1]));
		this.plan(index, here, player);
		this.next(Phase.WALK);
	}

	private void walk() {
		LocalPlayer player = this.minecraft.player;
		if (player == null) {
			this.fail("no_player");
			return;
		}
		if (this.waypoint >= this.waypoints.size() || this.phaseTicks > 75 * TICKS
				|| (this.rejoin != null && this.phaseTicks > 12 * TICKS)) {
			this.forward(false);
			this.mark("walked waypoints=" + this.waypoint + "/" + this.waypoints.size() + " ticks=" + this.phaseTicks);
			this.panFrom = player.getYRot();
			this.next(this.rejoin != null ? Phase.REJOIN_SAVE : Phase.PAN);
			return;
		}
		double[] target = this.waypoints.get(this.waypoint);
		double dx = target[0] - player.getX();
		double dz = target[1] - player.getZ();
		if (dx * dx + dz * dz < 0.45 * 0.45) {
			this.waypoint++;
			return;
		}
		float want = (float) (Mth.atan2(-dx, dz) * Mth.RAD_TO_DEG);
		float yaw = approach(player.getYRot(), want, TURN_PER_TICK);
		this.look(player, yaw, CAMERA_PITCH);
		this.forward(Math.abs(Mth.wrapDegrees(want - yaw)) < 50f && !InputLock.locked());
		// stuck: say what's in the way, then plan again from here (a few times)
		double moved = Math.hypot(player.getX() - this.lastX, player.getZ() - this.lastZ);
		this.lastX = player.getX();
		this.lastZ = player.getZ();
		this.stuckTicks = moved < 0.01 ? this.stuckTicks + 1 : 0;
		if (this.stuckTicks > 30) {
			this.stuckTicks = 0;
			this.stuck(player, target);
			WorldIndex index = ClientWorldIndex.current();
			if (++this.replans > MAX_REPLANS || index == null || this.zone == null) {
				this.waypoint = this.waypoints.size();
			} else {
				this.plan(index, this.zone, player);
			}
		}
	}

	/** What holds the player: the lock, a screen, the blocks one step ahead, the entities close by (ids and counts). */
	private void stuck(LocalPlayer player, double[] target) {
		ClientLevel level = this.minecraft.level;
		WorldIndex index = ClientWorldIndex.current();
		StringBuilder line = new StringBuilder("stuck waypoint=").append(this.waypoint).append('/').append(this.waypoints.size());
		line.append(" locked=").append(InputLock.locked());
		line.append(" screen=").append(this.minecraft.screen == null ? "none" : this.minecraft.screen.getClass().getSimpleName());
		if (index != null && this.zone != null) {
			int[] cell = ZoneView.cellAt(index, this.zone, player.getX(), player.getZ());
			if (cell != null) {
				line.append(" cx=").append(cell[0]).append(" cy=").append(cell[1]);
			}
		}
		if (level != null) {
			double dx = target[0] - player.getX();
			double dz = target[1] - player.getZ();
			double norm = Math.max(1e-6, Math.hypot(dx, dz));
			BlockPos ahead = BlockPos.containing(player.getX() + dx / norm * 0.8, player.getY() + 0.5, player.getZ() + dz / norm * 0.8);
			line.append(" ahead=").append(BuiltInRegistries.BLOCK.getKey(level.getBlockState(ahead).getBlock()).getPath());
			line.append('/').append(BuiltInRegistries.BLOCK.getKey(level.getBlockState(ahead.above()).getBlock()).getPath());
			line.append('/').append(BuiltInRegistries.BLOCK.getKey(level.getBlockState(ahead.below()).getBlock()).getPath());
			List<String> near = new ArrayList<>();
			for (Entity e : level.getEntities(player, player.getBoundingBox().inflate(1.5))) {
				near.add(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath());
			}
			line.append(" near=").append(near.isEmpty() ? "-" : String.join(",", near));
		}
		line.append(String.format(Locale.ROOT, " y=%.2f fps=%d", player.getY(), this.minecraft.getFps()));
		this.mark(line.toString());
	}

	/** The camera once around the player, over eight seconds. */
	private void pan() {
		LocalPlayer player = this.minecraft.player;
		if (player == null) {
			this.fail("no_player");
			return;
		}
		float t = Math.min(1f, this.phaseTicks / (8f * TICKS));
		this.look(player, this.panFrom + 360f * smoothstep(t), CAMERA_PITCH);
		if (t >= 1f) {
			this.next(Phase.OUTRO);
		}
	}

	// ---- the tour ----

	/** First person, no HUD, facing the stop's way, a few seconds for the view to load; the recording starts at stop one. */
	private void tourSettle() {
		LocalPlayer player = this.minecraft.player;
		if (player == null) {
			this.fail("no_player");
			return;
		}
		if (this.phaseTicks == 1) {
			this.minecraft.options.setCameraType(CameraType.FIRST_PERSON);
			this.minecraft.options.hideGui = true;
		}
		this.look(player, this.walkYaw, TOUR_PITCH);
		this.minecraft.getToasts().clear();
		if (this.phaseTicks >= 6 * TICKS) {
			this.startRecording(this.part(this.stopsSeen + 1));
			this.panFrom = this.walkYaw;
			this.next(Phase.TOUR_PAN);
		}
	}

	/** Once round, tilted up and swaying, so the roof (or the canopy) and the ground both show. */
	private void tourPan() {
		LocalPlayer player = this.minecraft.player;
		ClientBridgeState state = this.state();
		if (player == null || state == null) {
			this.fail("no_player");
			return;
		}
		float t = Math.min(1f, this.phaseTicks / (TOUR_PAN_SECONDS * TICKS));
		float sway = (float) Math.sin(2 * Math.PI * t) * TOUR_SWAY;
		this.look(player, this.panFrom + 360f * smoothstep(t), TOUR_PITCH + sway);
		if (t >= 1f) {
			this.stopsSeen++;
			this.stopRecording();
			this.mark("toured stop=" + this.stopsSeen + " map=" + state.map());
			this.startMap = state.map();
			this.next(this.stopsSeen >= this.stops ? Phase.OUTRO : Phase.TOUR_WAIT);
		}
	}

	/** Stop {@code n}'s recording: the record file with {@code -stop<n>} before its extension (null without a file). */
	private @Nullable Path part(int n) {
		if (this.record == null) {
			return null;
		}
		String name = this.record.getFileName().toString();
		int dot = name.lastIndexOf('.');
		return this.record.resolveSibling((dot < 0 ? name : name.substring(0, dot)) + "-stop" + n + (dot < 0 ? "" : name.substring(dot)));
	}

	/** Until the driver's next warp has put the player on a new map's cell, linked and free, for two seconds. */
	private void tourWait() {
		LocalPlayer player = this.minecraft.player;
		WorldIndex index = ClientWorldIndex.current();
		ClientBridgeState state = this.state();
		if (player != null) {
			this.look(player, player.getYRot(), TOUR_PITCH);
		}
		boolean ok = player != null && index != null && state != null && state.ingame() && state.map() != null
				&& !state.map().equals(this.startMap) && !InputLock.locked() && this.minecraft.screen == null
				&& this.onHiddenCell(player, index, state);
		this.stableTicks = ok ? this.stableTicks + 1 : 0;
		if (this.stableTicks >= 2 * TICKS) {
			this.walkYaw = yawOf(state.facing());
			this.mark("next map=" + state.map() + " x=" + state.x() + " y=" + state.y() + " facing=" + state.facing());
			this.next(Phase.TOUR_SETTLE);
		} else {
			this.limit(3 * 60, "tour_wait");
		}
	}

	private void outro() {
		if (this.phaseTicks >= 2 * TICKS) {
			this.next(Phase.STOP);
		}
	}

	private void stop() {
		this.stopRecording();
		this.minecraft.options.hideGui = false;
		this.mark("done");
		this.next(Phase.DONE);
		if (this.quit) {
			this.minecraft.stop();
		}
	}


	// ---- WS0: save in the zone, leave, rejoin ----

	/** A warning notice (an AlertScreen: a save mismatch, another game) is acknowledged as a player would; true when one was. */
	private boolean dismissNotice() {
		if (this.minecraft.screen instanceof net.minecraft.client.gui.screens.AlertScreen) {
			this.mark("notice_dismissed");
			this.minecraft.setScreen(null);
			return true;
		}
		return false;
	}

	/** The body, the zone, away, the lock, the hidden player and the counters, as one line (ids, cells and counts). */
	private String where() {
		LocalPlayer player = this.minecraft.player;
		WorldIndex index = ClientWorldIndex.current();
		MovementSync sync = MoveRuntime.sync();
		var controller = MoveRuntime.controller();
		ClientBridgeState state = this.state();
		StringBuilder line = new StringBuilder();
		if (player != null) {
			line.append(String.format(Locale.ROOT, "body=%d,%d,%d", player.getBlockX(), player.getBlockY(), player.getBlockZ()));
			WorldIndex.Zone zone = index == null ? null : ZoneView.zoneAt(index, player.getX(), player.getZ());
			line.append(" zone=").append(zone == null ? "-" : zone.id());
			if (index != null && zone != null) {
				int[] cell = ZoneView.cellAt(index, zone, player.getX(), player.getZ());
				if (cell != null) {
					line.append(" zcell=").append(cell[0]).append(',').append(cell[1]);
				}
			}
		} else {
			line.append("body=-");
		}
		line.append(" away=").append(sync != null && sync.away());
		line.append(" lock=").append(controller == null ? "-" : controller.reason().name());
		line.append(" unbuilt=").append(sync != null && sync.hiddenUnbuilt());
		var hidden = sync == null ? null : sync.hidden();
		line.append(" hidden=").append(hidden == null ? "-" : hidden.map() + "," + hidden.x() + "," + hidden.y() + "," + hidden.facing());
		line.append(" state=").append(state == null ? "-" : state.map() + "," + state.x() + "," + state.y() + " ingame=" + state.ingame());
		MovementSync.Stats stats = MoveRuntime.stats();
		if (stats != null) {
			line.append(" syncs=").append(stats.syncs).append(" teleports=").append(stats.teleports).append(" steps=").append(stats.steps)
					.append(" faces=").append(stats.faces).append(" blocked=").append(stats.blocked).append(" zoneTicks=").append(stats.zoneTicks);
		}
		return line.toString();
	}

	/** Standing in the zone for eight seconds: the test driver saves the hidden game on the route map meanwhile. */
	private void rejoinSave() {
		if (this.phaseTicks == 1) {
			this.mark("rejoin_save " + this.where());
			var server = this.minecraft.getSingleplayerServer();
			if (server != null) {
				this.levelId = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize()
						.getFileName().toString();
			}
		}
		if (this.phaseTicks >= 8 * TICKS) {
			this.mark("rejoin_saved level=" + this.levelId + " " + this.where());
			this.next(Phase.REJOIN_LEAVE);
		}
	}

	/** "Save and Quit to Title", as the pause menu runs it (PauseScreen.onDisconnect). */
	private void rejoinLeave() {
		this.stopRecording();
		this.mark("left_world " + this.where());
		boolean local = this.minecraft.isLocalServer();
		if (this.minecraft.level != null) {
			this.minecraft.level.disconnect();
		}
		if (local) {
			this.minecraft.disconnect(new net.minecraft.client.gui.screens.GenericMessageScreen(
					net.minecraft.network.chat.Component.translatable("menu.savingLevel")));
		} else {
			this.minecraft.disconnect();
		}
		this.minecraft.setScreen(new TitleScreen());
		this.linkDropped = false;
		this.next(Phase.REJOIN_TITLE);
	}

	/** At the title: with relaunch, the link must drop and come back first; then the world opens again. */
	private void rejoinTitle() {
		BridgeRuntime bridge = BridgeRuntime.get();
		boolean connected = bridge != null && bridge.client().state() == ConnectionState.CONNECTED;
		if (!connected && !this.linkDropped) {
			this.linkDropped = true;
			this.mark("link_dropped ticks=" + this.phaseTicks);
		}
		boolean waitDrop = "relaunch".equals(this.rejoin) && !this.linkDropped;
		this.stableTicks = connected && !waitDrop && this.minecraft.screen instanceof TitleScreen ? this.stableTicks + 1 : 0;
		if (this.stableTicks < 3 * TICKS) {
			this.limit(10 * 60, "rejoin_title");
			return;
		}
		if (this.levelId == null || !this.minecraft.getLevelSource().levelExists(this.levelId)) {
			this.fail("no_level");
			return;
		}
		this.mark("reopen level=" + this.levelId + " ticks=" + this.phaseTicks);
		this.minecraft.createWorldOpenFlows().openWorld(this.levelId, () -> this.minecraft.setScreen(new TitleScreen()));
		this.next(Phase.REJOIN_WORLD);
	}

	private void rejoinWorld() {
		boolean in = this.minecraft.level != null && this.minecraft.player != null && ClientWorldIndex.current() != null;
		if (in) {
			LocalPlayer player = this.minecraft.player;
			this.rejoinX = player.getX();
			this.rejoinZ = player.getZ();
			MovementSync.Stats stats = MoveRuntime.stats();
			this.syncsBefore = stats == null ? 0 : stats.syncs;
			this.mark("rejoined ticks=" + this.phaseTicks + " " + this.where());
			this.next(Phase.REJOIN_WATCH);
		} else {
			this.limit(5 * 60, "rejoin_world");
		}
	}

	/** Watching: a line a second, and every jump of the body (a teleport). */
	private void rejoinWatch() {
		LocalPlayer player = this.minecraft.player;
		if (player == null) {
			this.fail("no_player");
			return;
		}
		this.dismissNotice();
		double jump = Math.hypot(player.getX() - this.rejoinX, player.getZ() - this.rejoinZ);
		if (jump > REJOIN_JUMP) {
			this.mark(String.format(Locale.ROOT, "body_jump blocks=%.1f tick=%d ", jump, this.phaseTicks) + this.where());
		}
		this.rejoinX = player.getX();
		this.rejoinZ = player.getZ();
		if (this.phaseTicks % TICKS == 0) {
			this.mark("watch t=" + this.phaseTicks / TICKS + " " + this.where());
		}
		if (this.phaseTicks >= REJOIN_WATCH_SECONDS * TICKS) {
			this.next(Phase.REJOIN_WALK);
		}
	}

	/** Forward for a few seconds (the camera's way): does the body walk, does the hidden player move? */
	private void rejoinWalk() {
		LocalPlayer player = this.minecraft.player;
		if (player == null) {
			this.fail("no_player");
			return;
		}
		if (this.phaseTicks == 1) {
			this.rejoinX = player.getX();
			this.rejoinZ = player.getZ();
			this.mark("walk_start " + this.where());
		}
		this.forward(this.phaseTicks < REJOIN_WALK_TICKS);
		if (this.phaseTicks >= REJOIN_WALK_TICKS + 2 * TICKS) {
			this.forward(false);
			double moved = Math.hypot(player.getX() - this.rejoinX, player.getZ() - this.rejoinZ);
			MovementSync.Stats stats = MoveRuntime.stats();
			this.mark(String.format(Locale.ROOT, "walk_end moved=%.1f rejoin_syncs=%d ", moved,
					(stats == null ? 0 : stats.syncs) - this.syncsBefore) + this.where());
			this.next(Phase.OUTRO);
		}
	}

	// ---- the caves pilot ----

	/** The shots, planned from the index and the export's cells (the chunks far off aren't loaded yet). */
	private void pilotPlan() {
		WorldIndex index = ClientWorldIndex.current();
		if (index == null || this.pilotDir == null) {
			this.fail("no_index_or_pilot_dir");
			return;
		}
		List<String> caves = PilotPlan.caves3d(index);
		Map<String, ExportMap> maps = new HashMap<>();
		try {
			ExportBundle bundle = ExportBundle.open(this.minecraft.gameDirectory.toPath().resolve("kantobridge").resolve("export").resolve(index.game()));
			for (String id : caves) {
				maps.put(id, bundle.map(id));
			}
		} catch (BundleException | RuntimeException e) {
			this.fail("no_export " + e.getClass().getSimpleName());
			return;
		}
		// Floor a player stands on: walkable, no water, no warp of any kind (the driver checks objects with the game)
		PilotPlan.Cells cells = (map, cx, cy) -> {
			ExportMap m = maps.get(map);
			if (m == null || cx < 0 || cy < 0 || cx >= m.cellWidth() || cy >= m.cellHeight()) {
				return false;
			}
			int flags = m.flags(cx, cy);
			return CellFlags.has(flags, CellFlags.WALKABLE) && !CellFlags.has(flags, CellFlags.WATER) && !CellFlags.has(flags, CellFlags.WARP_TILE)
					&& !CellFlags.has(flags, CellFlags.DOOR) && !CellFlags.has(flags, CellFlags.HOLE);
		};
		this.shots.clear();
		this.shots.addAll(PilotPlan.shots(index, caves, cells, this.shotList));
		this.mark("pilot_plan caves=" + caves.size() + " shafts=" + index.shafts().size() + " shots=" + this.shots.size() + " listed="
				+ (this.shotList != null));
		for (int i = 0; i < this.shots.size(); i++) {
			PilotPlan.Shot shot = this.shots.get(i);
			if (shot.stairsShot()) {
				PilotPlan.Stairs s = shot.stairs().get(0);
				this.mark("pilot_shot n=" + (i + 1) + " kind=" + shot.kind() + " options=" + shot.options() + " map=" + s.map() + " x="
						+ s.start()[0] + " y=" + s.start()[1] + " cells=" + s.path().size() + " edge_x=" + s.edge()[0] + " edge_y=" + s.edge()[1]
						+ " look=" + s.look().id());
			} else {
				PilotPlan.Ladder l = shot.ladders().get(0);
				this.mark("pilot_shot n=" + (i + 1) + " kind=" + shot.kind() + " options=" + shot.options() + " map=" + l.shaft().top().map()
						+ " x=" + l.x() + " y=" + l.y() + " facing=" + l.facing().id() + " to=" + l.shaft().bottom().map() + " length="
						+ (l.shaft().y1() - l.shaft().y0()) + " up=" + l.shaft().up());
			}
		}
		if (this.shots.isEmpty()) {
			this.fail("nothing_to_film");
			return;
		}
		this.shot = 0;
		this.option = 0;
		this.pick();
		this.request();
	}

	/** The current shot's current option, as the stairs or the ladder filmed. */
	private void pick() {
		PilotPlan.Shot shot = this.shots.get(this.shot);
		if (shot.stairsShot()) {
			this.stairs = shot.stairs().get(this.option);
		} else {
			this.ladder = shot.ladders().get(this.option);
		}
	}

	private boolean stairsShot() {
		return this.shots.get(this.shot).stairsShot();
	}

	/** The current shot's next option (another stairs, another ladder or cell beside one), or false when none is left. */
	private boolean nextOption() {
		if (++this.option >= Math.min(this.shots.get(this.shot).options(), 16)) {
			return false;
		}
		this.pick();
		return true;
	}

	/** Asks the driver to put the hidden player on the current shot's first cell (request.txt, written whole). */
	private void request() {
		String shot = this.shots.get(this.shot).kind();
		StringBuilder line = new StringBuilder().append(++this.requestSeq).append(' ');
		if (this.stairsShot()) {
			PilotPlan.Stairs s = this.stairs;
			line.append("walk ").append(s.map()).append(' ').append(s.facing().id());
			for (int[] c : s.path()) {
				line.append(' ').append(c[0]).append(',').append(c[1]);
			}
		} else {
			PilotPlan.Ladder l = this.ladder;
			CellPos top = l.shaft().top();
			line.append("ladder ").append(top.map()).append(' ').append(l.facing().id()).append(' ').append(l.x()).append(',').append(l.y())
					.append(' ').append(top.x()).append(',').append(top.y());
		}
		try {
			Files.createDirectories(this.pilotDir);
			Path tmp = this.pilotDir.resolve("request.txt.tmp");
			Files.writeString(tmp, line + "\n", StandardCharsets.UTF_8);
			Files.move(tmp, this.pilotDir.resolve("request.txt"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			this.fail("request " + e.getClass().getSimpleName());
			return;
		}
		if (this.stairsShot()) {
			this.mark("request n=" + this.requestSeq + " shot=" + shot + " option=" + this.option + " map=" + this.stairs.map() + " x="
					+ this.stairs.start()[0] + " y=" + this.stairs.start()[1]);
		} else {
			this.mark("request n=" + this.requestSeq + " shot=" + shot + " option=" + this.option + " map=" + this.ladder.shaft().top().map()
					+ " x=" + this.ladder.x() + " y=" + this.ladder.y() + " top_x=" + this.ladder.shaft().top().x() + " top_y="
					+ this.ladder.shaft().top().y());
		}
		this.next(Phase.PILOT_PLACE);
	}

	private @Nullable String answer() {
		try {
			Path file = this.pilotDir.resolve("answer.txt");
			return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8).trim() : null;
		} catch (IOException e) {
			return null;
		}
	}

	/** Until the driver has put the hidden player on the shot's first cell and the player stands there, free, for two seconds. */
	private void pilotPlace() {
		String answer = this.answer();
		String shot = this.shots.get(this.shot).kind();
		if (answer != null && answer.startsWith(this.requestSeq + " refused")) {
			this.mark("refused shot=" + shot + " option=" + this.option + " why=" + answer.substring(answer.indexOf("refused") + 7).trim()
					.replace(' ', '_'));
			if (this.nextOption()) {
				this.request();
			} else {
				this.nextShot();
			}
			return;
		}
		LocalPlayer player = this.minecraft.player;
		WorldIndex index = ClientWorldIndex.current();
		ClientBridgeState state = this.state();
		String map = this.stairsShot() ? this.stairs.map() : this.ladder.shaft().top().map();
		int x = this.stairsShot() ? this.stairs.start()[0] : this.ladder.x();
		int y = this.stairsShot() ? this.stairs.start()[1] : this.ladder.y();
		boolean ok = (this.requestSeq + " ok").equals(answer) && player != null && index != null && state != null && state.ingame()
				&& map.equals(state.map()) && state.x() == x && state.y() == y && !InputLock.locked() && this.minecraft.screen == null
				&& this.onHiddenCell(player, index, state);
		this.stableTicks = ok ? this.stableTicks + 1 : 0;
		if (this.stableTicks >= 2 * TICKS) {
			this.mark(String.format(Locale.ROOT, "placed shot=%s map=%s x=%d y=%d feet=%.2f", shot, map, x, y, player.getY()));
			this.next(Phase.PILOT_SETTLE);
		} else if (this.phaseTicks > 90 * TICKS) {
			this.mark("place_timeout shot=" + shot + " answered=" + (answer != null) + " at=" + (state == null ? "-" : state.map() + ":" + state.x()
					+ ":" + state.y()) + " locked=" + InputLock.locked());
			this.nextShot();
		}
	}

	/** First person, no HUD, facing the shot's way, a few seconds for the view to load; then the shot's recording starts. */
	private void pilotSettle() {
		LocalPlayer player = this.minecraft.player;
		if (player == null) {
			this.fail("no_player");
			return;
		}
		boolean stairsShot = this.stairsShot();
		if (this.phaseTicks == 1) {
			this.minecraft.options.setCameraType(CameraType.FIRST_PERSON);
			this.minecraft.options.hideGui = true;
			this.keys(false, false, false);
		}
		this.look(player, (stairsShot ? this.stairs.facing() : this.ladder.facing()).yaw(), stairsShot ? PILOT_WALK_PITCH : 30f);
		this.minecraft.getToasts().clear();
		if (this.phaseTicks >= 6 * TICKS) {
			this.startRecording(this.part(this.shot + 1));
			this.step = 0;
			this.stepTicks = 0;
			this.stuckTicks = 0;
			this.lastX = player.getX();
			this.lastZ = player.getZ();
			this.next(stairsShot ? Phase.PILOT_STAIRS : Phase.PILOT_LADDER);
		}
	}

	/** Along the planned cells: a run-up, the stairs, then over the raised ground to the edge. */
	private void pilotStairs() {
		LocalPlayer player = this.minecraft.player;
		WorldIndex index = ClientWorldIndex.current();
		PilotPlan.Stairs s = this.stairs;
		if (player == null || index == null) {
			this.fail("no_player");
			return;
		}
		if (this.phaseTicks == 1) {
			List<double[]> points = new ArrayList<>();
			for (int i = 1; i < s.path().size(); i++) {
				Vec3 c = index.toBlock(s.map(), s.path().get(i)[0], s.path().get(i)[1]);
				if (c != null) {
					points.add(new double[] {c.x, c.z});
				}
			}
			this.waypoints = points;
			this.waypoint = 0;
		}
		if (this.waypoint >= this.waypoints.size()) {
			this.forward(false);
			this.mark(String.format(Locale.ROOT, "stairs_walked ticks=%d y=%.2f", this.phaseTicks, player.getY()));
			this.lastX = player.getX();
			this.lastZ = player.getZ();
			this.stuckTicks = 0;
			this.next(Phase.PILOT_EDGE);
			return;
		}
		double[] target = this.waypoints.get(this.waypoint);
		double dx = target[0] - player.getX();
		double dz = target[1] - player.getZ();
		if (dx * dx + dz * dz < PILOT_REACHED * PILOT_REACHED) {
			int[] cell = s.path().get(this.waypoint + 1);
			this.mark(String.format(Locale.ROOT, "cell %d,%d feet=%.2f ticks=%d", cell[0], cell[1], player.getY(), this.phaseTicks));
			this.waypoint++;
			return;
		}
		float want = (float) (Mth.atan2(-dx, dz) * Mth.RAD_TO_DEG);
		float yaw = approach(player.getYRot(), want, TURN_PER_TICK);
		this.look(player, yaw, PILOT_WALK_PITCH);
		this.forward(Math.abs(Mth.wrapDegrees(want - yaw)) < 30f && !InputLock.locked());
		double moved = Math.hypot(player.getX() - this.lastX, player.getZ() - this.lastZ);
		this.lastX = player.getX();
		this.lastZ = player.getZ();
		this.stuckTicks = moved < 0.01 && !InputLock.locked() ? this.stuckTicks + 1 : 0;
		// a step the climb didn't take: a hop
		this.minecraft.options.keyJump.setDown(this.stuckTicks > 10 && this.stuckTicks % 10 < 3);
		if (this.stuckTicks > 6 * TICKS || this.phaseTicks > 60 * TICKS) {
			this.stuck(player, target);
			this.endShot("stairs_stuck");
		}
	}

	/** On toward the drop until the edge's wall stops the player; a look down over it and along it. */
	private void pilotEdge() {
		LocalPlayer player = this.minecraft.player;
		if (player == null) {
			this.fail("no_player");
			return;
		}
		float lookYaw = this.stairs.look().yaw();
		this.stepTicks++;
		switch (this.step) {
			case 0 -> {
				float yaw = approach(player.getYRot(), lookYaw, TURN_PER_TICK);
				this.look(player, yaw, PILOT_WALK_PITCH);
				boolean facing = Math.abs(Mth.wrapDegrees(lookYaw - yaw)) < 5f;
				this.forward(facing);
				double moved = Math.hypot(player.getX() - this.lastX, player.getZ() - this.lastZ);
				this.lastX = player.getX();
				this.lastZ = player.getZ();
				this.stuckTicks = facing && moved < 0.005 ? this.stuckTicks + 1 : 0;
				if (this.stuckTicks > 5 || this.stepTicks > 3 * TICKS) {
					this.forward(false);
					this.mark(String.format(Locale.ROOT, "edge y=%.2f ticks=%d stopped=%b", player.getY(), this.stepTicks, this.stuckTicks > 5));
					this.nextStep();
				}
			}
			case 1 -> {
				float t = Math.min(1f, this.stepTicks / (1.5f * TICKS));
				this.look(player, lookYaw, Mth.lerp(smoothstep(t), PILOT_WALK_PITCH, PILOT_EDGE_PITCH));
				if (this.stepTicks >= 4 * TICKS) {
					this.nextStep();
				}
			}
			case 2 -> {
				float t = Math.min(1f, this.stepTicks / (5f * TICKS));
				this.look(player, lookYaw + PILOT_SWAY * (float) Math.sin(2 * Math.PI * t), PILOT_EDGE_PITCH);
				if (t >= 1f) {
					this.nextStep();
				}
			}
			default -> {
				if (this.stepTicks >= TICKS) {
					this.endShot("edge_done");
				}
			}
		}
	}

	/**
	 * The ladder: into the shaft's top onto the ladder, hands off down it (the take at the bottom), a look round, then up
	 * it against the wall, hanging on at the top until the hidden player is there, and back onto the floor beside it.
	 */
	private void pilotLadder() {
		LocalPlayer player = this.minecraft.player;
		WorldIndex index = ClientWorldIndex.current();
		ClientBridgeState state = this.state();
		if (player == null || index == null) {
			this.fail("no_player");
			return;
		}
		PilotPlan.Ladder l = this.ladder;
		WorldIndex.Shaft shaft = l.shaft();
		Dir wall = shaft.wall();
		float wallYaw = wall.yaw();
		double toWall = wallDistance(shaft, player.getX(), player.getZ());
		boolean inColumn = player.getX() >= shaft.x() && player.getX() < shaft.x() + Scale.CELL_BLOCKS && player.getZ() >= shaft.z()
				&& player.getZ() < shaft.z() + Scale.CELL_BLOCKS;
		this.stepTicks++;
		if (this.stepTicks % 5 == 0) {
			MovementSync sync = MoveRuntime.sync();
			this.trace(String.format(Locale.ROOT,
					"ladder step=%d t=%d y=%.2f wall=%.2f column=%b ground=%b ladder=%b bump=%b locked=%b keys=%b/%b climb=%s take=%s hidden=%s mode=%s",
					this.step, this.stepTicks, player.getY(), toWall, inColumn, player.onGround(), player.onClimbable(), player.horizontalCollision,
					InputLock.locked(), this.minecraft.options.keyUp.isDown(), this.minecraft.options.keyShift.isDown(),
					sync == null ? "-" : sync.climbing(), sync == null ? "-" : sync.takeHolding(),
					state == null ? "-" : state.map() + ":" + state.x() + ":" + state.y() + ":" + state.facing(), state == null ? "-" : state.mode())
					+ this.climbStats());
		}
		switch (this.step) {
			case 0 -> {
				// a look at the shaft's top
				this.look(player, l.facing().yaw(), 30f);
				if (this.stepTicks >= TICKS) {
					this.nextStep();
				}
			}
			case 1 -> {
				// in, onto the ladder (the ladder's half of the top cell is open)
				double[] p = ladderPoint(shaft);
				double dx = p[0] - player.getX();
				double dz = p[1] - player.getZ();
				float want = (float) (Mth.atan2(-dx, dz) * Mth.RAD_TO_DEG);
				float yaw = approach(player.getYRot(), want, TURN_PER_TICK);
				this.look(player, yaw, Mth.lerp(Math.min(1f, this.stepTicks / (float) TICKS), 30f, PILOT_DOWN_PITCH));
				this.forward(Math.abs(Mth.wrapDegrees(want - yaw)) < 30f);
				if (inColumn && toWall <= PILOT_ON_LADDER) {
					this.forward(false);
					player.setDeltaMovement(0, player.getDeltaMovement().y, 0);
					this.mark(String.format(Locale.ROOT, "on_ladder y=%.2f wall=%.2f ticks=%d", player.getY(), toWall, this.stepTicks));
					this.nextStep();
				} else if (this.stepTicks > 6 * TICKS) {
					this.endShot(String.format(Locale.ROOT, "walk_in_timeout wall=%.2f column=%b", toWall, inColumn));
				}
			}
			case 2 -> {
				// down, hands off: the ladder slows the fall; the take at the bottom moves the hidden player down
				this.look(player, approach(player.getYRot(), wallYaw, TURN_PER_TICK), PILOT_DOWN_PITCH);
				boolean onFloor = player.onGround() && player.getY() > shaft.y1() + 0.5;
				// still on the top cell's floor beside the ladder: a nudge on
				this.forward(onFloor && this.stepTicks > 10 && this.stepTicks % 10 < 2);
				boolean down = state != null && shaft.bottom().map().equals(state.map()) && state.x() == shaft.bottom().x()
						&& state.y() == shaft.bottom().y();
				if (down && player.onGround() && !InputLock.locked()) {
					this.mark(String.format(Locale.ROOT, "down y=%.2f ticks=%d", player.getY(), this.stepTicks) + this.climbStats());
					this.nextStep();
				} else if (this.stepTicks > 20 * TICKS) {
					this.endShot(String.format(Locale.ROOT, "down_timeout y=%.2f at=%s", player.getY(),
							state == null ? "-" : state.map() + ":" + state.x() + ":" + state.y()) + this.climbStats());
				}
			}
			case 3 -> {
				// a look round the floor below (turned away from the ladder), then back to it, looking up the shaft
				float t = this.stepTicks / (float) TICKS;
				if (t <= 2f) {
					this.look(player, wallYaw + 180f * smoothstep(t / 2f), Mth.lerp(smoothstep(t / 2f), PILOT_DOWN_PITCH, 8f));
				} else if (t <= 3f) {
					this.look(player, wallYaw + 180f, 8f);
				} else if (t <= 5f) {
					float u = smoothstep((t - 3f) / 2f);
					this.look(player, wallYaw + 180f - 180f * u, Mth.lerp(u, 8f, PILOT_UP_PITCH));
				} else {
					this.nextStep();
				}
			}
			case 4 -> {
				// up, pressing against the ladder (the take is sent at the top cell's feet)
				this.look(player, wallYaw, PILOT_UP_PITCH);
				this.forward(true);
				if (player.getY() >= shaft.y1() + 1.3) {
					this.forward(false);
					this.sneak(true);
					this.mark(String.format(Locale.ROOT, "up y=%.2f ticks=%d", player.getY(), this.stepTicks));
					this.nextStep();
				} else if (this.stepTicks > 20 * TICKS) {
					this.endShot(String.format(Locale.ROOT, "up_timeout y=%.2f", player.getY()) + this.climbStats());
				}
			}
			case 5 -> {
				// hanging on at the top until the hidden player stands on it, then round to the floor beside the ladder
				boolean top = state != null && shaft.top().map().equals(state.map()) && state.x() == shaft.top().x() && state.y() == shaft.top().y();
				if ((!top || InputLock.locked()) && this.stepTicks < 8 * TICKS) {
					this.look(player, wallYaw, Mth.lerp(Math.min(1f, this.stepTicks / (float) TICKS), PILOT_UP_PITCH, 10f));
					return;
				}
				float back = wallYaw + 180f;
				float yaw = approach(player.getYRot(), back, TURN_PER_TICK);
				this.look(player, yaw, 10f);
				if (Math.abs(Mth.wrapDegrees(back - yaw)) < 1f) {
					this.mark(String.format(Locale.ROOT, "top hidden_on_top=%b y=%.2f ticks=%d", top, player.getY(), this.stepTicks) + this.climbStats());
					this.nextStep();
				}
			}
			case 6 -> {
				// off the ladder onto the floor beside it, and on to the cell the walk in came from
				this.sneak(false);
				Vec3 a = index.toBlock(shaft.top().map(), l.x(), l.y());
				if (a == null) {
					this.endShot("no_cell");
					return;
				}
				double dx = a.x - player.getX();
				double dz = a.z - player.getZ();
				float want = (float) (Mth.atan2(-dx, dz) * Mth.RAD_TO_DEG);
				float yaw = approach(player.getYRot(), want, TURN_PER_TICK);
				this.look(player, yaw, 10f);
				this.forward(Math.abs(Mth.wrapDegrees(want - yaw)) < 30f && !InputLock.locked());
				if (dx * dx + dz * dz < PILOT_REACHED * PILOT_REACHED || this.stepTicks > 4 * TICKS) {
					this.forward(false);
					this.mark(String.format(Locale.ROOT, "off y=%.2f at=%s ticks=%d", player.getY(),
							state == null ? "-" : state.map() + ":" + state.x() + ":" + state.y(), this.stepTicks));
					this.nextStep();
				}
			}
			default -> {
				this.look(player, player.getYRot(), 10f);
				if (this.stepTicks >= 2 * TICKS) {
					this.endShot("ladder_done");
				}
			}
		}
	}

	/** The player's middle to the plane of the shaft's ladder wall, in blocks. */
	static double wallDistance(WorldIndex.Shaft shaft, double x, double z) {
		return switch (shaft.wall()) {
			case UP -> z - shaft.z();
			case DOWN -> shaft.z() + Scale.CELL_BLOCKS - z;
			case LEFT -> x - shaft.x();
			case RIGHT -> shaft.x() + Scale.CELL_BLOCKS - x;
		};
	}

	/** Where the walk in aims: the middle of the ladder's half of the column, {@link #PILOT_ON_LADDER} less a little from the wall. */
	static double[] ladderPoint(WorldIndex.Shaft shaft) {
		double in = PILOT_ON_LADDER - 0.12;
		double cx = shaft.x() + Scale.CELL_BLOCKS / 2.0;
		double cz = shaft.z() + Scale.CELL_BLOCKS / 2.0;
		return switch (shaft.wall()) {
			case UP -> new double[] {cx, shaft.z() + in};
			case DOWN -> new double[] {cx, shaft.z() + Scale.CELL_BLOCKS - in};
			case LEFT -> new double[] {shaft.x() + in, cz};
			case RIGHT -> new double[] {shaft.x() + Scale.CELL_BLOCKS - in, cz};
		};
	}

	private String climbStats() {
		MovementSync.Stats stats = MoveRuntime.stats();
		return stats == null ? "" : " steps=" + stats.steps + " pushes=" + stats.pushes + " blocked=" + stats.blocked + " snaps=" + stats.snaps
				+ " steers=" + stats.steers + " drifts=" + stats.drifts + " syncs=" + stats.syncs + " teleports=" + stats.teleports + " climbs="
				+ stats.climbs + " takes=" + stats.takes + " takes_failed=" + stats.takesFailed;
	}

	private void nextStep() {
		LocalPlayer player = this.minecraft.player;
		this.step++;
		this.stepTicks = 0;
		this.stuckTicks = 0;
		if (player != null) {
			this.lastX = player.getX();
			this.lastZ = player.getZ();
		}
	}

	/** The shot is over (done or not): its recording stops and the next one is asked for. */
	private void endShot(String why) {
		this.keys(false, false, false);
		this.mark("shot_end shot=" + this.shots.get(this.shot).kind() + " why=" + why + this.climbStats());
		this.stopRecording();
		this.nextShot();
	}

	private void nextShot() {
		this.keys(false, false, false);
		this.shot++;
		this.option = 0;
		if (this.shot < this.shots.size()) {
			this.pick();
			this.request();
		} else {
			this.mark("pilot_shots done=" + this.shots.size() + this.climbStats());
			this.next(Phase.OUTRO);
		}
	}

	// ---- the walk ----

	private void plan(WorldIndex index, WorldIndex.Zone zone, LocalPlayer player) {
		ClientLevel level = this.minecraft.level;
		if (level == null) {
			return;
		}
		Set<Long> avoid = this.avoidedCells(index, zone);
		// rounded: a dirt path, a lane's floor, is 15/16 of a block high
		int feet = (int) Math.floor(player.getY() + 0.5);
		WalkPlanner.Grid grid = (x, z) -> {
			if (!zone.containsColumn(x, z)) {
				return false;
			}
			int[] cell = ZoneView.cellAt(index, zone, x + 0.5, z + 0.5);
			if (cell != null && avoid.contains(key(cell[0], cell[1]))) {
				return false;
			}
			return standable(level, x, feet, z);
		};
		int sx = (int) Math.floor(player.getX());
		int sz = (int) Math.floor(player.getZ());
		WalkPlanner.Box box = new WalkPlanner.Box(Math.max(zone.x(), sx - WALK_STEPS), Math.max(zone.z(), sz - WALK_STEPS),
				Math.min(zone.x() + zone.w() - 1, sx + WALK_STEPS), Math.min(zone.z() + zone.d() - 1, sz + WALK_STEPS));
		int open = 0;
		for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
			open += grid.walkable(sx + d[0], sz + d[1]) ? 1 : 0;
		}
		this.mark("start feet=" + feet + " standable=" + standable(level, sx, feet, sz) + " open_sides=" + open);
		List<int[]> path = box.contains(sx, sz) ? WalkPlanner.farthest(grid, box, sx, sz, WALK_STEPS) : List.of();
		this.waypoints = path.size() < 2 ? List.of() : WalkPlanner.smooth(grid, path, HALF_WIDTH);
		this.waypoint = this.waypoints.isEmpty() ? 0 : 1;
		this.lastX = player.getX();
		this.lastZ = player.getZ();
		this.mark(String.format(Locale.ROOT, "planned steps=%d waypoints=%d length=%.0f avoided=%d", Math.max(0, path.size() - 1),
				this.waypoints.size(), WalkPlanner.length(this.waypoints), avoid.size()));
	}

	/** The zone cells the walk keeps out of: trainers and their sight, balls, the dungeon entrance and the way home (A). */
	static Set<Long> avoidedCells(WorldIndex index, WorldIndex.Zone zone) {
		Set<Long> out = new HashSet<>();
		out.add(key(0, 0)); // the route map's A: stepping on it takes the player home
		for (WorldIndex.ZoneBall b : zone.balls()) {
			out.add(key(b.x(), b.y()));
		}
		if (zone.dungeon() != null) {
			out.add(key(zone.dungeon().x(), zone.dungeon().y()));
		}
		for (WorldIndex.ZoneTrainer t : zone.trainers()) {
			out.add(key(t.x(), t.y()));
			// every cell it sees (the server checks the player's cell; the walk keeps the whole body off these)
			for (int cx = t.x() - WorldIndex.MAX_SIGHT; cx <= t.x() + WorldIndex.MAX_SIGHT; cx++) {
				for (int cy = t.y() - WorldIndex.MAX_SIGHT; cy <= t.y() + WorldIndex.MAX_SIGHT; cy++) {
					if (t.sees(cx, cy)) {
						out.add(key(cx, cy));
					}
				}
			}
		}
		return out;
	}

	static boolean standable(ClientLevel level, int x, int feet, int z) {
		BlockPos floor = new BlockPos(x, feet - 1, z);
		BlockPos body = new BlockPos(x, feet, z);
		BlockPos head = new BlockPos(x, feet + 1, z);
		return !level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()
				&& level.getBlockState(body).getCollisionShape(level, body).isEmpty()
				&& level.getBlockState(head).getCollisionShape(level, head).isEmpty()
				&& level.getFluidState(body).isEmpty();
	}

	// ---- helpers ----

	private boolean onHiddenCell(LocalPlayer player, WorldIndex index, ClientBridgeState state) {
		BlockPos origin = state.map() == null ? null : index.cellOrigin(state.map(), state.x(), state.y());
		if (origin == null) {
			return false;
		}
		int bx = (int) Math.floor(player.getX());
		int bz = (int) Math.floor(player.getZ());
		return bx >= origin.getX() && bx < origin.getX() + Scale.CELL_BLOCKS && bz >= origin.getZ() && bz < origin.getZ() + Scale.CELL_BLOCKS;
	}

	private @Nullable ClientBridgeState state() {
		BridgeRuntime bridge = BridgeRuntime.get();
		return bridge == null ? null : bridge.state();
	}

	private void options() {
		this.optionsSet = true;
		var o = this.minecraft.options;
		o.renderDistance().set(4);
		o.simulationDistance().set(6);
		o.framerateLimit().set(60);
		o.enableVsync().set(false);
		o.graphicsMode().set(GraphicsStatus.FANCY);
		o.cloudStatus().set(CloudStatus.OFF);
		o.ambientOcclusion().set(true);
		o.entityShadows().set(true);
		o.bobView().set(false);
		o.fov().set(70);
		o.pauseOnLostFocus = false;
		o.tutorialStep = TutorialSteps.NONE;
		o.save();
		this.mark("options");
	}

	private void look(LocalPlayer player, float yaw, float pitch) {
		player.setYRot(yaw);
		player.setYHeadRot(yaw);
		player.setXRot(pitch);
	}

	private void forward(boolean down) {
		this.minecraft.options.keyUp.setDown(down);
	}

	private void sneak(boolean down) {
		this.minecraft.options.keyShift.setDown(down);
	}

	private void keys(boolean forward, boolean sneak, boolean jump) {
		this.forward(forward);
		this.sneak(sneak);
		this.minecraft.options.keyJump.setDown(jump);
	}

	private void startRecording() {
		this.startRecording(this.record);
	}

	/** Records the window into {@code file} (null: times the scene without a file). */
	private void startRecording(@Nullable Path file) {
		this.recordStartNanos = System.nanoTime();
		this.recording = file;
		if (file == null) {
			this.mark("record_start file=false");
			return;
		}
		String display = System.getenv("DISPLAY");
		int w = this.minecraft.getWindow().getWidth() & ~1;
		int h = this.minecraft.getWindow().getHeight() & ~1;
		List<String> command = new ArrayList<>(List.of(System.getProperty(PROPERTY + ".ffmpeg", "ffmpeg"), "-y", "-loglevel", "error",
				"-f", "x11grab", "-draw_mouse", "0", "-framerate", "30", "-video_size", w + "x" + h, "-i",
				(display == null ? ":0" : display) + "+0,0", "-c:v", "libx264", "-preset", "veryfast", "-crf", "21", "-pix_fmt",
				"yuv420p", "-movflags", "+faststart", file.toString()));
		try {
			Files.createDirectories(file.toAbsolutePath().getParent());
			Path ffLog = file.resolveSibling(file.getFileName() + ".ffmpeg.log");
			this.ffmpeg = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ffLog.toFile()).start();
			this.mark("record_start size=" + w + "x" + h);
		} catch (IOException e) {
			this.mark("record_failed " + e.getClass().getSimpleName());
		}
	}

	private void stopRecording() {
		if (this.recordStartNanos == 0) {
			return;
		}
		long seconds = (System.nanoTime() - this.recordStartNanos) / 1_000_000_000L;
		this.recordStartNanos = 0;
		if (this.fpsSamples > 0) {
			this.mark("fps mean=" + this.fpsTotal / this.fpsSamples + " min=" + this.fpsMin + " samples=" + this.fpsSamples);
		}
		Process process = this.ffmpeg;
		this.ffmpeg = null;
		if (process == null) {
			this.mark("record_stop seconds=" + seconds);
			return;
		}
		try (OutputStream in = process.getOutputStream()) {
			in.write('q');
			in.flush();
		} catch (IOException ignored) {
			// ffmpeg already gone
		}
		try {
			if (!process.waitFor(20, TimeUnit.SECONDS)) {
				process.destroy();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		long bytes = -1;
		try {
			bytes = this.recording == null ? -1 : Files.size(this.recording);
		} catch (IOException ignored) {
			// reported as -1
		}
		this.mark("record_stop seconds=" + seconds + " bytes=" + bytes);
	}

	private void next(Phase phase) {
		this.phase = phase;
		this.phaseTicks = 0;
		this.stableTicks = 0;
	}

	private void limit(int seconds, String what) {
		if (this.phaseTicks > seconds * TICKS) {
			this.fail("timeout " + what);
		}
	}

	private void fail(String why) {
		this.keys(false, false, false);
		this.mark("failed phase=" + this.phase.name().toLowerCase(Locale.ROOT) + " " + why);
		this.stopRecording();
		this.phase = Phase.FAILED;
		if (this.quit) {
			this.minecraft.stop();
		}
	}

	/** A line for the log file only (the pilot's tick-by-tick trace of a climb): positions, flags and counts. */
	private void trace(String event) {
		if (this.log != null) {
			try {
				Files.writeString(this.log, Instant.now() + " autopilot: " + event + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
						StandardOpenOption.APPEND);
			} catch (IOException ignored) {
				// a trace line less
			}
		}
	}

	private void mark(String event) {
		String line = "autopilot: " + event;
		KantoBridge.LOGGER.info(line);
		if (this.log != null) {
			try {
				Files.createDirectories(this.log.toAbsolutePath().getParent());
				Files.writeString(this.log, Instant.now() + " " + line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
						StandardOpenOption.APPEND);
			} catch (IOException ignored) {
				// the game log has the line
			}
		}
	}

	static long key(int cx, int cy) {
		return ((long) cx << 32) ^ (cy & 0xffffffffL);
	}

	/** Minecraft's yaw for a Gen 1 facing: 0 south (+z), 90 west, 180 north, -90 east. */
	static float yawOf(@Nullable String facing) {
		return switch (facing == null ? "" : facing) {
			case "up" -> 180f;
			case "left" -> 90f;
			case "right" -> -90f;
			default -> 0f;
		};
	}

	static float approach(float from, float to, float step) {
		float delta = Mth.wrapDegrees(to - from);
		return from + Mth.clamp(delta, -step, step);
	}

	static float smoothstep(float t) {
		return t * t * (3f - 2f * t);
	}
}
