package dev.kantobridge.client.net;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * WORLD_SESSIONS_PLAN §2.5 (WS5; D6, D7): the quit choreography, without Minecraft. When the player leaves a world
 * ("Save and Quit to Title", or the window's close) every bound hidden game is first asked for a save of its own
 * ({@code save.request{reason:"quit"}}); the leave waits until each has saved, refused, or the wait ran out
 * ({@link #QUIT_WAIT_MS}, the window's close {@link #WINDOW_WAIT_MS}); then Minecraft's own disconnect runs; then
 * {@code session.close} goes to every game that saved (or had nothing to save: it was at its title), which returns it
 * to its title and its offline slot. A game that didn't save is named on the HUD and left as it is: never returned to
 * the title, never stopped (D7). Plain Java; {@link SessionRuntime} is the glue.
 */
public final class SessionClose {
	public static final long QUIT_WAIT_MS = 10_000;
	public static final long WINDOW_WAIT_MS = 5_000;

	/** One bound hidden game. */
	public interface Target {
		/** Its label for the screen and the HUD (the game's name). */
		String name();

		/** Sends {@code save.request{reason:"quit"}}; false when it couldn't go out (the link is down). */
		boolean requestSave(long now);

		/** Where that request stands. */
		SaveRelay.RequestState saveState();

		/** gen1-bridge's refusal, when refused. */
		@Nullable String refusal();

		/** Sends {@code session.close}. */
		void close();
	}

	/** How one game left. */
	public enum Outcome {
		/** Its quit save was written (or its Minecraft side done). */
		SAVED,
		/** At its title: nothing to save (closed as well). */
		NOTHING,
		/** Refused or out of time: left as it is (D7). */
		FAILED
	}

	/** What the glue does next. */
	public enum Step {
		/** Keep waiting. */
		WAIT,
		/** Run Minecraft's own disconnect now, then call {@link #afterDisconnect}. */
		DISCONNECT,
		/** Nothing (not started, or finished). */
		IDLE
	}

	private final Map<Target, Outcome> outcomes = new LinkedHashMap<>();
	private final List<Target> targets = new ArrayList<>();
	private long deadline = -1;
	private boolean running;
	private boolean disconnected;

	/** Starts the choreography for these targets; false when there is nothing to wait for (the vanilla leave runs at once). */
	public boolean begin(List<Target> bound, long now, boolean window) {
		this.targets.clear();
		this.outcomes.clear();
		this.disconnected = false;
		for (Target target : bound) {
			if (target.requestSave(now)) {
				this.targets.add(target);
			}
		}
		this.running = !this.targets.isEmpty();
		this.deadline = now + (window ? WINDOW_WAIT_MS : QUIT_WAIT_MS);
		return this.running;
	}

	public boolean running() {
		return this.running;
	}

	/** One tick while waiting. */
	public Step tick(long now) {
		if (!this.running || this.disconnected) {
			return Step.IDLE;
		}
		boolean all = true;
		for (Target target : this.targets) {
			Outcome outcome = outcomeOf(target);
			if (outcome == null) {
				all = false;
			} else {
				this.outcomes.put(target, outcome);
			}
		}
		if (all || now >= this.deadline) {
			for (Target target : this.targets) {
				this.outcomes.putIfAbsent(target, Outcome.FAILED);
			}
			return Step.DISCONNECT;
		}
		return Step.WAIT;
	}

	static @Nullable Outcome outcomeOf(Target target) {
		return switch (target.saveState()) {
			case WRITTEN, DONE -> Outcome.SAVED;
			case REFUSED -> "not_ingame".equals(target.refusal()) ? Outcome.NOTHING : Outcome.FAILED;
			case NONE, SENT -> null;
		};
	}

	/** Minecraft has left the world: {@code session.close} to every game that saved or had nothing to save. */
	public void afterDisconnect() {
		if (!this.running || this.disconnected) {
			return;
		}
		this.disconnected = true;
		for (Target target : this.targets) {
			if (this.outcomes.get(target) != Outcome.FAILED) {
				target.close();
			}
		}
		this.running = false;
	}

	/** A legacy world: the leave ran, no session.close is sent (the games keep running as saved). */
	public void abandon() {
		this.disconnected = true;
		this.running = false;
	}

	/** The games that didn't save (for the HUD), after the choreography. */
	public List<String> failed() {
		List<String> out = new ArrayList<>();
		this.outcomes.forEach((target, outcome) -> {
			if (outcome == Outcome.FAILED) {
				out.add(target.name());
			}
		});
		return out;
	}

	/** The names being saved (the screen's line). */
	public List<String> names() {
		return this.targets.stream().map(Target::name).toList();
	}

	public Map<Target, Outcome> outcomes() {
		return Map.copyOf(this.outcomes);
	}
}
