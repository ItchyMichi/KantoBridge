package dev.kantobridge.client.puppet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.Dir;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Puppet bookkeeping: negative entity ids from -1,000,000 down, kept for a puppet's life; full lists rebuild (a map
 * change replaces everything); adds, removes, moves, faces and emotes; malformed messages change nothing.
 */
class PuppetManagerTest {
	final List<String> events = new ArrayList<>();
	PuppetManager manager;

	@BeforeEach
	void setUp() {
		this.manager = new PuppetManager(new PuppetManager.Listener() {
			@Override
			public void added(Puppet puppet) {
				PuppetManagerTest.this.events.add("+" + puppet.id() + "@" + puppet.entityId());
			}

			@Override
			public void removed(Puppet puppet) {
				PuppetManagerTest.this.events.add("-" + puppet.id());
			}
		});
	}

	static JsonObject json(String text) throws Exception {
		return ProtoJson.parseObject(text);
	}

	static String npc(String id, String sprite, String kind, int x, int y, String facing) {
		return "{\"id\":\"" + id + "\",\"sprite\":\"" + sprite + "\",\"kind\":\"" + kind + "\",\"x\":" + x + ",\"y\":" + y + ",\"facing\":\""
				+ facing + "\",\"moving\":false}";
	}

	static final String TOWN = "{\"map\":\"TEST_TOWN\",\"list\":[" + npc("TEST_TOWN_obj_1", "SPRITE_TEST_GIRL", "person", 10, 8, "down") + ","
			+ npc("TEST_TOWN_obj_2", "SPRITE_TEST_BALL", "item", 15, 4, "down") + ","
			+ npc("TEST_TOWN_obj_3", "SPRITE_TEST_GUARD", "trainer", 12, 10, "left") + "]}";

	@Test
	void aFullListAddsPuppetsWithNegativeIdsCountingDown() throws Exception {
		this.manager.onSet(json(TOWN));
		assertEquals(List.of("+TEST_TOWN_obj_1@-1000000", "+TEST_TOWN_obj_2@-1000001", "+TEST_TOWN_obj_3@-1000002"), this.events);
		assertEquals(3, this.manager.size());
		assertEquals("TEST_TOWN", this.manager.map());
		Puppet guard = this.manager.get("TEST_TOWN_obj_3");
		assertEquals(Dir.LEFT, guard.facing());
		assertEquals("trainer", guard.kind());
		assertSame(guard, this.manager.byEntityId(-1_000_002));
	}

	@Test
	void theSameListAgainKeepsPuppetsAndTheirIds() throws Exception {
		this.manager.onSet(json(TOWN));
		this.events.clear();
		this.manager.onSet(json(TOWN.replace("\"x\":10,\"y\":8", "\"x\":11,\"y\":8")));
		assertEquals(List.of(), this.events, "no puppet came or went");
		Puppet girl = this.manager.get("TEST_TOWN_obj_1");
		assertEquals(-1_000_000, girl.entityId());
		assertEquals(11, girl.x(), "the list's data replaced the old");
		assertEquals(11.0, girl.cellX(0), 1e-9, "placed, not walked");
	}

	@Test
	void anotherMapsListReplacesEveryPuppet() throws Exception {
		this.manager.onSet(json(TOWN));
		this.events.clear();
		this.manager.onSet(json("{\"map\":\"TEST_HOUSE\",\"list\":[" + npc("TEST_HOUSE_obj_1", "SPRITE_TEST_MOM", "person", 6, 4, "left") + "]}"));
		assertEquals(List.of("-TEST_TOWN_obj_1", "-TEST_TOWN_obj_2", "-TEST_TOWN_obj_3", "+TEST_HOUSE_obj_1@-1000003"), this.events);
		assertEquals("TEST_HOUSE", this.manager.map());
		assertNull(this.manager.byEntityId(-1_000_000));
		// Coming back makes new puppets with new ids: ids are never reused.
		this.events.clear();
		this.manager.onSet(json(TOWN));
		assertTrue(this.events.contains("+TEST_TOWN_obj_1@-1000004"));
	}

	@Test
	void addsRemovesMovesAndFaces() throws Exception {
		this.manager.onSet(json(TOWN));
		this.events.clear();
		JsonObject add = json(npc("TEST_TOWN_obj_4", "SPRITE_TEST_GIRL", "person", 2, 2, "up"));
		add.addProperty("map", "TEST_TOWN");
		this.manager.onAdd(add);
		this.manager.onRemove(json("{\"id\":\"TEST_TOWN_obj_2\"}"));
		assertEquals(List.of("+TEST_TOWN_obj_4@-1000003", "-TEST_TOWN_obj_2"), this.events);
		this.manager.onMove(json("{\"id\":\"TEST_TOWN_obj_1\",\"x\":10,\"y\":9,\"facing\":\"down\",\"frames\":16,\"kind\":\"walk\"}"));
		Puppet girl = this.manager.get("TEST_TOWN_obj_1");
		assertEquals(9, girl.y());
		assertTrue(girl.stepping());
		this.manager.onFace(json("{\"id\":\"TEST_TOWN_obj_1\",\"facing\":\"right\"}"));
		assertEquals(Dir.RIGHT, girl.facing());
		PuppetManager.Stats stats = this.manager.stats();
		assertEquals(1, stats.adds);
		assertEquals(1, stats.removes);
		assertEquals(1, stats.moves);
		assertEquals(1, stats.faces);
	}

	@Test
	void emotesGoToAPuppetOrThePlayer() throws Exception {
		this.manager.onSet(json(TOWN));
		this.manager.onEmote(json("{\"target\":\"TEST_TOWN_obj_3\",\"bubble\":\"!\",\"frames\":60}"));
		assertEquals("!", this.manager.get("TEST_TOWN_obj_3").bubble());
		this.manager.onEmote(json("{\"target\":\"player\",\"bubble\":\"happy\",\"frames\":6}"));
		assertEquals("happy", this.manager.playerBubble());
		this.manager.advancePlayer(3);
		this.manager.advancePlayer(3);
		assertNull(this.manager.playerBubble());
		this.manager.onEmote(json("{\"target\":\"TEST_TOWN_obj_3\",\"bubble\":null,\"frames\":0}"));
		assertNull(this.manager.get("TEST_TOWN_obj_3").bubble());
		this.manager.onEmote(json("{\"target\":\"TEST_TOWN_obj_9\",\"bubble\":\"?\",\"frames\":10}"));
		assertEquals(1, this.manager.stats().stale);
	}

	@Test
	void malformedMessagesChangeNothing() throws Exception {
		this.manager.onSet(json(TOWN));
		this.events.clear();
		this.manager.onSet(json("{\"map\":\"TEST_TOWN\",\"list\":[" + npc("A", "SPRITE_X", "dragon", 1, 1, "down") + "]}"));
		this.manager.onSet(json("{\"map\":\"TEST_TOWN\",\"list\":[" + npc("A", "SPRITE_X", "person", 1, 1, "down") + ","
				+ npc("A", "SPRITE_X", "person", 2, 2, "down") + "]}"));
		this.manager.onSet(json("{\"map\":\"bad map\",\"list\":[]}"));
		this.manager.onSet(null);
		JsonObject extra = json(npc("TEST_TOWN_obj_5", "SPRITE_X", "person", 1, 1, "down"));
		extra.addProperty("map", "TEST_TOWN");
		extra.addProperty("name", "nope");
		this.manager.onAdd(extra);
		this.manager.onMove(json("{\"id\":\"TEST_TOWN_obj_1\",\"x\":99999,\"y\":9,\"facing\":\"down\",\"frames\":16,\"kind\":\"walk\"}"));
		this.manager.onFace(json("{\"id\":\"TEST_TOWN_obj_1\",\"facing\":\"north\"}"));
		this.manager.onEmote(json("{\"target\":\"TEST_TOWN_obj_1\",\"bubble\":\"zzz\",\"frames\":10}"));
		this.manager.onRemove(json("{\"id\":\"no such npc!\"}"));
		assertEquals(List.of(), this.events);
		assertEquals(3, this.manager.size());
		assertEquals(9, this.manager.stats().refused);
		assertNotNull(this.manager.warnings().get("refused.set"));
	}

	@Test
	void clearRemovesEverything() throws Exception {
		this.manager.onSet(json(TOWN));
		this.manager.onEmote(json("{\"target\":\"player\",\"bubble\":\"!\",\"frames\":60}"));
		this.events.clear();
		this.manager.clear();
		assertEquals(3, this.events.size());
		assertEquals(0, this.manager.size());
		assertNull(this.manager.map());
		assertNull(this.manager.playerBubble());
	}
}
