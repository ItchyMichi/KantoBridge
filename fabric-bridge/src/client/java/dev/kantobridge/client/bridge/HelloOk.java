package dev.kantobridge.client.bridge;

import org.jetbrains.annotations.Nullable;

/**
 * The facts gen1-bridge gives in {@code hello.ok}. All are versions or ids, never game content.
 *
 * @param session random per gen1-bridge start: a new value means gen1-bridge restarted
 * @param game    {@code red} or {@code blue}
 * @param engine  the Gen1Recomp engine version
 * @param bridge  gen1-bridge's version
 * @param tested  the engine version gen1-bridge was tested with (M10), or null when it doesn't say
 */
public record HelloOk(String session, String game, String engine, String bridge, @Nullable String tested) {
	/** A {@code hello.ok} without {@code tested}. */
	public HelloOk(String session, String game, String engine, String bridge) {
		this(session, game, engine, bridge, null);
	}

	/** True when gen1-bridge names a tested engine version and this engine is another one (M10: a warning, not a refusal). */
	public boolean untestedEngine() {
		return this.tested != null && !this.tested.equals(this.engine);
	}
}
