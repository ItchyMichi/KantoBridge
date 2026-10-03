package dev.kantobridge.client.act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Why a field move didn't happen, as the player reads it: act.result refusals matched to the request and translated. */
class FieldFeedbackTest {
	@Test
	void aRefusalOfTheFieldRequestInFlightIsShown() {
		FieldFeedback feedback = new FieldFeedback();
		feedback.sent(7, "cut", 1_000);
		assertTrue(feedback.pending());
		assertNull(feedback.onResult(6L, false, "nothing"), "an answer to something else (A on a sign) stays silent");
		assertNull(feedback.onResult(null, false, "no_move"));
		FieldFeedback.Message message = feedback.onResult(7L, false, "no_move");
		assertNotNull(message);
		assertEquals(new FieldFeedback.Message("kantobridge.field.action.cut", "kantobridge.field.reason.no_move"), message);
		assertFalse(feedback.pending());
		assertNull(feedback.onResult(7L, false, "no_move"), "answered once");
		assertEquals("no_move", feedback.lastReason());
		assertEquals(1, feedback.refusedCount());
	}

	@Test
	void successAndDroppedLinksShowNothing() {
		FieldFeedback feedback = new FieldFeedback();
		feedback.sent(3, "surf", 0);
		assertNull(feedback.onResult(3L, true, null), "she surfs: the hidden game's own text says so");
		feedback.sent(4, "surf", 10);
		feedback.connectionChanged();
		assertNull(feedback.onResult(4L, false, "busy"), "a new link numbers from 1 again: the old request is gone");
		assertEquals(2, feedback.sentCount());
		assertEquals(0, feedback.refusedCount());
	}

	@Test
	void aRequestUnderWayHoldsBackTheNextForAWhile() {
		FieldFeedback feedback = new FieldFeedback();
		assertFalse(feedback.busy(0));
		feedback.sent(5, "cut", 1_000);
		assertTrue(feedback.busy(1_000 + FieldFeedback.PENDING_MS - 1), "a held right-click sends no second one");
		assertFalse(feedback.busy(1_000 + FieldFeedback.PENDING_MS), "an answer that never came holds nothing back for long");
		feedback.sent(6, "cut", 9_000);
		assertNull(feedback.onResult(5L, false, "busy"), "the late answer to the first one is no longer followed");
		assertNull(feedback.onResult(6L, true, null));
		assertFalse(feedback.busy(9_001), "answered");
	}

	@Test
	void reasonsAndActionsMapToLangKeysWithAFallback() {
		FieldFeedback feedback = new FieldFeedback();
		assertEquals(new FieldFeedback.Message("kantobridge.field.action.surf", "kantobridge.field.reason.not_adjacent"),
				feedback.refusal("surf", "not_adjacent"));
		assertEquals("kantobridge.field.reason.other", FieldFeedback.reasonKey("something_new"));
		assertEquals("kantobridge.field.reason.other", FieldFeedback.reasonKey(null));
		assertEquals("kantobridge.field.action.other", FieldFeedback.actionKey("teleport_home"));
		feedback.sent(9, "fly", 0);
		assertEquals("kantobridge.field.reason.other", feedback.onResult(9L, false, "weird.code").reasonKey());
		assertEquals("other", feedback.lastReason());
	}

	@Test
	void everyKeyHasEnglishText() throws Exception {
		JsonObject lang;
		try (InputStream in = FieldFeedbackTest.class.getClassLoader().getResourceAsStream("assets/kantobridge/lang/en_us.json")) {
			assertNotNull(in, "en_us.json");
			lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
		List<String> keys = new ArrayList<>(List.of("kantobridge.field.refused", FieldFeedback.reasonKey(null), FieldFeedback.actionKey(null)));
		FieldFeedback.REASONS.forEach(reason -> keys.add(FieldFeedback.reasonKey(reason)));
		FieldFeedback.ACTIONS.forEach(action -> keys.add(FieldFeedback.actionKey(action)));
		for (String key : keys) {
			assertTrue(lang.has(key) && !lang.get(key).getAsString().isBlank(), "no text for " + key);
		}
	}
}
