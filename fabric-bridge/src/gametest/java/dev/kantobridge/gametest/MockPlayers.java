package dev.kantobridge.gametest;

import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerPlayer;

/** Mock players for GameTests: a real {@link ServerPlayer} on an in-memory connection, removed when the test ends. */
final class MockPlayers {
	/** How long the end of a test waits for Cobblemon's recall (30 ticks of animation) before taking the Pokémon back. */
	static final int RECALL_TICKS = 60;

	private MockPlayers() {
	}

	/**
	 * Joins a mock player (going through the normal join path, so Cobblemon sets up its data), runs {@code test},
	 * and always removes the player again so it doesn't linger in the shared test world.
	 */
	static void with(GameTestHelper helper, Consumer<ServerPlayer> test) {
		ServerPlayer player = join(helper);
		try {
			test.accept(player);
		} finally {
			leave(helper, player);
		}
	}

	/** Joins a mock player for a test that spans ticks; the test must {@link #leave} it on every path. */
	@SuppressWarnings("removal") // the only GameTest helper that yields a real, joined ServerPlayer on 1.21.1
	static ServerPlayer join(GameTestHelper helper) {
		return helper.makeMockServerPlayerInLevel();
	}

	/** Removes the player as a disconnect does (the same server path: {@code PlayerList.remove}). */
	static void leave(GameTestHelper helper, ServerPlayer player) {
		helper.getLevel().getServer().getPlayerList().remove(player);
	}

	/** Stops the player's Cobblemon battle, if one runs (what a disconnect does first). */
	static void endBattle(ServerPlayer player) {
		PokemonBattle battle = BattleRegistry.getBattleByParticipatingPlayer(player);
		if (battle != null) {
			battle.stop();
		}
	}

	/** True when none of the player's Pokémon is out in the world (Cobblemon's recall after a battle is done). */
	static boolean nothingSentOut(ServerPlayer player) {
		for (Pokemon pokemon : PlayerExtensionsKt.party(player)) {
			if (pokemon.getEntity() != null) {
				return false;
			}
		}
		return true;
	}

	/** Takes the player's sent-out Pokémon back at once, without Cobblemon's animation (from test code: outside tracking). */
	static void recallNow(ServerPlayer player) {
		for (Pokemon pokemon : PlayerExtensionsKt.party(player)) {
			if (pokemon.getEntity() != null) {
				pokemon.recall();
			}
		}
	}

	/**
	 * The end of a test that fought, added to {@code sequence}: the players' battles stop, Cobblemon's recall of their
	 * sent-out Pokémon finishes (its animation takes 30 ticks; after {@value #RECALL_TICKS} they are taken back here), a
	 * tick passes (end handlers, our discards), then {@code last} runs (closing relay taps, last checks), the players leave
	 * and the test succeeds. Nobody leaves in the tick their battle ends, or with a Pokémon out.
	 */
	static void leaveAfterBattle(GameTestHelper helper, GameTestSequence sequence, Runnable last, ServerPlayer... players) {
		List<ServerPlayer> all = List.of(players);
		long[] stoppedAt = {0};
		sequence.thenExecute(() -> {
					all.forEach(MockPlayers::endBattle);
					stoppedAt[0] = helper.getTick();
				})
				.thenWaitUntil(() -> {
					boolean out = !all.stream().allMatch(MockPlayers::nothingSentOut);
					if (out && helper.getTick() - stoppedAt[0] < RECALL_TICKS) {
						throw new GameTestAssertException("a Pokémon is still out after the battle");
					}
					if (out) {
						all.forEach(MockPlayers::recallNow);
					}
				})
				.thenIdle(1)
				.thenExecute(() -> {
					last.run();
					all.forEach(player -> leave(helper, player));
				})
				.thenSucceed();
	}

	/**
	 * A failing test's clean-up, at once (a failed test runs no later step): the battle stops and the player's Pokémon are
	 * taken back without their animation, then the player leaves.
	 */
	static void leaveNow(GameTestHelper helper, ServerPlayer player) {
		endBattle(player);
		recallNow(player);
		leave(helper, player);
	}
}
