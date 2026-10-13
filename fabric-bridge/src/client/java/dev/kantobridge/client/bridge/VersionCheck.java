package dev.kantobridge.client.bridge;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * The version checks that warn rather than refuse (M10; DESIGN §9 "version handshake"): plain Java, client thread. A
 * different protocol is refused outright ({@code hello.reject}, the pause overlay); these only tell the player.
 *
 * <ul>
 * <li><b>The engine</b>: gen1-bridge names the Gen1Recomp version it was tested with ({@code hello.ok.tested}); another
 * engine gets one toast per pair of versions in a client run (and a line on F7).</li>
 * <li><b>The Union Room</b>: gen1-bridge says whether Gen1Recomp's Union Room is on ({@code hello.ok.union_room}); on, it
 * gets one toast per gen1-bridge start (the toast says to restart it, so a restart with the room still on is told again;
 * a reconnect isn't) and a word on the F7 line.</li>
 * <li><b>The game</b>: in singleplayer (the integrated server's own player), a world built from one version (its
 * WorldIndex's {@code game}) while Gen1Recomp runs the other gets one warning per world join. Multiplayer guests may
 * differ on purpose (M9: the host's version decides wild exclusives).</li>
 * <li><b>The save pair</b>: each distinct {@code save.mismatch} shows once per world join (the server sends it after
 * every pairing).</li>
 * </ul>
 */
public final class VersionCheck {
	private final Set<String> enginesShown = new HashSet<>();
	private final Set<String> unionShown = new HashSet<>();
	private final Set<String> mismatchesShown = new HashSet<>();
	private boolean gameShown;

	/** {@code hello.ok}: true when the untested-engine toast is due now (once per pair of versions). */
	public boolean engineNotice(HelloOk ok) {
		return ok.untestedEngine() && this.enginesShown.add(ok.engine() + "|" + ok.tested());
	}

	/** {@code hello.ok}: true when the Union Room toast is due now (once per gen1-bridge start while the room is on). */
	public boolean unionNotice(HelloOk ok) {
		return ok.unionRoomOn() && this.unionShown.add(ok.session());
	}

	/** The player joined a world: the game warning and the save mismatches may show again. */
	public void joined() {
		this.gameShown = false;
		this.mismatchesShown.clear();
	}

	/**
	 * True when the game warning is due now: on the player's own integrated server, with both games known and different,
	 * not shown since the world was joined.
	 *
	 * @param localServer the player runs this world's server (singleplayer or a LAN host)
	 * @param worldGame   the WorldIndex's {@code game}, or null before it arrived
	 * @param hiddenGame  {@code hello.ok.game}, or null without a link
	 */
	public boolean gameNotice(boolean localServer, @Nullable String worldGame, @Nullable String hiddenGame) {
		if (this.gameShown || !localServer || worldGame == null || hiddenGame == null
				|| worldGame.toLowerCase(Locale.ROOT).equals(hiddenGame.toLowerCase(Locale.ROOT))) {
			return false;
		}
		this.gameShown = true;
		return true;
	}

	/** True when a {@code save.mismatch} (by its kind, count and times) hasn't shown since the world was joined. */
	public boolean mismatchNotice(String kind, long ops, long hiddenAt, long worldAt) {
		return this.mismatchesShown.add(kind + "|" + ops + "|" + hiddenAt + "|" + worldAt);
	}
}
