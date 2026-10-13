package dev.kantobridge.story;

import dev.kantobridge.world.StoryCellType;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * One player's blockade-relevant story state (docs/PLAN.md §4.2 "Story state"): which story cells are closed for
 * them, where their boulders stand, whether they surf or ride, and which dark maps they have lit.
 *
 * <p>It lives on the player as a data attachment ({@link StoryStates}), on both sides: the client fills its own
 * player's copy from the hidden game and relays changes; the server applies {@code story.state}. Never keep it in
 * statics: in singleplayer the client and the integrated server share them.
 *
 * <p>Whether a cell blocks depends on its type ({@link #blocks}):
 * <ul>
 * <li>{@code object}, {@code tile}, {@code route} (a Wild Routes ball) and {@code pass} (a connecting route's pass, MG5): closed
 * while the cell is in the active set;</li>
 * <li>{@code water}: closed unless the player surfs, or while the cell is active;</li>
 * <li>{@code boulder_area}: closed where one of the player's boulders stands, or while the cell is active.</li>
 * </ul>
 * Cell keys are {@code "MAP:x:y"}. Only the owning thread (server or client) touches an instance.
 */
public final class StoryState {
	private final Set<String> active = new HashSet<>();
	private final Set<String> boulders = new HashSet<>();
	private final Set<String> lit = new HashSet<>();
	private boolean surf;
	private boolean bike;

	/** True when a story cell of this type and key closes for this player. */
	public boolean blocks(String key, StoryCellType type) {
		return switch (type) {
			case OBJECT, TILE, ROUTE, PASS -> this.active.contains(key);
			case WATER -> !this.surf || this.active.contains(key);
			case BOULDER_AREA -> this.boulders.contains(key) || this.active.contains(key);
		};
	}

	/** The rule for an entity that has no state yet: water is closed (nobody surfs), everything else is open. */
	public static boolean blocksWithoutState(StoryCellType type) {
		return type == StoryCellType.WATER;
	}

	// ---- active cells ----

	public boolean isActive(String key) {
		return this.active.contains(key);
	}

	public Set<String> active() {
		return Collections.unmodifiableSet(this.active);
	}

	/** Replaces the whole active set (a full {@code story.state}). */
	public void setActive(Collection<String> keys) {
		this.active.clear();
		this.active.addAll(keys);
	}

	public void activate(String key) {
		this.active.add(key);
	}

	public void deactivate(String key) {
		this.active.remove(key);
	}

	// ---- boulders ----

	public Set<String> boulders() {
		return Collections.unmodifiableSet(this.boulders);
	}

	public void setBoulders(Collection<String> keys) {
		this.boulders.clear();
		this.boulders.addAll(keys);
	}

	// ---- flags ----

	public boolean surf() {
		return this.surf;
	}

	public void setSurf(boolean surf) {
		this.surf = surf;
	}

	public boolean bike() {
		return this.bike;
	}

	public void setBike(boolean bike) {
		this.bike = bike;
	}

	// ---- lit maps ----

	public boolean isLit(String map) {
		return this.lit.contains(map);
	}

	public Set<String> lit() {
		return Collections.unmodifiableSet(this.lit);
	}

	public void setLit(Collection<String> maps) {
		this.lit.clear();
		this.lit.addAll(maps);
	}

	/** Back to a fresh player's state. */
	public void clear() {
		this.active.clear();
		this.boulders.clear();
		this.lit.clear();
		this.surf = false;
		this.bike = false;
	}
}
