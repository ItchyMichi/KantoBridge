package dev.kantobridge.server;

import dev.kantobridge.KantoBridge;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.story.StoryStateMessages;
import dev.kantobridge.story.StoryStates;
import dev.kantobridge.story.Surfing;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ServerPlayer;

/**
 * Applies each client's {@code story.state} to that player's {@link dev.kantobridge.story.StoryState} attachment
 * (docs/PLAN.md §4.4 {@code StoryStateService}): a full state on join, then deltas. The client applied the same message
 * to its own copy before sending it, so client prediction and the server's collision agree. A change of the bike flag
 * re-applies the Gen 1 speed ({@link Rules#applyMovement}); the lit maps decide Flash's Night Vision at once
 * ({@link FlashService}). The active cells are the client's verdict under docs/PROTOCOL.md "Story rules (M8)". The surf
 * flag also goes to the {@link Surfing} attachment, so other players' clients draw the surf sprite under this player (M10).
 *
 * <p>A player's client speaks only for that player (relay authority). Malformed messages change nothing and are
 * answered with {@code error{code:"bad_message"}} and a counted warning. Server thread only.
 */
public final class StoryStateService {
	private static final AtomicLong REJECTED = new AtomicLong();

	private StoryStateService() {
	}

	/** Call once from common init, after the relay. */
	public static void init() {
		RelayService.get().register(MsgTypes.STORY_STATE, StoryStateService::onState);
	}

	static void onState(ServerPlayer player, Envelope message) {
		StoryStateMessages.Applied applied;
		try {
			applied = StoryStateMessages.apply(StoryStates.of(player), message.data());
		} catch (StoryStateMessages.Invalid e) {
			long count = REJECTED.incrementAndGet();
			KantoBridge.LOGGER.warn("story.state: rejected ({}); {} rejected so far", e.code(), count);
			RelayService.get().sendError(player, "bad_message", message.seq());
			return;
		}
		if (applied.full() || applied.bikeChanged()) {
			Rules.applyMovement(player);
		}
		Surfing.set(player, StoryStates.of(player).surf());
		FlashService.update(player, WorldIndexSync.current(player.server));
		KantoBridge.LOGGER.debug("story.state: {} applied ({} cells changed)", applied.full() ? "full" : "delta", applied.cellsChanged());
	}

	public static long rejected() {
		return REJECTED.get();
	}
}
