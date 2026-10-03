package dev.kantobridge.story;

import dev.kantobridge.KantoBridge;
import io.netty.buffer.ByteBuf;
import java.util.Locale;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * What a player is doing for the hidden game (protocol/messages/activity.schema.json), shown to other players as a
 * speech bubble (DESIGN §8 "Smoothing it over", M9): in a battle, in a dialogue (one of the bridge's screens), in a
 * cutscene, or nothing. Each client reports its own player's; the server keeps it as an attachment on the player (not
 * saved: the client reports again on every join) and Fabric syncs it to every client that tracks the player, except the
 * player's own ({@link #SYNC}): the bubble is for the others.
 */
public enum Activity {
	NONE, DIALOGUE, CUTSCENE, BATTLE;

	/** Everyone but the player it is about (docs/PLAN.md §4.3 {@code fx/}: {@code allButTarget}). */
	public static final AttachmentSyncPredicate SYNC = AttachmentSyncPredicate.allButTarget();
	/** On the wire: the ordinal; an unknown one reads as {@link #NONE}. */
	public static final StreamCodec<ByteBuf, Activity> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(Activity::byOrdinal, Activity::ordinal);

	public static final AttachmentType<Activity> TYPE = AttachmentRegistry.create(
			ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "activity"), builder -> builder.copyOnDeath().syncWith(STREAM_CODEC, SYNC));

	public String id() {
		return this.name().toLowerCase(Locale.ROOT);
	}

	public static @Nullable Activity byId(@Nullable String id) {
		for (Activity activity : values()) {
			if (activity.id().equals(id)) {
				return activity;
			}
		}
		return null;
	}

	static Activity byOrdinal(int ordinal) {
		Activity[] all = values();
		return ordinal >= 0 && ordinal < all.length ? all[ordinal] : NONE;
	}

	/** A player's reported activity; {@link #NONE} until their client reported one (or, on a client, until it synced). */
	public static Activity of(Player player) {
		Activity activity = ((AttachmentTarget) player).getAttached(TYPE);
		return activity == null ? NONE : activity;
	}

	/** Sets a player's activity (server side; the change syncs to the other players' clients). Unchanged costs nothing. */
	public static void set(Player player, Activity activity) {
		if (((AttachmentTarget) player).getAttached(TYPE) != activity) {
			((AttachmentTarget) player).setAttached(TYPE, activity);
		}
	}

	/** Registers the attachment type (class initialisation); call once from common init. */
	public static void init() {
		// TYPE is created when this class initialises.
	}
}
