package dev.kantobridge.battle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Battle decisions that need no server: the proxy class (AI skill), the outcome, where the proxy stands. */
class BattleRulesTest {
	@Test
	void proxyClassFollowsPlanP11() {
		assertEquals(ProxyClass.REGULAR, ProxyClass.of(BattleStart.Kind.TRAINER, "OPP_YOUNGSTER"));
		assertEquals(ProxyClass.REGULAR, ProxyClass.of(BattleStart.Kind.SCRIPTED, null));
		assertEquals(ProxyClass.RIVAL, ProxyClass.of(BattleStart.Kind.RIVAL, "OPP_RIVAL1"));
		assertEquals(ProxyClass.RIVAL, ProxyClass.of(BattleStart.Kind.TRAINER, "OPP_RIVAL2"));
		assertEquals(ProxyClass.RIVAL, ProxyClass.of(BattleStart.Kind.RIVAL, null));
		for (String leader : new String[] {"OPP_BROCK", "OPP_MISTY", "OPP_LT_SURGE", "OPP_ERIKA", "OPP_KOGA", "OPP_SABRINA", "OPP_BLAINE",
				"OPP_GIOVANNI", "BROCK", "opp_misty"}) {
			assertEquals(ProxyClass.GYM, ProxyClass.of(BattleStart.Kind.TRAINER, leader), leader);
		}
		for (String elite : new String[] {"OPP_LORELEI", "OPP_BRUNO", "OPP_AGATHA", "OPP_LANCE", "LANCE"}) {
			assertEquals(ProxyClass.ELITE, ProxyClass.of(BattleStart.Kind.TRAINER, elite), elite);
		}
		// The Champion is the rival's third class: Elite skill, even as a rival kind.
		assertEquals(ProxyClass.ELITE, ProxyClass.of(BattleStart.Kind.RIVAL, "OPP_RIVAL3"));
		assertEquals(1, ProxyClass.REGULAR.skill());
		assertEquals(3, ProxyClass.RIVAL.skill());
		assertEquals(3, ProxyClass.GYM.skill());
		assertEquals(5, ProxyClass.ELITE.skill());
		assertEquals("battle_proxy_elite", ProxyClass.ELITE.path());
	}

	@Test
	void outcomesFromTheEndHandler() {
		assertEquals(BattleOutcome.WIN, BattleOutcome.classify(true, true, false));
		// A forfeit makes the other side win: a loss (Gen 1 has no forfeit; it blacks out).
		assertEquals(BattleOutcome.LOSE, BattleOutcome.classify(true, false, false));
		assertEquals(BattleOutcome.FLED, BattleOutcome.classify(false, false, true));
		assertEquals(BattleOutcome.ABORTED, BattleOutcome.classify(false, false, false));
		// A real result wins over a stale flee mark.
		assertEquals(BattleOutcome.WIN, BattleOutcome.classify(true, true, true));
		JsonObject result = BattleOutcome.LOSE.result("b-3");
		assertEquals("b-3", result.get("battle").getAsString());
		assertEquals("lose", result.get("outcome").getAsString());
		assertEquals(2, result.size());
		assertThrows(IllegalStateException.class, () -> BattleOutcome.CAUGHT.result("b-4"));
	}

	@Test
	void theProxyStandsOnANearbyTrainerCellOrInFrontOfThePlayer() {
		Vec3 player = new Vec3(10.5, 64.0, 20.5);
		Vec3 cell = new Vec3(13.0, 64.0, 20.0);
		assertEquals(cell, ProxyPlacement.choose(cell, player, 0.0F));
		// Too far (another layer, a stale message): in front of the player instead.
		Vec3 far = new Vec3(10.5, 20.0, 20.5);
		Vec3 ahead = ProxyPlacement.choose(far, player, 0.0F);
		assertEquals(player.x, ahead.x, 1.0E-6);
		assertEquals(player.z + ProxyPlacement.IN_FRONT, ahead.z, 1.0E-6, "yaw 0 looks south (+z)");
		assertEquals(player.y, ahead.y, 1.0E-6);
		Vec3 west = ProxyPlacement.choose(null, player, 90.0F);
		assertEquals(player.x - ProxyPlacement.IN_FRONT, west.x, 1.0E-6, "yaw 90 looks west (-x)");
		// A trainer cell in a piece stacked 11 blocks below (CAVES_3D_PLAN §3.1) is another level, though near.
		assertEquals(player.z + ProxyPlacement.IN_FRONT, ProxyPlacement.choose(new Vec3(13.0, 53.0, 20.0), player, 0.0F).z, 1.0E-6);
		assertEquals(new Vec3(13.0, 66.0, 20.0), ProxyPlacement.choose(new Vec3(13.0, 66.0, 20.0), player, 0.0F), "a raised cell is not");
	}

	@Test
	void theProxyFacesThePlayer() {
		Vec3 proxy = new Vec3(0.0, 64.0, 0.0);
		assertEquals(0.0F, ProxyPlacement.yawTowards(proxy, new Vec3(0.0, 64.0, 5.0)), 1.0E-3F);
		assertEquals(90.0F, ProxyPlacement.yawTowards(proxy, new Vec3(-5.0, 64.0, 0.0)), 1.0E-3F);
		assertEquals(180.0F, Math.abs(ProxyPlacement.yawTowards(proxy, new Vec3(0.0, 64.0, -5.0))), 1.0E-3F);
		float east = ProxyPlacement.yawTowards(proxy, new Vec3(5.0, 64.0, 0.0));
		assertTrue(Math.abs(Mth.wrapDegrees(east + 90.0F)) < 1.0E-3F, "east is -90");
	}
}
