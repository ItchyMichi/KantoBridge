package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.export.ExportSwaps;
import dev.kantobridge.builder.export.G2Map;
import dev.kantobridge.builder.export.G2Tiles;
import dev.kantobridge.world.StoryCellTable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A Gen 2 map's cell model (MG9, docs/specs/GEN2_BRIDGE_PLAN.md §7): from a {@code g2map}, its art variant's
 * {@code g2tiles} and the export's swap sites on it. Pure Java.
 *
 * <ul>
 * <li><b>Art:</b> each cell is a quadrant of its block: the block's 16 baked tiles (4×4) give the cell's 2×2. Block 0 draws
 * as the map's border block and collides as a wall, as the engine reads it.</li>
 * <li><b>Kind and flags</b> from the meaning the export gives the cell's collision byte (the engine's own predicates):
 * land is walkable (ice and the one-way side walls too), water is water (currents, waterfalls, whirlpools), counters
 * are counters, ledges hop their first facing, warps by kind (door, stairs, ladders and mats, pads, holes), Cut trees; any byte
 * the export doesn't describe is built solid ({@code g2_collision_unknown}). The cells the map's warps stand on are
 * warps and its bg events signs.</li>
 * <li><b>Wild cells</b> (the engine's encounter gate, FieldMoves.canEncounterWildMon): walkable cells of a cave or
 * dungeon, else walkable cells whose byte rolls encounters; never ice.</li>
 * <li><b>Swap sites</b> (Gen 1's rule, {@link MapModel#build}): a site is accepted when the map data holds either of its
 * blocks, or any block for a script's; each cell of its block is built in the state that is walkable there, and the
 * closed model shows the other state where the art or the passability differs.</li>
 * <li><b>Blocks with several states</b> (several sites on one block: the bedroom's decorations, docs/specs/
 * STORY_EVENTS_PLAN.md §5.5): every block the sites write is kept ({@link MapModel#blockStates}); each cell is built in
 * its most walkable state (ground, then water, then the rest; on a tie the first site's state, as above) and never
 * closes: its {@code cell} story cell draws the other states per player ({@link G2StoryCells}).</li>
 * </ul>
 */
public final class G2Cells {
	/** The ledge directions as {@link CellFlags#ledgeDir}: 0 down, 1 up, 2 left, 3 right. */
	private static final List<String> LEDGE_DIRS = List.of("down", "up", "left", "right");

	private G2Cells() {
	}

	/**
	 * The model of a map, open (its sites built walkable) with its closed state beside it.
	 *
	 * @param sites    the export's swap sites on this map
	 * @param interior placed under the surface (interior walls and no measured heights)
	 */
	public static MapModel model(G2Map map, G2Tiles tiles, List<ExportSwaps.Site> sites, boolean interior, Warnings warnings) {
		Map<Integer, ExportSwaps.Site> valid = new HashMap<>();
		// a block a script can turn into several others (the bedroom's decorations): the map data's block, then every block
		// its sites write, in the sites' order
		Map<Integer, List<Integer>> several = new TreeMap<>();
		for (ExportSwaps.Site site : sites) {
			Map<String, String> detail = Warnings.symbols("map", map.id(), "kind", site.kind());
			if (!map.inBlocks(site.bx(), site.by())) {
				warnings.warn("swap_out_of_bounds", detail);
				continue;
			}
			if (site.before() == site.after()) {
				warnings.note("swap_site_unchanged", detail);
				continue;
			}
			int index = site.by() * map.w() + site.bx();
			int data = map.dataBlock(site.bx(), site.by());
			boolean neither = data != site.before() && data != site.after();
			if (neither && !MapModel.overwritable(site)) {
				warnings.warn("swap_block_mismatch", detail);
				continue;
			}
			ExportSwaps.Site first = valid.get(index);
			if (first != null) {
				List<Integer> states = several.computeIfAbsent(index, k -> new ArrayList<>(List.of(data)));
				for (int block : new int[] {first.before(), first.after(), site.before(), site.after()}) {
					if (!states.contains(block)) {
						states.add(block);
					}
				}
				warnings.note("g2_block_several_states", detail);
				continue;
			}
			valid.put(index, site);
		}
		int cw = map.cellWidth();
		int ch = map.cellHeight();
		int[] openBlock = new int[cw * ch];
		int[] closedBlock = new int[cw * ch];
		Arrays.fill(openBlock, -1);
		Arrays.fill(closedBlock, -1);
		boolean anyChange = false;
		for (ExportSwaps.Site site : valid.values()) {
			List<Integer> states = several.get(site.by() * map.w() + site.bx());
			for (int qy = 0; qy < 2; qy++) {
				for (int qx = 0; qx < 2; qx++) {
					int i = (site.by() * 2 + qy) * cw + site.bx() * 2 + qx;
					int open = openState(tiles, site, qx, qy);
					if (states != null) {
						// several states: the most walkable here, no closed state (its story cell draws the others)
						int built = mostWalkable(tiles, states, open, qx, qy);
						openBlock[i] = built;
						closedBlock[i] = built;
						continue;
					}
					openBlock[i] = open;
					boolean changes = changes(tiles, site, qx, qy);
					closedBlock[i] = changes ? (open == site.after() ? site.before() : site.after()) : open;
					anyChange |= changes;
				}
			}
		}
		Warnings scratch = new Warnings();
		MapModel closed = anyChange ? build(map, tiles, valid, openBlock, closedBlock, several, interior, true, null, scratch) : null;
		return build(map, tiles, valid, openBlock, closedBlock, several, interior, false, closed, warnings);
	}

	/**
	 * How a block's cell (qx, qy) lets players through, as the story cells say it: {@code ground} on land (a ledge too:
	 * the hidden game makes the hop), {@code water} where only surfers cross, {@code solid} everywhere else.
	 */
	public static StoryCellTable.Pass pass(G2Tiles tiles, int block, int qx, int qy) {
		G2Tiles.Meaning m = tiles.meaning(tiles.coll(block, qx, qy));
		return switch (m.perm()) {
			case "land" -> StoryCellTable.Pass.GROUND;
			case "water" -> StoryCellTable.Pass.WATER;
			default -> StoryCellTable.Pass.SOLID;
		};
	}

	/** Walkable before water before the rest, as a rank (lower is more walkable). */
	private static int rank(G2Tiles tiles, int block, int qx, int qy) {
		G2Tiles.Meaning m = tiles.meaning(tiles.coll(block, qx, qy));
		if (walkable(m)) {
			return 0;
		}
		return "water".equals(m.perm()) ? 1 : 2;
	}

	/** Of a block's states, the one cell (qx, qy) is built in: the most walkable there, {@code first} on a tie. */
	static int mostWalkable(G2Tiles tiles, List<Integer> states, int first, int qx, int qy) {
		int best = first;
		for (int block : states) {
			if (rank(tiles, block, qx, qy) < rank(tiles, best, qx, qy)) {
				best = block;
			}
		}
		return best;
	}

	private static final Map<String, int[]> DELTA = Map.of("up", new int[] {0, -1}, "down", new int[] {0, 1}, "left", new int[] {-1, 0},
			"right", new int[] {1, 0});

	/**
	 * The map's one-way edges (Permissions.stepPermitted, the engine's rule): a cell's own {@code sides} close the edge on
	 * each side it names; its {@code neighbor} arms close, for a player on the cell in that direction of it, the edge it
	 * refuses that player (Crystal: the same edge, from the other side). Built on the open model.
	 */
	public static PairEdges oneWay(MapModel model, G2Map map, G2Tiles tiles) {
		List<int[]> walls = new java.util.ArrayList<>();
		int cw = map.cellWidth();
		int ch = map.cellHeight();
		for (int cy = 0; cy < ch; cy++) {
			for (int cx = 0; cx < cw; cx++) {
				int block = map.wallBlock(cx / 2, cy / 2) ? -1 : map.drawnBlock(cx / 2, cy / 2);
				if (block < 0) {
					continue;
				}
				G2Tiles.Meaning m = tiles.meaning(tiles.coll(block, cx % 2, cy % 2));
				for (String side : m.sides()) {
					int[] d = DELTA.get(side);
					walls.add(new int[] {cx, cy, cx + d[0], cy + d[1]});
				}
				for (Map.Entry<String, String> arm : m.neighbor().entrySet()) {
					int[] at = DELTA.get(arm.getKey());
					int[] go = DELTA.get(arm.getValue());
					int px = cx - at[0];
					int py = cy - at[1];
					walls.add(new int[] {px, py, px + go[0], py + go[1]});
				}
			}
		}
		return PairEdges.walls(model, walls);
	}

	/** True for a collision byte a player walks on (land, not a ledge). */
	static boolean walkable(G2Tiles.Meaning m) {
		return "land".equals(m.perm()) && m.ledge().isEmpty();
	}

	/** The state cell (qx, qy) of a site's block is built in: {@code after} unless only {@code before} is walkable there. */
	static int openState(G2Tiles tiles, ExportSwaps.Site site, int qx, int qy) {
		boolean before = walkable(tiles.meaning(tiles.coll(site.before(), qx, qy)));
		boolean after = walkable(tiles.meaning(tiles.coll(site.after(), qx, qy)));
		return after || !before ? site.after() : site.before();
	}

	/** True when a site changes cell (qx, qy) of its block: its art or its passability differs. */
	static boolean changes(G2Tiles tiles, ExportSwaps.Site site, int qx, int qy) {
		for (int ty = qy * 2; ty < qy * 2 + 2; ty++) {
			for (int tx = qx * 2; tx < qx * 2 + 2; tx++) {
				if (tiles.tile(site.before(), tx, ty) != tiles.tile(site.after(), tx, ty)) {
					return true;
				}
			}
		}
		G2Tiles.Meaning before = tiles.meaning(tiles.coll(site.before(), qx, qy));
		G2Tiles.Meaning after = tiles.meaning(tiles.coll(site.after(), qx, qy));
		return walkable(before) != walkable(after) || !before.perm().equals(after.perm());
	}

	/** The {@link CellFlags} bits of a collision byte's meaning (the positional warp and sign bits aside). */
	static int flags(G2Tiles.Meaning m) {
		int f = 0;
		switch (m.perm()) {
			case "land" -> f |= CellFlags.WALKABLE;
			case "water" -> f |= CellFlags.WATER;
			default -> {
			}
		}
		if (m.counter()) {
			f = (f & ~CellFlags.WALKABLE) | CellFlags.COUNTER;
		}
		if (m.grass() || m.longGrass()) {
			f |= CellFlags.GRASS;
		}
		if (m.cutTree()) {
			f |= CellFlags.CUT_TREE;
		}
		if (!m.ledge().isEmpty()) {
			// a ledge is hopped, not walked on (as Gen 1's and 3's)
			int dir = Math.max(0, LEDGE_DIRS.indexOf(m.ledge().get(0)));
			f = (f & ~CellFlags.WALKABLE) | CellFlags.LEDGE | dir << CellFlags.LEDGE_DIR_SHIFT;
			// a second facing (a corner ledge, hopped two ways): its hop's jumped cell is built too (FA8 item 17)
			int dir2 = m.ledge().size() > 1 ? LEDGE_DIRS.indexOf(m.ledge().get(1)) : -1;
			if (dir2 >= 0 && dir2 != dir) {
				f |= CellFlags.LEDGE2 | dir2 << CellFlags.LEDGE2_DIR_SHIFT;
			}
		}
		if (m.warp() != null) {
			f |= switch (m.warp()) {
				case "door" -> CellFlags.DOOR;
				case "pad" -> CellFlags.PAD;
				case "hole" -> CellFlags.HOLE;
				default -> CellFlags.WARP_TILE; // stairs, ladders, mats
			};
		}
		return f;
	}

	private static MapModel build(G2Map map, G2Tiles tiles, Map<Integer, ExportSwaps.Site> sites, int[] openBlock, int[] closedBlock,
			Map<Integer, List<Integer>> several, boolean interior, boolean closedState, MapModel closed, Warnings warnings) {
		int cw = map.cellWidth();
		int ch = map.cellHeight();
		int cols = cw * 2;
		Set<Integer> warpCells = new HashSet<>();
		for (G2Map.Warp warp : map.warps()) {
			if (map.inCells(warp.x(), warp.y())) {
				warpCells.add(warp.y() * cw + warp.x());
			}
		}
		Set<Integer> signCells = new HashSet<>();
		for (G2Map.Event sign : map.signs()) {
			if (map.inCells(sign.x(), sign.y())) {
				signCells.add(sign.y() * cw + sign.x());
			}
		}
		int[] art = new int[cols * ch * 2];
		int[] flags = new int[cw * ch];
		boolean[] wild = new boolean[cw * ch];
		int missing = 0;
		int unknown = 0;
		for (int cy = 0; cy < ch; cy++) {
			for (int cx = 0; cx < cw; cx++) {
				int i = cy * cw + cx;
				int bx = cx / 2;
				int by = cy / 2;
				int qx = cx % 2;
				int qy = cy % 2;
				int block = map.drawnBlock(bx, by);
				if (openBlock[i] >= 0) {
					block = closedState ? closedBlock[i] : openBlock[i];
				}
				boolean wall = openBlock[i] < 0 && map.wallBlock(bx, by);
				if (block >= tiles.blockCount()) {
					missing++;
				}
				for (int ty = 0; ty < 2; ty++) {
					for (int tx = 0; tx < 2; tx++) {
						int tile = tiles.tile(block, qx * 2 + tx, qy * 2 + ty);
						art[(cy * 2 + ty) * cols + cx * 2 + tx] = Math.max(tile, 0);
					}
				}
				int coll = wall ? 0xFF : tiles.coll(block, qx, qy);
				G2Tiles.Meaning m = tiles.meaning(coll);
				if (!tiles.knows(coll) && coll != 0xFF) {
					unknown++;
				}
				int f = flags(m);
				if (warpCells.contains(i)) {
					f |= CellFlags.WARP;
				}
				if (signCells.contains(i)) {
					f |= CellFlags.SIGN;
				}
				flags[i] = f;
				// the engine's encounter gate: a cave or dungeon's every floor, else the encounter bytes; never ice
				wild[i] = walkable(m) && !m.ice() && (map.caveLike() || m.encounter());
			}
		}
		warnings.warn("g2_block_missing", missing);
		warnings.warn("g2_collision_unknown", unknown);
		int[] border = new int[16];
		for (int t = 0; t < 16; t++) {
			border[t] = Math.max(tiles.tile(map.border(), t % 4, t / 4), 0);
		}
		return MapModel.gen2(map.id(), interior, closedState, cw, ch, art, flags, wild, border, sites, openBlock, closedBlock, map.blocks(),
				map.w(), several, closed);
	}
}
