package dev.kantobridge.battle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.kantobridge.proto.ProtoJson;
import dev.kantobridge.world.DataException;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Wild encounters without a server: reading their start, where the Pokémon appears, how a catch is reported. */
class EncounterRulesTest {
	@Test
	void wildStartsRead() throws Exception {
		BattleStart surf = BattleStart.read(ProtoJson.parseObject(
				"{\"battle\":\"w-1\",\"kind\":\"surf\",\"wild\":{\"dex\":72,\"level\":20},\"rules\":{\"catch\":true,\"flee\":true}}"));
		assertEquals(BattleStart.Kind.SURF, surf.kind());
		assertFalse(surf.hasTeam(), "a wild encounter goes to the EncounterService");
		assertEquals(new BattleStart.Wild(72, 20, null, null), surf.wild());
		assertTrue(surf.canCatch() && surf.canFlee());
		BattleStart fixed = BattleStart.read(ProtoJson.parseObject(
				"{\"battle\":\"w-2\",\"kind\":\"static\",\"wild\":{\"dex\":143,\"level\":30,\"x\":4,\"y\":7},\"rules\":{\"catch\":true,\"flee\":false}}"));
		assertEquals(new BattleStart.Wild(143, 30, 4, 7), fixed.wild());
		assertFalse(fixed.canFlee());
		BattleStart scripted = BattleStart.read(ProtoJson.parseObject(
				"{\"battle\":\"w-3\",\"kind\":\"scripted\",\"wild\":{\"dex\":105,\"level\":30},\"rules\":{\"catch\":false,\"flee\":false}}"));
		assertFalse(scripted.hasTeam(), "a scripted battle with a wild Pokémon is an encounter too");
		// Gen 1 species only; a fish without its Pokémon is malformed.
		assertThrows(DataException.class, () -> BattleStart.read(ProtoJson.parseObject(
				"{\"battle\":\"w-4\",\"kind\":\"fish\",\"wild\":{\"dex\":152,\"level\":5},\"rules\":{\"catch\":true,\"flee\":true}}")));
		assertThrows(DataException.class, () -> BattleStart.read(ProtoJson.parseObject(
				"{\"battle\":\"w-5\",\"kind\":\"fish\",\"rules\":{\"catch\":true,\"flee\":true}}")));
	}

	@Test
	void catchesAreReportedWithThePokemon() {
		// A catch wins over everything: the Pokémon is in the party, even if the battle then stopped.
		assertEquals(BattleOutcome.CAUGHT, BattleOutcome.classify(true, true, false, true));
		assertEquals(BattleOutcome.CAUGHT, BattleOutcome.classify(false, false, false, true));
		assertEquals(BattleOutcome.WIN, BattleOutcome.classify(true, true, false, false), "defeated");
		assertEquals(BattleOutcome.LOSE, BattleOutcome.classify(true, false, false, false));
		assertEquals(BattleOutcome.FLED, BattleOutcome.classify(false, false, true, false));
		assertEquals(BattleOutcome.ABORTED, BattleOutcome.classify(false, false, false, false));
		UUID uid = UUID.fromString("0f8b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b");
		JsonObject result = BattleOutcome.caught("w-6", uid, 19, 3);
		assertEquals("{\"battle\":\"w-6\",\"caught\":{\"dex\":19,\"level\":3,\"uid\":\"0f8b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b\"},\"outcome\":\"caught\"}",
				ProtoJson.write(result));
		assertThrows(IllegalArgumentException.class, () -> BattleOutcome.caught("w-7", uid, 19, 0));
	}

	@Test
	void thePokemonAppearsOnTheGivenCellOrAhead() {
		Vec3 player = new Vec3(10.5, 64.0, 20.5);
		// A given cell nearby: there.
		Vec3 cell = new Vec3(15.0, 64.0, 21.0);
		assertEquals(cell, EncounterPlacement.choose(cell, player, 0.0F));
		// The player's own cell, or one too far away: two cells ahead in the facing direction.
		assertEquals(new Vec3(10.5, 64.0, 24.5), EncounterPlacement.choose(new Vec3(11.0, 64.0, 21.0), player, 0.0F), "facing down (south)");
		assertEquals(new Vec3(10.5, 64.0, 16.5), EncounterPlacement.choose(new Vec3(100.0, 64.0, 20.0), player, 180.0F), "facing up (north)");
		assertEquals(new Vec3(6.5, 64.0, 20.5), EncounterPlacement.choose(null, player, 90.0F), "facing left (west)");
		assertEquals(new Vec3(14.5, 64.0, 20.5), EncounterPlacement.choose(null, player, -80.0F), "facing right (east), snapped");
		// A raised cell 2 up is the same level; a piece stacked 11 blocks up or down is another (CAVES_3D_PLAN §3.1).
		Vec3 raised = new Vec3(15.0, 66.0, 21.0);
		assertEquals(raised, EncounterPlacement.choose(raised, player, 0.0F));
		assertEquals(new Vec3(10.5, 64.0, 24.5), EncounterPlacement.choose(new Vec3(15.0, 75.0, 21.0), player, 0.0F));
		assertEquals(new Vec3(10.5, 64.0, 24.5), EncounterPlacement.choose(new Vec3(15.0, 53.0, 21.0), player, 0.0F));
		assertEquals(12.0F, EncounterService.FLEE_DISTANCE, "Appendix C's flee distance");
	}
}
