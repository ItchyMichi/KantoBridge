package dev.kantobridge.client.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.world.WorldRecord;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** WORLD_SESSIONS_PLAN WS6: the creation screen's rules (D1–D3). */
class CreateWorldRulesTest {
	private static CreateWorldRules rules() {
		return new CreateWorldRules(List.of(new CreateWorldRules.Row("blue", "kanto", true, true), new CreateWorldRules.Row("red", "kanto", true, false),
				new CreateWorldRules.Row("crystal", "johto", true, true), new CreateWorldRules.Row("emerald", "hoenn", false, false)));
	}

	@Test
	void atLeastOneGameAndOneGamePerWorldSpace() {
		CreateWorldRules r = rules();
		assertEquals(CreateWorldRules.Problem.NONE_TICKED, r.problem());
		assertTrue(r.toggle("blue"));
		assertEquals("blue", r.blockedBy(r.rows().get(1)), "red is the same world as blue");
		assertFalse(r.toggle("red"));
		assertNull(r.problem());
		assertEquals("blue", r.start(), "the first ticked is the start by default");
	}

	@Test
	void theStartIsAmongTheTickedAndTheRecordAndChoicesFollow() {
		CreateWorldRules r = rules();
		r.toggle("blue");
		r.toggle("crystal");
		assertFalse(r.startAt("emerald"), "not ticked");
		assertTrue(r.startAt("crystal"));
		r.toggleSave("blue");
		assertEquals(new WorldRecord("johto", List.of(new WorldRecord.Game("blue", "kanto"), new WorldRecord.Game("crystal", "johto"))), r.record());
		assertEquals(Map.of("kanto", "new", "johto", "current"), r.choices());
		r.toggle("crystal");
		assertEquals("blue", r.start(), "the start went with its game");
	}

	@Test
	void aTickedGameWhoseLinkIsDownIsRefused() {
		CreateWorldRules r = rules();
		r.toggle("emerald");
		assertEquals(CreateWorldRules.Problem.LINK_DOWN, r.problem());
		assertEquals("emerald", r.downGame());
		assertNull(r.record());
		assertFalse(r.toggleSave("emerald"), "no save to keep");
	}
}
