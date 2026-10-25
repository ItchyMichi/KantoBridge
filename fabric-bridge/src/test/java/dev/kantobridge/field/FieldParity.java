package dev.kantobridge.field;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kantobridge.McBootstrap;
import dev.kantobridge.block.EdgeWallBlock;
import dev.kantobridge.block.LedgeColliderBlock;
import dev.kantobridge.block.SeatColliderBlock;
import dev.kantobridge.block.StepColliderBlock;
import dev.kantobridge.builder.TestData;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.place.CodeStates;
import dev.kantobridge.builder.plan.BlockCode;
import dev.kantobridge.builder.plan.ChunkCodesView;
import dev.kantobridge.builder.plan.PlacedMap;
import dev.kantobridge.builder.plan.RegionBuild;
import dev.kantobridge.builder.plan.WorldPlan;
import dev.kantobridge.builder.plan.WorldPlanner;
import dev.kantobridge.story.StoryRules;
import dev.kantobridge.story.StoryState;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.SlotMap;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import dev.kantobridge.world.WorldIndex;
import java.nio.charset.StandardCharsets;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * FA2 (docs/specs/FIELD_MECHANICS_AUDIT_PLAN.md §3.5, §4.3): collision parity between the hidden game and the built
 * world. The real export bundle is planned as a build plans it (today's code, the build options the test JVM gets:
 * kantobridge.outdoorLevels, kantobridge.chainLevels, kantobridge.landscape.models, kb.caves3d), and every cell and
 * direction of the engine's own verdicts (the field check's parity dump, written by drivers/kb_g{1,2,3}field.lua) is
 * compared with what Minecraft's collision does there: a player box (0.6 x 1.8, step height 0.6, no jumping, as
 * {@code Rules} sets the player) is moved from the cell's middle two blocks towards the neighbour through the planned
 * blocks' collision shapes, and lands in a cell or doesn't. Story cells take the new game's state, with the visit's
 * live overrides fed through {@link StoryRules} as the bridge sends them; objects (people, boulders) are left out on
 * both sides (terrain). The ledge collider and the story blocks, whose shapes depend on the player, are modelled from
 * their own rules ({@link LedgeColliderBlock#entirelyDownhill}, {@link StoryState#blocks}).
 *
 * <p>Outcomes per cell, direction and state (walking, surfing): <b>A</b> the game refuses and Minecraft lets the player
 * through; <b>B</b> the game allows and Minecraft stops the player (or lands it in another cell); agreements otherwise.
 * A ledge hop counts as allowed into the ledge cell. Counted by map, class and kind; up to 40 examples per class with map
 * symbol, cell and direction (positions only, never art or text). Pure Java over the plan; no world, no game.
 */
final class FieldParity {
	static final String[] DIRS = {"up", "down", "left", "right"};
	static final int[][] DELTA = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
	private static final double HALF = 0.3;
	private static final double HEIGHT = 1.8;
	private static final double STEP_UP = 0.6;
	private static final double EPS = 1.0E-6;
	private static final double MOVE = 0.125;
	private static final VoxelShape HALF_SHAPE = Block.box(0, 0, 0, 16, 8, 16);
	private static final int EXAMPLES = 40;
	/** One letter per block kind (BlockCode.KIND_*): air, Full, Half, Ledge, light, Story, sTory tile, sHape, decor, Vanilla, prop, Edge, steP, ladder, seat, ? */
	private static final String KIND_LETTERS = ".FHLlSTXdVpEPKsB?";

	private static WorldIndex REAL;

	private FieldParity() {
	}

	/** The family of a bundle, as the planners have it. */
	static String family(ExportBundle bundle) {
		return bundle.isGen3() ? "gen3" : bundle.isGen2() ? "gen2" : "gen1";
	}

	static WorldPlan plan(ExportBundle bundle) throws Exception {
		String family = family(bundle);
		WorldPlanner.Options options = new WorldPlanner.Options(null, 0, 0, TestData.MIN_BUILD_HEIGHT, true);
		return switch (family) {
			case "gen3" -> WorldPlanner.plan(bundle, options,
					new RegionBuild("hoenn", 1, bundle.game(), new SlotMap.Range(0, SlotMap.familiesFor("gen3") - 1), null, "hoenn_"));
			case "gen2" -> WorldPlanner.plan(bundle, options,
					new RegionBuild("johto", 2, bundle.game(), new SlotMap.Range(0, SlotMap.familiesFor("gen2") - 1), null, "johto_"));
			default -> WorldPlanner.plan(bundle, options);
		};
	}

	/** The world index's id of an engine map. */
	static String mcId(String family, String engineMap) {
		return "gen2".equals(family) && !engineMap.startsWith("G2_") ? "G2_" + engineMap : engineMap;
	}

	/** Runs the comparison and returns the report (counts, codes, map ids, a few example cells). */
	static JsonObject run(Path dumpFile, Path bundleFile) throws Exception {
		McBootstrap.ensure();
		ExportBundle bundle = ExportBundle.open(bundleFile);
		String family = family(bundle);
		long started = System.nanoTime();
		WorldPlan plan = plan(bundle);
		long planned = System.nanoTime();
		WorldIndex index = plan.index();
		Map<String, PlacedMap> placed = new HashMap<>();
		for (PlacedMap map : plan.maps()) {
			placed.put(map.id(), map);
		}
		List<StoryCellTable.Entry> entries = plan.storyCells().entries();
		Map<String, StoryCellTable.Entry> byKey = new HashMap<>();
		for (StoryCellTable.Entry e : entries) {
			byKey.put(e.key(), e);
		}
		Set<CellPos> shaftEnds = new HashSet<>();
		for (WorldIndex.Shaft shaft : index.shafts()) {
			shaftEnds.add(shaft.top());
			shaftEnds.add(shaft.bottom());
		}
		JsonObject dump = JsonParser.parseString(Files.readString(dumpFile, StandardCharsets.UTF_8)).getAsJsonObject();
		Report report = new Report();
		// kb.fieldRealIndex: a built world's world_index.json, to name the example cells' real block positions (the
		// cross-check of the plan against a world on disk)
		String realPath = System.getProperty("kb.fieldRealIndex", "");
		REAL = realPath.isBlank() ? null : WorldIndex.fromJson(Files.readString(Path.of(realPath), StandardCharsets.UTF_8));
		for (Map.Entry<String, JsonElement> item : new TreeMap<>(dump.getAsJsonObject("maps").asMap()).entrySet()) {
			JsonObject rec = item.getValue().getAsJsonObject();
			if (!rec.has("walk")) {
				report.count("maps_without_verdicts");
				continue;
			}
			String engineMap = item.getKey();
			String map = mcId(family, engineMap);
			PlacedMap pm = placed.get(map);
			if (pm == null) {
				report.count("maps_not_built");
				report.mapsNotBuilt.add(engineMap);
				continue;
			}
			report.count("maps");
			MapRun run = new MapRun(plan, index, pm, map, family, byKey, entries, rec, shaftEnds, report);
			run.compare();
		}
		JsonObject out = report.toJson();
		out.addProperty("family", family);
		out.addProperty("game", bundle.game());
		out.addProperty("plan_ms", (planned - started) / 1_000_000L);
		out.addProperty("compare_ms", (System.nanoTime() - planned) / 1_000_000L);
		out.addProperty("outdoor_levels", System.getProperty("kantobridge.outdoorLevels", ""));
		out.addProperty("chain_levels", System.getProperty("kantobridge.chainLevels", ""));
		out.addProperty("landscape_models", !System.getProperty("kantobridge.landscape.models", "").isBlank());
		out.addProperty("caves3d", System.getProperty("kb.caves3d", ""));
		return out;
	}

	/** One map's comparison. */
	private static final class MapRun {
		final WorldPlan plan;
		final WorldIndex index;
		final PlacedMap pm;
		final String map;
		final String family;
		final Map<String, StoryCellTable.Entry> byKey;
		final List<StoryCellTable.Entry> entries;
		final JsonObject rec;
		final Set<CellPos> shaftEnds;
		final Report report;
		final ChunkCodesView view;
		final StoryState walkState = new StoryState();
		final StoryState surfState = new StoryState();
		final int w;
		final int h;

		MapRun(WorldPlan plan, WorldIndex index, PlacedMap pm, String map, String family, Map<String, StoryCellTable.Entry> byKey,
				List<StoryCellTable.Entry> entries, JsonObject rec, Set<CellPos> shaftEnds, Report report) {
			this.plan = plan;
			this.index = index;
			this.pm = pm;
			this.map = map;
			this.family = family;
			this.byKey = byKey;
			this.entries = entries;
			this.rec = rec;
			this.shaftEnds = shaftEnds;
			this.report = report;
			this.view = new ChunkCodesView(plan);
			this.w = rec.get("w").getAsInt();
			this.h = rec.get("h").getAsInt();
			// the client's story rules run with the hidden game's surf state: each state its own active set
			this.walkState.setActive(this.storyActive(false));
			this.surfState.setActive(this.storyActive(true));
			this.surfState.setSurf(true);
		}

		/** The new game's active story cells on this map, with the visit's live overrides (objects left out: terrain). */
		private Set<String> storyActive(boolean surf) {
			StoryRules rules = new StoryRules();
			rules.setIndex(this.index);
			rules.onPlayerState(JsonParser.parseString(
					"{\"mode\":\"idle\",\"bike\":false,\"surf\":" + surf + ",\"slope\":false,\"hidden\":false,\"dark\":false,\"lit\":false}")
					.getAsJsonObject());
			rules.onPlayerMap(this.map);
			if ("gen3".equals(this.family) && this.rec.has("overrides")) {
				JsonArray cells = new JsonArray();
				for (JsonElement e : this.rec.getAsJsonArray("overrides")) {
					JsonObject o = e.getAsJsonObject();
					JsonArray l = o.getAsJsonArray("l");
					JsonObject c = new JsonObject();
					c.addProperty("x", o.get("x").getAsInt());
					c.addProperty("y", o.get("y").getAsInt());
					c.addProperty("metatile", l.get(0).getAsInt());
					c.addProperty("pass", gen3Pass(l.get(1).getAsInt()));
					cells.add(c);
				}
				for (int from = 0; from < cells.size(); from += 1024) {
					JsonArray part = new JsonArray();
					for (int i = from; i < Math.min(cells.size(), from + 1024); i++) {
						part.add(cells.get(i));
					}
					JsonObject msg = new JsonObject();
					msg.addProperty("map", this.map);
					msg.add("cells", part);
					rules.onCells(msg);
				}
			} else if (this.rec.has("blocks") && this.rec.get("blocks").isJsonArray()) {
				JsonObject msg = new JsonObject();
				msg.addProperty("map", this.map);
				msg.add("changes", this.rec.getAsJsonArray("blocks"));
				rules.onBlocks(msg);
			}
			Set<String> active = new HashSet<>();
			for (String key : rules.cells().active()) {
				StoryCellTable.Entry e = this.byKey.get(key);
				if (e != null && (e.type() == StoryCellType.OBJECT || e.type() == StoryCellType.BOULDER_AREA)) {
					continue;
				}
				active.add(key);
			}
			return active;
		}

		void compare() {
			String walk = this.rec.get("walk").getAsString();
			String surf = this.rec.has("surf") ? this.rec.get("surf").getAsString() : null;
			String stand = this.rec.has("stand") ? this.rec.get("stand").getAsString() : null;
			for (int cy = 0; cy < this.h; cy++) {
				for (int cx = 0; cx < this.w; cx++) {
					int cell = cy * this.w + cx;
					char st = stand == null ? '.' : stand.charAt(cell);
					if (this.shaftEnds.contains(new CellPos(this.map, cx, cy))) {
						this.report.count("cells_shaft_end");
						continue;
					}
					for (int d = 0; d < 4; d++) {
						if (st == '.') {
							this.one(cx, cy, d, walk.charAt(cell * 4 + d), false);
						}
						if (surf != null && st == '~') {
							this.one(cx, cy, d, surf.charAt(cell * 4 + d), true);
						}
					}
				}
			}
		}

		private void one(int cx, int cy, int d, char verdict, boolean surfing) {
			String state = surfing ? "surf" : "walk";
			if (verdict == '?') {
				this.report.count(state + "_varies");
				return;
			}
			boolean gameOpen = verdict == '.' || verdict == 'J' || verdict == 'E';
			int tx = cx + DELTA[d][0];
			int ty = cy + DELTA[d][1];
			if (this.shaftEnds.contains(new CellPos(this.map, tx, ty))) {
				// a cave shaft's end: the client never steps there, it climbs or falls (PROTOCOL.md "Ladders and holes")
				this.report.count(state + "_into_shaft_end");
				return;
			}
			Walk mc = this.walk(cx, cy, d, surfing ? this.surfState : this.walkState);
			if (mc == null) {
				this.report.count(state + "_source_not_standable");
				this.report.example("source_not_standable:" + state, this.map, cx, cy, d, state);
				return;
			}
			if (mc.onTop) {
				// the game stands here; Minecraft has a block at the feet: an invisible wall round the whole cell
				if (d == 0) {
					this.report.disagree("B", state, "source_solid", this.map, cx, cy, d, this.detail(cx, cy, cx, cy, mc));
				}
				return;
			}
			this.report.count(state + "_compared");
			boolean mcOpen;
			if (verdict == 'E' || verdict == 'e' || verdict == 'b') {
				// off the map: the game's crossing lands on another map (or nowhere)
				mcOpen = mc.reached;
			} else {
				mcOpen = mc.reached && mc.cell != null && mc.cell.map().equals(this.map) && mc.cell.x() == tx && mc.cell.y() == ty;
				if (gameOpen && mc.reached && !mcOpen) {
					this.report.count(state + "_landed_elsewhere");
				}
			}
			String cls = this.classOf(verdict, tx, ty, d, surfing, mc);
			if (gameOpen == mcOpen) {
				this.report.agree(state, cls);
				return;
			}
			if (verdict == 'E' && (this.pm.placement().kind() == MapPlacement.Kind.SURFACE || WorldPlan.openSky(this.pm.placement()))
					&& this.beyond(cx, cy, tx, ty).isEmpty() && this.sideTouched(d)) {
				// a surface or open-air map's side past the end of the neighbour's strip (the side meets a neighbour elsewhere):
				// the engine clamps onto the neighbour's nearest cell, the original game's border stops the player, and so does
				// the build's seam wall or ring (FIELD_MECHANICS_AUDIT_PLAN §14, SC6)
				this.report.count(state + "_clamp");
				this.report.example("clamp:" + state, this.map, cx, cy, d, state);
				return;
			}
			String rule = gameOpen ? "B" : "A";
			this.report.disagree(rule, state, cls, this.map, cx, cy, d, this.detail(cx, cy, tx, ty, mc));
		}

		/** Per direction, whether any cell along the map's side in that direction meets another map's cell (null: not worked out yet). */
		private final Boolean[] touched = new Boolean[4];

		/** True when some cell along the map's side in direction {@code d} meets another map's cell beyond it. */
		private boolean sideTouched(int d) {
			if (this.touched[d] == null) {
				int w = this.pm.model().cellWidth();
				int h = this.pm.model().cellHeight();
				boolean any = false;
				int n = DELTA[d][0] != 0 ? h : w;
				for (int k = 0; k < n && !any; k++) {
					int cx = DELTA[d][0] > 0 ? w - 1 : DELTA[d][0] < 0 ? 0 : k;
					int cy = DELTA[d][1] > 0 ? h - 1 : DELTA[d][1] < 0 ? 0 : k;
					any = this.beyond(cx, cy, cx + DELTA[d][0], cy + DELTA[d][1]).isPresent();
				}
				this.touched[d] = any;
			}
			return this.touched[d];
		}

		/** The cell of another map just beyond cell (cx, cy)'s edge towards (tx, ty), at any height within 12 blocks of its feet. */
		private Optional<CellPos> beyond(int cx, int cy, int tx, int ty) {
			int[] s0 = WorldPlan.cellOrigin(this.pm.placement(), this.pm.layout(), cx, cy);
			for (int dy = 0; dy <= 12; dy++) {
				for (int sign : dy == 0 ? new int[] {1} : new int[] {1, -1}) {
					for (int k = 0; k < 2; k++) {
						int bx = s0[0] + (tx > cx ? 2 : tx < cx ? -1 : k);
						int bz = s0[2] + (ty > cy ? 2 : ty < cy ? -1 : k);
						Optional<CellPos> cell = this.index.toCell(bx, s0[1] + sign * dy, bz);
						if (cell.isPresent() && !cell.get().map().equals(this.map)) {
							return cell;
						}
					}
				}
			}
			return Optional.empty();
		}

		/**
		 * What the blocks are: the source's and target's feet levels and, per cell, the kinds of its four columns from
		 * feet - 1 to feet + 2 (codes as kind letters), and where the Minecraft walk ended.
		 */
		private String detail(int cx, int cy, int tx, int ty, Walk mc) {
			StringBuilder b = new StringBuilder();
			int[] s0 = WorldPlan.cellOrigin(this.pm.placement(), this.pm.layout(), cx, cy);
			b.append("src feet ").append(s0[1]).append(' ').append(this.columns(s0)).append(';');
			if (tx >= 0 && ty >= 0 && tx < this.pm.model().cellWidth() && ty < this.pm.model().cellHeight()) {
				int[] t0 = WorldPlan.cellOrigin(this.pm.placement(), this.pm.layout(), tx, ty);
				b.append(" dst feet ").append(t0[1]).append(' ').append(this.columns(t0)).append(" h ").append(this.pm.model().height(tx, ty))
						.append(" flags ").append(this.pm.model().flags(tx, ty))
						.append(this.pm.layout() == null ? "" : this.pm.layout().pieceAt(tx, ty) == null ? " nopiece" : " piece").append(';');
			} else {
				// off the map: the blocks just beyond the edge at the source's feet, and the cell the index names there
				int[] beyond = {s0[0] + (tx - cx) * 2, s0[1], s0[2] + (ty - cy) * 2};
				Optional<CellPos> there = this.index.toCell(beyond[0], beyond[1], beyond[2]);
				b.append(" beyond ").append(this.columns(beyond)).append(" cell ").append(there.map(c -> c.map() + ":" + c.x() + ":" + c.y()).orElse("none"));
				for (int dy = -12; dy <= 12 && there.isEmpty(); dy++) {
					Optional<CellPos> near = this.index.toCell(beyond[0], beyond[1] + dy, beyond[2]);
					if (near.isPresent()) {
						b.append(" at dy ").append(dy).append(' ').append(near.get().map()).append(':').append(near.get().x()).append(':')
								.append(near.get().y());
						break;
					}
				}
				b.append(';');
			}
			if (this.plan.shapes() != null && this.plan.shapes().shapes(this.map) != null && tx >= 0 && ty >= 0
					&& tx < this.pm.model().cellWidth() && ty < this.pm.model().cellHeight()) {
				int[] blocks = this.plan.shapes().shapes(this.map).blocks();
				int cols = this.pm.model().cellWidth() * 2;
				b.append(" planned");
				for (int q = 0; q < 4; q++) {
					int i = (ty * 2 + q / 2) * cols + tx * 2 + q % 2;
					b.append(' ').append(i >= 0 && i < blocks.length ? blocks[i] : -1);
				}
				b.append(';');
			}
			if (REAL != null) {
				net.minecraft.core.BlockPos r = REAL.cellOrigin(this.map, tx, ty);
				b.append(" real ").append(r == null ? "none" : r.getX() + "," + r.getY() + "," + r.getZ()).append(';');
			}
			if (mc != null) {
				b.append(" mc ").append(mc.reached ? "reached" : "stopped").append(mc.blockedBy == null ? "" : " by " + mc.blockedBy)
						.append(" at ").append(mc.cell == null ? "none" : mc.cell.map() + ":" + mc.cell.x() + ":" + mc.cell.y());
			}
			return b.toString();
		}

		private String columns(int[] at) {
			StringBuilder b = new StringBuilder();
			for (int dz = 0; dz < 2; dz++) {
				for (int dx = 0; dx < 2; dx++) {
					b.append('[');
					for (int y = at[1] - 1; y <= at[1] + 2; y++) {
						int k = BlockCode.kind(this.view.code(at[0] + dx, y, at[2] + dz));
						b.append(k < 0 ? 'u' : KIND_LETTERS.charAt(Math.min(KIND_LETTERS.length() - 1, k)));
					}
					b.append(']');
				}
			}
			return b.toString();
		}

		/** A short name for what the step meets, for the counts (built kind of the target, the verdict). */
		private String classOf(char verdict, int tx, int ty, int d, boolean surfing, Walk mc) {
			String v = switch (verdict) {
				case '.' -> "open";
				case 'J' -> "ledge_hop";
				case 'E' -> "edge_open";
				case 'e' -> "edge_refused";
				case 'b' -> "off_map";
				case 'x' -> "tile";
				case 'w' -> "water";
				case 'v' -> "elevation";
				case 'g' -> "gate";
				case 'a' -> "acro";
				default -> "other";
			};
			String built = "off_map";
			if (tx >= 0 && ty >= 0 && tx < this.pm.model().cellWidth() && ty < this.pm.model().cellHeight()) {
				built = this.pm.model().kind(tx, ty).name().toLowerCase(java.util.Locale.ROOT);
				StoryCellTable.Entry e = this.byKey.get(this.map + ":" + tx + ":" + ty);
				if (e != null) {
					built += "+story_" + e.type().id();
				}
			}
			return v + "/" + built + (mc.blockedBy == null ? "" : "/" + mc.blockedBy);
		}

		/** The Minecraft walk from cell (cx, cy) one cell in direction d: null when nobody can stand on the cell. */
		private Walk walk(int cx, int cy, int d, StoryState state) {
			int[] at = WorldPlan.cellOrigin(this.pm.placement(), this.pm.layout(), cx, cy);
			double x = at[0] + 1.0;
			double z = at[2] + 1.0;
			Double y = this.restingY(x, at[1] + 1.0, z, state, 4.0);
			if (y == null || y < at[1] - 1.5) {
				return null;
			}
			if (y > at[1] + 0.75 + EPS && !this.hasSteps(at)) {
				Walk top = new Walk();
				top.onTop = true;
				return top;
			}
			double dx = DELTA[d][0];
			double dz = DELTA[d][1];
			double moved = 0;
			double py = y;
			String blockedBy = null;
			while (moved < 2.0 - EPS) {
				double stepLen = Math.min(MOVE, 2.0 - moved);
				double nx = x + dx * (moved + stepLen);
				double nz = z + dz * (moved + stepLen);
				AABB box = box(nx, py, nz);
				// Minecraft judges a block's collision for the player's box before the move (CollisionContext)
				AABB now = box(x + dx * moved, py, z + dz * moved);
				List<AABB> hits = this.colliders(box, state, dx, dz, now);
				if (!hits.isEmpty()) {
					double top = py;
					for (AABB hit : hits) {
						top = Math.max(top, hit.maxY);
					}
					if (top - py <= STEP_UP + EPS && this.colliders(box(nx, top, nz), state, dx, dz, now).isEmpty()) {
						py = top;
					} else {
						blockedBy = this.blockerKind(hits.get(0));
						break;
					}
				}
				moved += stepLen;
				Double fall = this.restingY(nx, py, nz, state, 8.0);
				if (fall == null) {
					blockedBy = "fell";
					break;
				}
				py = fall;
			}
			Walk out = new Walk();
			out.reached = moved >= 2.0 - EPS;
			out.blockedBy = out.reached ? null : blockedBy;
			double ex = x + dx * moved;
			double ez = z + dz * moved;
			Optional<CellPos> cell = this.index.toCell((int) Math.floor(ex), (int) Math.floor(py + EPS), (int) Math.floor(ez));
			out.cell = cell.orElse(null);
			return out;
		}

		private String blockerKind(AABB hit) {
			int code = this.view.code((int) Math.floor((hit.minX + hit.maxX) / 2), (int) Math.floor((hit.minY + hit.maxY) / 2),
					(int) Math.floor((hit.minZ + hit.maxZ) / 2));
			if (code == BlockCode.UNOWNED) {
				return "unowned";
			}
			return switch (BlockCode.kind(code)) {
				case BlockCode.KIND_FULL -> "full";
				case BlockCode.KIND_SHAPE -> "shape";
				case BlockCode.KIND_HALF -> "half";
				case BlockCode.KIND_LEDGE -> "ledge";
				case BlockCode.KIND_STORY, BlockCode.KIND_STORY_TILE -> "story";
				case BlockCode.KIND_EDGE -> "edge_wall";
				case BlockCode.KIND_STEP -> "step";
				case BlockCode.KIND_SEAT -> "seat";
				case BlockCode.KIND_DECOR -> "decor";
				case BlockCode.KIND_VANILLA -> "vanilla";
				default -> "other";
			};
		}

		/** True when a ramp's step colliders stand in the cell's columns (its floor climbs within the cell). */
		private boolean hasSteps(int[] at) {
			for (int dz = 0; dz < 2; dz++) {
				for (int dx = 0; dx < 2; dx++) {
					for (int y = at[1]; y <= at[1] + 3; y++) {
						if (BlockCode.kind(this.view.code(at[0] + dx, y, at[2] + dz)) == BlockCode.KIND_STEP) {
							return true;
						}
					}
				}
			}
			return false;
		}

		private static AABB box(double x, double y, double z) {
			return new AABB(x - HALF, y, z - HALF, x + HALF, y + HEIGHT, z + HALF);
		}

		/** The highest floor under the box at (x, z) from {@code fromY} down at most {@code reach}; null for none. */
		private Double restingY(double x, double fromY, double z, StoryState state, double reach) {
			AABB column = new AABB(x - HALF, fromY - reach, z - HALF, x + HALF, fromY + EPS, z + HALF);
			double best = Double.NEGATIVE_INFINITY;
			for (AABB a : this.shapesIn(column, state, 0, 0, box(x, fromY, z))) {
				if (a.maxY <= fromY + EPS && a.maxY > best) {
					best = a.maxY;
				}
			}
			if (best == Double.NEGATIVE_INFINITY) {
				return null;
			}
			// standing there must not collide
			return this.colliders(box(x, best, z), state, 0, 0, box(x, best, z)).isEmpty() ? best : null;
		}

		/** The collision boxes that overlap {@code box}. */
		private List<AABB> colliders(AABB box, StoryState state, double dx, double dz, AABB player) {
			List<AABB> out = new ArrayList<>();
			for (AABB a : this.shapesIn(box, state, dx, dz, player)) {
				if (a.minX < box.maxX - EPS && a.maxX > box.minX + EPS && a.minY < box.maxY - EPS && a.maxY > box.minY + EPS
						&& a.minZ < box.maxZ - EPS && a.maxZ > box.minZ + EPS) {
					out.add(a);
				}
			}
			return out;
		}

		/** Every collision box of the blocks in {@code region}, for a player at {@code player}. */
		private List<AABB> shapesIn(AABB region, StoryState state, double dx, double dz, AABB player) {
			List<AABB> out = new ArrayList<>();
			for (int bx = (int) Math.floor(region.minX); bx <= (int) Math.floor(region.maxX - EPS); bx++) {
				for (int bz = (int) Math.floor(region.minZ); bz <= (int) Math.floor(region.maxZ - EPS); bz++) {
					for (int by = (int) Math.floor(region.minY) - 1; by <= (int) Math.floor(region.maxY - EPS); by++) {
						VoxelShape shape = this.shapeAt(bx, by, bz, state, player);
						if (shape.isEmpty()) {
							continue;
						}
						for (AABB a : shape.toAabbs()) {
							out.add(a.move(bx, by, bz));
						}
					}
				}
			}
			return out;
		}

		private VoxelShape shapeAt(int x, int y, int z, StoryState state, AABB player) {
			int code = this.view.code(x, y, z);
			if (code == BlockCode.UNOWNED) {
				// outside the build's volume: the flat world's own ground below its surface, air above
				return y < this.index.surfaceY() ? Shapes.block() : Shapes.empty();
			}
			int value = BlockCode.value(code);
			return switch (BlockCode.kind(code)) {
				case BlockCode.KIND_FULL, BlockCode.KIND_SHAPE, BlockCode.KIND_VANILLA -> Shapes.block();
				case BlockCode.KIND_HALF -> HALF_SHAPE;
				case BlockCode.KIND_LEDGE -> LedgeColliderBlock.walls(player, new net.minecraft.core.BlockPos(x, y, z),
						CodeStates.downhill(value), LedgeColliderBlock.Mode.of(value >> 2)) ? LedgeColliderBlock.WALL : Shapes.empty();
				case BlockCode.KIND_STORY, BlockCode.KIND_STORY_TILE -> {
					int i = BlockCode.storyIndex(code);
					StoryCellTable.Entry e = i >= 0 && i < this.entries.size() ? this.entries.get(i) : null;
					boolean closed = e != null && e.type() != StoryCellType.OBJECT && e.type() != StoryCellType.BOULDER_AREA
							&& state.blocks(e.key(), e.type());
					yield closed ? Shapes.block() : EdgeWallBlock.shape(BlockCode.storySides(code));
				}
				case BlockCode.KIND_EDGE -> EdgeWallBlock.shape(value);
				case BlockCode.KIND_STEP -> StepColliderBlock.shape(CodeStates.downhill(value), StepColliderBlock.Stage.of(value >> 2 & 3));
				case BlockCode.KIND_SEAT -> SeatColliderBlock.SHAPE;
				case BlockCode.KIND_DECOR -> (value & 4) != 0 ? Shapes.block() : Shapes.empty();
				default -> Shapes.empty();
			};
		}
	}

	private static String gen3Pass(int coll) {
		// PROTOCOL.md "Story rules" (SE1), as RealGen3StoryReplayTest has it
		return Set.of(0x00, 0x18, 0x2B, 0x71, 0x72, 0xA0, 0xA1, 0xA2, 0xA3).contains(coll) ? "ground" : coll == 0x29 ? "water" : "solid";
	}

	private static final class Walk {
		boolean reached;
		boolean onTop;
		CellPos cell;
		String blockedBy;
	}

	/** Counts and examples. */
	private static final class Report {
		final Map<String, Integer> counts = new TreeMap<>();
		final Map<String, Integer> agree = new TreeMap<>();
		final Map<String, Integer> breaks = new TreeMap<>();
		final Map<String, Map<String, Integer>> breaksByMap = new TreeMap<>();
		final Map<String, JsonArray> examples = new TreeMap<>();
		final List<String> mapsNotBuilt = new ArrayList<>();

		void count(String key) {
			this.counts.merge(key, 1, Integer::sum);
		}

		void agree(String state, String cls) {
			this.agree.merge(state + ":" + cls, 1, Integer::sum);
		}

		void disagree(String rule, String state, String cls, String map, int cx, int cy, int d, String detail) {
			String key = rule + ":" + state + ":" + cls;
			this.breaks.merge(key, 1, Integer::sum);
			this.breaksByMap.computeIfAbsent(key, k -> new TreeMap<>()).merge(map, 1, Integer::sum);
			this.count(rule + "_" + state);
			this.example(key, map, cx, cy, d, state, detail);
		}

		void example(String key, String map, int cx, int cy, int d, String state) {
			this.example(key, map, cx, cy, d, state, null);
		}

		void example(String key, String map, int cx, int cy, int d, String state, String detail) {
			JsonArray list = this.examples.computeIfAbsent(key, k -> new JsonArray());
			if (list.size() < EXAMPLES) {
				JsonObject e = new JsonObject();
				e.addProperty("map", map);
				e.addProperty("x", cx);
				e.addProperty("y", cy);
				e.addProperty("dir", DIRS[d]);
				if (detail != null) {
					e.addProperty("detail", detail);
				}
				list.add(e);
			}
		}

		JsonObject toJson() {
			JsonObject out = new JsonObject();
			out.add("counts", map(this.counts));
			out.add("agree", map(this.agree));
			out.add("breaks", map(this.breaks));
			JsonObject byMap = new JsonObject();
			for (Map.Entry<String, Map<String, Integer>> e : this.breaksByMap.entrySet()) {
				byMap.add(e.getKey(), map(e.getValue()));
			}
			out.add("breaks_by_map", byMap);
			JsonObject ex = new JsonObject();
			this.examples.forEach(ex::add);
			out.add("examples", ex);
			JsonArray nb = new JsonArray();
			this.mapsNotBuilt.forEach(nb::add);
			out.add("maps_not_built", nb);
			return out;
		}

		private static JsonObject map(Map<String, Integer> m) {
			JsonObject o = new JsonObject();
			m.forEach(o::addProperty);
			return o;
		}
	}

	/** The test body every game's class runs: kb.fieldParity = "<parity dump>;<export bundle>;<report out>". */
	static void runFromProperty(String wantFamily) throws Exception {
		String[] parts = System.getProperty("kb.fieldParity", "").split(";");
		if (parts.length < 3) {
			throw new IllegalArgumentException("kb.fieldParity wants <dump>;<bundle>;<out>");
		}
		ExportBundle probe = ExportBundle.open(Path.of(parts[1]));
		if (!wantFamily.equals(family(probe))) {
			return; // another game's run
		}
		JsonObject report = run(Path.of(parts[0]), Path.of(parts[1]));
		Path out = Path.of(parts[2]);
		Files.createDirectories(out.toAbsolutePath().getParent());
		Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
	}
}
