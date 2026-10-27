package dev.kantobridge.client.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.kantobridge.world.WorldRecord;
import java.util.List;
import org.junit.jupiter.api.Test;

/** WORLD_SESSIONS_PLAN WS3: the join's decision per link (D3, D4, D13). */
class JoinDecisionTest {
	private static final WorldRecord WORLD = new WorldRecord("johto",
			List.of(new WorldRecord.Game("blue", "kanto"), new WorldRecord.Game("crystal", "johto")));

	private static JoinDecision.Result decide(String region, String game, String followed, RecordedChoices.Choice recorded, boolean slotLost,
			boolean statusKnown, boolean hasSave, String answer) {
		return JoinDecision.decide(new JoinDecision.Input(WORLD, region, game, followed, true, recorded, slotLost, statusKnown, hasSave, answer));
	}

	@Test
	void aLegacyWorldBindsAsToday() {
		assertEquals(new JoinDecision.Ready(JoinDecision.Bind.TODAY),
				JoinDecision.decide(new JoinDecision.Input(null, "kanto", "blue", null, false, null, false, false, false, null)));
	}

	@Test
	void theFirstJoinAsksOnlyForASavedGameAndTheStartIsPlayed() {
		assertEquals(new JoinDecision.Ask("play"), decide("johto", "crystal", null, null, false, true, true, null), "the start region is played");
		assertEquals(new JoinDecision.Ask("park"), decide("kanto", "blue", null, null, false, true, true, null), "every other game is parked");
		assertEquals(new JoinDecision.Ready(new JoinDecision.Bind(null, "new", "park")), decide("kanto", "blue", null, null, false, true, false, null),
				"no save: new without asking");
		assertEquals(new JoinDecision.Wait("status"), decide("kanto", "blue", null, null, false, false, false, null));
	}

	@Test
	void laterJoinsBindTheRecordedSlotWithoutAsking() {
		RecordedChoices.Choice recorded = new RecordedChoices.Choice(3, "copy");
		assertEquals(new JoinDecision.Ready(new JoinDecision.Bind(3, "copy", "play")), decide("kanto", "blue", "kanto", recorded, false, false, true, null),
				"the server's presence names Kanto: it is played; no status needed");
	}

	@Test
	void aMissingSlotAsksAgainAndAnAnswerOrADeclineIsUsed() {
		RecordedChoices.Choice recorded = new RecordedChoices.Choice(3, "copy");
		assertEquals(new JoinDecision.Ask("play"), decide("johto", "crystal", null, recorded, true, true, true, null), "session.no_slot: asked again");
		assertEquals(new JoinDecision.Ready(new JoinDecision.Bind(null, "current", "play")),
				decide("johto", "crystal", null, null, false, true, true, "current"));
		assertEquals(new JoinDecision.Ready(new JoinDecision.Bind(null, null, "title")),
				decide("johto", "crystal", null, null, false, true, true, JoinDecision.DECLINED), "Esc: the title for this join");
	}

	@Test
	void anUnlistedGameStaysAtItsTitleAndThePresenceIsWaitedFor() {
		assertEquals(new JoinDecision.Ready(new JoinDecision.Bind(null, null, "title")), decide("hoenn", "emerald", null, null, false, true, true, null));
		assertEquals(new JoinDecision.Wait("presence"),
				JoinDecision.decide(new JoinDecision.Input(WORLD, "kanto", "blue", null, false, null, false, true, true, null)));
		assertEquals("copy", JoinDecision.continueChoice(true));
		assertEquals("current", JoinDecision.continueChoice(false));
	}
}
