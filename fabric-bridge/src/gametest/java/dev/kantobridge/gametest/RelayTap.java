package dev.kantobridge.gametest;

import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.server.level.ServerPlayer;

/** Collects what the server relay sends one (mock) player while a test runs; mock players have no client to read it. */
final class RelayTap {
	private RelayTap() {
	}

	/** A tap that stays open across ticks; close it when the test ends. */
	static final class Recorder implements AutoCloseable {
		final List<Envelope> sent = new ArrayList<>();
		private final AutoCloseable tap;

		private Recorder(ServerPlayer player) {
			this.tap = RelayService.get().tap((to, message) -> {
				if (to == player) {
					this.sent.add(message);
				}
			});
		}

		/** The messages of one type, in order. */
		List<Envelope> of(String type) {
			return this.sent.stream().filter(message -> message.type().equals(type)).toList();
		}

		@Override
		public void close() {
			try {
				this.tap.close();
			} catch (Exception e) {
				throw new GameTestAssertException("relay tap could not be removed");
			}
		}
	}

	/** Starts recording what the relay sends {@code player}, for tests that span ticks. */
	static Recorder open(ServerPlayer player) {
		return new Recorder(player);
	}

	/** Runs {@code test} with a list that fills with the envelopes sent to {@code player}. */
	static void capture(ServerPlayer player, Consumer<List<Envelope>> test) {
		List<Envelope> sent = new ArrayList<>();
		AutoCloseable tap = RelayService.get().tap((to, message) -> {
			if (to == player) {
				sent.add(message);
			}
		});
		try {
			test.accept(sent);
		} finally {
			try {
				tap.close();
			} catch (Exception e) {
				throw new GameTestAssertException("relay tap could not be removed");
			}
		}
	}
}
