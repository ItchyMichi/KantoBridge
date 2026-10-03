package dev.kantobridge.client.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The version checks that only warn (M10): an untested engine once per pair of versions, a world built from the other
 * game once per world join and only in singleplayer, and each distinct save mismatch once per world join.
 */
class VersionCheckTest {
	private final VersionCheck check = new VersionCheck();

	@Test
	void anUntestedEngineIsToldOncePerPairOfVersions() {
		assertFalse(this.check.engineNotice(new HelloOk("s", "blue", "0.3.49", "0.1.0", "0.3.49")), "the tested engine");
		assertFalse(this.check.engineNotice(new HelloOk("s", "blue", "0.3.50", "0.1.0")), "no tested version named");
		assertTrue(this.check.engineNotice(new HelloOk("s", "blue", "0.3.50", "0.1.0", "0.3.49")));
		assertFalse(this.check.engineNotice(new HelloOk("s2", "blue", "0.3.50", "0.1.0", "0.3.49")), "a reconnect: told already");
		this.check.joined();
		assertFalse(this.check.engineNotice(new HelloOk("s2", "blue", "0.3.50", "0.1.0", "0.3.49")), "once per client run, not per world");
		assertTrue(this.check.engineNotice(new HelloOk("s3", "blue", "0.3.51", "0.1.0", "0.3.49")), "another engine is told again");
	}

	@Test
	void theWorldsGameIsCheckedInSingleplayerOncePerJoin() {
		assertFalse(this.check.gameNotice(true, "blue", "blue"));
		assertFalse(this.check.gameNotice(true, "blue", "BLUE"), "the same game, whatever its case");
		assertFalse(this.check.gameNotice(false, "blue", "red"), "a multiplayer guest may differ on purpose (M9)");
		assertFalse(this.check.gameNotice(true, null, "red"), "the WorldIndex hasn't arrived");
		assertFalse(this.check.gameNotice(true, "blue", null), "no link yet");
		assertTrue(this.check.gameNotice(true, "blue", "red"));
		assertFalse(this.check.gameNotice(true, "blue", "red"), "once per world join");
		this.check.joined();
		assertTrue(this.check.gameNotice(true, "blue", "red"), "a new join warns again");
	}

	@Test
	void eachDistinctSaveMismatchShowsOncePerJoin() {
		assertTrue(this.check.mismatchNotice("hidden_behind", 2, 100, 100));
		assertFalse(this.check.mismatchNotice("hidden_behind", 2, 100, 100), "the server sends it after every pairing");
		assertTrue(this.check.mismatchNotice("hidden_behind", 3, 100, 100), "a different count is news");
		assertTrue(this.check.mismatchNotice("other", -1, -1, -1));
		this.check.joined();
		assertTrue(this.check.mismatchNotice("hidden_behind", 2, 100, 100), "a new join shows it again");
	}
}
