package dev.kantobridge.server;

import dev.kantobridge.world.Dir;
import dev.kantobridge.world.Scale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Crossings between regions on the server (docs/specs/MULTI_GAME_PLAN.md §4.8 "The pass service", MG5), without
 * Minecraft: the decisions, modelled on {@link dev.kantobridge.dungeon.DungeonVisits} (positions and facts in, actions
 * out). {@link PassService} asks the published indices and carries the actions out.
 *
 * <ul>
 * <li><b>{@code pass.cross}:</b> the player must stand on the pass cell of a connecting route's half (the far doorway of
 * its gate house) and head for the other end; the far region must exist ({@code pass.no_region}) and be built
 * ({@code pass.not_built}); the player must have a link for its game ({@code pass.no_link}); one crossing at a time
 * ({@code pass.busy}). Accepted: the player is faded and the crossing waits for the client's {@code region.state}.</li>
 * <li><b>{@code region.state} naming the far region:</b> the player is put into the other half's gate house, just inside
 * its far doorway ({@link Teleport}); when the far game was at its title when the crossing began, the client starts it
 * and relinks itself (the "one accepted teleport" of a first start, §4.7), so the server moves nobody ({@link Relink}).</li>
 * <li><b>{@code pass.abort}, or {@value #TIMEOUT_TICKS} ticks without an answer:</b> the crossing ends where the player
 * stands ({@code pass.timeout}).</li>
 * </ul>
 * Server thread only. Ids, codes and counts only.
 */
public final class PassCrossings {
	/** The fade before a crossing moves the player, in ticks. */
	public static final int FADE_TICKS = 10;
	/** A crossing that hasn't finished after this many ticks is over (15 s). */
	public static final long TIMEOUT_TICKS = 300;

	public static final String NO_REGION = "pass.no_region";
	public static final String NO_LINK = "pass.no_link";
	public static final String NOT_BUILT = "pass.not_built";
	public static final String BUSY = "pass.busy";
	public static final String TIMEOUT = "pass.timeout";
	/** The player doesn't stand on that pass, or heads the wrong way. */
	public static final String BAD_MESSAGE = "bad_message";

	/**
	 * One half of a connecting route as the WorldIndex describes it (a zone with a {@code pass} and a {@code gateHouse}).
	 *
	 * @param region    the region the half is built in
	 * @param zone      the zone's id
	 * @param route     the connecting route's id
	 * @param end       {@code from} or {@code to}: which end of the route this half is
	 * @param toRegion  the region of the other half
	 * @param routeMapX the route map's block X (its cell (0, 0)'s north-west corner)
	 * @param feetY     the zone's floor, the feet level
	 * @param routeMapZ the route map's block Z
	 * @param passCells the pass cells in the route map's grid
	 * @param facing    the gate house's facing: from its near doorway toward the pass
	 */
	public record Half(String region, String zone, String route, String end, String toRegion, int routeMapX, int feetY, int routeMapZ,
			List<int[]> passCells, Dir facing) {
		public Half {
			passCells = List.copyOf(passCells);
		}

		/** True when a player at this position stands on one of the pass cells, at the zone's floor. */
		public boolean onPass(double x, double y, double z) {
			if (Math.abs(y - this.feetY) >= 2) {
				return false;
			}
			int cx = Math.floorDiv((int) Math.floor(x) - this.routeMapX, Scale.CELL_BLOCKS);
			int cy = Math.floorDiv((int) Math.floor(z) - this.routeMapZ, Scale.CELL_BLOCKS);
			for (int[] cell : this.passCells) {
				if (cell[0] == cx && cell[1] == cy) {
					return true;
				}
			}
			return false;
		}

		/** The end a player standing in this half heads for: the other one. */
		public String otherEnd() {
			return "from".equals(this.end) ? "to" : "from";
		}

		/**
		 * The middle of the cell just inside this half's far doorway (one cell from the pass, into the house), at the
		 * floor, looking into the house: where a crossing arrives.
		 */
		public Teleport arrival() {
			int[] pass = this.passCells.get(0);
			Dir inward = this.facing.opposite();
			int cx = pass[0] + inward.dx();
			int cy = pass[1] + inward.dy();
			return new Teleport(this.routeMapX + Scale.cellToBlock(cx) + 1.0, this.feetY, this.routeMapZ + Scale.cellToBlock(cy) + 1.0,
					inward.yaw());
		}
	}

	/** What the service knows about the world and the player; the published indices answer it. */
	public interface Lookup {
		/** The half whose zone holds the player's position, or null (not in a connecting route's half). */
		@Nullable Half standing(UUID player, double x, double y, double z);

		/** True when the region is in the world's table. */
		boolean regionExists(String region);

		/** True when the region has a published index. */
		boolean regionBuilt(String region);

		/** The half of {@code route} built in {@code region}, or null. */
		@Nullable Half half(String route, String region);

		/** True when the player's {@code region.state} lists a link for the region's game. */
		boolean hasLink(UUID player, String region);

		/** The playthrough the player's progress record holds for the region ({@code title}, {@code fresh}, {@code saved}), or null. */
		@Nullable String playthrough(UUID player, String region);
	}

	/** The answer to a {@code pass.cross}. */
	public sealed interface Decision permits Accepted, Refused {
	}

	/**
	 * The crossing may go ahead: fade the player.
	 *
	 * @param start the far game is at its title: the client starts it and relinks itself (no server teleport)
	 */
	public record Accepted(String route, String from, String to, boolean start) implements Decision {
	}

	public record Refused(String code) implements Decision {
	}

	/** What to do when the client's {@code region.state} arrives. */
	public sealed interface Move permits Teleport, Relink, None {
	}

	/** Put the player here (into the far half's gate house). */
	public record Teleport(double x, double y, double z, float yaw) implements Move {
	}

	/** The far game was started: the client's own relink places the player (nothing to do here). */
	public record Relink() implements Move {
	}

	/** Not a crossing's answer: nothing to do. */
	public record None() implements Move {
	}

	/** A crossing under way. */
	public record Crossing(String route, String from, String to, long startedTick, boolean start) {
	}

	private final Lookup lookup;
	private final Map<UUID, Crossing> crossings = new HashMap<>();

	public PassCrossings(Lookup lookup) {
		this.lookup = lookup;
	}

	/**
	 * A {@code pass.cross} from a player standing at (x, y, z): {@code dir} is the end they head for ({@code to} from
	 * the route's {@code from} half, {@code from} from its {@code to} half).
	 */
	public Decision cross(UUID player, String route, String dir, double x, double y, double z, long tick) {
		Half here = this.lookup.standing(player, x, y, z);
		if (here == null || !here.route().equals(route) || !here.onPass(x, y, z) || !here.otherEnd().equals(dir)) {
			return new Refused(BAD_MESSAGE);
		}
		String to = here.toRegion();
		if (!this.lookup.regionExists(to)) {
			return new Refused(NO_REGION);
		}
		if (!this.lookup.regionBuilt(to) || this.lookup.half(route, to) == null) {
			return new Refused(NOT_BUILT);
		}
		if (!this.lookup.hasLink(player, to)) {
			return new Refused(NO_LINK);
		}
		if (this.crossings.containsKey(player)) {
			return new Refused(BUSY);
		}
		boolean start = "title".equals(this.lookup.playthrough(player, to));
		this.crossings.put(player, new Crossing(route, here.region(), to, tick, start));
		return new Accepted(route, here.region(), to, start);
	}

	/**
	 * The client's {@code region.state}: when its followed region is the crossing's target, the crossing ends with the
	 * player moved into the far half's gate house, or left to the client's relink after a start.
	 */
	public Move onRegionState(UUID player, @Nullable String followed) {
		Crossing crossing = this.crossings.get(player);
		if (crossing == null || followed == null || !followed.equals(crossing.to())) {
			return new None();
		}
		if (crossing.start()) {
			this.crossings.remove(player);
			return new Relink();
		}
		Half far = this.lookup.half(crossing.route(), crossing.to());
		if (far == null) {
			// the far half went away meanwhile: the timer ends the crossing
			return new None();
		}
		this.crossings.remove(player);
		return far.arrival();
	}

	/** {@code pass.abort}: ends the player's crossing. True when there was one. */
	public boolean abort(UUID player) {
		return this.crossings.remove(player) != null;
	}

	/** Ends the crossings older than {@value #TIMEOUT_TICKS} ticks; returns their players. */
	public List<UUID> tick(long now) {
		List<UUID> expired = new ArrayList<>();
		for (Iterator<Map.Entry<UUID, Crossing>> it = this.crossings.entrySet().iterator(); it.hasNext();) {
			Map.Entry<UUID, Crossing> entry = it.next();
			if (now - entry.getValue().startedTick() >= TIMEOUT_TICKS) {
				expired.add(entry.getKey());
				it.remove();
			}
		}
		return expired;
	}

	/** A player left: their crossing goes with them. */
	public void forget(UUID player) {
		this.crossings.remove(player);
	}

	/** The player's crossing under way, or null. */
	public @Nullable Crossing crossing(UUID player) {
		return this.crossings.get(player);
	}

	public Map<UUID, Crossing> crossings() {
		return Collections.unmodifiableMap(this.crossings);
	}
}
