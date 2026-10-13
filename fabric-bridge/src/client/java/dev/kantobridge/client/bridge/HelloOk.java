package dev.kantobridge.client.bridge;

import com.google.gson.JsonObject;
import dev.kantobridge.world.Games;
import org.jetbrains.annotations.Nullable;

/**
 * The facts gen1-bridge gives in {@code hello.ok}. All are versions, ids or settings, never game content.
 *
 * @param session   random per gen1-bridge start: a new value means gen1-bridge restarted
 * @param game      the game the hidden process runs: an id of the game catalogue (MULTI_GAME_PLAN §4.2), e.g. {@code blue}
 * @param engine    the Gen1Recomp engine version
 * @param bridge    gen1-bridge's version
 * @param tested    the engine version gen1-bridge was tested with (M10), or null when it doesn't say
 * @param unionRoom Gen1Recomp's Union Room setting as gen1-bridge found it ({@link #UNION_ON} or {@link #UNION_OFF}), or null
 *                  when it doesn't say (an older gen1-bridge): taken as off
 * @param family    the game's engine family (MG1): what the message said, else the catalogue's for {@code game}; null for a
 *                  game the catalogue doesn't know
 * @param compat    the adapter's compatibility limits (MG1): what the message said, else the catalogue's profile for
 *                  {@code game}; null for a game the catalogue doesn't know
 */
public record HelloOk(String session, String game, String engine, String bridge, @Nullable String tested, @Nullable String unionRoom,
		@Nullable String family, Games.@Nullable Compat compat) {
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

	/** A {@code hello.ok} that says nothing about the family or the limits: the catalogue's for the game (an older gen1-bridge). */
	public HelloOk(String session, String game, String engine, String bridge, @Nullable String tested, @Nullable String unionRoom) {
		this(session, game, engine, bridge, tested, unionRoom, Games.family(game), Games.compat(game));
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

	/** True when the game catalogue names the game (a game this mod knows, with or without an adapter). */
	public boolean knownGame() {
		return Games.known(this.game);
	}

	/** {@code hello.ok.union_room} as this record keeps it: {@link #UNION_ON} or {@link #UNION_OFF}; anything else (or none) is null. */
	public static @Nullable String unionRoomField(@Nullable String value) {
		return UNION_ON.equals(value) || UNION_OFF.equals(value) ? value : null;
	}

	/**
	 * {@code hello.ok.family} as this record keeps it: the message's value when it is a family the catalogue knows, else the
	 * catalogue's family for {@code game} (null for an unknown game).
	 */
	public static @Nullable String familyField(@Nullable String value, String game) {
		return value != null && Games.FAMILIES.contains(value) ? value : Games.family(game);
	}

	/**
	 * {@code hello.ok.compat} as this record keeps it: the message's three limits when they are whole numbers within the
	 * protocol's ranges, else the catalogue's profile for {@code game} (null for an unknown game).
	 */
	public static Games.@Nullable Compat compatField(@Nullable JsonObject value, String game) {
		if (value != null) {
			long maxDex = JsonFields.integer(value, "maxDex", -1);
			long maxMove = JsonFields.integer(value, "maxMove", -1);
			long nick = JsonFields.integer(value, "nick", -1);
			if (Games.Compat.fits(maxDex, maxMove, nick)) {
				return new Games.Compat((int) maxDex, (int) maxMove, (int) nick);
			}
		}
		return Games.compat(game);
	}
}
