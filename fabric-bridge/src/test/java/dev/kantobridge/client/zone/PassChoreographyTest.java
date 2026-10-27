package dev.kantobridge.client.zone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A crossing on the client (MG5), without Minecraft, the server or gen1-bridge: the pass under the player's feet asks
 * the server once, the fade parks the followed game, the far game is resumed (a save) or stashed-into and started (a
 * title screen, with the player's name), and its link is followed; every failure ends with {@code pass.abort} and the
 * fade lifted. Message data is made up; ids are the plan's examples.
 */
class PassChoreographyTest {
	static final String ROUTE = "eastern_pass";
	static final PassChoreography.PassAt PASS = new PassChoreography.PassAt(ROUTE, "to", "kanto", "johto", "KB_ROUTE_EASTERN_PASS_TO");

	/** One hidden game's link: what it is told, numbered as gen1-bridge would. */
	static final class FakeLink implements PassChoreography.LinkPort {
		final String region;
		final List<String> sent = new ArrayList<>();
		boolean connected = true;
		@Nullable String playthrough;
		@Nullable String name;
		/** WS3 (B1): game.status.save at the title. */
		boolean titleSave;
		long nextSeq = 100;

		@Override
		public boolean titleSave() {
			return this.titleSave;
		}

		FakeLink(String region, @Nullable String playthrough, @Nullable String name) {
			this.region = region;
			this.playthrough = playthrough;
			this.name = name;
		}

		@Override
		public @Nullable String region() {
			return this.region;
		}

		@Override
		public boolean connected() {
			return this.connected;
		}

		@Override
		public @Nullable String playthrough() {
			return this.playthrough;
		}

		@Override
		public @Nullable String name() {
			return this.name;
		}

		@Override
		public long sendTracked(String type, JsonObject data) {
			if (!this.connected) {
				return -1;
			}
			this.sent.add(type + " " + ProtoJson.write(data));
			return this.nextSeq++;
		}

		/** The seq of the last message sent. */
		long lastSeq() {
			return this.nextSeq - 1;
		}

		List<String> take() {
			List<String> out = List.copyOf(this.sent);
			this.sent.clear();
			return out;
		}
	}

	/** The client around the choreography, recording everything. */
	static final class FakeHost implements PassChoreography.Host {
		final FakeLink kanto = new FakeLink("kanto", "saved", "RED");
		final FakeLink johto = new FakeLink("johto", "saved", null);
		final List<String> server = new ArrayList<>();
		final List<String> follows = new ArrayList<>();
		final List<String> names = new ArrayList<>();
		final List<PassChoreography.Fade> fades = new ArrayList<>();
		final List<String> notices = new ArrayList<>();
		FakeLink followed = this.kanto;
		boolean serverUp = true;
		boolean johtoKnown = true;
		boolean followWorks = true;
		long nextSeq = 10;

		@Override
		public PassChoreography.@Nullable LinkPort followed() {
			return this.followed;
		}

		@Override
		public PassChoreography.@Nullable LinkPort forRegion(String region) {
			if (region.equals("kanto")) {
				return this.kanto;
			}
			return region.equals("johto") && this.johtoKnown ? this.johto : null;
		}

		@Override
		public long toServer(String type, JsonObject data) {
			if (!this.serverUp) {
				return -1;
			}
			this.server.add(type + " " + ProtoJson.write(data));
			return this.nextSeq++;
		}

		long lastSeq() {
			return this.nextSeq - 1;
		}

		@Override
		public boolean follow(String region, boolean allowTeleport) {
			this.follows.add(region + (allowTeleport ? " teleport" : " step"));
			return this.followWorks;
		}

		@Override
		public void armName(@Nullable String name) {
			this.names.add(name == null ? "-" : name);
		}

		@Override
		public void fade(PassChoreography.Fade action) {
			this.fades.add(action);
		}

		@Override
		public void notice(String key, List<String> args, boolean warning) {
			this.notices.add(key + " " + args);
		}

		@Override
		public String label(String region) {
			return region.equals("johto") ? "Crystal" : "Blue";
		}

		List<String> takeServer() {
			List<String> out = List.copyOf(this.server);
			this.server.clear();
			return out;
		}
	}

	FakeHost host;
	PassChoreography pass;

	@BeforeEach
	void setUp() {
		this.host = new FakeHost();
		this.pass = new PassChoreography(this.host);
	}

	/** Stands on the pass (idle) and gets as far as the server's fade: the followed game is being parked. */
	long cross(long now) {
		this.pass.standing(PASS, true, now);
		assertEquals(List.of("pass.cross {\"dir\":\"to\",\"route\":\"eastern_pass\"}"), this.host.takeServer());
		assertEquals(PassChoreography.State.CROSS_SENT, this.pass.state());
		this.pass.fadeStarted(now + 100);
		assertEquals(List.of("region.park {\"region\":\"kanto\"}"), this.host.kanto.take());
		assertEquals(PassChoreography.State.PARKING, this.pass.state());
		return this.host.kanto.lastSeq();
	}

	@Test
	void aSavedFarGameIsResumedOntoItsRouteMapAndFollowed() {
		long park = this.cross(1_000);
		this.pass.linkResult(this.host.kanto, park, true, null, 1_200);
		assertEquals(List.of("region.resume {\"region\":\"johto\",\"to\":{\"facing\":\"right\",\"map\":\"KB_ROUTE_EASTERN_PASS_TO\",\"x\":1,\"y\":0}}"),
				this.host.johto.take());
		assertEquals(PassChoreography.State.RESUMING, this.pass.state());
		this.pass.linkResult(this.host.johto, this.host.johto.lastSeq(), true, null, 1_400);
		assertEquals(List.of("johto step"), this.host.follows);
		assertEquals(PassChoreography.State.IDLE, this.pass.state());
		assertEquals(1, this.pass.stats().resumes);
		assertEquals(0, this.pass.stats().aborts);
		assertEquals(List.of(), this.host.takeServer(), "no abort, no second cross");
		assertEquals(List.of(), this.host.fades, "the fade ends by itself when the server's teleport lands");
	}

	@Test
	void aFreshFarGameIsResumedTooAndAnUnknownPlaythroughCountsAsASave() {
		this.host.johto.playthrough = null;
		long park = this.cross(0);
		this.pass.linkResult(this.host.kanto, park, true, null, 10);
		assertEquals(1, this.host.johto.take().size());
		assertEquals(PassChoreography.State.RESUMING, this.pass.state());
	}

	@Test
	void aFarGameAtItsTitleGetsTheStashThenAStartThenTheName() {
		this.host.johto.playthrough = "title";
		long park = this.cross(0);
		this.pass.linkResult(this.host.kanto, park, true, null, 10);
		assertEquals(List.of("party.stash {\"region\":\"johto\"}"), this.host.takeServer());
		assertEquals(PassChoreography.State.STASHING, this.pass.state());
		assertEquals(List.of("kantobridge.pass.starting [Crystal]"), this.host.notices);
		long stash = this.host.lastSeq();
		this.pass.serverResult(stash, true, "stash", 20);
		assertEquals(List.of("region.start {\"region\":\"johto\"}"), this.host.johto.take());
		assertEquals(PassChoreography.State.STARTING, this.pass.state());
		this.pass.linkResult(this.host.johto, this.host.johto.lastSeq(), true, null, 30);
		assertEquals(List.of("RED"), this.host.names, "the parked save's name answers the intro's naming screen (D6)");
		assertEquals(List.of("johto teleport"), this.host.follows, "followed at once, so the intro shows on the body");
		assertEquals(PassChoreography.State.AWAIT_INGAME, this.pass.state());
		// The intro: the fade is kept from timing out, and goes dark again after each screen for the final teleport.
		this.pass.tick(40);
		assertEquals(List.of(PassChoreography.Fade.EXTEND), this.host.fades);
		this.pass.uiClosed(50);
		assertEquals(PassChoreography.Fade.RESTART, this.host.fades.get(this.host.fades.size() - 1));
		this.pass.gameStatus(this.host.kanto, true, 60);
		assertEquals(PassChoreography.State.AWAIT_INGAME, this.pass.state(), "another link's status isn't the far game's");
		this.pass.gameStatus(this.host.johto, true, 60);
		assertEquals(PassChoreography.State.IDLE, this.pass.state());
		assertEquals(1, this.pass.stats().starts);
		assertEquals(1, this.pass.stats().stashes);
		assertEquals(List.of(), this.host.takeServer());
	}

	@Test
	void b1ASavedFarGameAtItsTitleIsContinuedWithoutAStash() {
		// WORLD_SESSIONS_PLAN WS3: the far game waits at its title but its slot holds a save (game.status.save):
		// the party stays, region.start CONTINUEs it
		this.host.johto.playthrough = "title";
		this.host.johto.titleSave = true;
		long park = this.cross(0);
		this.pass.linkResult(this.host.kanto, park, true, null, 10);
		assertEquals(List.of(), this.host.takeServer(), "no party.stash");
		assertEquals(List.of("region.start {\"region\":\"johto\"}"), this.host.johto.take());
		assertEquals(PassChoreography.State.STARTING, this.pass.state());
		assertEquals(0, this.pass.stats().stashes);
		assertEquals(1, this.pass.stats().continuedAtTitle);
	}

	@Test
	void aStartedGameThatNeverReachesItsOverworldEndsTheCrossingTenSecondsAfterItsLastScreen() {
		this.host.johto.playthrough = "title";
		long park = this.cross(0);
		this.pass.linkResult(this.host.kanto, park, true, null, 10);
		assertEquals(1, this.host.takeServer().size(), "the stash");
		this.pass.serverResult(this.host.lastSeq(), true, "stash", 20);
		this.pass.linkResult(this.host.johto, this.host.johto.lastSeq(), true, null, 30);
		this.pass.uiClosed(1_000);
		this.pass.tick(1_000 + PassChoreography.INGAME_GRACE_MS);
		assertEquals(PassChoreography.State.AWAIT_INGAME, this.pass.state());
		this.pass.tick(1_001 + PassChoreography.INGAME_GRACE_MS);
		assertEquals(PassChoreography.State.IDLE, this.pass.state());
		assertEquals(1, this.pass.stats().timeouts);
		assertEquals(List.of(), this.host.takeServer(), "the game is started and followed: nothing to abort");
	}

	@Test
	void aFullPcAbortsTheCrossingWithANotice() {
		this.host.johto.playthrough = "title";
		long park = this.cross(0);
		this.pass.linkResult(this.host.kanto, park, true, null, 10);
		this.host.takeServer();
		this.pass.serverError("stash.pc_full", this.host.lastSeq(), 20);
		assertEquals(List.of("pass.abort {\"reason\":\"stash.pc_full\",\"route\":\"eastern_pass\"}"), this.host.takeServer());
		assertEquals(List.of("kantobridge.pass.starting [Crystal]", "kantobridge.pass.pc_full []"), this.host.notices);
		assertEquals(List.of(PassChoreography.Fade.LIFT), this.host.fades);
		assertEquals(PassChoreography.State.IDLE, this.pass.state());
		assertEquals(List.of(), this.host.johto.take(), "no start without the stash");
		assertEquals(List.of(), this.host.follows);
	}

	@Test
	void aRefusedParkAbortsWithItsReason() {
		long park = this.cross(0);
		this.pass.linkResult(this.host.kanto, park, false, "busy", 10);
		assertEquals(List.of("pass.abort {\"reason\":\"busy\",\"route\":\"eastern_pass\"}"), this.host.takeServer());
		assertEquals(List.of(PassChoreography.Fade.LIFT), this.host.fades);
		assertEquals(List.of("kantobridge.pass.failed [busy]"), this.host.notices);
		assertEquals(PassChoreography.State.IDLE, this.pass.state());
		assertEquals(List.of(), this.host.johto.take());
	}

	@Test
	void aRefusedResumeOrStartAbortsToo() {
		long park = this.cross(0);
		this.pass.linkResult(this.host.kanto, park, true, null, 10);
		this.pass.linkResult(this.host.johto, this.host.johto.lastSeq(), false, "no_save", 20);
		assertEquals(List.of("pass.abort {\"reason\":\"no_save\",\"route\":\"eastern_pass\"}"), this.host.takeServer());
		assertEquals(List.of(), this.host.follows);
		assertEquals(PassChoreography.State.IDLE, this.pass.state());

		this.setUp();
		this.host.johto.playthrough = "title";
		park = this.cross(100);
		this.pass.linkResult(this.host.kanto, park, true, null, 110);
		this.host.takeServer();
		this.pass.serverResult(this.host.lastSeq(), true, "stash", 120);
		this.pass.linkResult(this.host.johto, this.host.johto.lastSeq(), false, null, 130);
		assertEquals(List.of("pass.abort {\"reason\":\"start_refused\",\"route\":\"eastern_pass\"}"), this.host.takeServer());
		assertEquals(List.of(), this.host.names, "no name armed for a game that didn't start");
		assertEquals(PassChoreography.State.IDLE, this.pass.state());
	}

	@Test
	void answersToOtherMessagesAreNotTheCrossings() {
		long park = this.cross(0);
		this.pass.linkResult(this.host.johto, park, true, null, 5);
		this.pass.linkResult(this.host.kanto, park + 7, false, "x", 6);
		this.pass.linkResult(this.host.kanto, null, true, null, 7);
		this.pass.serverResult(this.host.lastSeq(), true, "stash", 8);
		assertEquals(PassChoreography.State.PARKING, this.pass.state());
		this.pass.linkResult(this.host.kanto, park, true, null, 10);
		this.pass.serverResult(99L, true, "stash", 12);
		this.pass.serverResult(this.host.lastSeq(), true, "give", 12);
		assertEquals(PassChoreography.State.RESUMING, this.pass.state());
	}

	@Test
	void fifteenSecondsAfterTheCrossWithoutTheFarLinkFollowedAbortsWithTimeout() {
		long park = this.cross(0);
		this.pass.linkResult(this.host.kanto, park, true, null, 14_000);
		assertEquals(PassChoreography.State.RESUMING, this.pass.state());
		this.pass.tick(PassChoreography.TIMEOUT_MS);
		assertEquals(PassChoreography.State.RESUMING, this.pass.state(), "one clock for the whole crossing, from pass.cross");
		this.pass.tick(PassChoreography.TIMEOUT_MS + 1);
		assertEquals(List.of("pass.abort {\"reason\":\"timeout\",\"route\":\"eastern_pass\"}"), this.host.takeServer());
		assertEquals(List.of("kantobridge.pass.timeout []"), this.host.notices);
		assertEquals(List.of(PassChoreography.Fade.LIFT), this.host.fades);
		assertEquals(1, this.pass.stats().timeouts);
		assertEquals(PassChoreography.State.IDLE, this.pass.state());
	}

	@Test
	void aServerThatNeverAnswersTheCrossIsAskedAgainFromTheNextStanding() {
		this.pass.standing(PASS, true, 0);
		this.host.takeServer();
		this.pass.standing(PASS, true, 1_000);
		assertEquals(List.of(), this.host.takeServer(), "asked once per standing");
		this.pass.tick(PassChoreography.TIMEOUT_MS + 1);
		assertEquals(PassChoreography.State.IDLE, this.pass.state());
		assertEquals(List.of(), this.host.takeServer(), "nothing to abort before the fade");
		assertEquals(List.of(), this.host.fades);
		this.pass.standing(PASS, true, 20_000);
		assertEquals(List.of(), this.host.takeServer(), "still standing there");
		this.pass.standing(null, true, 20_050);
		this.pass.standing(PASS, true, 20_100);
		assertEquals(1, this.host.takeServer().size());
	}

	@Test
	void aLinkDroppingMidCrossingAborts() {
		long park = this.cross(0);
		this.pass.linkResult(this.host.kanto, park, true, null, 10);
		this.host.johto.connected = false;
		this.pass.linkDown(this.host.johto, 20);
		assertEquals(List.of("pass.abort {\"reason\":\"link_down\",\"route\":\"eastern_pass\"}"), this.host.takeServer());
		assertEquals(PassChoreography.State.IDLE, this.pass.state());
		assertEquals(List.of(PassChoreography.Fade.LIFT), this.host.fades);

		this.setUp();
		this.cross(100);
		this.pass.linkDown(this.host.johto, 110);
		assertEquals(PassChoreography.State.PARKING, this.pass.state(), "the far link isn't part of it yet");
		this.pass.linkDown(this.host.kanto, 120);
		assertEquals(PassChoreography.State.IDLE, this.pass.state());
	}

	@Test
	void aFarLinkThatIsDownWhenThePassOpensAborts() {
		this.host.johtoKnown = false;
		long park = this.cross(0);
		this.pass.linkResult(this.host.kanto, park, true, null, 10);
		assertEquals(List.of("pass.abort {\"reason\":\"no_link\",\"route\":\"eastern_pass\"}"), this.host.takeServer());
		assertEquals(List.of("kantobridge.pass.no_link [Crystal]"), this.host.notices);
		assertEquals(PassChoreography.State.IDLE, this.pass.state());
	}

	@Test
	void theServersRefusalEndsTheCrossingWhereThePlayerStands() {
		this.pass.standing(PASS, true, 0);
		this.host.takeServer();
		this.pass.serverError("pass.no_link", this.host.lastSeq(), 10);
		assertEquals(List.of("kantobridge.pass.no_link [Crystal]"), this.host.notices);
		assertEquals(PassChoreography.State.IDLE, this.pass.state());
		assertEquals(List.of(), this.host.takeServer(), "no abort for a crossing the server refused");
		assertEquals(List.of(), this.host.fades, "no fade came");
		assertEquals(List.of(), this.host.kanto.take(), "nothing parked");

		this.setUp();
		this.cross(100);
		this.pass.serverError("pass.timeout", null, 200);
		assertEquals(List.of("kantobridge.pass.timeout []"), this.host.notices);
		assertEquals(List.of(PassChoreography.Fade.LIFT), this.host.fades, "the server's timer ran out under the fade");
		assertEquals(PassChoreography.State.IDLE, this.pass.state());
		assertEquals(List.of(), this.host.takeServer());

		this.pass.serverError("pass.busy", null, 300);
		assertEquals(1, this.host.notices.size(), "an error while idle is nobody's");
	}

	@Test
	void noCrossUnlessIdleOnAPassOfTheFollowedRegion() {
		this.pass.standing(PASS, false, 0);
		assertEquals(List.of(), this.host.takeServer(), "walking");
		this.host.followed = this.host.johto;
		this.pass.standing(PASS, true, 10);
		assertEquals(List.of(), this.host.takeServer(), "the pass is another region's");
		this.host.followed = this.host.kanto;
		this.host.kanto.connected = false;
		this.pass.standing(PASS, true, 20);
		assertEquals(List.of(), this.host.takeServer(), "the followed link is down");
		this.host.kanto.connected = true;
		this.host.serverUp = false;
		this.pass.standing(PASS, true, 30);
		assertEquals(PassChoreography.State.IDLE, this.pass.state(), "not sent: asked again next tick");
		this.host.serverUp = true;
		this.pass.standing(PASS, true, 40);
		assertEquals(1, this.host.takeServer().size());
		assertFalse(this.pass.crossing() && this.pass.route() == null);
		assertEquals(ROUTE, this.pass.route());
	}

	@Test
	void aFarHalfNotInTheIndexAbortsAResume() {
		PassChoreography.PassAt unknown = new PassChoreography.PassAt(ROUTE, "to", "kanto", "johto", null);
		this.pass.standing(unknown, true, 0);
		this.host.takeServer();
		this.pass.fadeStarted(10);
		this.pass.linkResult(this.host.kanto, this.host.kanto.lastSeq(), true, null, 20);
		assertEquals(List.of("pass.abort {\"reason\":\"not_built\",\"route\":\"eastern_pass\"}"), this.host.takeServer());
		assertNull(this.pass.route());
		assertTrue(this.pass.aborts().containsKey("not_built"));
	}

	@Test
	void aFollowThatFailsAbortsAfterAResume() {
		this.host.followWorks = false;
		long park = this.cross(0);
		this.pass.linkResult(this.host.kanto, park, true, null, 10);
		this.pass.linkResult(this.host.johto, this.host.johto.lastSeq(), true, null, 20);
		assertEquals(List.of("pass.abort {\"reason\":\"follow_failed\",\"route\":\"eastern_pass\"}"), this.host.takeServer());
		assertEquals(PassChoreography.State.IDLE, this.pass.state());
	}

	@Test
	void theDataOfAPassComesFromTheIndex() {
		assertEquals("to", PASS.dir());
		assertEquals("johto", PASS.toRegion());
		assertEquals(1, PassChoreography.ARRIVE_X);
		assertEquals(0, PassChoreography.ARRIVE_Y);
		assertEquals("right", PassChoreography.ARRIVE_FACING);
	}
}
