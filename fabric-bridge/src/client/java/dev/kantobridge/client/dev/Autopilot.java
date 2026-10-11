package dev.kantobridge.client.dev;

import dev.kantobridge.KantoBridge;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.bridge.ClientBridgeState;
import dev.kantobridge.client.bridge.ConnectionState;
import dev.kantobridge.client.move.InputLock;
import dev.kantobridge.client.world.ClientWorldIndex;
import dev.kantobridge.client.world.CreateKantoWorldFlow;
import dev.kantobridge.client.zone.ZoneView;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.Scale;
import dev.kantobridge.world.WorldIndex;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
 */
public final class Autopilot {
	static final String PROPERTY = "kantobridge.autopilot";
	static final String SCENE_WILD_ZONE = "wild_zone";
	static final String SCENE_TOUR = "tour";
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
		TOUR_WAIT
	}

	private final Minecraft minecraft;
	private final @Nullable String world;
	private final @Nullable Path record;
	private final @Nullable Path log;
	private final boolean quit;
	private final boolean tour;
	private final int stops;
	private int stopsSeen;

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
			int stops) {
		this.minecraft = minecraft;
		this.world = world;
		this.record = record;
		this.log = log;
		this.quit = quit;
		this.tour = tour;
		this.stops = stops;
	}

	/** Call once from client init; does nothing without {@value #PROPERTY}. */
	public static void init() {
		String scene = System.getProperty(PROPERTY);
		if (scene == null || scene.isBlank()) {
			return;
		}
		if (!SCENE_WILD_ZONE.equals(scene) && !SCENE_TOUR.equals(scene)) {
			KantoBridge.LOGGER.warn("autopilot: unknown scene");
			return;
		}
		String world = System.getProperty(PROPERTY + ".world");
		String record = System.getProperty(PROPERTY + ".record");
		String log = System.getProperty(PROPERTY + ".log");
		Autopilot pilot = new Autopilot(Minecraft.getInstance(), world == null || world.isBlank() ? null : world,
				record == null || record.isBlank() ? null : Path.of(record), log == null || log.isBlank() ? null : Path.of(log),
				Boolean.getBoolean(PROPERTY + ".quit"), SCENE_TOUR.equals(scene), Math.max(1, Integer.getInteger(PROPERTY + ".stops", 2)));
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
			this.next(this.tour ? Phase.TOUR_SETTLE : Phase.SETTLE);
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
		if (this.waypoint >= this.waypoints.size() || this.phaseTicks > 75 * TICKS) {
			this.forward(false);
			this.mark("walked waypoints=" + this.waypoint + "/" + this.waypoints.size() + " ticks=" + this.phaseTicks);
			this.panFrom = player.getYRot();
			this.next(Phase.PAN);
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
	private Set<Long> avoidedCells(WorldIndex index, WorldIndex.Zone zone) {
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

	private static boolean standable(ClientLevel level, int x, int feet, int z) {
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
		MapPlacement map = index.map(state.map());
		if (map == null) {
			return false;
		}
		int cx = Scale.blockToCell((int) Math.floor(player.getX()) - map.x());
		int cy = Scale.blockToCell((int) Math.floor(player.getZ()) - map.z());
		return cx == state.x() && cy == state.y();
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
		this.forward(false);
		this.mark("failed phase=" + this.phase.name().toLowerCase(Locale.ROOT) + " " + why);
		this.stopRecording();
		this.phase = Phase.FAILED;
		if (this.quit) {
			this.minecraft.stop();
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
