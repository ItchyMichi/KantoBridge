package dev.kantobridge.gametest;

import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.server.KantoGameRules;
import dev.kantobridge.server.Rules;
import dev.kantobridge.story.StoryState;
import dev.kantobridge.story.StoryStates;
import java.util.Objects;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;

/**
 * Kanto's rules on a real server (docs/PLAN.md P2, P7, §4.4 {@code Rules}, Appendix D): the game rules, Peaceful and the
 * rest at server start; Adventure, Gen 1 speeds and no jumping on join; the bike flag from {@code story.state}; and no
 * damage for players. They hold on the GameTest server like on any server running the mod.
 */
public class RulesGameTests implements FabricGameTest {
	static void assertMovement(GameTestHelper helper, ServerPlayer player, float speed) {
		helper.assertTrue(player.getAttributeBaseValue(Attributes.JUMP_STRENGTH) == 0.0, "jumping must be off");
		helper.assertTrue(player.getAttributeBaseValue(Attributes.MOVEMENT_SPEED) == speed, "movement speed attribute");
		helper.assertTrue(player.getAbilities().getWalkingSpeed() == speed, "walking-speed ability (keeps the field of view)");
		helper.assertFalse(player.isSprinting(), "sprinting");
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void serverStartAppliesKantosRules(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		GameRules rules = server.getGameRules();
		KantoGameRules.BOOLEANS.forEach((key, value) -> helper.assertTrue(rules.getBoolean(key) == value, "game rule " + key.getId()));
		KantoGameRules.INTEGERS.forEach((key, value) -> helper.assertTrue(rules.getInt(key) == value, "game rule " + key.getId()));
		helper.assertTrue(server.getWorldData().getDifficulty() == Difficulty.PEACEFUL, "difficulty must be Peaceful");
		helper.assertTrue(server.getWorldData().isDifficultyLocked(), "difficulty must be locked");
		helper.assertTrue(server.getDefaultGameType() == GameType.ADVENTURE, "default game type must be Adventure");
		ServerLevel overworld = server.overworld();
		helper.assertFalse(overworld.isRaining() || overworld.isThundering(), "weather must be clear");
		helper.assertTrue(overworld.getDayTime() % 24000L == Rules.NOON, "time must stay at noon");
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void playersJoinInAdventureWithGen1Movement(GameTestHelper helper) {
		MockPlayers.with(helper, player -> {
			helper.assertTrue(player.gameMode.getGameModeForPlayer() == GameType.ADVENTURE, "a joining player must be in Adventure");
			assertMovement(helper, player, Rules.WALK_SPEED);
		});
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void theBikeFlagSwitchesTheSpeed(GameTestHelper helper) {
		RelayService relay = RelayService.get();
		MockPlayers.with(helper, player -> RelayTap.capture(player, sent -> {
			relay.receive(player, "{\"type\":\"story.state\",\"seq\":1,\"data\":{\"full\":true,\"active\":[\"TEST_TOWN:15:4\"],"
					+ "\"surf\":true,\"bike\":true,\"lit\":[]}}");
			StoryState state = StoryStates.of(player);
			helper.assertTrue(state.bike() && state.surf() && state.isActive("TEST_TOWN:15:4"), "the full state was not applied");
			assertMovement(helper, player, Rules.BIKE_SPEED);

			relay.receive(player, "{\"type\":\"story.state\",\"seq\":2,\"data\":{\"full\":false,\"bike\":false}}");
			helper.assertTrue(!state.bike() && state.surf(), "the delta changed more than the bike");
			assertMovement(helper, player, Rules.WALK_SPEED);

			relay.receive(player, "{\"type\":\"story.state\",\"seq\":3,\"data\":{\"full\":false,\"bike\":\"yes\"}}");
			helper.assertFalse(state.bike(), "a malformed message changed the state");
			helper.assertTrue(sent.size() == 1, "expected one reply, got " + sent.size());
			Envelope error = sent.get(0);
			helper.assertTrue(error.type().equals("error") && Objects.equals(error.re(), 3L), "a malformed message must be answered with error");
		}));
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, timeoutTicks = 200)
	public void playersTakeNoDamage(GameTestHelper helper) {
		ServerPlayer player = MockPlayers.join(helper);
		DamageSources sources = helper.getLevel().damageSources();
		ArmorStand stand = new ArmorStand(helper.getLevel(), 0.0, 0.0, 0.0);
		ServerLivingEntityEvents.AllowDamage allow = ServerLivingEntityEvents.ALLOW_DAMAGE.invoker();
		try {
			helper.assertFalse(allow.allowDamage(player, sources.generic(), 4.0F), "player damage must be vetoed");
			helper.assertTrue(allow.allowDamage(player, sources.fellOutOfWorld(), 4.0F), "the void must still reach players");
			helper.assertTrue(allow.allowDamage(stand, sources.generic(), 4.0F), "only players are protected");
		} catch (RuntimeException e) {
			MockPlayers.leave(helper, player);
			throw e;
		}
		float health = player.getHealth();
		// Past the 60 ticks of spawn protection, only the veto stands between the player and the hit.
		helper.startSequence().thenIdle(65).thenExecute(() -> {
			try {
				helper.assertFalse(player.hurt(sources.generic(), 4.0F), "the hit must be refused");
				helper.assertTrue(player.getHealth() == health, "health changed");
			} finally {
				MockPlayers.leave(helper, player);
			}
		}).thenSucceed();
	}
}
