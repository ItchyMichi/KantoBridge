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
	@SuppressWarnings("removal") // the only GameTest helper that yields a real, joined ServerPlayer on 1.21.1
	static void with(GameTestHelper helper, Consumer<ServerPlayer> test) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		try {
			test.accept(player);
		} finally {
			helper.getLevel().getServer().getPlayerList().remove(player);
		}
	}
}
