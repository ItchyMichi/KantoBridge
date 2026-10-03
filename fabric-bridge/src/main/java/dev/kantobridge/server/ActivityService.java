package dev.kantobridge.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kantobridge.KantoBridge;
import dev.kantobridge.net.RelayService;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.story.Activity;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ServerPlayer;

/**
 * Keeps each player's {@code activity} (docs/PLAN.md §3.3, §4.3 {@code fx/}) as the {@link Activity} attachment, for
 * M9's speech bubbles; nothing is drawn yet. A client speaks only for its own player. A malformed message changes nothing
 * and is answered with {@code error{bad_message}} and a counted warning. Server thread only.
 */
public final class ActivityService {
	private static final AtomicLong REJECTED = new AtomicLong();

	private ActivityService() {
	}

	/** Call once from common init, after the relay. */
	public static void init() {
		Activity.init();
		RelayService.get().register(MsgTypes.ACTIVITY, ActivityService::onActivity);
	}

	static void onActivity(ServerPlayer player, Envelope message) {
		JsonObject data = message.data();
		JsonElement value = data.size() == 1 ? data.get("activity") : null;
		Activity activity = value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? Activity.byId(value.getAsString()) : null;
		if (activity == null) {
			long count = REJECTED.incrementAndGet();
			KantoBridge.LOGGER.warn("activity: rejected a malformed message; {} so far", count);
			RelayService.get().sendError(player, "bad_message", message.seq());
			return;
		}
		Activity.set(player, activity);
	}
}
