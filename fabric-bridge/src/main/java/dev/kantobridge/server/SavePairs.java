package dev.kantobridge.server;

import com.mojang.serialization.Codec;
import dev.kantobridge.KantoBridge;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * A player's save pairs per region (docs/specs/MULTI_GAME_PLAN.md §4.6, MG3): with several regions a player has one
 * hidden game, and so one {@link SavePair}, per region. The legacy region's pair stays in the attachment it always had
 * ({@code kantobridge:save_pair}, {@link SavePair#TYPE}: a legacy world's player data is byte for byte what it was); every
 * other region's pair lives here, under {@code kantobridge:save_pairs}, keyed by region id. Persistent and copied on death,
 * like the single pair.
 *
 * @param byRegion region id → that region's pair (never the legacy region's)
 */
public record SavePairs(Map<String, SavePair> byRegion) {
	public static final SavePairs NONE = new SavePairs(Map.of());

	public static final Codec<SavePairs> CODEC = Codec.unboundedMap(Codec.STRING, SavePair.CODEC).xmap(SavePairs::new, SavePairs::byRegion);

	public static final AttachmentType<SavePairs> TYPE = AttachmentRegistry.create(
			ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "save_pairs"), builder -> builder.persistent(CODEC).copyOnDeath());

	public SavePairs {
		byRegion = Collections.unmodifiableMap(new TreeMap<>(byRegion));
	}

	/** Registers the attachment type (class initialisation); call once from common init. */
	public static void init() {
		// TYPE is created when this class initialises.
	}

	/** The pairs of every non-legacy region; {@link #NONE} before the first save in such a region. */
	public static SavePairs all(Player player) {
		SavePairs pairs = ((AttachmentTarget) player).getAttached(TYPE);
		return pairs == null ? NONE : pairs;
	}

	/**
	 * The player's pair for a region: the legacy region's ({@code region} null, or the world's legacy id) from
	 * {@link SavePair#of}, another region's from here ({@link SavePair#NONE} before its first save).
	 */
	public static SavePair of(Player player, @Nullable String region) {
		if (Regions.isLegacy(player.getServer(), region)) {
			return SavePair.of(player);
		}
		return all(player).get(region);
	}

	/** Sets the player's pair for a region (the legacy region's into {@link SavePair#TYPE}). */
	public static void set(Player player, @Nullable String region, SavePair pair) {
		if (Regions.isLegacy(player.getServer(), region)) {
			SavePair.set(player, pair);
			return;
		}
		((AttachmentTarget) player).setAttached(TYPE, all(player).with(region, pair));
	}

	/** This region's pair, or {@link SavePair#NONE}. */
	public SavePair get(String region) {
		return this.byRegion.getOrDefault(region, SavePair.NONE);
	}

	/** A copy with {@code region}'s pair replaced. */
	public SavePairs with(String region, SavePair pair) {
		Map<String, SavePair> out = new TreeMap<>(this.byRegion);
		out.put(region, pair);
		return new SavePairs(out);
	}

	public int size() {
		return this.byRegion.size();
	}
}
