package dev.kantobridge.client.fx;

import dev.kantobridge.client.world.ClientWorldIndex;
import dev.kantobridge.story.StoryState;
import dev.kantobridge.story.StoryStates;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.StoryCellTable;
import dev.kantobridge.world.StoryCellType;
import dev.kantobridge.world.WorldIndex;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.Optional;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * Other players fade while they stand inside one of your blockades (DESIGN §8 "Smoothing it over"; docs/PLAN.md §4.3
 * {@code fx/}). Every player's story cells are their own: where your Cut tree still stands, your Snorlax sleeps or your
 * guard bars the way, another player who already got past walks through it. Seen from your side they stand inside the
 * blockade, so they are drawn translucent there ({@code mixin/client/LivingEntityRendererMixin}: vanilla's translucent
 * body, at {@link #COLOR}'s alpha, without held items or other layers).
 *
 * <p>A blockade is a story cell that is closed for the local player ({@link StoryState#blocks}, fed by the local
 * {@code StoryRules}): an object, a closed tile or a boulder. Water is no blockade: a surfer on it is just surfing. The
 * set of faded players is decided once per client tick ({@link #fades} is plain Java).
 */
public final class BlockadeFade {
	/** The faded body's colour: white at about 55% alpha ("slightly"; vanilla's invisible-teammate ghost is 15%). */
	public static final int COLOR = 0x8CFFFFFF;

	private static final IntSet FADED = new IntOpenHashSet();

	private BlockadeFade() {
	}

	/** Call once from the client entrypoint. */
	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(BlockadeFade::tick);
	}

	/**
	 * True when a player standing at block position ({@code x}, {@code y}, {@code z}) (their feet) is inside a blockade
	 * that {@code local}'s story still has closed: the cell under their feet is a story cell other than water, and the
	 * local player's state blocks it.
	 */
	public static boolean fades(@Nullable WorldIndex index, @Nullable StoryState local, double x, double y, double z) {
		if (index == null || local == null) {
			return false;
		}
		Optional<CellPos> cell = index.toCell((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
		if (cell.isEmpty()) {
			return false;
		}
		StoryCellTable.Entry entry = index.storyCells().get(cell.get().key());
		return entry != null && entry.type() != StoryCellType.WATER && local.blocks(entry.key(), entry.type());
	}

	/** True while {@code entity} (another player) is drawn faded. Render thread (the client thread). */
	public static boolean faded(Entity entity) {
		return !FADED.isEmpty() && FADED.contains(entity.getId());
	}

	private static void tick(Minecraft minecraft) {
		FADED.clear();
		LocalPlayer local = minecraft.player;
		ClientLevel level = minecraft.level;
		WorldIndex index = ClientWorldIndex.current();
		if (local == null || level == null || index == null) {
			return;
		}
		StoryState state = StoryStates.peek(local);
		if (state == null) {
			return;
		}
		for (AbstractClientPlayer other : level.players()) {
			if (other != local && !other.isSpectator() && fades(index, state, other.getX(), other.getY(), other.getZ())) {
				FADED.add(other.getId());
			}
		}
	}

	/** How many players are faded now (F7). */
	public static int count() {
		return FADED.size();
	}
}
