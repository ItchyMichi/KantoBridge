package dev.kantobridge.client.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The version checks that only warn (M10): an untested engine once per pair of versions, Gen1Recomp's Union Room being
 * on once per gen1-bridge start, a world built from the other game once per world join and only in singleplayer, and
 * each distinct save mismatch once per world join.
 */
class VersionCheckTest {
	private final VersionCheck check = new VersionCheck();

	@Test
	void anUntestedEngineIsToldOncePerPairOfVersions() {
		assertFalse(this.check.engineNotice(new HelloOk("s", "blue", "0.3.85", "0.1.0", "0.3.85")), "the tested engine");
		assertFalse(this.check.engineNotice(new HelloOk("s", "blue", "0.3.86", "0.1.0")), "no tested version named");
		assertTrue(this.check.engineNotice(new HelloOk("s", "blue", "0.3.86", "0.1.0", "0.3.85")));
		assertFalse(this.check.engineNotice(new HelloOk("s2", "blue", "0.3.86", "0.1.0", "0.3.85")), "a reconnect: told already");
		this.check.joined();
		assertFalse(this.check.engineNotice(new HelloOk("s2", "blue", "0.3.86", "0.1.0", "0.3.85")), "once per client run, not per world");
		assertTrue(this.check.engineNotice(new HelloOk("s3", "blue", "0.3.87", "0.1.0", "0.3.85")), "another engine is told again");
	}

	@Test
	void theUnionRoomIsToldOncePerGen1BridgeStartWhileItIsOn() {
		assertFalse(this.check.unionNotice(new HelloOk("s", "blue", "0.3.85", "0.1.0")), "an older gen1-bridge doesn't say: taken as off");
		assertFalse(this.check.unionNotice(new HelloOk("s", "blue", "0.3.85", "0.1.0", "0.3.85", HelloOk.UNION_OFF)), "off");
		assertTrue(this.check.unionNotice(new HelloOk("s", "blue", "0.3.85", "0.1.0", "0.3.85", HelloOk.UNION_ON)));
		assertFalse(this.check.unionNotice(new HelloOk("s", "blue", "0.3.85", "0.1.0", "0.3.85", HelloOk.UNION_ON)),
				"a reconnect: told already");
		this.check.joined();
		assertFalse(this.check.unionNotice(new HelloOk("s", "blue", "0.3.85", "0.1.0", "0.3.85", HelloOk.UNION_ON)),
				"not per world join either");
		assertTrue(this.check.unionNotice(new HelloOk("s2", "blue", "0.3.85", "0.1.0", "0.3.85", HelloOk.UNION_ON)),
				"gen1-bridge restarted with the room still on: told again (the toast asked for that restart)");
		assertFalse(this.check.unionNotice(new HelloOk("s3", "blue", "0.3.85", "0.1.0", "0.3.85", HelloOk.UNION_OFF)),
				"turned off: nothing to say");
		assertTrue(this.check.engineNotice(new HelloOk("s3", "blue", "0.3.86", "0.1.0", "0.3.85", HelloOk.UNION_ON)),
				"the engine notice is its own");
	}

	@Test
	void theNoticesHaveEnglishText() throws Exception {
		JsonObject lang;
		try (InputStream in = VersionCheckTest.class.getClassLoader().getResourceAsStream("assets/kantobridge/lang/en_us.json")) {
			assertNotNull(in, "en_us.json");
			lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
		for (String key : List.of("kantobridge.version.engine.title", "kantobridge.version.engine.text", "kantobridge.version.union.title",
				"kantobridge.version.union.text", "kantobridge.debug.versions", "kantobridge.debug.versions.untested",
				"kantobridge.debug.versions.union", "kantobridge.create.error.union_room")) {
			assertTrue(lang.has(key) && !lang.get(key).getAsString().isBlank(), "no text for " + key);
		}
		assertTrue(lang.get("kantobridge.version.union.text").getAsString().contains("Launcher Options"), "the toast says what to click");
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
