package dev.kantobridge.client.puppet;

import dev.kantobridge.world.Dir;
import org.jetbrains.annotations.Nullable;

/**
 * One hidden-game NPC as this client draws it (docs/PROTOCOL.md "NPCs"): where it is, which way it faces, its sprite,
 * and the step it is walking. Plain Java, advanced in Gen 1 frames (60 a second; a client tick is 3).
 *
 * <p>Positions are map cells; while walking, {@link #cellX}/{@link #cellY} slide from the step's start to its target
 * over the step's frames. The sprite frame follows Gen 1: standing, then the walking frame for the middle half of each
 * 16-frame step (frames 4 to 11), and for up and down the walking frame is mirrored on every other step. An NPC that
 * walks in place ({@code moving} without a step, a march) cycles the same way.
 */
public final class Puppet {
	/** Gen 1 frames per client tick (60 Hz over 20 Hz). */
	public static final int FRAMES_PER_TICK = 3;
	/** A walk cycle (one step's animation) is 16 frames; the walking frame shows for frames 4 to 11. */
	public static final int CYCLE = 16;
	/** An emote with no length of its own shows for a second. */
	public static final int DEFAULT_EMOTE_FRAMES = 60;

	private final String id;
	private final int entityId;
	private String map;
	private String sprite;
	private String kind;
	private int x;
	private int y;
	private Dir facing;
	/** Walking in place, or caught mid-step by a full list. */
	private boolean moving;
	private double fromX;
	private double fromY;
	private int stepFrames;
	private int stepElapsed;
	private int steps;
	private int marchClock;
	private @Nullable String bubble;
	private int bubbleFrames;
	/** The hidden game's elevation of the object (D7: which floor of a two-floor cell), -1 for none. */
	private int elev = -1;

	Puppet(String id, int entityId, String map, String sprite, String kind, int x, int y, Dir facing, boolean moving) {
		this.id = id;
		this.entityId = entityId;
		this.map = map;
		this.sprite = sprite;
		this.kind = kind;
		this.place(x, y, facing);
		this.moving = moving;
	}

	// ---- changes ----

	/** {@code npc.set}/{@code npc.add} for a puppet that exists: takes the new data, standing at the cell. */
	void reset(String map, String sprite, String kind, int x, int y, Dir facing, boolean moving) {
		this.map = map;
		this.sprite = sprite;
		this.kind = kind;
		if (x != this.x || y != this.y) {
			this.place(x, y, facing);
		}
		this.facing = facing;
		this.moving = moving;
	}

	/** The object's elevation (-1: none): on a two-floor cell, its floor. */
	void setElev(int elev) {
		this.elev = elev;
	}

	public int elev() {
		return this.elev;
	}

	/** {@code npc.move}: a step (or a scripted move) to {@code x, y} over {@code frames}; 0 frames jumps there. */
	void move(int x, int y, Dir facing, int frames) {
		this.fromX = this.cellX(0);
		this.fromY = this.cellY(0);
		this.x = x;
		this.y = y;
		this.facing = facing;
		this.stepFrames = Math.max(0, frames);
		this.stepElapsed = 0;
		if (this.stepFrames > 0) {
			this.steps++;
		} else {
			this.fromX = x;
			this.fromY = y;
		}
	}

	void face(Dir facing) {
		this.facing = facing;
	}

	/** An emote bubble ({@code !}, {@code ?}, {@code happy}) for {@code frames}, or none. */
	void emote(@Nullable String bubble, int frames) {
		this.bubble = bubble;
		this.bubbleFrames = bubble == null ? 0 : (frames > 0 ? frames : DEFAULT_EMOTE_FRAMES);
	}

	/** Advances the step, the march and the emote by {@code frames}. */
	public void advance(int frames) {
		if (this.stepElapsed < this.stepFrames) {
			this.stepElapsed = Math.min(this.stepFrames, this.stepElapsed + frames);
		}
		if (this.moving && !this.stepping()) {
			this.marchClock += frames;
		}
		if (this.bubbleFrames > 0) {
			this.bubbleFrames -= frames;
			if (this.bubbleFrames <= 0) {
				this.bubble = null;
				this.bubbleFrames = 0;
			}
		}
	}

	/** Finishes any step at once (a puppet that appears mid-walk). */
	void settle() {
		this.stepElapsed = this.stepFrames;
	}

	private void place(int x, int y, Dir facing) {
		this.x = x;
		this.y = y;
		this.fromX = x;
		this.fromY = y;
		this.facing = facing;
		this.stepFrames = 0;
		this.stepElapsed = 0;
	}

	// ---- view ----

	/** The cell X the puppet is at, {@code partialFrames} Gen 1 frames after the last advance (fractional mid-step). */
	public double cellX(float partialFrames) {
		return this.fromX + (this.x - this.fromX) * this.progress(partialFrames);
	}

	public double cellY(float partialFrames) {
		return this.fromY + (this.y - this.fromY) * this.progress(partialFrames);
	}

	/** True while a step with frames is under way. */
	public boolean stepping() {
		return this.stepElapsed < this.stepFrames;
	}

	/** The walking frame shows now (otherwise the standing one). */
	public boolean walkingFrame(float partialFrames) {
		double phase;
		if (this.stepping()) {
			phase = (this.stepElapsed + partialFrames) * CYCLE / (double) this.stepFrames;
		} else if (this.moving) {
			phase = this.marchClock + partialFrames;
		} else {
			return false;
		}
		double inCycle = phase % CYCLE;
		return inCycle >= 4 && inCycle < 12;
	}

	/** Where in its walk cycle the puppet is, 0..1, or -1 when it stands (rigged figures swing their limbs by it). */
	public float walkPhase(float partialFrames) {
		double phase;
		if (this.stepping()) {
			phase = (this.stepElapsed + partialFrames) * CYCLE / (double) this.stepFrames;
		} else if (this.moving) {
			phase = this.marchClock + partialFrames;
		} else {
			return -1.0F;
		}
		return (float) (phase % CYCLE / CYCLE);
	}

	/** For up and down, every other step shows the walking frame mirrored (the other foot forward). */
	public boolean mirroredWalk() {
		// The first step (or walk cycle) is drawn as the sheet has it, the second mirrored, and so on.
		int cycle = this.stepping() ? this.steps - 1 : this.marchClock / CYCLE;
		return (this.facing == Dir.UP || this.facing == Dir.DOWN) && (cycle & 1) == 1;
	}

	private double progress(float partialFrames) {
		if (this.stepFrames <= 0) {
			return 1.0;
		}
		return Math.min(1.0, (this.stepElapsed + partialFrames) / this.stepFrames);
	}

	public String id() {
		return this.id;
	}

	/** The client-only entity id (negative). */
	public int entityId() {
		return this.entityId;
	}

	public String map() {
		return this.map;
	}

	public String sprite() {
		return this.sprite;
	}

	/** {@code person}, {@code item}, {@code boulder}, {@code static} or {@code trainer}. */
	public String kind() {
		return this.kind;
	}

	/** The cell it stands on, or walks to. */
	public int x() {
		return this.x;
	}

	public int y() {
		return this.y;
	}

	public Dir facing() {
		return this.facing;
	}

	public boolean moving() {
		return this.moving;
	}

	public @Nullable String bubble() {
		return this.bubble;
	}

	public int bubbleFrames() {
		return this.bubbleFrames;
	}
}
