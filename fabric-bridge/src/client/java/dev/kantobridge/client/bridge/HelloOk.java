package dev.kantobridge.client.bridge;

import org.jetbrains.annotations.Nullable;

/**
 * The facts gen1-bridge gives in {@code hello.ok}. All are versions, ids or settings, never game content.
 *
 * @param session   random per gen1-bridge start: a new value means gen1-bridge restarted
 * @param game      {@code red} or {@code blue}
 * @param engine    the Gen1Recomp engine version
 * @param bridge    gen1-bridge's version
 * @param tested    the engine version gen1-bridge was tested with (M10), or null when it doesn't say
 * @param unionRoom Gen1Recomp's Union Room setting as gen1-bridge found it ({@link #UNION_ON} or {@link #UNION_OFF}), or null
 *                  when it doesn't say (an older gen1-bridge): taken as off
 */
public record HelloOk(String session, String game, String engine, String bridge, @Nullable String tested, @Nullable String unionRoom) {
	/** {@code hello.ok.union_room}'s values. */
	public static final String UNION_ON = "on";
	public static final String UNION_OFF = "off";

	/** A {@code hello.ok} without {@code tested} or {@code union_room}. */
	public HelloOk(String session, String game, String engine, String bridge) {
		this(session, game, engine, bridge, null, null);
	}

	/** A {@code hello.ok} without {@code union_room}. */
	public HelloOk(String session, String game, String engine, String bridge, @Nullable String tested) {
		this(session, game, engine, bridge, tested, null);
	}

	/** True when gen1-bridge names a tested engine version and this engine is another one (M10: a warning, not a refusal). */
	public boolean untestedEngine() {
		return this.tested != null && !this.tested.equals(this.engine);
	}

	/**
	 * True when gen1-bridge says Gen1Recomp's Union Room is on: the engine then rewrites the Pokémon Centers the world was
	 * built without (ENGINE_UPGRADE_PLAN §1.3 B2), so the player is told to turn it off and the export is refused. Not said
	 * means off.
	 */
	public boolean unionRoomOn() {
		return UNION_ON.equals(this.unionRoom);
	}

	/** {@code hello.ok.union_room} as this record keeps it: {@link #UNION_ON} or {@link #UNION_OFF}; anything else (or none) is null. */
	public static @Nullable String unionRoomField(@Nullable String value) {
		return UNION_ON.equals(value) || UNION_OFF.equals(value) ? value : null;
	}
}
