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
import org.junit.jupiter.api.Test;

/**
 * Native special battles (docs/PLAN.md P9, §6 M8 item 7): {@code battle.native} keeps the mirror up between the
 * battle's own UIs, with the keys going to the hidden game, and gives way to the hidden game's UIs whenever one is open.
 */
class NativeBattleTest {
	private final List<String> sent = new ArrayList<>();
	private final UiRouter router = new UiRouter(new UiRouter.Port() {
		@Override
		public boolean send(String type, JsonObject data) {
			NativeBattleTest.this.sent.add(type);
			return true;
		}
	});
	private final NativeBattle battle = new NativeBattle();

	private static JsonObject json(String text) throws Exception {
		return ProtoJson.parseObject(text);
	}

	@Test
	void aSafariBattleKeepsTheMirrorUpBetweenItsUis() throws Exception {
		assertNull(this.battle.view(this.router), "nothing runs");
		this.battle.onMessage(json("{\"kind\":\"safari\",\"phase\":\"start\"}"));
		assertTrue(this.battle.active());
		assertEquals("safari", this.battle.kind());
		UiRouter.View view = this.battle.view(this.router);
		assertNotNull(view);
		assertEquals(UiKind.MIRROR, view.main().kind());
		assertEquals(NativeBattle.UI_ID, view.main().id());
		assertNull(view.overlay());
		assertTrue(this.battle.takesInput(this.router, view.main()), "the keys go to the hidden game");
		assertSame(view.main(), this.battle.view(this.router).main(), "the same mirror, so the screen stays");

		// The battle screen arrives as the hidden game's own mirror UI, a text box over it: those show and take the keys.
		this.router.onOpen(json("{\"ui\":3,\"kind\":\"mirror\",\"screen\":\"BattleState\"}"), 0);
		this.router.onOpen(json("{\"ui\":4,\"kind\":\"text\",\"pages\":[[\"A wild one appeared!\"]],\"cont\":[[false]]}"), 0);
		UiRouter.View text = this.battle.view(this.router);
		assertEquals(4, text.main().id());
		assertFalse(this.battle.takesInput(this.router, view.main()), "our mirror is not on top now");
		this.router.onClose(json("{\"ui\":4}"));
		assertEquals(3, this.battle.view(this.router).main().id());
		assertTrue(this.battle.takesInput(this.router, this.battle.view(this.router).main()));
		this.router.onClose(json("{\"ui\":3}"));
		assertEquals(NativeBattle.UI_ID, this.battle.view(this.router).main().id(), "between UIs: still the mirror");

		this.battle.onMessage(json("{\"kind\":\"safari\",\"phase\":\"end\"}"));
		assertFalse(this.battle.active());
		assertNull(this.battle.view(this.router));
		assertFalse(this.battle.takesInput(this.router, view.main()));
		assertEquals(1, this.battle.starts());
		assertEquals(1, this.battle.ends());
	}

	@Test
	void snapshotsAndDroppedLinksForgetItUntilAnnouncedAgain() throws Exception {
		this.battle.onMessage(json("{\"kind\":\"ghost\",\"phase\":\"start\"}"));
		this.battle.onSnapshot();
		assertFalse(this.battle.active(), "gen1-bridge announces it again right after the snapshot");
		this.battle.onMessage(json("{\"kind\":\"ghost\",\"phase\":\"start\"}"));
		assertTrue(this.battle.active());
		this.battle.onDisconnected();
		assertFalse(this.battle.active());
		this.battle.onMessage(json("{\"kind\":\"old_man\",\"phase\":\"start\"}"));
		this.battle.onMessage(json("{\"kind\":\"old_man\",\"phase\":\"start\"}"));
		assertEquals(3, this.battle.starts(), "a repeated start of the same battle counts once");
	}

	@Test
	void anIdleHiddenPlayerMeansTheBattleIsOverEvenWithoutItsEnd() throws Exception {
		this.battle.onMode("screen", 0);
		this.battle.onMessage(json("{\"kind\":\"ghost\",\"phase\":\"start\"}"));
		this.battle.tick(5_000);
		assertTrue(this.battle.active(), "the battle screen is up: not idle");
		// Its end went missing; the hidden player walks again.
		this.battle.onMode("idle", 6_000);
		this.battle.tick(6_000 + NativeBattle.IDLE_GRACE_MS - 1);
		assertTrue(this.battle.active(), "a moment's grace");
		this.battle.tick(6_000 + NativeBattle.IDLE_GRACE_MS);
		assertFalse(this.battle.active());
		assertEquals(1, this.battle.stale());
		// The next battle starts with the mode leaving idle first (player.state comes before battle.native).
		this.battle.onMode("busy", 9_000);
		this.battle.onMessage(json("{\"kind\":\"ghost\",\"phase\":\"start\"}"));
		this.battle.tick(20_000);
		assertTrue(this.battle.active());
	}

	@Test
	void malformedOrForeignMessagesChangeNothing() throws Exception {
		this.battle.onMessage(json("{\"kind\":\"champion\",\"phase\":\"start\"}"));
		this.battle.onMessage(json("{\"kind\":\"safari\"}"));
		assertFalse(this.battle.active());
		this.battle.onMessage(json("{\"kind\":\"safari\",\"phase\":\"start\"}"));
		this.battle.onMessage(json("{\"kind\":\"ghost\",\"phase\":\"end\"}"));
		assertTrue(this.battle.active(), "the end of another battle");
		assertTrue(this.sent.isEmpty(), "nothing is sent by the model itself");
	}
}
