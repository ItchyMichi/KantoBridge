package dev.kantobridge.field;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kantobridge.McBootstrap;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.client.move.ForcedMover;
import dev.kantobridge.client.move.MoveController;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.story.StoryRules;
import dev.kantobridge.story.StoryState;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import dev.kantobridge.world.WorldIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * FA6 (docs/specs/FIELD_MECHANICS_AUDIT_PLAN.md §4.3, "Replay"): every row of the live sweep (the field check's
 * g{1,2,3}_field_sweep_*.json with the messages the stand-in client heard, g{1,2,3}_field_stream_*.json) is played
 * through the client's own movement logic ({@link MoveController}: MovementSync, ForcedMover, WarpFade and the lock) on
 * the planned world, with the player's box moved through the planned blocks' collision ({@link PlanPhysics}) and the
 * server's teleports answered as {@code WarpService} answers them (the player put at the cell's {@code toBlock}). The
 * row's step is walked as a player walks it (the controller sends the step when the box crosses into the cell); the
 * recorded answers are given the controller's step numbers. When the rows' messages are done and the client has
 * settled (4 s), the cell under the player must be the engine's own end cell (rule C, "left behind" otherwise).
 * Counts by class, kind and outcome; up to 30 examples per outcome (map symbols and cells only).
 */
final class FieldReplay {
	private static final double WALK = 0.215;   // blocks per tick, Minecraft's walking speed with Rules' speed
	private static final int SETTLE_TICKS = 80;
	private static final int EXAMPLES = 30;

	private FieldReplay() {
	}

	/** One player, one controller. */
	private static final class Rig implements MoveController.Port {
		final List<String> bridge = new ArrayList<>();
		final List<JsonObject> warpRequests = new ArrayList<>();
		final List<Long> warpSeqs = new ArrayList<>();
		final MoveController controller = new MoveController(this);
		final WorldIndex index;
		final PlanPhysics physics;
		StoryState state;
		long seq = 1;
		long now = 5_000;
		double x;
		double y;
		double z;
		String mode = "idle";
		int teleports;
		int stepsSent;
		final List<Long> stepNs = new ArrayList<>();

		Rig(WorldIndex index, PlanPhysics physics, StoryState state) {
			this.index = index;
			this.physics = physics;
			this.state = state;
		}

		@Override
		public boolean toBridge(String type, JsonObject data) {
			this.bridge.add(type);
			if ("move.step".equals(type)) {
				this.stepsSent++;
				this.stepNs.add(data.get("n").getAsLong());
			}
			return true;
		}

		@Override
		public long toBridgeTracked(String type, JsonObject data) {
			this.toBridge(type, data);
			return this.seq++;
		}

		@Override
		public long toServer(String type, JsonObject data) {
			long s = this.seq++;
			if ("warp.request".equals(type)) {
				this.warpRequests.add(data);
				this.warpSeqs.add(s);
			}
			return s;
		}

		@Override
		public boolean destinationReady(@Nullable Vec3 target) {
			return true;
		}

		@Override
		public void warn(String area, String code, long count) {
		}

		void at(String map, int cx, int cy) {
			Vec3 c = this.index.toBlock(map, cx, cy);
			this.x = c.x;
			this.y = c.y;
			this.z = c.z;
			Double rest = this.physics.restingY(this.x, this.y + 1.0, this.z, this.state, 4.0);
			if (rest != null) {
				this.y = rest;
			}
		}

		void bridge(String type, @Nullable Long re, JsonObject data) throws Exception {
			if ("player.state".equals(type) && data.has("mode")) {
				this.mode = data.get("mode").getAsString();
			}
			this.controller.onBridgeMessage(new Envelope(type, this.seq++, re, ProtoJson.parseObject(data.toString())), this.now);
		}

		/** One 50 ms tick: the forced velocity or the player's own walk, through the collision; server teleports answered. */
		void tick(@Nullable Dir intent) throws Exception {
			this.now += 50;
			ForcedMover.Velocity v = this.controller.beforeMove(this.now,
					new MoveController.Frame(this.index, false, true, true, this.mode, false, false, this.x, this.z));
			double dx = 0;
			double dz = 0;
			if (v != null) {
				dx = v.x();
				dz = v.z();
			} else if (intent != null && this.controller.reason() == dev.kantobridge.client.move.InputLock.Reason.NONE) {
				dx = intent.dx() * WALK;
				dz = intent.dy() * WALK;
			}
			if (dx != 0 || dz != 0) {
				double[] p = this.physics.move(this.x, this.y, this.z, dx, dz, this.state);
				this.x = p[0];
				this.y = p[1];
				this.z = p[2];
			}
			float yaw = intent == null ? 0F : intent.yaw();
			this.controller.afterMove(this.now, this.x, this.y, this.z, intent, yaw);
			while (!this.warpRequests.isEmpty()) {
				JsonObject req = this.warpRequests.remove(0);
				long s = this.warpSeqs.remove(0);
				JsonObject to = req.getAsJsonObject("to");
				Vec3 c = this.index.toBlock(to.get("map").getAsString(), to.get("x").getAsInt(), to.get("y").getAsInt());
				JsonObject done = new JsonObject();
				done.addProperty("ok", c != null);
				if (c != null) {
					this.x = c.x;
					this.y = c.y;
					this.z = c.z;
					this.teleports++;
					done.add("to", to);
				}
				this.controller.onServerMessage(new Envelope("warp.done", this.seq++, s, ProtoJson.parseObject(done.toString())), this.now);
			}
		}

		@Nullable CellPos cell() {
			Optional<CellPos> c = this.index.toCell((int) Math.floor(this.x), (int) Math.floor(this.y + 1.0E-3), (int) Math.floor(this.z));
			return c.orElse(null);
		}
	}

	/** The row's messages, each printed (type, kind, phase, cell, n: never text) with the client's counters as it is fed. */
	private static List<JsonObject> traced(List<JsonObject> msgs, Rig rig) {
		return new java.util.AbstractList<>() {
			@Override
			public JsonObject get(int i) {
				JsonObject m = msgs.get(i);
				JsonObject data = m.has("data") ? m.getAsJsonObject("data") : new JsonObject();
				StringBuilder b = new StringBuilder("TRACE ").append(m.has("type") ? m.get("type").getAsString() : "?");
				for (String k : new String[] {"phase", "kind", "map", "x", "y", "n", "reason"}) {
					if (data.has(k) && data.get(k).isJsonPrimitive()) {
						b.append(' ').append(k).append('=').append(data.get(k).getAsString());
					}
				}
				if (data.has("to") && data.get("to").isJsonObject()) {
					b.append(" to=").append(data.get("to"));
				}
				b.append(" | ").append(statsLine(rig)).append(" cell ").append(rig.cell());
				System.out.println(b);
				return m;
			}

			@Override
			public int size() {
				return msgs.size();
			}
		};
	}

	private static String statsLine(Rig rig) {
		dev.kantobridge.client.move.MovementSync.Stats s = rig.controller.sync().stats();
		return "positions " + s.positions + " forced " + s.forced + " snaps " + s.snaps + " drifts " + s.drifts + " steers " + s.steers
				+ " teleports " + s.teleports + " syncs " + s.syncs + " pending " + rig.controller.sync().pendingSteps()
				+ " warping " + rig.controller.fade().holding();
	}

	static JsonObject run(Path bundleFile, Path reports, String prefix) throws Exception {
		McBootstrap.ensure();
		ExportBundle bundle = ExportBundle.open(bundleFile);
		String family = FieldParity.family(bundle);
		WorldPlan plan = FieldParity.plan(bundle);
		WorldIndex index = plan.index();
		PlanPhysics physics = new PlanPhysics(plan);
		Map<String, StoryCellTable.Entry> byKey = new HashMap<>();
		for (StoryCellTable.Entry e : plan.storyCells().entries()) {
			byKey.put(e.key(), e);
		}
		Map<String, Integer> counts = new TreeMap<>();
		Map<String, Integer> byClass = new TreeMap<>();
		Map<String, JsonArray> examples = new TreeMap<>();
		List<Path> sweeps = new ArrayList<>();
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(reports, prefix + "_field_sweep_*.json")) {
			ds.forEach(sweeps::add);
		}
		sweeps.sort(null);
		for (Path sweepFile : sweeps) {
			String tag = sweepFile.getFileName().toString().substring((prefix + "_field_sweep").length()).replace(".json", "");
			if (tag.startsWith("_analysis")) {
				continue;
			}
			String trace = System.getProperty("kb.fieldReplayTrace", "");
			if (!trace.isBlank() && !tag.equals("_" + trace.split(":")[0])) {
				continue;
			}
			Path streamFile = reports.resolve(prefix + "_field_stream" + tag + ".json");
			if (!Files.isRegularFile(streamFile)) {
				counts.merge("sweeps_without_stream", 1, Integer::sum);
				continue;
			}
			JsonObject sweep = JsonParser.parseString(Files.readString(sweepFile, StandardCharsets.UTF_8)).getAsJsonObject();
			JsonObject stream = JsonParser.parseString(Files.readString(streamFile, StandardCharsets.UTF_8)).getAsJsonObject();
			Map<Long, List<JsonObject>> byRow = new HashMap<>();
			for (JsonElement e : stream.getAsJsonArray("received")) {
				JsonObject m = e.getAsJsonObject();
				if (m.has("row") && !m.get("row").isJsonNull()) {
					byRow.computeIfAbsent(m.get("row").getAsLong(), k -> new ArrayList<>()).add(m);
				}
			}
			JsonObject done = stream.getAsJsonObject("rows");
			for (JsonElement item : sweep.getAsJsonArray("rows")) {
				JsonObject rec = item.getAsJsonObject();
				long n = rec.get("n").getAsLong();
				String cls = rec.has("cls") ? rec.get("cls").getAsString() : "?";
				String clsKey = cls.startsWith("warp:") ? cls : cls;
				if (rec.has("err") && !rec.get("err").isJsonNull() || !rec.has("end") || !rec.has("start")
						|| !rec.getAsJsonObject("start").has("x")) {
					counts.merge("rows_skipped", 1, Integer::sum);
					continue;
				}
				String map = FieldParity.mcId(family, rec.get("map").getAsString());
				JsonObject start = rec.getAsJsonObject("start");
				JsonObject end = rec.getAsJsonObject("end");
				String endMap = FieldParity.mcId(family, end.get("map").getAsString());
				if (index.map(map) == null || index.map(endMap) == null) {
					counts.merge("rows_map_not_built", 1, Integer::sum);
					continue;
				}
				counts.merge("rows", 1, Integer::sum);
				boolean surf = start.has("surfing") && start.get("surfing").getAsBoolean();
				StoryRules story = new StoryRules();
				story.setIndex(index);
				story.onPlayerState(stateJson(surf));
				story.onPlayerMap(map);
				StoryState st = storyState(story, byKey, surf);
				Rig rig = new Rig(index, physics, st);
				int sx = start.get("x").getAsInt();
				int sy = start.get("y").getAsInt();
				String facing = start.has("facing") ? start.get("facing").getAsString() : "down";
				rig.at(map, sx, sy);
				JsonObject snap = new JsonObject();
				snap.addProperty("ingame", true);
				JsonObject pl = new JsonObject();
				pl.addProperty("map", map);
				pl.addProperty("x", sx);
				pl.addProperty("y", sy);
				pl.addProperty("facing", facing);
				snap.add("player", pl);
				snap.add("state", stateJson(surf));
				rig.bridge("snapshot", null, snap);
				rig.tick(null);
				// the row's step(s), walked as a player walks: the controller sends a step when the box crosses into the cell
				List<String> dirs = new ArrayList<>();
				JsonObject d = done != null && done.has(Long.toString(n)) ? done.getAsJsonObject(Long.toString(n)) : null;
				JsonObject ready = d != null && d.has("ready") ? d.getAsJsonObject("ready") : null;
				String dirsText = ready == null ? null : ready.has("dirs") ? ready.get("dirs").getAsString()
						: ready.has("dir") ? ready.get("dir").getAsString() : null;
				if (dirsText != null) {
					for (String one : dirsText.replace('+', ',').split(",")) {
						if (Dir.byId(one) != null) {
							dirs.add(one);
						}
					}
				}
				List<JsonObject> msgs = byRow.getOrDefault(n, List.of());
				boolean tracing = !trace.isBlank() && trace.endsWith(":" + n);
				if (tracing) {
					msgs = traced(msgs, rig);
				}
				int mi = 0;
				Map<Long, Long> nMap = new HashMap<>();
				for (int i = 0; i < dirs.size(); i++) {
					Dir dir = Dir.byId(dirs.get(i));
					int sent = rig.stepsSent;
					for (int t = 0; t < 14 && rig.stepsSent == sent; t++) {
						rig.tick(dir);
					}
					long rowN = i == 0 ? n : n * 1000 + i;
					if (rig.stepsSent > sent) {
						nMap.put(rowN, rig.stepNs.get(rig.stepNs.size() - 1));
					} else {
						counts.merge("steps_not_sent", 1, Integer::sum);
						example(examples, "step_not_sent", rec);
					}
					// the answer for this step and whatever came with it
					while (mi < msgs.size()) {
						JsonObject m = msgs.get(mi);
						JsonObject data = m.has("data") ? m.getAsJsonObject("data") : new JsonObject();
						mi++;
						feed(rig, story, byKey, m, data, nMap);
						rig.tick(null);
						if (data.has("n") && data.get("n").getAsLong() == rowN) {
							break;
						}
					}
				}
				while (mi < msgs.size()) {
					JsonObject m = msgs.get(mi++);
					JsonObject data = m.has("data") ? m.getAsJsonObject("data") : new JsonObject();
					feed(rig, story, byKey, m, data, nMap);
					rig.tick(null);
					rig.tick(null);
				}
				for (int t = 0; t < SETTLE_TICKS; t++) {
					rig.tick(null);
				}
				CellPos got = rig.cell();
				if (tracing) {
					System.out.println("TRACE end " + got + " teleports " + rig.teleports + " " + statsLine(rig));
				}
				boolean ok = got != null && got.map().equals(endMap) && got.x() == end.get("x").getAsInt() && got.y() == end.get("y").getAsInt();
				String outcome = ok ? "ok" : got == null ? "C:nowhere" : !got.map().equals(endMap) ? "C:other_map" : "C:other_cell";
				byClass.merge(clsKey + ":" + outcome + (rig.teleports > 0 ? "+teleport" : ""), 1, Integer::sum);
				counts.merge(outcome, 1, Integer::sum);
				if (rig.teleports > 0) {
					counts.merge("rows_with_teleport", 1, Integer::sum);
				}
				if (!ok) {
					JsonObject e = example(examples, outcome + ":" + clsKey, rec);
					if (e != null) {
						e.addProperty("client", got == null ? "none" : got.map() + ":" + got.x() + ":" + got.y());
						e.addProperty("engine", endMap + ":" + end.get("x").getAsInt() + ":" + end.get("y").getAsInt());
						e.addProperty("teleports", rig.teleports);
					}
				}
				physics.trim();
			}
		}
		JsonObject out = new JsonObject();
		JsonObject c = new JsonObject();
		counts.forEach(c::addProperty);
		out.add("counts", c);
		JsonObject b = new JsonObject();
		byClass.forEach(b::addProperty);
		out.add("by_class", b);
		JsonObject ex = new JsonObject();
		examples.forEach(ex::add);
		out.add("examples", ex);
		out.addProperty("family", family);
		return out;
	}

	private static void feed(Rig rig, StoryRules story, Map<String, StoryCellTable.Entry> byKey, JsonObject m, JsonObject data,
			Map<Long, Long> nMap) throws Exception {
		String type = m.get("type").getAsString();
		Long re = m.has("re") && !m.get("re").isJsonNull() ? m.get("re").getAsLong() : null;
		switch (type) {
			case "story.cells" -> {
				story.onCells(data);
				rig.state = storyState(story, byKey, rig.state.surf());
			}
			case "story.blocks" -> {
				story.onBlocks(data);
				rig.state = storyState(story, byKey, rig.state.surf());
			}
			case "player.state" -> {
				story.onPlayerState(data);
				boolean surf = data.has("surf") && data.get("surf").getAsBoolean();
				rig.state = storyState(story, byKey, surf);
				rig.bridge(type, re, data);
			}
			case "warp" -> {
				if ("arrive".equals(data.has("phase") ? data.get("phase").getAsString() : "") && data.has("to")) {
					story.onPlayerMap(data.getAsJsonObject("to").get("map").getAsString());
				}
				rig.bridge(type, re, data);
			}
			case "player.pos", "player.blocked" -> {
				JsonObject copy = data.deepCopy();
				if (copy.has("n")) {
					Long mapped = nMap.get(copy.get("n").getAsLong());
					if (mapped == null) {
						copy.remove("n");
					} else {
						copy.addProperty("n", mapped);
					}
				}
				if ("player.pos".equals(type) && copy.has("map")) {
					story.onPlayerMap(copy.get("map").getAsString());
				}
				rig.bridge(type, re, copy);
			}
			case "snapshot", "warp.taken" -> rig.bridge(type, re, data);
			default -> {
			}
		}
	}

	private static JsonObject stateJson(boolean surf) {
		return JsonParser.parseString("{\"mode\":\"idle\",\"bike\":false,\"surf\":" + surf
				+ ",\"slope\":false,\"hidden\":false,\"dark\":false,\"lit\":false}").getAsJsonObject();
	}

	private static StoryState storyState(StoryRules rules, Map<String, StoryCellTable.Entry> byKey, boolean surf) {
		Set<String> active = new HashSet<>();
		for (String key : rules.cells().active()) {
			StoryCellTable.Entry e = byKey.get(key);
			if (e != null && (e.type() == StoryCellType.OBJECT || e.type() == StoryCellType.BOULDER_AREA)) {
				continue;
			}
			active.add(key);
		}
		StoryState st = new StoryState();
		st.setActive(active);
		st.setSurf(surf);
		return st;
	}

	private static @Nullable JsonObject example(Map<String, JsonArray> examples, String key, JsonObject rec) {
		JsonArray list = examples.computeIfAbsent(key, k -> new JsonArray());
		if (list.size() >= EXAMPLES) {
			return null;
		}
		JsonObject e = new JsonObject();
		e.addProperty("n", rec.get("n").getAsLong());
		e.addProperty("map", rec.get("map").getAsString());
		e.addProperty("cls", rec.has("cls") ? rec.get("cls").getAsString() : "?");
		JsonObject s = rec.getAsJsonObject("start");
		e.addProperty("start", s.get("x").getAsInt() + "," + s.get("y").getAsInt());
		list.add(e);
		return e;
	}

	/** kb.fieldReplay = "<export bundle>;<reports dir>;<g1|g2|g3>;<report out>". */
	static void runFromProperty(String wantFamily) throws Exception {
		String[] parts = System.getProperty("kb.fieldReplay", "").split(";");
		if (parts.length < 4) {
			throw new IllegalArgumentException("kb.fieldReplay wants <bundle>;<reports dir>;<prefix>;<out>");
		}
		ExportBundle probe = ExportBundle.open(Path.of(parts[0]));
		if (!wantFamily.equals(FieldParity.family(probe))) {
			return;
		}
		JsonObject report = run(Path.of(parts[0]), Path.of(parts[1]), parts[2]);
		Files.writeString(Path.of(parts[3]), report.toString(), StandardCharsets.UTF_8);
	}
}
