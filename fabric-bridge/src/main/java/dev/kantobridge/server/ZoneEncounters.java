package dev.kantobridge.server;

import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.Scale;
import dev.kantobridge.world.WorldIndex;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Wild Routes trainers on the server (docs/specs/WILD_ROUTES_PLAN.md §4.6, WR5), without Minecraft.
 *
 * <ul>
 * <li><b>Visits:</b> a player inside a zone's box is on a visit. Trainers they beat leave them alone until the visit
 * ends (they leave the box: the warp home, a blackout, Fly), and challenge them again on the next one (Q7).</li>
 * <li><b>Sight</b> is Gen 1's ({@link WorldIndex.ZoneTrainer#sees}): straight ahead, within the trainer's sight, while
 * the player is free. Lanes are clear by construction. Then {@link Sight}: the "!" ({@value #EXCLAIM_FRAMES} frames) and
 * the walk to the cell in front of the player ({@value #STEP_FRAMES} frames a cell); once those have passed,
 * {@link Battle}. One encounter at a time.</li>
 * <li><b>The answer</b> ({@code route.result}) ends the encounter: a win marks the trainer beaten for the visit; anything
 * else lets it look again after {@value #RETRY_TICKS} ticks. An answer that doesn't come while the player is free for
 * {@value #RESULT_TIMEOUT_TICKS} ticks ends it the same way (a battle's own time doesn't count).</li>
 * </ul>
 * Server thread only.
 */
public final class ZoneEncounters {
	public static final int EXCLAIM_FRAMES = 60;
	public static final int STEP_FRAMES = 16;
	static final int FRAMES_PER_TICK = 3;
	/** Ticks between the end of the walk and route.battle, for the client's last step. */
	static final int SLACK_TICKS = 4;
	public static final long RETRY_TICKS = 100;
	public static final long RESULT_TIMEOUT_TICKS = 600;

	/** What to send a player. */
	public sealed interface Action permits Sight, Battle {
	}

	/** {@code route.sight}: the trainer walks to cell (x, y) of the zone's route-map grid over {@code frames} after its "!". */
	public record Sight(String route, String id, int x, int y, int frames) implements Action {
	}

	/** {@code route.battle} for {@code trainer}. */
	public record Battle(String route, WorldIndex.ZoneTrainer trainer) implements Action {
	}

	private static final class Visit {
		final String zone;
		final Set<String> beaten = new HashSet<>();
		/** Trainer id → the tick it may look again. */
		final Map<String, Long> resting = new HashMap<>();
		@Nullable String trainer;
		long battleAt;
		boolean battleSent;
		long waitingSince;

		Visit(String zone) {
			this.zone = zone;
		}
	}

	private final Map<UUID, Visit> visits = new HashMap<>();

	/**
	 * One tick of one player standing at block position (x, z). {@code free}: not battling, not busy with the hidden
	 * game, not held. Returns what to send them, or null.
	 */
	public @Nullable Action tick(UUID player, @Nullable WorldIndex index, double x, double z, boolean free, long tick) {
		WorldIndex.Zone zone = index == null ? null : index.zoneAt((int) Math.floor(x), (int) Math.floor(z));
		if (zone == null) {
			this.visits.remove(player);
			return null;
		}
		Visit visit = this.visits.get(player);
		if (visit == null || !visit.zone.equals(zone.id())) {
			visit = new Visit(zone.id());
			this.visits.put(player, visit);
		}
		if (visit.trainer != null) {
			return this.encounterTick(visit, zone, free, tick);
		}
		MapPlacement routeMap = index.map(zone.routeMap());
		if (!free || routeMap == null) {
			return null;
		}
		int cx = Math.floorDiv((int) Math.floor(x) - routeMap.x(), Scale.CELL_BLOCKS);
		int cy = Math.floorDiv((int) Math.floor(z) - routeMap.z(), Scale.CELL_BLOCKS);
		for (WorldIndex.ZoneTrainer t : zone.trainers()) {
			if (visit.beaten.contains(t.id()) || visit.resting.getOrDefault(t.id(), Long.MIN_VALUE) > tick || !t.sees(cx, cy)) {
				continue;
			}
			int steps = (cx - t.x()) * t.facing().dx() + (cy - t.y()) * t.facing().dy() - 1;
			int frames = steps * STEP_FRAMES;
			visit.trainer = t.id();
			visit.battleSent = false;
			visit.battleAt = tick + (EXCLAIM_FRAMES + frames + FRAMES_PER_TICK - 1) / FRAMES_PER_TICK + SLACK_TICKS;
			return new Sight(zone.id(), t.id(), t.x() + t.facing().dx() * steps, t.y() + t.facing().dy() * steps, frames);
		}
		return null;
	}

	private @Nullable Action encounterTick(Visit visit, WorldIndex.Zone zone, boolean free, long tick) {
		if (!visit.battleSent) {
			if (tick < visit.battleAt) {
				return null;
			}
			WorldIndex.ZoneTrainer trainer = zone.trainer(visit.trainer);
			if (trainer == null) {
				visit.trainer = null;
				return null;
			}
			visit.battleSent = true;
			visit.waitingSince = tick;
			return new Battle(zone.id(), trainer);
		}
		if (!free) {
			// the battle (or its text) is under way
			visit.waitingSince = tick;
		} else if (tick - visit.waitingSince > RESULT_TIMEOUT_TICKS) {
			visit.resting.put(visit.trainer, tick + RETRY_TICKS);
			visit.trainer = null;
		}
		return null;
	}

	/** {@code route.result} from the player's hidden game. */
	public void onResult(UUID player, @Nullable String op, boolean ok, @Nullable String route, @Nullable String id, @Nullable String outcome,
			long tick) {
		Visit visit = this.visits.get(player);
		if (visit == null || !"battle".equals(op) || route == null || id == null || !visit.zone.equals(route)) {
			return;
		}
		boolean won = ok && "win".equals(outcome);
		if (won) {
			visit.beaten.add(id);
		}
		if (id.equals(visit.trainer)) {
			if (!won) {
				visit.resting.put(id, tick + RETRY_TICKS);
			}
			visit.trainer = null;
		}
	}

	public void forget(UUID player) {
		this.visits.remove(player);
	}

	/** The trainers {@code player} beat on this visit (empty off a zone). */
	public Set<String> beaten(UUID player) {
		Visit visit = this.visits.get(player);
		return visit == null ? Set.of() : Collections.unmodifiableSet(visit.beaten);
	}

	/** The trainer whose encounter with {@code player} is under way, or null. */
	public @Nullable String encounter(UUID player) {
		Visit visit = this.visits.get(player);
		return visit == null ? null : visit.trainer;
	}

	/** The zone {@code player} is visiting, or null. */
	public @Nullable String zone(UUID player) {
		Visit visit = this.visits.get(player);
		return visit == null ? null : visit.zone;
	}
}
