package dev.kantobridge.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The UI router's state machine: opening, updating and closing in any order, one view at a time (a choice shown over
 * its text box), answers only to the top UI and only once, resync from a snapshot, and clearing on a dropped link or
 * an idle hidden player. Message data is made up.
 */
class UiRouterTest {
	/** Records what goes to gen1-bridge; can pretend to be disconnected. */
	static final class FakePort implements UiRouter.Port {
		final List<String> sent = new ArrayList<>();
		final List<String> warnings = new ArrayList<>();
		int changes;
		boolean connected = true;

		@Override
		public boolean send(String type, JsonObject data) {
			if (!this.connected) {
				return false;
			}
			this.sent.add(type + " " + ProtoJson.write(data));
			return true;
		}

		@Override
		public void changed() {
			this.changes++;
		}

		@Override
		public void warn(String code, long count) {
			this.warnings.add(code);
		}

		List<String> take() {
			List<String> out = List.copyOf(this.sent);
			this.sent.clear();
			return out;
		}
	}

	FakePort port;
	UiRouter router;

	@BeforeEach
	void setUp() {
		this.port = new FakePort();
		this.router = new UiRouter(this.port);
	}

	static JsonObject json(String text) throws Exception {
		return ProtoJson.parseObject(text);
	}

	void open(String text) throws Exception {
		this.router.onOpen(json(text), 0);
	}

	static final String TEXT_1 = "{\"ui\":1,\"kind\":\"text\",\"pages\":[[\"Want a test?\"]],\"cont\":[[false]]}";
	static final String CHOICE_2 = "{\"ui\":2,\"kind\":\"choice\",\"labels\":[\"YES\",\"NO\"],\"index\":0}";
	static final String LIST_3 = "{\"ui\":3,\"kind\":\"list\",\"items\":[{\"label\":\"TEST ITEM\",\"right\":\"¥200\"},"
			+ "{\"label\":\"CANCEL\"}],\"index\":0,\"top\":0,\"rows\":4,\"money\":3000,\"footer\":\"Take your time.\"}";
	static final String QTY_4 = "{\"ui\":4,\"kind\":\"quantity\",\"qty\":1,\"max\":15,\"unit\":200}";
	static final String NAMING_5 = "{\"ui\":5,\"kind\":\"naming\",\"title\":\"YOUR NAME?\",\"max\":7,\"presets\":[],\"value\":\"\"}";

	@Test
	void aTextBoxOpensAdvancesOnceAtItsEndAndCloses() throws Exception {
		this.open(TEXT_1);
		UiRouter.View view = this.router.view();
		assertNotNull(view);
		assertEquals(UiKind.TEXT, view.main().kind());
		assertNull(view.overlay());
		assertTrue(this.port.changes > 0);
		UiSession text = view.main();
		this.router.press(text, 0);
		assertEquals(List.of(), this.port.take(), "the first press only finished the typing");
		this.router.press(text, 10);
		assertEquals(List.of("ui.advance {\"ui\":1}"), this.port.take());
		this.router.press(text, 20);
		assertEquals(List.of(), this.port.take(), "once");
		this.router.onClose(json("{\"ui\":1}"));
		assertNull(this.router.view());
		assertFalse(this.router.open());
	}

	@Test
	void aChoiceOverItsTextBoxIsOneViewAndOnlyTheChoiceAnswers() throws Exception {
		this.open(TEXT_1);
		this.open(CHOICE_2);
		UiRouter.View view = this.router.view();
		assertEquals(UiKind.TEXT, view.main().kind());
		assertEquals(UiKind.CHOICE, view.overlay().kind());
		assertSame(view.overlay(), view.active());
		UiSession text = view.main();
		UiSession choice = view.overlay();
		this.router.press(text, 0);
		this.router.press(text, 10);
		assertEquals(List.of(), this.port.take(), "the text box under the choice takes no input");
		choice.choice().move(1);
		this.router.pick(choice, choice.choice().index(), 0);
		assertEquals(List.of("ui.pick {\"index\":1,\"ui\":2}"), this.port.take());
		this.router.pick(choice, 0, 0);
		this.router.back(choice, 0);
		assertEquals(List.of(), this.port.take(), "one answer until the choice changes");
		this.router.onUpdate(json("{\"index\":0,\"ui\":2}"), 0);
		assertEquals(0, choice.choice().index(), "the hidden cursor is followed");
		this.router.back(choice, 0);
		assertEquals(List.of("ui.back {\"ui\":2}"), this.port.take(), "an update frees it again");
		// Gen 1 pops the choice and its text box together.
		this.router.onClose(json("{\"ui\":2}"));
		this.router.onClose(json("{\"ui\":1}"));
		assertFalse(this.router.open());
	}

	@Test
	void aLoneChoiceIsItsOwnView() throws Exception {
		this.open("{\"ui\":2,\"kind\":\"choice\",\"labels\":[\"HEAL\",\"CANCEL\"],\"index\":1,\"question\":\"Shall we heal?\"}");
		UiRouter.View view = this.router.view();
		assertEquals(UiKind.CHOICE, view.main().kind());
		assertNull(view.overlay());
		assertEquals("Shall we heal?", view.main().choice().question());
		this.router.pick(view.main(), 5, 0);
		assertEquals(List.of(), this.port.take(), "an index out of range is never sent");
		this.router.pick(view.main(), 1, 0);
		assertEquals(List.of("ui.pick {\"index\":1,\"ui\":2}"), this.port.take());
	}

	@Test
	void onlyTheTopUiAnswersAndClosingAboveFreesTheOneUnder() throws Exception {
		this.open(LIST_3);
		UiSession list = this.router.top();
		this.router.pick(list, 0, 0);
		assertEquals(List.of("ui.pick {\"index\":0,\"ui\":3}"), this.port.take());
		this.open(QTY_4);
		UiSession quantity = this.router.top();
		assertEquals(List.of(list), this.router.under(this.router.view()), "the list is drawn under the quantity box");
		this.router.back(list, 0);
		this.router.pick(list, 1, 0);
		assertEquals(List.of(), this.port.take(), "not the top");
		quantity.quantity().step(2);
		this.router.quantity(quantity, 0);
		assertEquals(List.of("ui.qty {\"qty\":3,\"ui\":4}"), this.port.take());
		this.router.onClose(json("{\"ui\":4}"));
		assertSame(list, this.router.top());
		this.router.pick(list, 1, 0);
		assertEquals(List.of("ui.pick {\"index\":1,\"ui\":3}"), this.port.take(), "the list takes input again once it is on top");
	}

	@Test
	void anUnansweredPickFreesItselfAfterTheTimeout() throws Exception {
		this.open(LIST_3);
		UiSession list = this.router.top();
		this.router.pick(list, 0, 0);
		this.router.pick(list, 0, 100);
		assertEquals(1, this.port.take().size());
		this.router.pick(list, 0, UiSession.ANSWER_TIMEOUT_MS);
		assertEquals(1, this.port.take().size());
	}

	@Test
	void closesAndUpdatesFindTheirUiAnywhereInTheStack() throws Exception {
		this.open(TEXT_1);
		this.open(LIST_3);
		this.open(QTY_4);
		this.router.onClose(json("{\"ui\":3}"));
		assertEquals(List.of(1, 4), this.router.sessions().stream().map(UiSession::id).toList());
		this.router.onUpdate(json("{\"ui\":4,\"qty\":7}"), 0);
		assertEquals(7, this.router.top().quantity().qty());
		this.router.onUpdate(json("{\"index\":1,\"ui\":99}"), 0);
		this.router.onClose(json("{\"ui\":99}"));
		assertEquals(2, this.router.stats().stale, "unknown ids are counted and ignored");
		assertEquals(2, this.router.sessions().size());
	}

	@Test
	void reopeningAnIdReplacesItInPlace() throws Exception {
		this.open(TEXT_1);
		this.open(LIST_3);
		this.open("{\"ui\":1,\"kind\":\"text\",\"pages\":[[\"Other\"]],\"cont\":[[false]]}");
		assertEquals(List.of(1, 3), this.router.sessions().stream().map(UiSession::id).toList());
		assertEquals(UiKind.LIST, this.router.top().kind());
	}

	@Test
	void malformedMessagesChangeNothing() throws Exception {
		this.open(TEXT_1);
		int changes = this.port.changes;
		for (String bad : List.of("{\"ui\":0,\"kind\":\"text\",\"pages\":[],\"cont\":[]}", "{\"ui\":2,\"kind\":\"dialog\"}",
				"{\"ui\":2,\"kind\":\"text\",\"pages\":\"oops\",\"cont\":[]}", "{\"ui\":2,\"kind\":\"choice\",\"labels\":[],\"index\":0}",
				"{\"ui\":2,\"kind\":\"menu\",\"items\":[{\"label\":1}],\"index\":0,\"top\":0,\"rows\":2}",
				"{\"ui\":2,\"kind\":\"menu\",\"items\":[{\"label\":\"A\",\"extra\":true}],\"index\":0,\"top\":0,\"rows\":2}",
				"{\"ui\":2,\"kind\":\"quantity\",\"qty\":1,\"max\":0}", "{\"ui\":2,\"kind\":\"naming\",\"title\":\"T\",\"max\":7,\"presets\":[],"
						+ "\"value\":\"\",\"grid\":[[[\"TOOLONGTOKEN\"]]]}")) {
			this.open(bad);
		}
		this.router.onUpdate(json("{\"ui\":1,\"page\":-1}"), 0);
		this.router.onClose(json("{\"ui\":\"x\"}"));
		assertEquals(changes, this.port.changes);
		assertEquals(List.of(1), this.router.sessions().stream().map(UiSession::id).toList());
		assertEquals(10, this.router.stats().refused);
		assertTrue(this.port.warnings.contains("open.bad_kind"));
		assertTrue(this.port.warnings.contains("open.bad_grid"));
	}

	@Test
	void theChoiceAndWrapFieldsAreRead() throws Exception {
		this.open("{\"ui\":6,\"kind\":\"text\",\"pages\":[[\"Want it?\"]],\"cont\":[[false]],\"choice\":true}");
		UiSession text = this.router.top();
		assertTrue(text.text().choice());
		this.router.tick(1000);
		assertEquals(TextBoxModel.Stop.END, text.text().stop());
		assertFalse(text.text().arrow());
		this.router.press(text, 1000);
		this.router.press(text, 5000);
		assertEquals(List.of(), this.port.take(), "no ui.advance: the YES/NO box opens over it by itself");
		this.open("{\"ui\":7,\"kind\":\"list\",\"items\":[{\"label\":\"A\"},{\"label\":\"B\"},{\"label\":\"C\"}],"
				+ "\"index\":2,\"top\":0,\"rows\":4,\"wrap\":true}");
		MenuModel list = this.router.top().menu();
		assertTrue(list.wrap());
		list.move(1);
		assertEquals(0, list.index());
		this.open("{\"ui\":8,\"kind\":\"menu\",\"items\":[{\"label\":\"A\"},{\"label\":\"B\"}],\"index\":0,\"top\":0,\"rows\":2,"
				+ "\"wrap\":false}");
		assertFalse(this.router.top().menu().wrap());
		// Not booleans: refused like any other malformed field.
		this.open("{\"ui\":9,\"kind\":\"text\",\"pages\":[[\"x\"]],\"cont\":[[false]],\"choice\":\"yes\"}");
		this.open("{\"ui\":9,\"kind\":\"menu\",\"items\":[{\"label\":\"A\"}],\"index\":0,\"top\":0,\"rows\":1,\"wrap\":1}");
		assertEquals(2, this.router.stats().refused);
		assertTrue(this.port.warnings.containsAll(List.of("open.bad_choice", "open.bad_wrap")));
		assertEquals(List.of(6, 7, 8), this.router.sessions().stream().map(UiSession::id).toList());
	}

	@Test
	void aSnapshotStartsOverFromItsUi() throws Exception {
		this.open(TEXT_1);
		this.open(CHOICE_2);
		this.router.onSnapshot(json("{\"ingame\":true,\"ui\":" + LIST_3 + "}"), 0);
		assertEquals(List.of(3), this.router.sessions().stream().map(UiSession::id).toList());
		this.router.onSnapshot(json("{\"ingame\":true}"), 0);
		assertFalse(this.router.open());
		assertEquals(3, this.router.stats().cleared);
	}

	@Test
	void aDroppedLinkOrAnIdlePlayerClosesEverything() throws Exception {
		this.open(TEXT_1);
		this.router.onDisconnected();
		assertFalse(this.router.open());
		this.open(LIST_3);
		this.router.onMode("screen", 0);
		this.router.onMode("cutscene", 0);
		this.router.tick(5000);
		assertTrue(this.router.open(), "only idle means nothing can be open");
		// gen1-bridge may report idle just before the close in the same logic step: that close still arrives.
		this.router.onMode("idle", 6000);
		this.router.tick(6000);
		assertTrue(this.router.open());
		this.router.onClose(json("{\"ui\":3}"));
		this.router.tick(6000 + UiRouter.IDLE_GRACE_MS);
		assertTrue(this.port.warnings.isEmpty(), "a normal close is no problem");
		// A UI still open after the grace is stale.
		this.open(LIST_3);
		this.router.onMode("idle", 7000);
		this.router.tick(7000 + UiRouter.IDLE_GRACE_MS - 1);
		assertTrue(this.router.open());
		this.router.tick(7000 + UiRouter.IDLE_GRACE_MS);
		assertFalse(this.router.open());
		assertTrue(this.port.warnings.contains("stale_on_idle"));
		// Leaving idle, or a new UI, cancels the countdown.
		this.router.onMode("idle", 8000);
		this.open(LIST_3);
		this.router.tick(9000);
		assertTrue(this.router.open());
	}

	@Test
	void namesAreCheckedAndSentAsGlyphTokens() throws Exception {
		this.open(NAMING_5);
		UiSession naming = this.router.top();
		this.router.name(naming, List.of("A", "B", "C", "D", "E", "F", "G", "H"), 0);
		this.router.name(naming, List.of("AB"), 0);
		assertEquals(List.of(), this.port.take(), "too long, or not one glyph per entry");
		this.router.name(naming, List.of("A", "<PK>", "é"), 0);
		assertEquals(List.of("ui.name {\"glyphs\":[\"A\",\"<PK>\",\"é\"],\"ui\":5}"), this.port.take());
		this.router.back(naming, 0);
		assertEquals(List.of(), this.port.take(), "B on the naming screen deletes; it never answers");
	}

	@Test
	void aNicknameLeftEmptyIsSentEmpty() throws Exception {
		// the follow-up's item 9: no default and no preset; the hidden screen confirms its own empty name (no nickname)
		this.open("{\"ui\":8,\"kind\":\"naming\",\"title\":\"NICKNAME?\",\"max\":10,\"presets\":[],\"value\":\"\",\"mon\":25}");
		UiSession naming = this.router.top();
		List<String> glyphs = naming.naming().confirm();
		assertEquals(List.of(), glyphs);
		this.router.name(naming, glyphs, 0);
		assertEquals(List.of("ui.name {\"glyphs\":[],\"ui\":8}"), this.port.take());
	}

	@Test
	void aFailedSendLeavesTheUiAnswerable() throws Exception {
		this.open(LIST_3);
		UiSession list = this.router.top();
		this.port.connected = false;
		this.router.pick(list, 0, 0);
		assertFalse(list.answered(0));
		this.port.connected = true;
		this.router.pick(list, 0, 0);
		assertEquals(1, this.port.take().size());
		assertTrue(list.answered(0));
	}

	@Test
	void typewritersRunOnTheRoutersTicks() throws Exception {
		this.open(TEXT_1);
		UiSession text = this.router.top();
		this.router.tick(1000);
		assertEquals(TextBoxModel.Stop.END, text.text().stop());
		assertEquals("text#1", text.toString(), "kind and id only: safe for logs");
	}

	// ---- MG5 (D6): the parked save's name answers the started game's naming screen ----

	@Test
	void anArmedNameThatFitsAnswersTheNamingScreenWithoutShowingIt() throws Exception {
		this.router.setAutoName(() -> "RED");
		this.open(NAMING_5);
		assertEquals(List.of("ui.name {\"glyphs\":[\"R\",\"E\",\"D\"],\"ui\":5}"), this.port.take());
		UiSession naming = this.router.top();
		assertNotNull(naming);
		assertTrue(this.router.autoAnswered(naming), "nothing to show for it");
		assertTrue(naming.answered(0));
		assertEquals(1, this.router.stats().autoNamed);
		this.router.onClose(json("{\"ui\":5}"));
		assertFalse(this.router.autoAnswered(naming));
		// One prompt only: the rival's name is the player's to type.
		this.open("{\"ui\":6,\"kind\":\"naming\",\"title\":\"RIVAL's NAME?\",\"max\":7,\"presets\":[],\"value\":\"\"}");
		assertEquals(List.of(), this.port.take());
		assertFalse(this.router.autoAnswered(this.router.top()));
		assertEquals(List.of(), this.router.top().naming().typed());
	}

	@Test
	void anArmedNameTooLongForTheGameIsShownPrefilledAndCut() throws Exception {
		this.router.setAutoName(() -> "ABCDEFGHIJ");
		this.open(NAMING_5);
		assertEquals(List.of(), this.port.take(), "the player confirms or finishes it");
		UiSession naming = this.router.top();
		assertFalse(this.router.autoAnswered(naming));
		assertEquals(List.of("A", "B", "C", "D", "E", "F", "G"), naming.naming().typed());
		assertEquals(NamingModel.Phase.GRID, naming.naming().phase());
		assertEquals(NamingModel.Control.END, naming.naming().cell().control(), "the cursor waits on ED");
		assertEquals(List.of("A", "B", "C", "D", "E", "F", "G"), naming.naming().confirm());
	}

	@Test
	void theArmedNameIsOnlyForANamingScreenAndCanBeDisarmed() throws Exception {
		this.router.setAutoName(() -> "RED");
		this.open(TEXT_1);
		assertEquals(List.of(), this.port.take());
		this.router.setAutoName(null);
		this.open(NAMING_5);
		assertEquals(List.of(), this.port.take());
		assertFalse(this.router.autoAnswered(this.router.top()));
		this.router.setAutoName(() -> null);
		this.open("{\"ui\":7,\"kind\":\"naming\",\"title\":\"NAME?\",\"max\":7,\"presets\":[],\"value\":\"\"}");
		assertEquals(List.of(), this.port.take(), "a parked save without a name arms nothing");
	}
}
