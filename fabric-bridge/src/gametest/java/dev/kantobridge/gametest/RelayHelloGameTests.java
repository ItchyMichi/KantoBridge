package dev.kantobridge.gametest;

import dev.kantobridge.net.RelayHandshake;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * The relay's version handshake on a real server (M10; docs/PROTOCOL.md {@code relay.hello}), as far as mock players
 * allow: they have no client, so the join check that would send them away is run with what their client would have
 * said, and the disconnect itself is watched instead of done ({@link RelayHandshake#kickWith}). A client that can't
 * receive the relay, or answers with another protocol, is sent away with both versions in the reason; ours stays; the
 * hello goes to a client that can receive it; mock players themselves are left alone. A batch of its own: it swaps the
 * kicker.
 */
public class RelayHelloGameTests implements FabricGameTest {
	private static final String BATCH = "kantobridge_relay_hello";
	/** On its own: it compares a counter the other tests change. */
	private static final String BATCH_JOIN = "kantobridge_relay_hello_join";

	/** Runs {@code test} with kicks recorded instead of done. */
	private static void watchingKicks(ServerPlayer player, java.util.function.Consumer<List<Component>> test) {
		List<Component> kicked = new ArrayList<>();
		RelayHandshake.kickWith((who, reason) -> {
			if (who == player) {
				kicked.add(reason);
			}
		});
		try {
			test.accept(kicked);
		} finally {
			RelayHandshake.kickWith(null);
		}
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void aClientWithoutTheRelayIsSentAwayAndOneWithItGetsTheHello(GameTestHelper helper) {
		MockPlayers.with(helper, player -> watchingKicks(player, kicked -> RelayTap.capture(player, sent -> {
			RelayHandshake.join(player, false);
			helper.assertTrue(kicked.size() == 1, "a client without kantobridge wasn't sent away");
			String reason = kicked.get(0).getString();
			helper.assertTrue(reason.contains(RelayHandshake.modVersion()) && reason.contains(Integer.toString(RelayHandshake.protocol())),
					"the reason doesn't name the server's version");
			helper.assertTrue(sent.isEmpty(), "a client that can't receive the relay was sent something");

			RelayHandshake.join(player, true);
			helper.assertTrue(kicked.size() == 1, "a client with the relay was sent away");
			helper.assertTrue(sent.size() == 1 && sent.get(0).type().equals(MsgTypes.RELAY_HELLO), "no relay.hello");
			Envelope hello = sent.get(0);
			helper.assertTrue(hello.data().get("protocol").getAsInt() == RelayHandshake.protocol()
					&& hello.data().get("mod").getAsString().equals(RelayHandshake.modVersion()) && hello.data().size() == 2, "the hello's body");
		})));
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
	public void anotherProtocolIsSentAwayAndOursStays(GameTestHelper helper) {
		MockPlayers.with(helper, player -> watchingKicks(player, kicked -> RelayTap.capture(player, sent -> {
			RelayService relay = RelayService.get();
			relay.receive(player, BattleGameTests.envelope(1, MsgTypes.RELAY_HELLO,
					"{\"protocol\":" + RelayHandshake.protocol() + ",\"mod\":\"" + RelayHandshake.modVersion() + "\"}"));
			helper.assertTrue(kicked.isEmpty() && RelayHandshake.agreed(player), "our own protocol was refused");

			relay.receive(player, BattleGameTests.envelope(2, MsgTypes.RELAY_HELLO, "{\"protocol\":" + (RelayHandshake.protocol() + 1)
					+ ",\"mod\":\"9.9.9\"}"));
			helper.assertTrue(kicked.size() == 1, "another protocol stayed");
			String reason = kicked.get(0).getString();
			helper.assertTrue(reason.contains("9.9.9") && reason.contains(Integer.toString(RelayHandshake.protocol() + 1)),
					"the reason doesn't name the client's version");
			helper.assertTrue(reason.contains(RelayHandshake.modVersion()), "the reason doesn't name the server's version");

			// Malformed: an error, nobody sent away.
			relay.receive(player, BattleGameTests.envelope(3, MsgTypes.RELAY_HELLO, "{\"protocol\":\"one\",\"mod\":\"0.1.0\"}"));
			relay.receive(player, BattleGameTests.envelope(4, MsgTypes.RELAY_HELLO, "{\"protocol\":1}"));
			helper.assertTrue(kicked.size() == 1, "a malformed hello sent the player away");
			helper.assertTrue(sent.size() == 2 && sent.stream().allMatch(message -> message.type().equals(MsgTypes.ERROR))
					&& Objects.equals(sent.get(0).re(), 3L), "malformed hellos weren't answered with errors");
		})));
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE, batch = BATCH_JOIN, timeoutTicks = 40)
	public void mockPlayersAreLeftAlone(GameTestHelper helper) {
		long noMod = RelayHandshake.counts().getOrDefault("no_mod", 0L);
		ServerPlayer player = MockPlayers.join(helper);
		helper.startSequence()
				.thenIdle(2)
				.thenExecute(() -> {
					boolean present = helper.getLevel().getServer().getPlayerList().getPlayer(player.getUUID()) != null;
					MockPlayers.leave(helper, player);
					helper.assertTrue(present, "a mock player was sent away for having no client");
					helper.assertTrue(RelayHandshake.counts().getOrDefault("no_mod", 0L) == noMod, "a mock player counted as a client without the mod");
				})
				.thenSucceed();
	}
}
