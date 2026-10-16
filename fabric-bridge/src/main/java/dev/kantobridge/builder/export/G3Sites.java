package dev.kantobridge.builder.export;

import dev.kantobridge.world.DataException;
import dev.kantobridge.world.DataReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Every cell the hidden game's story can change at run time (protocol/export/g3sites.schema.json; SE2,
 * docs/specs/STORY_EVENTS_PLAN.md §4): per site its map's own state, as g3map exports it, and every other state the cell
 * can show. The builder builds each site in its most walkable state and gives it a story cell of type {@code cell}
 * ({@code G3Built}, {@code G3StoryCells}). An export from before SE2 has none. ROM-derived: never logged.
 *
 * @param baked cells whose unconditional change the export already bakes into g3map (counted only)
 */
public record G3Sites(List<Site> sites, int baked, Map<String, List<Site>> byMap) {
	/** What puts a cell in a state (the schema's {@code source}). */
	public static final Set<String> SOURCES = Set.of("script", "layout", "native");

	/**
	 * One state of a cell.
	 *
	 * @param coll   the engine's collision byte in this state (g3map's vocabulary)
	 * @param source what puts the cell in this state; null for the map's own state
	 */
	public record State(int metatile, int coll, int elev, @Nullable String source) {
	}

	/** One changeable cell of {@code map}: its own state and the others (one at least). */
	public record Site(String map, int x, int y, State own, List<State> states) {
		public Site {
			states = List.copyOf(states);
		}
	}

	public G3Sites {
		sites = List.copyOf(sites);
		byMap = Collections.unmodifiableMap(byMap);
	}

	/** The sites, with {@link #byMap} worked out. */
	public static G3Sites of(List<Site> sites, int baked) {
		Map<String, List<Site>> byMap = new LinkedHashMap<>();
		for (Site site : sites) {
			byMap.computeIfAbsent(site.map(), k -> new ArrayList<>()).add(site);
		}
		Map<String, List<Site>> frozen = new LinkedHashMap<>();
		for (Map.Entry<String, List<Site>> entry : byMap.entrySet()) {
			frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
		}
		return new G3Sites(sites, baked, frozen);
	}

	public static G3Sites empty() {
		return of(List.of(), 0);
	}

	/** {@code map}'s sites, in the export's order; none when it has none. */
	public List<Site> of(String map) {
		return this.byMap.getOrDefault(map, List.of());
	}

	static G3Sites parse(DataReader r) throws DataException {
		List<Site> sites = new ArrayList<>();
		for (DataReader s : DataReader.objects(r.array("sites"), r.path("sites"))) {
			State own = state(s.object("own"), false);
			List<State> states = new ArrayList<>();
			for (DataReader st : DataReader.objects(s.array("states"), s.path("states"))) {
				states.add(state(st, true));
			}
			if (states.isEmpty()) {
				throw new DataException(s.path("states"), "a site with no other state");
			}
			sites.add(new Site(s.string("map", DataReader.MAP_ID), s.integer("x", 0, 1023), s.integer("y", 0, 1023), own, states));
		}
		return of(sites, r.has("baked") ? r.integer("baked", 0, Integer.MAX_VALUE) : 0);
	}

	private static State state(DataReader r, boolean sourced) throws DataException {
		String source = null;
		if (sourced) {
			source = r.string("source");
			if (!SOURCES.contains(source)) {
				throw new DataException(r.path("source"), "unknown source");
			}
		}
		return new State(r.integer("metatile", 0, 1023), r.integer("coll", 0, 255), r.integer("elev", 0, 15), source);
	}
}
