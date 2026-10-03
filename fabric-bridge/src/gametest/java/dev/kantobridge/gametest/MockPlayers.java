package dev.kantobridge.gametest;

import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/** Mock players for GameTests: a real {@link ServerPlayer} on an in-memory connection, removed when the test ends. */
final class MockPlayers {
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

	static void leave(GameTestHelper helper, ServerPlayer player) {
		helper.getLevel().getServer().getPlayerList().remove(player);
	}
}
