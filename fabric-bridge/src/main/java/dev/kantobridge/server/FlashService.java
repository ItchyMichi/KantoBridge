package dev.kantobridge.server;

import dev.kantobridge.story.StoryState;
import dev.kantobridge.story.StoryStates;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.WorldIndex;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import org.jetbrains.annotations.Nullable;

/**
 * Flash on dark maps (docs/PLAN.md §4.5 "Rock Tunnel", §6 M8 item 5): dark maps are built without lights, and a player
 * whose hidden game has lit the map they stand on with Flash gets Night Vision there, never otherwise. The lit maps
 * are the player's {@link StoryState} (relayed by their own client, from {@code story.lit} and {@code player.state});
 * the map under the player and its darkness come from the WorldIndex. Checked every {@value #CHECK_EVERY_TICKS} ticks.
 *
 * <p>Our effect is the one without particles or icon and with an infinite duration; any other Night Vision (a potion) is
 * left alone. Server thread only; logs nothing (F7 and the counters show it).
 */
public final class FlashService {
	static final int CHECK_EVERY_TICKS = 5;

	private static final AtomicLong GIVEN = new AtomicLong();
	private static final AtomicLong TAKEN = new AtomicLong();

	private FlashService() {
	}

	/** Call once from common init. */
	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(FlashService::tick);
	}

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % CHECK_EVERY_TICKS != 0) {
			return;
		}
		WorldIndex index = WorldIndexSync.current(server);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			update(player, index);
		}
	}

	/** Gives or takes our Night Vision for one player now; true when they have it afterwards. */
	public static boolean update(ServerPlayer player, @Nullable WorldIndex index) {
		boolean want = wants(index, StoryStates.peek(player), player.getX(), player.getY(), player.getZ());
		MobEffectInstance current = player.getEffect(MobEffects.NIGHT_VISION);
		if (want && current == null) {
			player.addEffect(nightVision());
			GIVEN.incrementAndGet();
			return true;
		}
		if (!want && current != null && ours(current)) {
			player.removeEffect(MobEffects.NIGHT_VISION);
			TAKEN.incrementAndGet();
			return false;
		}
		return current != null;
	}

	/** Night Vision is due when the player stands on a dark map that their hidden game has lit with Flash. */
	public static boolean wants(@Nullable WorldIndex index, @Nullable StoryState state, double x, double y, double z) {
		if (index == null || state == null || state.lit().isEmpty()) {
			return false;
		}
		Optional<CellPos> cell = index.toCell(Mth.floor(x), Mth.floor(y), Mth.floor(z));
		if (cell.isEmpty()) {
			return false;
		}
		MapPlacement map = index.map(cell.get().map());
		return map != null && map.dark() && state.isLit(map.id());
	}

	/** Our effect: endless, no particles, no icon (Gen 1's lit cave has no status to show). */
	public static MobEffectInstance nightVision() {
		return new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false, false);
	}

	public static boolean ours(MobEffectInstance effect) {
		return effect.isInfiniteDuration() && !effect.isVisible() && !effect.showIcon() && !effect.isAmbient();
	}

	public static long given() {
		return GIVEN.get();
	}

	public static long taken() {
		return TAKEN.get();
	}
}
