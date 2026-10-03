package dev.kantobridge.client.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.client.packs.TextureSync.Decision;
import dev.kantobridge.client.packs.TextureSync.Phase;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The joining player's texture flow (docs/PLAN.md §4.3 "Multiplayer clients"): the hash comparison and the regenerate
 * decision, the flow through export, build and reload, placeholders without gen1-bridge (a wrong pack held back), and the
 * {@code texture.status} it reports (hashes and counts only).
 */
class TextureSyncTest {
	private static final String WORLD = "a".repeat(64);
	private static final String OTHER = "b".repeat(64);
	private static final Pattern REASON = Pattern.compile("^[a-z][a-z0-9_.]{0,63}$");

	/** Records what the flow asks of its glue; reports go through unless the server link is down. */
	private static final class FakePort implements TextureSync.Port {
		final List<JsonObject> reports = new ArrayList<>();
		final List<Long> regenerations = new ArrayList<>();
		final List<Boolean> reloads = new ArrayList<>();
		final List<Long> reloadTokens = new ArrayList<>();
		final List<String> warnings = new ArrayList<>();
		boolean serverUp = true;

		@Override
		public boolean report(JsonObject status) {
			if (!this.serverUp) {
				return false;
			}
			this.reports.add(status);
			return true;
		}

		@Override
		public void regenerate(long token) {
			this.regenerations.add(token);
		}

		@Override
		public void reload(long token, boolean offer) {
			this.reloadTokens.add(token);
			this.reloads.add(offer);
		}

		@Override
		public void warn(String code, long count) {
			this.warnings.add(code);
		}

		JsonObject last() {
			return this.reports.get(this.reports.size() - 1);
		}
	}

	private FakePort port;
	private TextureSync sync;

	@BeforeEach
	void setUp() {
		this.port = new FakePort();
		this.sync = new TextureSync(this.port);
		this.sync.reset();
	}

	private static PackInfo pack(String hash, int textures) {
		return new PackInfo("red", "c".repeat(64), hash, textures);
	}

	private static TextureSync.Inputs in(String server, String slots, PackInfo local, boolean loaded, boolean bridge) {
		return new TextureSync.Inputs(server, slots, local, loaded, bridge);
	}

	@Test
	void theDecisionTable() {
		assertEquals(Decision.NONE, TextureSync.decide(null, WORLD, true, WORLD, true), "no index: not a Kanto world");
		assertEquals(Decision.MATCH, TextureSync.decide(WORLD, WORLD, true, null, false), "the pack fits; nothing else matters");
		assertEquals(Decision.RELOAD, TextureSync.decide(WORLD, WORLD, false, null, false), "it fits but isn't loaded");
		assertEquals(Decision.WAIT_SLOTS, TextureSync.decide(WORLD, OTHER, true, null, true), "can't build before the slots came");
		assertEquals(Decision.WAIT_SLOTS, TextureSync.decide(WORLD, null, false, null, true), "no pack at all: same");
		assertEquals(Decision.SLOTS_MISMATCH, TextureSync.decide(WORLD, OTHER, true, OTHER, true), "slots that aren't the index's");
		assertEquals(Decision.NO_BRIDGE, TextureSync.decide(WORLD, OTHER, true, WORLD, false), "no gen1-bridge: placeholders");
		assertEquals(Decision.NO_BRIDGE, TextureSync.decide(WORLD, null, false, WORLD, false));
		assertEquals(Decision.REGENERATE, TextureSync.decide(WORLD, OTHER, true, WORLD, true), "a different pack: rebuild it");
		assertEquals(Decision.REGENERATE, TextureSync.decide(WORLD, null, false, WORLD, true), "no pack yet: build one");
	}

	@Test
	void aFittingPackIsReportedOnce() {
		this.sync.tick(0, in(WORLD, null, pack(WORLD, 4273), true, false), false);
		assertEquals(Phase.READY, this.sync.phase());
		assertEquals(1, this.port.reports.size());
		JsonObject status = this.port.last();
		assertTrue(status.get("ok").getAsBoolean());
		assertEquals(WORLD, status.get("slotMapHash").getAsString());
		assertEquals(4273, status.get("textures").getAsInt());
		assertEquals(3, status.size(), "ok, slotMapHash and textures only");
		for (int t = 1; t < 50; t++) {
			this.sync.tick(t * 50L, in(WORLD, WORLD, pack(WORLD, 4273), true, true), false);
		}
		assertEquals(1, this.port.reports.size(), "reported once per world");
		assertTrue(this.port.regenerations.isEmpty() && this.port.reloads.isEmpty(), "nothing to do for a fitting pack");
		assertNull(this.sync.hudLine(5000), "no HUD line when nothing was rebuilt");
	}

	@Test
	void aFittingPackThatIsntLoadedIsReloadedFirst() {
		this.sync.tick(0, in(WORLD, null, pack(WORLD, 10), false, false), false);
		assertEquals(Phase.RELOADING, this.sync.phase());
		assertEquals(List.of(true), this.port.reloads, "reloaded with the pack offered");
		this.sync.tick(50, in(WORLD, null, pack(WORLD, 10), false, false), false);
		assertEquals(List.of(true), this.port.reloads, "one reload at a time");
		this.sync.reloaded(this.port.reloadTokens.get(0), true);
		this.sync.tick(100, in(WORLD, null, pack(WORLD, 10), true, false), false);
		assertEquals(Phase.READY, this.sync.phase());
		assertTrue(this.port.last().get("ok").getAsBoolean());
	}

	@Test
	void aDifferentPackIsRebuiltAgainstTheServersSlots() {
		// The index arrives first; the slot map a moment later.
		this.sync.tick(0, in(WORLD, null, pack(OTHER, 99), true, true), false);
		assertEquals(Phase.WAITING, this.sync.phase());
		assertTrue(this.port.regenerations.isEmpty() && this.port.reports.isEmpty());
		this.sync.tick(50, in(WORLD, WORLD, pack(OTHER, 99), true, true), false);
		assertEquals(Phase.WORKING, this.sync.phase());
		assertEquals(1, this.port.regenerations.size(), "the export starts");
		assertNotNull(this.sync.hudLine(50));
		assertEquals("kantobridge.hud.textures.export", this.sync.hudLine(50).key());
		this.sync.work(TextureSync.Work.PACK);
		assertEquals("kantobridge.hud.textures.pack", this.sync.hudLine(60).key());
		// While the worker runs, nothing else happens (the main link is paused: the bridge looks disconnected).
		for (int t = 2; t < 20; t++) {
			this.sync.tick(t * 50L, in(WORLD, WORLD, pack(OTHER, 99), true, false), false);
		}
		assertEquals(1, this.port.regenerations.size());
		assertTrue(this.port.reports.isEmpty(), "no status while working");

		this.sync.finished(this.port.regenerations.get(0), null);
		assertEquals(Phase.RELOADING, this.sync.phase());
		assertEquals(List.of(true), this.port.reloads);
		this.sync.reloaded(this.port.reloadTokens.get(0), true);
		// The rebuilt pack now says it was made for the world's slots.
		this.sync.tick(1000, in(WORLD, WORLD, pack(WORLD, 4100), true, true), false);
		assertEquals(Phase.READY, this.sync.phase());
		JsonObject status = this.port.last();
		assertTrue(status.get("ok").getAsBoolean());
		assertEquals(WORLD, status.get("slotMapHash").getAsString(), "the server's slot map, never the export's own");
		assertEquals(4100, status.get("textures").getAsInt());
		assertEquals("kantobridge.hud.textures.ready", this.sync.hudLine(1000 + TextureSync.DONE_LINE_MS - 1).key());
		assertNull(this.sync.hudLine(1000 + TextureSync.DONE_LINE_MS + 1), "the line goes after a while");
	}

	@Test
	void withoutGen1BridgeTheWorldShowsPlaceholdersUntilItConnects() {
		this.sync.tick(0, in(WORLD, WORLD, pack(OTHER, 99), true, false), false);
		assertEquals(Phase.NO_BRIDGE, this.sync.phase());
		JsonObject status = this.port.last();
		assertFalse(status.get("ok").getAsBoolean());
		assertEquals("no_bridge", status.get("reason").getAsString());
		assertEquals(List.of(false), this.port.reloads, "the wrong pack is held back");
		assertTrue(this.sync.hudLine(0).warning(), "a HUD hint");
		this.sync.reloaded(this.port.reloadTokens.get(0), true);
		assertTrue(this.sync.hidden());

		for (int t = 1; t < 20; t++) {
			this.sync.tick(t * 50L, in(WORLD, WORLD, pack(OTHER, 99), false, false), false);
		}
		assertEquals(1, this.port.reports.size(), "placeholders reported once");
		assertEquals(1, this.port.reloads.size(), "held back once");
		assertTrue(this.port.regenerations.isEmpty());

		// gen1-bridge starts: the export runs after all.
		this.sync.tick(2000, in(WORLD, WORLD, pack(OTHER, 99), false, true), false);
		assertEquals(Phase.WORKING, this.sync.phase());
		assertEquals(1, this.port.regenerations.size());
	}

	@Test
	void withoutAnyPackNothingIsHeldBack() {
		this.sync.tick(0, in(WORLD, WORLD, null, false, false), false);
		assertEquals(Phase.NO_BRIDGE, this.sync.phase());
		assertTrue(this.port.reloads.isEmpty(), "placeholders already");
		assertEquals("no_bridge", this.port.last().get("reason").getAsString());
	}

	@Test
	void failuresEndInPlaceholdersWithAReason() {
		this.sync.tick(0, in(WORLD, WORLD, pack(OTHER, 99), true, true), false);
		this.sync.finished(this.port.regenerations.get(0), "export.index_timeout");
		assertEquals(Phase.FAILED, this.sync.phase());
		assertEquals("export.index_timeout", this.sync.reason());
		assertEquals("export.index_timeout", this.port.last().get("reason").getAsString());
		// The next tick holds the wrong pack back.
		this.sync.tick(50, in(WORLD, WORLD, pack(OTHER, 99), true, true), false);
		assertEquals(List.of(false), this.port.reloads);
		assertEquals("kantobridge.hud.textures.failed", this.sync.hudLine(50).key());
		// No retry loop in the same world.
		for (int t = 2; t < 40; t++) {
			this.sync.tick(t * 50L, in(WORLD, WORLD, pack(OTHER, 99), false, true), false);
		}
		assertEquals(1, this.port.regenerations.size());
		assertEquals(1, this.port.reports.size());
		assertTrue(this.port.warnings.contains("textures.export.index_timeout"));
	}

	@Test
	void aSlotMapThatNeverComesOrIsntTheIndexsFails() {
		this.sync.tick(0, in(WORLD, null, pack(OTHER, 1), false, true), false);
		this.sync.tick(TextureSync.SLOTS_WAIT_MS - 1, in(WORLD, null, pack(OTHER, 1), false, true), false);
		assertEquals(Phase.WAITING, this.sync.phase());
		this.sync.tick(TextureSync.SLOTS_WAIT_MS + 1, in(WORLD, null, pack(OTHER, 1), false, true), false);
		assertEquals(Phase.FAILED, this.sync.phase());
		assertEquals("no_slots", this.port.last().get("reason").getAsString());

		TextureSync other = new TextureSync(this.port);
		other.reset();
		other.tick(0, in(WORLD, OTHER, null, false, true), false);
		assertEquals(Phase.FAILED, other.phase());
		assertEquals("slot_map_mismatch", this.port.last().get("reason").getAsString());
		assertTrue(this.port.regenerations.isEmpty(), "nothing is built against slots the index doesn't name");
	}

	@Test
	void aFailedReloadIsReported() {
		this.sync.tick(0, in(WORLD, WORLD, null, false, true), false);
		this.sync.finished(this.port.regenerations.get(0), null);
		this.sync.reloaded(this.port.reloadTokens.get(0), false);
		assertEquals(Phase.FAILED, this.sync.phase());
		assertEquals("reload", this.port.last().get("reason").getAsString());
	}

	@Test
	void lateAnswersForAnOlderWorldAreDropped() {
		this.sync.tick(0, in(WORLD, WORLD, pack(OTHER, 1), true, true), false);
		long token = this.port.regenerations.get(0);
		// The player leaves while the worker runs and joins elsewhere.
		this.sync.reset();
		this.sync.finished(token, null);
		assertEquals(Phase.IDLE, this.sync.phase());
		assertTrue(this.port.reloads.isEmpty(), "a finished worker of the old world starts nothing");
		this.sync.tick(10, in(OTHER, OTHER, pack(OTHER, 7), true, true), false);
		assertEquals(Phase.READY, this.sync.phase());
		assertEquals(OTHER, this.port.last().get("slotMapHash").getAsString());
	}

	@Test
	void aRebuildThatPublishesANewIndexIsJudgedAgain() {
		this.sync.tick(0, in(WORLD, WORLD, pack(WORLD, 5), true, true), false);
		assertEquals(Phase.READY, this.sync.phase());
		this.sync.tick(50, in(OTHER, OTHER, pack(WORLD, 5), true, true), false);
		assertEquals(Phase.WORKING, this.sync.phase(), "the world's slots changed under the pack");
	}

	@Test
	void theCreateKantoWorldFlowOwnsThePackWhileItRuns() {
		for (int t = 0; t < 10; t++) {
			this.sync.tick(t * 50L, in(WORLD, WORLD, pack(OTHER, 1), true, true), true);
		}
		assertTrue(this.port.regenerations.isEmpty() && this.port.reports.isEmpty() && this.port.reloads.isEmpty());
	}

	@Test
	void aReportWaitsForTheServerLink() {
		this.port.serverUp = false;
		this.sync.tick(0, in(WORLD, WORLD, pack(WORLD, 3), true, false), false);
		assertTrue(this.port.reports.isEmpty());
		this.port.serverUp = true;
		this.sync.tick(50, in(WORLD, WORLD, pack(WORLD, 3), true, false), false);
		assertEquals(1, this.port.reports.size(), "sent once the link is up");
		this.sync.tick(100, in(WORLD, WORLD, pack(WORLD, 3), true, false), false);
		assertEquals(1, this.port.reports.size());
	}

	@Test
	void reasonsFollowTheProtocolPattern() {
		for (String raw : List.of("export.index_timeout", "pack.slot_map.full", "IO Error!", "1st", "", "x".repeat(100), "pack.bundle-hash")) {
			String code = TextureSync.reasonCode(raw);
			assertTrue(REASON.matcher(code).matches(), "reason " + code);
		}
		assertEquals("export.index_timeout", TextureSync.reasonCode("export.index_timeout"));
		JsonObject placeholders = TextureSync.placeholders("no_bridge");
		assertEquals(2, placeholders.size(), "ok and reason only");
	}

	@Test
	void packInfoIsReadFromTheGeneratedPack() {
		PackInfo info = PackInfo.parse("{\"exportHash\":\"" + "c".repeat(64) + "\",\"game\":\"blue\",\"slotMapHash\":\"" + WORLD
				+ "\",\"textures\":4273}");
		assertNotNull(info);
		assertEquals(WORLD, info.slotMapHash());
		assertEquals(4273, info.textures());
		assertEquals("blue", info.game());
		PackInfo old = PackInfo.parse("{\"exportHash\":\"" + "c".repeat(64) + "\",\"game\":\"red\",\"slotMapHash\":\"" + WORLD + "\"}");
		assertNotNull(old);
		assertEquals(0, old.textures(), "an older pack without a count");
		assertNull(PackInfo.parse("not json"));
		assertNull(PackInfo.parse("{\"game\":\"red\"}"));
		assertNull(PackInfo.parse("{\"exportHash\":\"" + "c".repeat(64) + "\",\"game\":\"red\",\"slotMapHash\":\"NOT A HASH\"}"));
	}
}
