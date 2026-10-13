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
 * The texture flow (docs/specs/VOXEL_BLOCKS_PLAN.md §5.2): every row of the decision table, the flow through export,
 * build and reload, placeholders without a compatible gen1-bridge, the export at most once per world, and the
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
		final List<Long> reloads = new ArrayList<>();
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
		public void reload(long token) {
			this.reloads.add(token);
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

	private static PackInfo pack(String game, int textures) {
		return new PackInfo(2, game, game, "c".repeat(64), textures - 4, 4, textures);
	}

	private static PackInfo layoutOne() {
		return new PackInfo(1, null, "red", "c".repeat(64), 0, 0, 19);
	}

	private static TextureSync.Inputs in(String game, PackInfo local, boolean loaded, int missing, boolean bridge) {
		return new TextureSync.Inputs(game, game == null ? null : WORLD, local, loaded, missing, bridge);
	}

	@Test
	void theDecisionTable() {
		PackInfo red = pack("red", 19);
		// Outside a Kanto world nothing is judged.
		assertEquals(Decision.NONE, TextureSync.decide(in(null, red, true, 0, true), false));
		// No usable pack (none, layout 1, another family's): export when a compatible gen1-bridge is there, else placeholders.
		assertEquals(Decision.REGENERATE, TextureSync.decide(in("red", null, false, -1, true), false));
		assertEquals(Decision.NO_ART, TextureSync.decide(in("red", null, false, -1, false), false));
		assertEquals(Decision.REGENERATE, TextureSync.decide(in("red", layoutOne(), true, -1, true), false), "a layout 1 pack counts as none");
		assertEquals(Decision.NO_ART, TextureSync.decide(in("red", layoutOne(), true, -1, false), false));
		assertEquals(Decision.NO_ART, TextureSync.decide(in("red", pack("gold", 19), true, 0, false), false), "a game of another family");
		// A usable pack that isn't the loaded one: reload.
		assertEquals(Decision.RELOAD, TextureSync.decide(in("red", red, false, 0, true), false));
		// Loaded: wait for the slot map's count, then ready, or refresh the art once, or placeholders for what is missing.
		assertEquals(Decision.WAIT_SLOTS, TextureSync.decide(in("red", red, true, -1, true), false));
		assertEquals(Decision.READY, TextureSync.decide(in("red", red, true, 0, false), false));
		assertEquals(Decision.READY, TextureSync.decide(in("blue", red, true, 0, false), false), "red art draws a blue world");
		assertEquals(Decision.REGENERATE, TextureSync.decide(in("red", red, true, 3, true), false), "the set is stale");
		assertEquals(Decision.INCOMPLETE, TextureSync.decide(in("red", red, true, 3, false), false));
		// The export runs once per world.
		assertEquals(Decision.INCOMPLETE, TextureSync.decide(in("red", red, true, 3, true), true));
		assertEquals(Decision.NO_ART, TextureSync.decide(in("red", null, false, -1, true), true));
	}

	@Test
	void aRegionWorldsReportNamesTheRegionAndALegacyWorldsDoesNot() {
		PackInfo red = pack("red", 19);
		// MG3: the followed region's inputs; the status names it, with the region's slice hash as slotMapHash.
		TextureSync.Inputs hoenn = in("red", red, true, 0, false).forRegion("kanto");
		assertEquals("kanto", hoenn.region());
		this.sync.tick(0, hoenn, false);
		assertEquals(Phase.READY, this.sync.phase());
		JsonObject status = this.port.last();
		assertEquals("kanto", status.get("region").getAsString());
		assertEquals(WORLD, status.get("slotMapHash").getAsString());
		// Another region's inputs are another world for the flow: judged afresh, reported with its own region.
		this.sync.tick(10, in("red", red, true, 3, false).forRegion("hoenn"), false);
		assertEquals(Phase.INCOMPLETE, this.sync.phase());
		assertEquals("hoenn", this.port.last().get("region").getAsString());
		assertEquals(3, this.port.last().get("missing").getAsInt());
		// Placeholders for a region name it too.
		this.sync.reset();
		this.sync.tick(20, in("red", null, false, -1, false).forRegion("hoenn"), false);
		assertEquals(Phase.NO_ART, this.sync.phase());
		assertEquals("hoenn", this.port.last().get("region").getAsString());
		// A legacy world's report carries no region: today's bytes.
		this.sync.reset();
		this.sync.tick(30, in("red", red, true, 0, false), false);
		assertFalse(this.port.last().has("region"));
		// The region rides along unchanged through the other inputs.
		TextureSync.Inputs full = new TextureSync.Inputs("red", WORLD, red, true, 0, false, 7, "d".repeat(64), null, 2).forRegion("kanto");
		assertEquals(7, full.shapes());
		assertEquals(2, full.packs());
		assertEquals("kanto", full.region());
	}

	@Test
	void aLoadedPackWithEveryTextureIsReportedOnce() {
		PackInfo red = pack("red", 19);
		this.sync.tick(0, in("red", red, true, 0, false), false);
		assertEquals(Phase.READY, this.sync.phase());
		assertEquals(1, this.port.reports.size());
		JsonObject status = this.port.last();
		assertTrue(status.get("ok").getAsBoolean());
		assertEquals(WORLD, status.get("slotMapHash").getAsString());
		assertEquals(19, status.get("textures").getAsInt());
		assertEquals(0, status.get("missing").getAsInt());
		this.sync.tick(50, in("red", red, true, 0, false), false);
		assertEquals(1, this.port.reports.size(), "reported once");
		assertTrue(this.port.regenerations.isEmpty() && this.port.reloads.isEmpty());
		assertNull(this.sync.hudLine(50), "nothing to say when the pack was already right");
	}

	@Test
	void theShapesItDrawsAreReportedWithTheirIndex() {
		// The voxel plan's V2: shape ids with geometry and the shape index they come from; a change is reported again.
		PackInfo red = pack("red", 19);
		String shapes = "d".repeat(64);
		this.sync.tick(0, new TextureSync.Inputs("red", WORLD, red, true, 0, false, 0, null, "building"), false);
		assertEquals(1, this.port.reports.size());
		assertFalse(this.port.last().has("shapes"), "nothing to say while the table builds");
		assertFalse(this.port.last().has("reason"));
		this.sync.tick(50, new TextureSync.Inputs("red", WORLD, red, true, 0, false, 120, shapes, null), false);
		assertEquals(2, this.port.reports.size(), "the finished table is reported");
		JsonObject status = this.port.last();
		assertTrue(status.get("ok").getAsBoolean());
		assertEquals(120, status.get("shapes").getAsInt());
		assertEquals(shapes, status.get("shapeIndexHash").getAsString());
		this.sync.tick(100, new TextureSync.Inputs("red", WORLD, red, true, 0, false, 120, shapes, null), false);
		assertEquals(2, this.port.reports.size(), "once");
		// Cubes for a reason: said, while the art itself is fine.
		this.sync.tick(150, new TextureSync.Inputs("red", WORLD, red, true, 0, false, 0, null, "shape_map_mismatch"), false);
		status = this.port.last();
		assertTrue(status.get("ok").getAsBoolean());
		assertEquals(0, status.get("shapes").getAsInt());
		assertEquals("shape_map_mismatch", status.get("reason").getAsString());
		assertTrue(REASON.matcher(status.get("reason").getAsString()).matches());
		// An art reason wins over a shapes reason.
		this.sync.reset();
		this.sync.tick(200, new TextureSync.Inputs("red", WORLD, red, true, 3, false, 0, null, "no_shapes"), false);
		assertEquals("art_incomplete", this.port.last().get("reason").getAsString());
	}

	@Test
	void aPackOnDiskThatIsntLoadedIsReloadedFirst() {
		PackInfo red = pack("red", 19);
		this.sync.tick(0, in("red", red, false, -1, false), false);
		assertEquals(Phase.RELOADING, this.sync.phase());
		assertEquals(1, this.port.reloads.size());
		this.sync.reloaded(this.port.reloads.get(0), true);
		this.sync.tick(10, in("red", red, true, 0, false), false);
		assertEquals(Phase.READY, this.sync.phase());
		assertTrue(this.port.last().get("ok").getAsBoolean());
	}

	@Test
	void withoutAPackTheArtIsExportedOnceAndLoaded() {
		this.sync.tick(0, in("red", null, false, -1, true), false);
		assertEquals(Phase.WORKING, this.sync.phase());
		assertEquals(1, this.port.regenerations.size());
		assertNotNull(this.sync.hudLine(0));
		long token = this.port.regenerations.get(0);
		this.sync.tick(10, in("red", null, false, -1, true), false);
		assertEquals(1, this.port.regenerations.size(), "one export at a time");
		this.sync.finished(token, null);
		assertEquals(Phase.RELOADING, this.sync.phase());
		assertEquals(1, this.port.reloads.size());
		this.sync.reloaded(token, true);
		PackInfo fresh = pack("red", 19);
		this.sync.tick(20, in("red", fresh, true, 0, true), false);
		assertEquals(Phase.READY, this.sync.phase());
		assertTrue(this.port.last().get("ok").getAsBoolean());
		assertEquals("kantobridge.hud.textures.ready", this.sync.hudLine(20).key(), "says the art is in place for a while");
		assertNull(this.sync.hudLine(20 + TextureSync.DONE_LINE_MS + 1));
		assertEquals(1, this.sync.regenerations());
	}

	@Test
	void withoutGen1BridgeTheWorldShowsPlaceholdersUntilItConnects() {
		this.sync.tick(0, in("red", null, false, -1, false), false);
		assertEquals(Phase.NO_ART, this.sync.phase());
		assertEquals("no_art", this.port.last().get("reason").getAsString());
		assertFalse(this.port.last().get("ok").getAsBoolean());
		assertTrue(this.sync.hudLine(0).warning());
		this.sync.tick(10, in("red", null, false, -1, false), false);
		assertEquals(1, this.port.reports.size(), "the same placeholders aren't reported twice");
		// gen1-bridge connects to a compatible game: the export runs.
		this.sync.tick(20, in("red", null, false, -1, true), false);
		assertEquals(Phase.WORKING, this.sync.phase());
		assertEquals(1, this.port.regenerations.size());
	}

	@Test
	void missingArtIsRefreshedOnceThenDrawnAsPlaceholders() {
		PackInfo red = pack("red", 19);
		this.sync.tick(0, in("red", red, true, 2, false), false);
		assertEquals(Phase.INCOMPLETE, this.sync.phase(), "no gen1-bridge: the missing keys show placeholders");
		JsonObject status = this.port.last();
		assertFalse(status.get("ok").getAsBoolean());
		assertEquals("art_incomplete", status.get("reason").getAsString());
		assertEquals(2, status.get("missing").getAsInt());
		assertEquals(WORLD, status.get("slotMapHash").getAsString());
		assertEquals("2", this.sync.hudLine(0).args().get(0));
		// gen1-bridge comes: the art is refreshed once.
		this.sync.tick(10, in("red", red, true, 2, true), false);
		assertEquals(Phase.WORKING, this.sync.phase());
		long token = this.port.regenerations.get(0);
		this.sync.finished(token, null);
		this.sync.reloaded(token, true);
		// Still missing (the host's export has tiles this one hasn't): placeholders for those, no second export.
		this.sync.tick(20, in("red", red, true, 1, true), false);
		assertEquals(Phase.INCOMPLETE, this.sync.phase());
		assertEquals(1, this.port.regenerations.size());
		assertEquals(1, this.port.last().get("missing").getAsInt());
	}

	@Test
	void failuresEndInPlaceholdersWithAReason() {
		this.sync.tick(0, in("red", null, false, -1, true), false);
		long token = this.port.regenerations.get(0);
		this.sync.finished(token, "export.timeout");
		assertEquals(Phase.FAILED, this.sync.phase());
		assertEquals("export.timeout", this.sync.reason());
		assertEquals("export.timeout", this.port.last().get("reason").getAsString());
		assertEquals(List.of("textures.export.timeout"), this.port.warnings);
		this.sync.tick(10, in("red", null, false, -1, true), false);
		assertEquals(Phase.FAILED, this.sync.phase(), "no retry until the world changes");
		assertEquals(1, this.port.regenerations.size());
	}

	@Test
	void aSlotMapThatNeverComesFails() {
		PackInfo red = pack("red", 19);
		this.sync.tick(0, in("red", red, true, -1, false), false);
		assertEquals(Phase.WAITING, this.sync.phase());
		this.sync.tick(TextureSync.SLOTS_WAIT_MS + 1, in("red", red, true, -1, false), false);
		assertEquals(Phase.FAILED, this.sync.phase());
		assertEquals("no_slots", this.port.last().get("reason").getAsString());
	}

	@Test
	void aFailedReloadIsReported() {
		PackInfo red = pack("red", 19);
		this.sync.tick(0, in("red", red, false, -1, false), false);
		this.sync.reloaded(this.port.reloads.get(0), false);
		assertEquals(Phase.FAILED, this.sync.phase());
		assertEquals("reload", this.port.last().get("reason").getAsString());
	}

	@Test
	void lateAnswersForAnOlderWorldAreDropped() {
		this.sync.tick(0, in("red", null, false, -1, true), false);
		long token = this.port.regenerations.get(0);
		this.sync.reset();
		this.sync.finished(token, null);
		assertEquals(Phase.IDLE, this.sync.phase(), "the old export's end changes nothing");
		assertTrue(this.port.reloads.isEmpty());
	}

	@Test
	void aRebuildThatPublishesANewIndexIsJudgedAgain() {
		PackInfo red = pack("red", 19);
		this.sync.tick(0, in("red", red, true, 0, false), false);
		assertEquals(Phase.READY, this.sync.phase());
		this.sync.tick(10, new TextureSync.Inputs("red", OTHER, red, true, 4, false), false);
		assertEquals(Phase.INCOMPLETE, this.sync.phase(), "the new slot map names keys this pack lacks");
	}

	@Test
	void theCreateKantoWorldFlowOwnsThePackWhileItRuns() {
		this.sync.tick(0, in("red", null, false, -1, true), true);
		assertEquals(Phase.IDLE, this.sync.phase());
		assertTrue(this.port.regenerations.isEmpty());
	}

	@Test
	void aReportWaitsForTheServerLink() {
		this.port.serverUp = false;
		PackInfo red = pack("red", 19);
		this.sync.tick(0, in("red", red, true, 0, false), false);
		assertTrue(this.port.reports.isEmpty());
		this.port.serverUp = true;
		this.sync.tick(10, in("red", red, true, 0, false), false);
		assertEquals(1, this.port.reports.size(), "sent once the link is up");
		assertEquals(1, this.sync.reports());
	}

	@Test
	void reasonsFollowTheProtocolPattern() {
		for (String raw : List.of("export.timeout", "Pack.IO", "9lives", "", "a".repeat(80), "no_art", "art_incomplete")) {
			assertTrue(REASON.matcher(TextureSync.reasonCode(raw)).matches(), raw);
		}
		assertEquals("pack.io", TextureSync.reasonCode("Pack.IO"));
	}

	@Test
	void packInfoIsReadFromTheGeneratedPack() {
		PackInfo info = PackInfo.parse("{\"artSet\":\"blue\",\"exportHash\":\"" + "c".repeat(64)
				+ "\",\"game\":\"blue\",\"layout\":2,\"sprites\":4,\"textures\":19,\"tiles\":15}");
		assertNotNull(info);
		assertTrue(info.usable());
		assertEquals("blue", info.artSet());
		assertEquals(15, info.tiles());
		assertEquals(4, info.sprites());
		assertEquals(19, info.textures());
		// A layout 1 pack (before the runtime model) parses, but can't be drawn from.
		PackInfo old = PackInfo.parse("{\"exportHash\":\"" + "c".repeat(64) + "\",\"game\":\"red\",\"slotMapHash\":\"" + WORLD
				+ "\",\"textures\":19}");
		assertNotNull(old);
		assertEquals(1, old.layout());
		assertFalse(old.usable());
		assertNull(PackInfo.parse("not json"));
		assertNull(PackInfo.parse("{\"game\":\"red\"}"));
		assertNull(PackInfo.parse("{\"exportHash\":\"" + "c".repeat(64) + "\",\"game\":\"red\",\"layout\":2}"), "layout 2 needs an art set");
		assertNull(PackInfo.parse("{\"artSet\":\"../x\",\"exportHash\":\"" + "c".repeat(64) + "\",\"game\":\"red\",\"layout\":2}"));
	}
}
