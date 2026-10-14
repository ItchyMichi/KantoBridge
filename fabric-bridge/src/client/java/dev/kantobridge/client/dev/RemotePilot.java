package dev.kantobridge.client.dev;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.battles.InBattleMove;
import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.battle.ClientBattle;
import com.cobblemon.mod.common.client.battle.SingleActionRequest;
import com.cobblemon.mod.common.client.gui.battle.BattleGUI;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.block.EdgeWallBlock;
import dev.kantobridge.block.LedgeColliderBlock;
import dev.kantobridge.client.bridge.BridgeLink;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.bridge.ClientBridgeState;
import dev.kantobridge.client.bridge.Links;
import dev.kantobridge.client.move.InputLock;
import dev.kantobridge.client.move.MoveRuntime;
import dev.kantobridge.client.move.MovementSync;
import dev.kantobridge.client.ui.ChoiceModel;
import dev.kantobridge.client.ui.Keybinds;
import dev.kantobridge.client.ui.MenuModel;
import dev.kantobridge.client.ui.NamingModel;
import dev.kantobridge.client.ui.TextBoxModel;
import dev.kantobridge.client.ui.UiRuntime;
import dev.kantobridge.client.ui.UiSession;
import dev.kantobridge.client.world.ClientWorldIndex;
import dev.kantobridge.client.zone.PassRuntime;
import dev.kantobridge.client.zone.ZoneView;
import dev.kantobridge.world.MapPlacement;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.CameraType;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * The dev client's remote pilot (MG8's story video, docs/specs/HOENN_PASS_PLAN.md): with
 * {@code -Dkantobridge.autopilot=remote} the client plays what a script outside it asks, one command at a time, through
 * the inputs a player has: the walk keys, the Game Boy buttons on our screens and in the world (Z, X, Enter, Backspace),
 * the arrows and A on the naming screen's grid (a name, or {@code @species}: the party's newest Pokémon's own), and a move in Cobblemon's battle screen. tools/e2e/story_video.py is
 * that script.
 *
 * <ul>
 * <li>From the title screen, once the link of {@code kantobridge.autopilot.follow}'s game (any link when unset) is
 * connected, it opens the world {@code kantobridge.autopilot.world}.</li>
 * <li><b>Commands</b>: JSON lines appended to {@code <pilotDir>/commands.jsonl}, each {@code {id, op, ...}}, run in
 * order. {@code key} (a, b, start, select, up, down, left, right: into the open screen, else in the world; {@code hold}
 * ticks; esc only into a screen that isn't ours, such as Cobblemon's PC); {@code step} (one cell: the player walks to the middle of the next cell in {@code dir}; done when the hidden
 * player stands there, when the hidden map changes, or refused when nothing moves for a while); {@code face} (turns the
 * player to {@code dir}); {@code type} (letters on the naming screen's grid: the cursor moved by the arrows, A, then
 * START); {@code battle} ({@code on}: whenever Cobblemon asks for an action, the battle screen shows the moves and the
 * first one with PP left is chosen); {@code zone_walk} (in a Wild Routes half: the walk to its pass, out of the trainers'
 * sight, the balls and the way home, until the crossing starts); {@code follow} ({@code game}: that link is followed, as
 * a player choosing where to play first, instead of the legacy region's); {@code camera} ({@code third} or {@code first}, {@code hud}); {@code record}
 * ({@code file}: ffmpeg grabs the window) and {@code stop}; {@code wait} ({@code ticks}); {@code quit}.</li>
 * <li><b>State</b>: {@code <pilotDir>/state.json}, rewritten every few ticks: the last command done and its result, the
 * open screen's class, the top hidden UI (kind, screen id, page, menu or choice index and size, the naming phase), the
 * followed link (game, region, map, cell, facing, mode, in game), every link's game and connection, the input lock, a
 * battle, the player's block position and the frame rate. Ids, cells and counts only, never the game's text.</li>
 * </ul>
 * Every command and its result is also a line in {@code kantobridge.autopilot.log}. Nothing runs without the property.
 * Client thread only.
 */
final class RemotePilot {
	static final String SCENE = "remote";
	private static final int TICKS = 20;
	private static final int STATE_EVERY = 4;
	/** A step's walk: done this close to the next cell's middle, refused after this many ticks without progress. */
	private static final double REACHED = 0.3;
	private static final int STEP_STALL_TICKS = 24;
	private static final int STEP_MAX_TICKS = 6 * TICKS;
	private static final float TURN_PER_TICK = 18f;
	/** Ticks between two presses while typing a name, and between showing the moves and choosing one. */
	private static final int TYPE_EVERY = 3;
	private static final int MOVE_SHOWN_TICKS = 25;

	private enum Phase {
		TITLE,
		WORLD,
		READY,
		DONE
	}

	private final Minecraft minecraft;
	private final String world;
	private final Path dir;
	private final @Nullable Path log;
	private final boolean quit;
	private final @Nullable String follow;

	private Phase phase = Phase.TITLE;
	private int ticks;
	private int phaseTicks;
	private int stableTicks;
	private long commandsSize = -1;
	private int linesRead;
	private final ArrayDeque<JsonObject> queue = new ArrayDeque<>();
	private @Nullable JsonObject current;
	private int currentTicks;
	private int lastDone;
	private JsonObject lastResult = new JsonObject();
	private final List<int[]> releases = new ArrayList<>();
	private @Nullable Screen pressedOn;

	private boolean battle;
	private int movesShownAt = -1;
	private int battleChoices;

	private @Nullable String stepMap;
	private int stepX;
	private int stepY;
	private double[] stepTarget = new double[0];
	private float stepYaw;
	private double lastX;
	private double lastZ;
	private int stallTicks;

	private List<double[]> waypoints = List.of();
	private int waypoint;
	private @Nullable String walkFollowed;
	/** The walk ends short of the pass (its far chunks weren't loaded yet): plan again from there, this many times. */
	private boolean walkPartial;
	private int walkReplans;

	private final ArrayDeque<String> typing = new ArrayDeque<>();
	private boolean typingConfirmed;

	private @Nullable Process ffmpeg;
	private @Nullable Path recording;
	private long recordStartNanos;

	private RemotePilot(Minecraft minecraft, String world, Path dir, @Nullable Path log, boolean quit, @Nullable String follow) {
		this.minecraft = minecraft;
		this.world = world;
		this.dir = dir;
		this.log = log;
		this.quit = quit;
		this.follow = follow;
	}

	/** Called by {@link Autopilot#init()} for the scene {@value #SCENE}. */
	static void init(Minecraft minecraft, @Nullable String world, @Nullable Path dir, @Nullable Path log, boolean quit,
			@Nullable String follow) {
		if (world == null || dir == null) {
			KantoBridge.LOGGER.warn("autopilot: the remote pilot needs a world and a pilot directory");
			return;
		}
		RemotePilot pilot = new RemotePilot(minecraft, world, dir, log, quit, follow);
		try {
			Files.createDirectories(dir);
			Files.deleteIfExists(dir.resolve("state.json"));
		} catch (IOException e) {
			KantoBridge.LOGGER.warn("autopilot: no pilot directory ({})", e.getClass().getSimpleName());
			return;
		}
		ClientTickEvents.END_CLIENT_TICK.register(client -> pilot.tick());
		pilot.mark("armed scene=remote follow=" + (follow == null ? "-" : follow));
	}

	private void tick() {
		this.ticks++;
		this.phaseTicks++;
		this.releaseKeys();
		try {
			switch (this.phase) {
				case TITLE -> this.title();
				case WORLD -> this.world();
				case READY -> this.ready();
				case DONE -> {
				}
			}
		} catch (RuntimeException e) {
			this.mark("error " + e.getClass().getSimpleName());
			this.finish(this.result(false, "error"));
		}
		if (this.ticks % STATE_EVERY == 0) {
			this.writeState();
		}
	}

	// ---- phases ----

	private void title() {
		if (!(this.minecraft.screen instanceof TitleScreen)) {
			return;
		}
		if (this.phaseTicks == 1) {
			this.options();
		}
		Links links = Links.get();
		BridgeLink link = links == null ? null : this.follow == null ? links.followed() : links.byGame(this.follow);
		this.stableTicks = link != null && link.connected() ? this.stableTicks + 1 : 0;
		if (this.stableTicks < 2 * TICKS) {
			return;
		}
		if (!this.minecraft.getLevelSource().levelExists(this.world)) {
			this.mark("failed no_world");
			this.phase = Phase.DONE;
			return;
		}
		this.mark("open_world");
		this.minecraft.createWorldOpenFlows().openWorld(this.world, () -> this.minecraft.setScreen(new TitleScreen()));
		this.next(Phase.WORLD);
	}

	private void world() {
		if (this.minecraft.level != null && this.minecraft.player != null && ClientWorldIndex.current() != null) {
			this.mark("in_world ticks=" + this.phaseTicks);
			this.minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			this.next(Phase.READY);
		}
	}

	private void ready() {
		this.readCommands();
		if (this.battle) {
			this.battleTick();
		}
		if (this.current == null && !this.queue.isEmpty()) {
			this.current = this.queue.poll();
			this.currentTicks = 0;
			this.start(this.current);
		}
		if (this.current != null) {
			this.currentTicks++;
			this.run(this.current);
		}
	}

	// ---- commands ----

	private void readCommands() {
		Path file = this.dir.resolve("commands.jsonl");
		long size;
		try {
			size = Files.isRegularFile(file) ? Files.size(file) : -1;
		} catch (IOException e) {
			return;
		}
		if (size == this.commandsSize) {
			return;
		}
		this.commandsSize = size;
		List<String> lines;
		try {
			lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			return;
		}
		for (int i = this.linesRead; i < lines.size(); i++) {
			String line = lines.get(i).trim();
			if (line.isEmpty()) {
				continue;
			}
			try {
				this.queue.add(JsonParser.parseString(line).getAsJsonObject());
			} catch (RuntimeException e) {
				this.mark("bad_command line=" + (i + 1));
			}
		}
		this.linesRead = lines.size();
	}

	private void start(JsonObject command) {
		String op = text(command, "op");
		this.mark("command id=" + command.get("id") + " op=" + op);
		switch (op == null ? "" : op) {
			case "key" -> this.press(text(command, "key"), Math.max(1, number(command, "hold", 2)));
			case "step" -> this.startStep(text(command, "dir"));
			case "face" -> {
				LocalPlayer player = this.minecraft.player;
				if (player != null) {
					look(player, Autopilot.yawOf(text(command, "dir")), player.getXRot());
				}
			}
			case "type" -> {
				this.typing.clear();
				String word = text(command, "text");
				if ("@species".equals(word)) {
					// a nickname: the Pokémon's own name, as an empty one keeps it in the game
					UiSession top = this.topUi();
					word = speciesName(top == null || top.naming() == null ? null : top.naming().mon());
				}
				for (int i = 0; word != null && i < word.length(); i++) {
					this.typing.add(String.valueOf(word.charAt(i)));
				}
				this.typingConfirmed = false;
			}
			case "battle" -> {
				this.battle = !"off".equals(text(command, "mode"));
				this.movesShownAt = -1;
			}
			case "zone_walk" -> {
				this.walkReplans = 0;
				this.startZoneWalk();
			}
			case "camera" -> {
				this.minecraft.options.setCameraType("first".equals(text(command, "mode")) ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_BACK);
				this.minecraft.options.hideGui = command.has("hud") && !command.get("hud").getAsBoolean();
			}
			case "record" -> {
				String file = text(command, "file");
				this.startRecording(file == null ? null : Path.of(file));
			}
			case "stop" -> this.stopRecording();
			case "quit" -> {
				this.stopRecording();
				this.mark("quit");
				this.phase = Phase.DONE;
				if (this.quit) {
					this.minecraft.stop();
				}
			}
			default -> {
			}
		}
	}

	private void run(JsonObject command) {
		String op = text(command, "op");
		switch (op == null ? "" : op) {
			case "key" -> {
				if (this.currentTicks > number(command, "hold", 2)) {
					this.finish(this.result(true, null));
				}
			}
			case "step" -> this.stepTick();
			case "zone_walk" -> this.zoneWalkTick();
			case "follow" -> {
				// the player's choice of game (a fresh player follows the legacy region's link once it connects)
				Links links = Links.get();
				boolean ok = links != null && links.follow(String.valueOf(text(command, "game")), true);
				this.finish(this.result(ok, ok ? null : "no_link"));
			}
			case "type" -> this.typeTick();
			case "wait" -> {
				if (this.currentTicks >= number(command, "ticks", TICKS)) {
					this.finish(this.result(true, null));
				}
			}
			default -> this.finish(this.result(true, null));
		}
	}

	private void finish(JsonObject result) {
		JsonObject command = this.current;
		this.current = null;
		if (command == null) {
			return;
		}
		this.lastDone = command.has("id") ? command.get("id").getAsInt() : this.lastDone;
		this.lastResult = result;
		this.mark("done id=" + this.lastDone + " " + result);
	}

	private JsonObject result(boolean ok, @Nullable String reason) {
		JsonObject out = new JsonObject();
		out.addProperty("ok", ok);
		if (reason != null) {
			out.addProperty("reason", reason);
		}
		ClientBridgeState state = this.state();
		if (state != null && state.map() != null) {
			out.addProperty("map", state.map());
			out.addProperty("x", state.x());
			out.addProperty("y", state.y());
		}
		return out;
	}

	// ---- keys ----

	/** A Game Boy button: into the open screen (as a key press), else in the world (as a key click). */
	private void press(@Nullable String name, int hold) {
		int code = keyCode(name);
		if (code < 0) {
			return;
		}
		Screen screen = this.minecraft.screen;
		if (screen != null) {
			screen.keyPressed(code, 0, 0);
			this.pressedOn = screen;
		} else {
			InputConstants.Key key = InputConstants.Type.KEYSYM.getOrCreate(code);
			KeyMapping.click(key);
			KeyMapping.set(key, true);
			this.pressedOn = null;
		}
		this.releases.add(new int[] {code, this.ticks + hold});
	}

	private void releaseKeys() {
		for (Iterator<int[]> it = this.releases.iterator(); it.hasNext();) {
			int[] release = it.next();
			if (release[1] > this.ticks) {
				continue;
			}
			it.remove();
			Screen screen = this.minecraft.screen;
			if (screen != null && screen == this.pressedOn) {
				screen.keyReleased(release[0], 0, 0);
			}
			KeyMapping.set(InputConstants.Type.KEYSYM.getOrCreate(release[0]), false);
		}
	}

	private static int keyCode(@Nullable String name) {
		return switch (name == null ? "" : name) {
			case "a" -> bound(Keybinds.a(), GLFW.GLFW_KEY_Z);
			case "b" -> bound(Keybinds.b(), GLFW.GLFW_KEY_X);
			case "start" -> bound(Keybinds.start(), GLFW.GLFW_KEY_ENTER);
			case "select" -> bound(Keybinds.select(), GLFW.GLFW_KEY_BACKSPACE);
			case "up" -> GLFW.GLFW_KEY_UP;
			case "down" -> GLFW.GLFW_KEY_DOWN;
			case "left" -> GLFW.GLFW_KEY_LEFT;
			case "right" -> GLFW.GLFW_KEY_RIGHT;
			// only for screens that aren't ours (Cobblemon's PC): on ours Esc opens the pause menu
			case "esc" -> GLFW.GLFW_KEY_ESCAPE;
			default -> -1;
		};
	}

	private static int bound(@Nullable KeyMapping mapping, int fallback) {
		if (mapping == null) {
			return fallback;
		}
		InputConstants.Key key = KeyBindingHelper.getBoundKeyOf(mapping);
		return key.getType() == InputConstants.Type.KEYSYM ? key.getValue() : fallback;
	}

	// ---- a step ----

	private void startStep(@Nullable String direction) {
		ClientBridgeState state = this.state();
		WorldIndex index = ClientWorldIndex.current();
		LocalPlayer player = this.minecraft.player;
		int[] d = delta(direction);
		if (state == null || state.map() == null || index == null || player == null || d == null) {
			this.finish(this.result(false, "no_position"));
			return;
		}
		BlockPos origin = index.cellOrigin(state.map(), state.x() + d[0], state.y() + d[1]);
		if (origin == null) {
			this.finish(this.result(false, "no_cell"));
			return;
		}
		this.stepMap = state.map();
		this.stepX = state.x() + d[0];
		this.stepY = state.y() + d[1];
		this.stepTarget = new double[] {origin.getX() + 1.0, origin.getZ() + 1.0};
		this.stepYaw = Autopilot.yawOf(direction);
		this.lastX = player.getX();
		this.lastZ = player.getZ();
		this.stallTicks = 0;
	}

	private void stepTick() {
		LocalPlayer player = this.minecraft.player;
		ClientBridgeState state = this.state();
		if (player == null || state == null) {
			this.forward(false);
			this.finish(this.result(false, "no_player"));
			return;
		}
		if (state.map() != null && !state.map().equals(this.stepMap)) {
			this.forward(false);
			this.finish(this.result(true, "map"));
			return;
		}
		double dx = this.stepTarget[0] - player.getX();
		double dz = this.stepTarget[1] - player.getZ();
		boolean there = state.x() == this.stepX && state.y() == this.stepY;
		if (there && dx * dx + dz * dz < REACHED * REACHED) {
			this.forward(false);
			this.finish(this.result(true, null));
			return;
		}
		float want = dx * dx + dz * dz < 0.01 ? this.stepYaw : (float) (Mth.atan2(-dx, dz) * Mth.RAD_TO_DEG);
		float yaw = Autopilot.approach(player.getYRot(), want, TURN_PER_TICK);
		look(player, yaw, player.getXRot());
		boolean free = !InputLock.locked() && this.minecraft.screen == null;
		this.forward(free && Math.abs(Mth.wrapDegrees(want - yaw)) < 45f && dx * dx + dz * dz >= REACHED * REACHED);
		double moved = Math.hypot(player.getX() - this.lastX, player.getZ() - this.lastZ);
		this.lastX = player.getX();
		this.lastZ = player.getZ();
		this.stallTicks = moved < 0.01 ? this.stallTicks + 1 : 0;
		if (this.stallTicks > STEP_STALL_TICKS || this.currentTicks > STEP_MAX_TICKS) {
			this.forward(false);
			look(player, this.stepYaw, player.getXRot());
			this.finish(this.result(false, there ? "short" : this.minecraft.screen != null ? "screen" : InputLock.locked() ? "locked"
					: "blocked"));
		}
	}

	private void forward(boolean down) {
		this.minecraft.options.keyUp.setDown(down);
	}

	private static int @Nullable [] delta(@Nullable String direction) {
		return switch (direction == null ? "" : direction) {
			case "up" -> new int[] {0, -1};
			case "down" -> new int[] {0, 1};
			case "left" -> new int[] {-1, 0};
			case "right" -> new int[] {1, 0};
			default -> null;
		};
	}

	// ---- a Wild Routes half: the walk to its pass ----

	private void startZoneWalk() {
		LocalPlayer player = this.minecraft.player;
		WorldIndex index = ClientWorldIndex.current();
		ClientLevel level = this.minecraft.level;
		WorldIndex.Zone zone = player == null || index == null ? null : ZoneView.zoneAt(index, player.getX(), player.getZ());
		MapPlacement routeMap = zone == null ? null : index.map(zone.routeMap());
		if (zone == null || zone.pass() == null || routeMap == null || level == null) {
			this.finish(this.result(false, "no_zone"));
			return;
		}
		Set<Long> goals = new HashSet<>();
		Set<Long> passCells = new HashSet<>();
		for (int[] cell : zone.pass().cells()) {
			passCells.add(Autopilot.key(cell[0], cell[1]));
			for (int i = 0; i < Scale.CELL_BLOCKS; i++) {
				for (int j = 0; j < Scale.CELL_BLOCKS; j++) {
					goals.add(Autopilot.key(routeMap.x() + cell[0] * Scale.CELL_BLOCKS + i, routeMap.z() + cell[1] * Scale.CELL_BLOCKS + j));
				}
			}
		}
		int feet = (int) Math.floor(player.getY() + 0.5);
		int margin = 8;
		WalkPlanner.Box box = new WalkPlanner.Box(zone.x() - margin, zone.z() - margin, zone.x() + zone.w() - 1 + margin,
				zone.z() + zone.d() - 1 + margin);
		int sx = (int) Math.floor(player.getX());
		int sz = (int) Math.floor(player.getZ());
		// out of the trainers' sight first; when that closes every lane, only their own cells (a challenge is a battle the
		// pilot fights), then nothing but the way home
		Set<Long> sight = Autopilot.avoidedCells(index, zone);
		sight.removeAll(passCells);
		Set<Long> bodies = new HashSet<>();
		bodies.add(Autopilot.key(0, 0));
		for (WorldIndex.ZoneTrainer t : zone.trainers()) {
			bodies.add(Autopilot.key(t.x(), t.y()));
		}
		for (WorldIndex.ZoneBall b : zone.balls()) {
			bodies.add(Autopilot.key(b.x(), b.y()));
		}
		List<int[]> path = List.of();
		WalkPlanner.Grid grid = null;
		String avoided = "none";
		for (Set<Long> avoid : List.of(sight, bodies, Set.of(Autopilot.key(0, 0)))) {
			WalkPlanner.Grid g = (x, z) -> {
				int[] cell = ZoneView.cellAt(index, zone, x + 0.5, z + 0.5);
				if (cell != null && avoid.contains(Autopilot.key(cell[0], cell[1]))) {
					return false;
				}
				return Autopilot.standable(level, x, feet, z) || stepUp(level, x, feet, z);
			};
			path = box.contains(sx, sz) ? WalkPlanner.toward(g, box, sx, sz, goals) : List.of();
			if (!path.isEmpty()) {
				grid = g;
				avoided = avoid == sight ? "sight" : avoid == bodies ? "bodies" : "home";
				break;
			}
			int reach = box.contains(sx, sz) ? WalkPlanner.farthest(g, box, sx, sz, 100_000).size() - 1 : -1;
			this.mark(String.format(Locale.ROOT, "zone_walk no_path avoid=%d feet=%d standable=%b reach=%d goals=%d in_box=%b", avoid.size(),
					feet, Autopilot.standable(level, sx, feet, sz), reach, goals.size(), box.contains(sx, sz)));
		}
		this.walkPartial = path.isEmpty();
		if (path.isEmpty()) {
			// the pass's chunks aren't loaded on the client yet (they read as air): towards it as far as the loaded ground goes
			long tx = 0;
			long tz = 0;
			for (long key : goals) {
				tx += (int) (key >> 32);
				tz += (int) key;
			}
			// out of the trainers' sight when that still gets somewhere, else past them
			for (Set<Long> avoid : List.of(sight, bodies)) {
				WalkPlanner.Grid g = (x, z) -> {
					int[] cell = ZoneView.cellAt(index, zone, x + 0.5, z + 0.5);
					if (cell != null && avoid.contains(Autopilot.key(cell[0], cell[1]))) {
						return false;
					}
					return Autopilot.standable(level, x, feet, z) || stepUp(level, x, feet, z);
				};
				path = box.contains(sx, sz) ? WalkPlanner.nearest(g, box, sx, sz, (int) (tx / goals.size()), (int) (tz / goals.size()))
						: List.of();
				grid = g;
				avoided = avoid == sight ? "partial_sight" : "partial_bodies";
				if (path.size() >= 8) {
					break;
				}
			}
		}
		if (path.size() < 2 && this.walkPartial) {
			this.finish(this.result(false, "no_path"));
			return;
		}
		this.waypoints = path.size() < 2 ? List.of(new double[] {sx + 0.5, sz + 0.5}) : WalkPlanner.smooth(grid, path, Autopilot.HALF_WIDTH);
		this.waypoint = this.waypoints.size() > 1 ? 1 : 0;
		Links links = Links.get();
		this.walkFollowed = links == null || links.followed() == null ? null : links.followed().game();
		this.lastX = player.getX();
		this.lastZ = player.getZ();
		this.stallTicks = 0;
		this.mark(String.format(Locale.ROOT, "zone_walk zone=%s steps=%d waypoints=%d avoided=%s", zone.id(), path.size() - 1,
				this.waypoints.size(), avoided));
	}

	private void zoneWalkTick() {
		LocalPlayer player = this.minecraft.player;
		if (player == null) {
			this.keys(false, false);
			this.finish(this.result(false, "no_player"));
			return;
		}
		Links links = Links.get();
		String followed = links == null || links.followed() == null ? null : links.followed().game();
		PassRuntime pass = PassRuntime.get();
		if (followed != null && !followed.equals(this.walkFollowed) || pass != null && pass.crossing() != null) {
			this.keys(false, false);
			this.finish(this.result(true, "crossing"));
			return;
		}
		if (this.currentTicks > 150 * TICKS) {
			this.keys(false, false);
			this.finish(this.result(false, "timeout"));
			return;
		}
		double[] target = this.waypoints.get(Math.min(this.waypoint, this.waypoints.size() - 1));
		double dx = target[0] - player.getX();
		double dz = target[1] - player.getZ();
		if (dx * dx + dz * dz < 0.45 * 0.45 && this.waypoint < this.waypoints.size() - 1) {
			this.waypoint++;
			return;
		}
		if (dx * dx + dz * dz < 0.45 * 0.45 && this.walkPartial) {
			// as far as the loaded ground went: plan again (the next chunks have come in meanwhile)
			if (++this.walkReplans > 40) {
				this.keys(false, false);
				this.finish(this.result(false, "too_far"));
				return;
			}
			this.startZoneWalk();
			return;
		}
		float want = (float) (Mth.atan2(-dx, dz) * Mth.RAD_TO_DEG);
		float yaw = Autopilot.approach(player.getYRot(), want, Autopilot.TURN_PER_TICK);
		look(player, yaw, Autopilot.CAMERA_PITCH);
		boolean free = !InputLock.locked() && this.minecraft.screen == null;
		double moved = Math.hypot(player.getX() - this.lastX, player.getZ() - this.lastZ);
		this.lastX = player.getX();
		this.lastZ = player.getZ();
		this.stallTicks = moved < 0.01 && free ? this.stallTicks + 1 : 0;
		// past the last waypoint the walk keeps pushing into the doorway: the pass starts on the cell itself
		this.keys(free && Math.abs(Mth.wrapDegrees(want - yaw)) < 50f, this.stallTicks > 10 && this.stallTicks % 20 < 4);
		if (this.stallTicks > 8 * TICKS) {
			this.keys(false, false);
			this.mark(String.format(Locale.ROOT, "zone_walk stuck at %.1f %.1f %.1f next %.1f %.1f waypoint %d/%d around %s", player.getX(),
					player.getY(), player.getZ(), target[0], target[1], this.waypoint, this.waypoints.size(), this.around(player)));
			this.finish(this.result(false, "stuck"));
		}
	}

	/**
	 * A column one block up the walk may climb onto: a real step under it (not a barrier, an edge wall, a ledge's collider,
	 * a fence or a wall: those are the zone's invisible walls and borders, never ground).
	 */
	private static boolean stepUp(ClientLevel level, int x, int feet, int z) {
		BlockPos step = new BlockPos(x, feet, z);
		BlockState state = level.getBlockState(step);
		if (state.is(Blocks.BARRIER) || state.getBlock() instanceof EdgeWallBlock || state.getBlock() instanceof LedgeColliderBlock) {
			return false;
		}
		VoxelShape shape = state.getCollisionShape(level, step);
		return !shape.isEmpty() && shape.max(Direction.Axis.Y) <= 1.0 && Autopilot.standable(level, x, feet + 1, z);
	}

	/** The blocks beside the player at its feet and head (Minecraft's ids: no game data), for a stuck walk's report. */
	private String around(LocalPlayer player) {
		ClientLevel level = this.minecraft.level;
		if (level == null) {
			return "-";
		}
		BlockPos at = player.blockPosition();
		StringBuilder out = new StringBuilder();
		for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
			out.append('[').append(d[0]).append(',').append(d[1]).append(':');
			for (int dy = -1; dy <= 1; dy++) {
				BlockPos pos = at.offset(d[0], dy, d[1]);
				out.append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath());
				out.append(dy < 1 ? "/" : "");
			}
			out.append(']');
		}
		return out.toString();
	}

	private void keys(boolean forward, boolean jump) {
		this.forward(forward);
		this.minecraft.options.keyJump.setDown(jump);
	}

	// ---- typing a name ----

	private void typeTick() {
		if (this.currentTicks % TYPE_EVERY != 0 || !this.releases.isEmpty()) {
			return;
		}
		UiSession top = this.topUi();
		NamingModel naming = top == null ? null : top.naming();
		if (naming == null) {
			if (this.typingConfirmed) {
				this.finish(this.result(true, null)); // START sent the name and the screen closed
			} else if (this.currentTicks > 10 * TICKS) {
				this.finish(this.result(false, "no_naming"));
			}
			return;
		}
		if (naming.phase() == NamingModel.Phase.PRESETS) {
			// entry 0 is NEW NAME: the grid
			this.press(naming.presetCursor() == 0 ? "a" : "up", 1);
			return;
		}
		if (this.typing.isEmpty()) {
			if (!this.typingConfirmed) {
				this.typingConfirmed = true;
				this.press("start", 1);
			} else {
				this.finish(this.result(true, null));
			}
			return;
		}
		int[] at = find(naming, this.typing.peek());
		if (at == null) {
			this.typing.poll(); // not on this grid: left out
			return;
		}
		if (naming.row() != at[0]) {
			this.press(naming.row() < at[0] ? "down" : "up", 1);
		} else if (naming.col() != at[1]) {
			this.press(naming.col() < at[1] ? "right" : "left", 1);
		} else {
			this.typing.poll();
			this.press("a", 1);
		}
	}

	/**
	 * A species' name, upper case and letters only: the naming screen's dex number (Cobblemon's species), else the party's
	 * last Pokémon's; empty when neither is known.
	 */
	private static String speciesName(@Nullable Integer dex) {
		String name = "";
		Species species = dex == null ? null : PokemonSpecies.getByPokedexNumber(dex, "cobblemon");
		if (species != null) {
			name = species.getName();
		} else {
			for (Pokemon pokemon : CobblemonClient.INSTANCE.getStorage().getParty().getSlots()) {
				if (pokemon != null) {
					name = pokemon.getSpecies().getName();
				}
			}
		}
		return name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
	}

	private static int @Nullable [] find(NamingModel naming, @Nullable String glyph) {
		List<List<NamingModel.Cell>> rows = naming.rows();
		for (int r = 0; r < rows.size(); r++) {
			for (int c = 0; c < rows.get(r).size(); c++) {
				NamingModel.Cell cell = rows.get(r).get(c);
				if (cell != null && cell.glyph() != null && cell.glyph().equals(glyph)) {
					return new int[] {r, c};
				}
			}
		}
		return null;
	}

	// ---- battles ----

	/** Cobblemon asks: the battle screen shows the moves, then the first one with PP left is chosen. */
	private void battleTick() {
		ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
		if (battle == null || !battle.getMustChoose()) {
			this.movesShownAt = -1;
			return;
		}
		if (!(this.minecraft.screen instanceof BattleGUI gui)) {
			if (this.minecraft.screen == null) {
				this.minecraft.setScreen(new BattleGUI());
			}
			return;
		}
		SingleActionRequest request = battle.getFirstUnansweredRequest();
		if (request == null || request.getMoveSet() == null || request.getForceSwitch()) {
			return;
		}
		if (this.movesShownAt < 0) {
			this.movesShownAt = this.ticks;
			gui.changeActionSelection(new BattleMoveSelection(gui, request));
			return;
		}
		if (this.ticks - this.movesShownAt < MOVE_SHOWN_TICKS) {
			return;
		}
		BattleMoveSelection moves = gui.getCurrentActionSelection() instanceof BattleMoveSelection shown ? shown
				: new BattleMoveSelection(gui, request);
		for (BattleMoveSelection.MoveTile tile : moves.getMoveTiles()) {
			InBattleMove move = tile.getMove();
			if (move.getPp() > 0 && !move.getDisabled()) {
				gui.selectAction(request, tile.getResponse());
				this.battleChoices++;
				this.mark("battle_choice n=" + this.battleChoices);
				break;
			}
		}
		this.movesShownAt = -1;
	}

	// ---- state ----

	private void writeState() {
		JsonObject out = new JsonObject();
		out.addProperty("tick", this.ticks);
		out.addProperty("phase", this.phase.name().toLowerCase(Locale.ROOT));
		out.addProperty("done", this.lastDone);
		out.add("result", this.lastResult);
		out.addProperty("busy", this.current != null);
		out.addProperty("queued", this.queue.size());
		Screen screen = this.minecraft.screen;
		out.addProperty("screen", screen == null ? null : screen.getClass().getSimpleName());
		UiSession top = this.topUi();
		if (top != null) {
			JsonObject ui = new JsonObject();
			ui.addProperty("id", top.id());
			ui.addProperty("kind", top.kind().id());
			ui.addProperty("screen", top.screen());
			ui.addProperty("answered", top.answered(UiRuntime.get().now()));
			TextBoxModel text = top.text();
			if (text != null) {
				ui.addProperty("page", text.page());
				ui.addProperty("pages", text.pageCount());
				ui.addProperty("arrow", text.arrow());
				ui.addProperty("stop", String.valueOf(text.stop()).toLowerCase(Locale.ROOT));
				ui.addProperty("choice", text.choice());
			}
			MenuModel menu = top.menu();
			if (menu != null) {
				ui.addProperty("index", menu.index());
				ui.addProperty("count", menu.items().size());
			}
			ChoiceModel choice = top.choice();
			if (choice != null) {
				ui.addProperty("index", choice.index());
				ui.addProperty("count", choice.labels().size());
			}
			NamingModel naming = top.naming();
			if (naming != null) {
				ui.addProperty("naming", naming.phase().name().toLowerCase(Locale.ROOT));
				ui.addProperty("typed", naming.typed().size());
				ui.addProperty("presets", naming.presets().size());
				ui.addProperty("preset", naming.presetCursor());
				ui.addProperty("mon", naming.mon() != null);
			}
			out.add("ui", ui);
		}
		ClientBridgeState state = this.state();
		if (state != null) {
			out.addProperty("ingame", state.ingame());
			out.addProperty("map", state.map());
			out.addProperty("x", state.x());
			out.addProperty("y", state.y());
			out.addProperty("facing", state.facing());
			out.addProperty("mode", state.mode());
			WorldIndex index = ClientWorldIndex.current();
			BlockPos cell = index == null || state.map() == null ? null : index.cellOrigin(state.map(), state.x(), state.y());
			if (cell != null) {
				// where the hidden player's cell is in the world (its north-west block)
				JsonObject at = new JsonObject();
				at.addProperty("x", cell.getX());
				at.addProperty("z", cell.getZ());
				out.add("hidden_block", at);
			}
		}
		MovementSync.Stats move = MoveRuntime.stats();
		if (move != null) {
			JsonObject counts = new JsonObject();
			counts.addProperty("steps", move.steps);
			counts.addProperty("pushes", move.pushes);
			counts.addProperty("blocked", move.blocked);
			counts.addProperty("snaps", move.snaps);
			counts.addProperty("drifts", move.drifts);
			counts.addProperty("steers", move.steers);
			counts.addProperty("teleports", move.teleports);
			counts.addProperty("jumps", move.jumps);
			counts.addProperty("forced", move.forced);
			counts.addProperty("syncs", move.syncs);
			counts.addProperty("positions", move.positions);
			out.add("move", counts);
		}
		Links links = Links.get();
		if (links != null) {
			BridgeLink followed = links.followed();
			out.addProperty("followed", followed == null ? null : followed.game());
			out.addProperty("region", followed == null ? null : followed.region());
			JsonArray all = new JsonArray();
			for (BridgeLink link : links.all()) {
				JsonObject one = new JsonObject();
				one.addProperty("game", link.game() != null ? link.game() : link.configuredGame());
				one.addProperty("connected", link.connected());
				one.addProperty("followed", link == followed);
				BridgeLink.Status status = link.lastStatus();
				if (status != null) {
					one.addProperty("playthrough", status.playthrough());
					one.addProperty("ingame", status.ingame());
				}
				all.add(one);
			}
			out.add("links", all);
		}
		out.addProperty("locked", InputLock.locked());
		PassRuntime pass = PassRuntime.get();
		if (pass != null) {
			out.addProperty("crossing", pass.crossing());
			JsonObject counts = new JsonObject();
			counts.addProperty("crossings", pass.stats().crossings);
			counts.addProperty("starts", pass.stats().starts);
			counts.addProperty("stashes", pass.stats().stashes);
			counts.addProperty("aborts", pass.stats().aborts);
			counts.addProperty("timeouts", pass.stats().timeouts);
			counts.addProperty("server_errors", pass.stats().serverErrors);
			out.add("pass", counts);
		}
		ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
		out.addProperty("battle", battle != null);
		out.addProperty("must_choose", battle != null && battle.getMustChoose());
		out.addProperty("battle_choices", this.battleChoices);
		LocalPlayer player = this.minecraft.player;
		if (player != null) {
			JsonObject at = new JsonObject();
			at.addProperty("x", Math.round(player.getX() * 100) / 100.0);
			at.addProperty("y", Math.round(player.getY() * 100) / 100.0);
			at.addProperty("z", Math.round(player.getZ() * 100) / 100.0);
			at.addProperty("yaw", Math.round(player.getYRot()));
			out.add("player", at);
		}
		out.addProperty("fps", this.minecraft.getFps());
		out.addProperty("recording", this.recordStartNanos != 0);
		Path file = this.dir.resolve("state.json");
		Path temp = this.dir.resolve("state.json.tmp");
		try {
			Files.writeString(temp, out.toString(), StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException ignored) {
			// the next one
		}
	}

	private @Nullable UiSession topUi() {
		UiRuntime ui = UiRuntime.get();
		return ui == null ? null : ui.router().top();
	}

	private @Nullable ClientBridgeState state() {
		BridgeRuntime bridge = BridgeRuntime.get();
		return bridge == null ? null : bridge.state();
	}

	// ---- recording ----

	/** Records the window into {@code file} (as the autopilot's scenes do). */
	private void startRecording(@Nullable Path file) {
		this.stopRecording();
		if (file == null) {
			return;
		}
		this.recordStartNanos = System.nanoTime();
		this.recording = file;
		String display = System.getenv("DISPLAY");
		int w = this.minecraft.getWindow().getWidth() & ~1;
		int h = this.minecraft.getWindow().getHeight() & ~1;
		List<String> command = new ArrayList<>(List.of(System.getProperty(Autopilot.PROPERTY + ".ffmpeg", "ffmpeg"), "-y", "-loglevel",
				"error", "-f", "x11grab", "-draw_mouse", "0", "-framerate", "30", "-video_size", w + "x" + h, "-i",
				(display == null ? ":0" : display) + "+0,0", "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p",
				"-movflags", "+faststart", file.toString()));
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
		Process process = this.ffmpeg;
		this.ffmpeg = null;
		if (process == null) {
			return;
		}
		try (OutputStream in = process.getOutputStream()) {
			in.write('q');
			in.flush();
		} catch (IOException ignored) {
			// ffmpeg already gone
		}
		try {
			if (!process.waitFor(30, TimeUnit.SECONDS)) {
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

	// ---- helpers ----

	private void options() {
		var o = this.minecraft.options;
		o.renderDistance().set(5);
		o.simulationDistance().set(6);
		o.framerateLimit().set(60);
		o.enableVsync().set(false);
		o.graphicsMode().set(GraphicsStatus.FANCY);
		o.cloudStatus().set(CloudStatus.OFF);
		o.bobView().set(false);
		o.fov().set(70);
		o.pauseOnLostFocus = false;
		o.tutorialStep = TutorialSteps.NONE;
		o.save();
		this.mark("options");
	}

	private static void look(LocalPlayer player, float yaw, float pitch) {
		player.setYRot(yaw);
		player.setYHeadRot(yaw);
		player.setXRot(pitch);
	}

	private void next(Phase phase) {
		this.phase = phase;
		this.phaseTicks = 0;
		this.stableTicks = 0;
	}

	private static @Nullable String text(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
	}

	private static int number(JsonObject object, String key, int fallback) {
		JsonElement value = object.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsInt() : fallback;
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
}
