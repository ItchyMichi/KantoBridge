package dev.kantobridge.server;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.kantobridge.KantoBridge;
import java.util.Optional;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * A player's last save pair (M10 crash recovery; DESIGN §9 "if one side crashes, the other pauses and resyncs from the
 * last save"): which hidden-game save this world last saved together with ({@code save.begin}'s {@code pair} and
 * {@code at}), and how many Pokémon operations ({@code party.give}, {@code party.take}, {@code daycare.deposit},
 * {@code daycare.withdraw}) the hidden game has sent since. {@link SavePairService} compares it with what the hidden game
 * reports in {@code save.loaded}.
 *
 * <p>A persistent attachment on the player, so it is written with the player's data: {@code save.begin} sets it before
 * the save writes that data, and Minecraft's autosaves keep the count. It survives respawn. Hashes and counts only.
 *
 * @param pair the hidden save's lasting id (a hex hash), or null before the first save in this world
 * @param at   that save's time (Unix seconds), or -1 when unknown
 * @param ops  Pokémon operations received since that save
 */
public record SavePair(@Nullable String pair, long at, int ops) {
	/** A player that never saved in this world. */
	public static final SavePair NONE = new SavePair(null, -1, 0);

	public static final Codec<SavePair> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.optionalFieldOf("pair").forGetter(pair -> Optional.ofNullable(pair.pair())),
			Codec.LONG.optionalFieldOf("at").forGetter(pair -> pair.at() < 0 ? Optional.empty() : Optional.of(pair.at())),
			Codec.INT.optionalFieldOf("ops", 0).forGetter(SavePair::ops)
	).apply(instance, (pair, at, ops) -> new SavePair(pair.orElse(null), at.orElse(-1L), Math.max(0, ops))));

	public static final AttachmentType<SavePair> TYPE = AttachmentRegistry.create(
			ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "save_pair"), builder -> builder.persistent(CODEC).copyOnDeath());

	/** Registers the attachment type (class initialisation); call once from common init. */
	public static void init() {
		// TYPE is created when this class initialises.
	}

	/** The player's save pair; {@link #NONE} before the first save. */
	public static SavePair of(Player player) {
		SavePair pair = ((AttachmentTarget) player).getAttached(TYPE);
		return pair == null ? NONE : pair;
	}

	static void set(Player player, SavePair pair) {
		((AttachmentTarget) player).setAttached(TYPE, pair);
	}

	/** One more operation (never overflowing). */
	public SavePair counted() {
		return new SavePair(this.pair, this.at, this.ops == Integer.MAX_VALUE ? this.ops : this.ops + 1);
	}
}
