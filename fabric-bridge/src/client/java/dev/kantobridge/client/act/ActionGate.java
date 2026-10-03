package dev.kantobridge.client.act;

/**
 * When the player may act on the hidden game from the world (docs/PLAN.md §2.4 flow 2, P20): A ({@code act.interact})
 * and START ({@code act.button}) go out only while the hidden player stands idle in its overworld and nothing of ours
 * is open, and not faster than a press: holding right-click repeats every 4 ticks, and a key held down repeats too.
 * Plain Java.
 */
public final class ActionGate {
	/** At most one A press this often (right-click repeats every 200 ms while held). */
	public static final long INTERACT_COOLDOWN_MS = 300;
	public static final long BUTTON_COOLDOWN_MS = 250;
	public static final String IDLE = "idle";

	private long lastInteract = Long.MIN_VALUE / 2;
	private long lastButton = Long.MIN_VALUE / 2;
	private long interacts;
	private long buttons;
	private long refused;

	/**
	 * True when an action from the world makes sense now.
	 *
	 * @param following the movement sync follows the hidden game (a Kanto world, not creative or spectator)
	 * @param held      the input lock holds the player (a warp, a forced move, a screen, a busy hidden game)
	 * @param uiOpen    one of the hidden game's UIs is open on our side
	 */
	public static boolean idle(boolean following, boolean connected, boolean ingame, String mode, boolean held, boolean uiOpen) {
		return following && connected && ingame && IDLE.equals(mode) && !held && !uiOpen;
	}

	/** Takes the A press if the last one was long enough ago. */
	public boolean tryInteract(long now) {
		if (now - this.lastInteract < INTERACT_COOLDOWN_MS) {
			this.refused++;
			return false;
		}
		this.lastInteract = now;
		this.interacts++;
		return true;
	}

	/** Takes a button press (START) if the last one was long enough ago. */
	public boolean tryButton(long now) {
		if (now - this.lastButton < BUTTON_COOLDOWN_MS) {
			this.refused++;
			return false;
		}
		this.lastButton = now;
		this.buttons++;
		return true;
	}

	public long interacts() {
		return this.interacts;
	}

	public long buttons() {
		return this.buttons;
	}

	public long refused() {
		return this.refused;
	}
}
