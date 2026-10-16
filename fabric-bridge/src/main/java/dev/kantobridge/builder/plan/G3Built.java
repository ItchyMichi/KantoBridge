package dev.kantobridge.builder.plan;

import dev.kantobridge.builder.export.BundleException;
import dev.kantobridge.builder.export.CellFlags;
import dev.kantobridge.builder.export.ExportBundle;
import dev.kantobridge.builder.export.G3Map;
import dev.kantobridge.builder.export.G3Sites;
import dev.kantobridge.world.StoryCellTable;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The state each changeable Gen 3 cell ({@link G3Sites}; SE3, docs/specs/STORY_EVENTS_PLAN.md §4.1 S3) is built in: the most
 * walkable of its states (ground, then water, then solid; the map's own state on a tie), and the maps with those states in
 * their cells. The planner, the art catalogue and the scene all read the maps from here, so they agree on what is built;
 * the other states are the cell's story cell's to draw and collide per player ({@link G3StoryCells}).
 */
public final class G3Built {
	private G3Built() {
	}

	/**
	 * How a cell with this collision byte lets players through: {@code ground} where it is walkable (a ledge too: the hidden
	 * game makes the hop), {@code water} where only surfers cross, {@code solid} everywhere else (a byte the vocabulary
	 * doesn't have included, as {@link G3Cells} builds it).
	 */
	public static StoryCellTable.Pass pass(int coll) {
		int flags = G3Cells.flags(coll);
		if (flags < 0) {
			return StoryCellTable.Pass.SOLID;
		}
		if (CellFlags.has(flags, CellFlags.WALKABLE) || CellFlags.has(flags, CellFlags.LEDGE)) {
			return StoryCellTable.Pass.GROUND;
		}
		return CellFlags.has(flags, CellFlags.WATER) ? StoryCellTable.Pass.WATER : StoryCellTable.Pass.SOLID;
	}

	private static int rank(StoryCellTable.Pass pass) {
		return switch (pass) {
			case GROUND -> 0;
			case WATER -> 1;
			case SOLID -> 2;
		};
	}

	/** The state {@code site} is built in. */
	public static G3Sites.State built(G3Sites.Site site) {
		G3Sites.State best = site.own();
		for (G3Sites.State state : site.states()) {
			if (rank(pass(state.coll())) < rank(pass(best.coll()))) {
				best = state;
			}
		}
		return best;
	}

	/** {@code map} with each of its sites' cells in the built state (the same map when none changes). */
	public static G3Map apply(G3Map map, List<G3Sites.Site> sites) {
		int[] mids = null;
		int[] coll = null;
		int[] elev = null;
		for (G3Sites.Site site : sites) {
			if (!map.inGrid(site.x(), site.y())) {
				continue;
			}
			G3Sites.State built = built(site);
			int i = map.index(site.x(), site.y());
			if (map.mids()[i] == built.metatile() && map.coll()[i] == built.coll() && map.elev()[i] == built.elev()) {
				continue;
			}
			if (mids == null) {
				mids = map.mids().clone();
				coll = map.coll().clone();
				elev = map.elev().clone();
			}
			mids[i] = built.metatile();
			coll[i] = built.coll();
			elev[i] = built.elev();
		}
		if (mids == null) {
			return map;
		}
		return new G3Map(map.id(), map.w(), map.h(), map.trueW(), map.trueH(), map.pair(), mids, coll, elev, map.border(), map.connections(),
				map.warps(), map.objects(), map.mapType(), map.kind(), map.music(), map.weather(), map.dark());
	}

	/** Every map of the export, by id, with its sites built. */
	public static Map<String, G3Map> maps(ExportBundle bundle) throws BundleException {
		G3Sites sites = bundle.g3sites();
		Map<String, G3Map> all = new TreeMap<>();
		for (String id : bundle.ids("g3map")) {
			all.put(id, apply(bundle.g3map(id), sites.of(id)));
		}
		return all;
	}

	/** One map of the export with its sites built. */
	public static G3Map map(ExportBundle bundle, String id) throws BundleException {
		return apply(bundle.g3map(id), bundle.g3sites().of(id));
	}
}
