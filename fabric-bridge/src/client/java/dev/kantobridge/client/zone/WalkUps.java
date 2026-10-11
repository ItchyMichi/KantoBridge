package dev.kantobridge.client.zone;

import dev.kantobridge.world.Dir;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The client's half of a Wild Routes trainer's sighting (route.sight; docs/specs/WILD_ROUTES_PLAN.md §4.6): the "!"
 * shows for {@link #EXCLAIM_MS} ms, then the trainer's puppet walks up; the player is held from the sighting until the
 * walk is over (and a little after, for the server's route.battle). Plain Java, times in milliseconds.
 */
final class WalkUps {
	/** Gen 1's "!": 60 frames. */
	static final long EXCLAIM_MS = 1_000;
	/** The player stays held this long after the walk, until the hidden game's text takes over. */
	static final long SLACK_MS = 500;

	/** A walk waiting for its "!" to end. */
	record Walk(String puppet, int x, int y, Dir facing, int frames, long at) {
	}

	private final List<Walk> waiting = new ArrayList<>();
	private long holdUntil = Long.MIN_VALUE;

	/** A sighting at {@code now}: the puppet walks to cell (x, y) facing {@code facing} over {@code frames} after the "!". */
	void sighted(String puppet, int x, int y, Dir facing, int frames, long now) {
		this.waiting.add(new Walk(puppet, x, y, facing, frames, now + EXCLAIM_MS));
		this.holdUntil = Math.max(this.holdUntil, now + EXCLAIM_MS + frames * 1000L / 60 + SLACK_MS);
	}

	/** The walks whose "!" is over, in order; they are done here. */
	List<Walk> due(long now) {
		List<Walk> out = new ArrayList<>();
		for (Iterator<Walk> it = this.waiting.iterator(); it.hasNext();) {
			Walk walk = it.next();
			if (walk.at() <= now) {
				out.add(walk);
				it.remove();
			}
		}
		return out;
	}

	/** True while a sighting holds the player. */
	boolean holding(long now) {
		return now < this.holdUntil;
	}

	/** Nothing waits or holds any more (left the zone, a new world). */
	void clear() {
		this.waiting.clear();
		this.holdUntil = Long.MIN_VALUE;
	}
}
